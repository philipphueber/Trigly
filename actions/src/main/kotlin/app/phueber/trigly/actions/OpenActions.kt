package app.phueber.trigly.actions

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent

/**
 * Only http and https are launched.
 *
 * A rule config is data that may have come from anywhere, and `Intent.ACTION_VIEW`
 * will happily follow schemes that do rather more than open a page — `file:` can
 * expose local content, and custom app schemes can invoke another app's deep
 * link with attacker-chosen parameters. Restricting the scheme keeps a "open a
 * website" action doing only that.
 */
fun isLaunchableWebUrl(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val scheme = raw.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https"
}

/** Opens a website in the user's browser. */
class OpenUrlAction(
    private val context: Context,
    private val url: String,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        if (!isLaunchableWebUrl(url)) {
            return ActionResult.Failure("only http and https URLs can be opened, got '$url'")
        }
        return context.launchForRule(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    companion object {
        const val TYPE = "open_url"
        const val CONFIG_URL = "url"
    }
}

class OpenUrlActionFactory(private val context: Context) : ActionFactory {
    override val type = OpenUrlAction.TYPE

    override val displayName = "Open a website"
    override val category = ActionCategory.OPEN

    override val configFields = listOf(
        ConfigField.Text(
            key = OpenUrlAction.CONFIG_URL,
            label = "Address",
            required = true,
            placeholder = "https://example.com",
            help = "Only http and https addresses can be opened.",
        ),
    )

    override val warning: String = BACKGROUND_START_WARNING

    override fun create(config: Map<String, String>): Action = OpenUrlAction(
        context = context,
        url = config[OpenUrlAction.CONFIG_URL] ?: error("$type needs '${OpenUrlAction.CONFIG_URL}'"),
    )
}

/**
 * Launches an installed app.
 *
 * On API 30+ package visibility rules mean `getLaunchIntentForPackage` returns
 * null for most apps unless they are declared in a `<queries>` element — which
 * cannot be done for an app whose package the *user* picks at runtime. The
 * honest options are `QUERY_ALL_PACKAGES` (Play-restricted) or a picker that
 * uses the system's own app-chooser. Reported as a clear failure until that is
 * resolved rather than silently doing nothing; see `docs/actions.md`.
 */
class OpenAppAction(
    private val context: Context,
    private val packageName: String,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failure(
                "cannot launch '$packageName': it is not installed, or not visible to " +
                    "Trigly under Android 11 package visibility rules"
            )

        return context.launchForRule(intent)
    }

    companion object {
        const val TYPE = "open_app"
        const val CONFIG_PACKAGE = "package"
    }
}

class OpenAppActionFactory(private val context: Context) : ActionFactory {
    override val type = OpenAppAction.TYPE

    override val displayName = "Open an app"
    override val category = ActionCategory.OPEN

    override val configFields = listOf(
        ConfigField.AppPackage(
            key = OpenAppAction.CONFIG_PACKAGE,
            label = "App",
            required = true,
        ),
    )

    override val warning: String = BACKGROUND_START_WARNING

    override fun create(config: Map<String, String>): Action = OpenAppAction(
        context = context,
        packageName = config[OpenAppAction.CONFIG_PACKAGE]
            ?: error("$type needs '${OpenAppAction.CONFIG_PACKAGE}'"),
    )
}
