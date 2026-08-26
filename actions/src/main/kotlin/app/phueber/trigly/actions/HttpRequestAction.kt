package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 2xx is success; everything else is a failure the rule log should show. */
fun isSuccessfulStatus(code: Int): Boolean = code in 200..299

/**
 * Whether [contentType] is a form of JSON, for choosing how a variable
 * substituted into the body is escaped.
 *
 * A prefix match rather than an exact one: a real content type often carries a
 * parameter, as in `application/json; charset=utf-8`, and that is still JSON.
 */
fun isJsonContentType(contentType: String): Boolean =
    contentType.trim().startsWith("application/json", ignoreCase = true)

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
                    "This action allows only https URLs. This URL is '$url'."
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
                    ActionResult.Failure("The server at $url answered with HTTP $code.")
                }
            } catch (io: IOException) {
                ActionResult.Failure("A request to $url failed. ${io.message}", io)
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
            help = "This action allows https only. A webhook URL usually carries a " +
                "token. $URL_SUBSTITUTION_HELP",
            substitution = Substitution.URL,
        ),
        ConfigField.Choice(
            key = HttpRequestAction.CONFIG_METHOD,
            label = "Method",
            options = HttpRequestAction.ALLOWED_METHODS.sorted()
                .map { ConfigField.Option(it, it) },
            required = false,
            default = HttpRequestAction.DEFAULT_METHOD,
        ),
        messageText(
            key = HttpRequestAction.CONFIG_BODY,
            label = "Body",
            required = false,
            help = "A variable used here is escaped for JSON when the content type " +
                "is JSON, and inserted as plain text otherwise.",
        ),
        ConfigField.Text(
            key = HttpRequestAction.CONFIG_CONTENT_TYPE,
            label = "Content type",
            blankMeaning = "Defaults to ${HttpRequestAction.DEFAULT_CONTENT_TYPE}",
        ),
        ConfigField.Duration(
            key = HttpRequestAction.CONFIG_TIMEOUT_MILLIS,
            label = "Timeout",
            defaultMillis = HttpRequestAction.DEFAULT_TIMEOUT_MILLIS.toLong(),
            preferred = DurationUnit.SECONDS,
        ),
    )

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission("android.permission.INTERNET"),
    )

    /**
     * The body's escaping depends on the content type, which a sibling field
     * carries. See [ConfigField.substitution] and `docs/variables.md` section 8.
     *
     * [HttpRequestAction.CONFIG_CONTENT_TYPE] is resolved with the exact
     * null-coalescing [create] uses for the same key, on purpose: the editor
     * calling this to render a picker and the engine calling it to escape a
     * value must land on the same content type, or one of them is wrong about
     * what the request actually sends. A notification title with a quotation
     * mark inserted raw into a JSON body is the quiet failure this whole field
     * exists to prevent: the server answers 400 and nothing on screen says why.
     */
    override fun substitutionsFor(config: Map<String, String>): Map<String, Substitution> {
        val effectiveContentType = config[HttpRequestAction.CONFIG_CONTENT_TYPE]
            ?: HttpRequestAction.DEFAULT_CONTENT_TYPE
        val bodySubstitution = if (isJsonContentType(effectiveContentType)) {
            Substitution.JSON_STRING
        } else {
            Substitution.TEXT
        }
        return super.substitutionsFor(config) + (HttpRequestAction.CONFIG_BODY to bodySubstitution)
    }

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
