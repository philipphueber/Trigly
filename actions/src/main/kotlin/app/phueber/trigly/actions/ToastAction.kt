package app.phueber.trigly.actions

import android.content.Context
import android.widget.Toast
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows a short message.
 *
 * Two constraints worth knowing before using this as a rule's only feedback:
 * a toast must be posted from the main thread, and from Android 12 toasts from a
 * background app are suppressed by the system. For a rule that fires while the
 * phone is idle, a notification is the reliable choice — see
 * `PostNotificationAction`.
 */
class ToastAction(
    private val context: Context,
    private val text: String,
    private val long: Boolean,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult =
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                text,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
            ).show()
            ActionResult.Success()
        }

    companion object {
        const val TYPE = "toast"
        const val CONFIG_TEXT = "text"
        const val CONFIG_LONG = "long"
    }
}

class ToastActionFactory(private val context: Context) : ActionFactory {
    override val type = ToastAction.TYPE

    override val displayName = "Show a brief message"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        messageText(ToastAction.CONFIG_TEXT, "Message"),
        ConfigField.Flag(ToastAction.CONFIG_LONG, "Show for longer"),
    )

    override val warning: String =
        "Android 12 and later suppress a toast message while the app is in the " +
            "background. Use a notification for anything that must be seen."

    override fun create(config: Map<String, String>): Action = ToastAction(
        context = context,
        text = config[ToastAction.CONFIG_TEXT] ?: error("$type needs '${ToastAction.CONFIG_TEXT}'"),
        long = config[ToastAction.CONFIG_LONG]?.toBoolean() ?: false,
    )
}
