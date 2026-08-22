package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 2xx is success; everything else is a failure the rule log should show. */
fun isSuccessfulStatus(code: Int): Boolean = code in 200..299

/**
 * Sends an HTTP request — the escape hatch that lets rules reach webhooks and
 * home automation without Trigly integrating with anything specific.
 *
 * Uses `HttpURLConnection` rather than adding OkHttp: one action does not
 * justify a dependency, and the platform client handles this shape fine.
 *
 * `https` only. A rule config can come from an import or a shared recipe, and
 * silently sending a webhook token over cleartext would be a real leak. Android
 * blocks cleartext by default anyway; failing with a clear message beats an
 * opaque network error.
 */
class HttpRequestAction(
    private val url: String,
    private val method: String,
    private val body: String?,
    private val contentType: String,
    private val timeoutMillis: Int,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://", ignoreCase = true)) {
                return@withContext ActionResult.Failure(
                    "only https URLs are allowed, got '$url'"
                )
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = timeoutMillis
                    readTimeout = timeoutMillis
                    instanceFollowRedirects = true
                }

                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", contentType)
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }

                val code = connection.responseCode
                if (isSuccessfulStatus(code)) {
                    ActionResult.Success
                } else {
                    ActionResult.Failure("HTTP $code from $url")
                }
            } catch (io: IOException) {
                ActionResult.Failure("request to $url failed: ${io.message}", io)
            } finally {
                // The response body is never read: a rule cares that the call
                // was made, and draining an arbitrary response into memory is a
                // liability, not a feature.
                connection?.disconnect()
            }
        }

    companion object {
        const val TYPE = "http_request"
        const val CONFIG_URL = "url"
        const val CONFIG_METHOD = "method"
        const val CONFIG_BODY = "body"
        const val CONFIG_CONTENT_TYPE = "contentType"
        const val CONFIG_TIMEOUT_MILLIS = "timeoutMillis"

        const val DEFAULT_METHOD = "GET"
        const val DEFAULT_CONTENT_TYPE = "application/json"
        const val DEFAULT_TIMEOUT_MILLIS = 15_000

        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    }
}

class HttpRequestActionFactory : ActionFactory {
    override val type = HttpRequestAction.TYPE

    override val displayName = "Send an HTTP request"
    override val category = ActionCategory.NETWORK

    override val configFields = listOf(
        ConfigField.Text(
            key = HttpRequestAction.CONFIG_URL,
            label = "URL",
            required = true,
            placeholder = "https://example.com/hook",
            help = "https only — a webhook URL usually carries a token.",
        ),
        ConfigField.Choice(
            key = HttpRequestAction.CONFIG_METHOD,
            label = "Method",
            options = HttpRequestAction.ALLOWED_METHODS.sorted()
                .map { ConfigField.Option(it, it) },
            required = false,
            default = HttpRequestAction.DEFAULT_METHOD,
        ),
        messageText(HttpRequestAction.CONFIG_BODY, "Body", required = false),
        ConfigField.Text(
            key = HttpRequestAction.CONFIG_CONTENT_TYPE,
            label = "Content type",
            blankMeaning = "Defaults to ${HttpRequestAction.DEFAULT_CONTENT_TYPE}",
        ),
        ConfigField.Number(
            key = HttpRequestAction.CONFIG_TIMEOUT_MILLIS,
            label = "Timeout",
            default = HttpRequestAction.DEFAULT_TIMEOUT_MILLIS.toLong(),
            min = 1000,
            unit = "ms",
        ),
    )

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission("android.permission.INTERNET"),
    )

    override fun create(config: Map<String, String>): Action {
        val method = (config[HttpRequestAction.CONFIG_METHOD]
            ?: HttpRequestAction.DEFAULT_METHOD).uppercase()

        require(method in HttpRequestAction.ALLOWED_METHODS) {
            "method must be one of ${HttpRequestAction.ALLOWED_METHODS.sorted()}, was '$method'"
        }

        return HttpRequestAction(
            url = config[HttpRequestAction.CONFIG_URL]
                ?: error("$type needs '${HttpRequestAction.CONFIG_URL}'"),
            method = method,
            body = config[HttpRequestAction.CONFIG_BODY],
            contentType = config[HttpRequestAction.CONFIG_CONTENT_TYPE]
                ?: HttpRequestAction.DEFAULT_CONTENT_TYPE,
            timeoutMillis = config[HttpRequestAction.CONFIG_TIMEOUT_MILLIS]?.toIntOrNull()
                ?: HttpRequestAction.DEFAULT_TIMEOUT_MILLIS,
        )
    }
}
