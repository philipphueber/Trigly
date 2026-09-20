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
 * phone is idle, a notification is the reliable choice. See
 * `PostNotificationAction`.
 *
 * **The icon in the toast is the app's, and the app cannot change it.** From
 * Android 12 the platform draws an icon beside the text, and API 30, the other
 * level this project tests on, draws none. Three facts settle what an app can
 * do about it, and all three point the same way.
 *
 * The icon is chosen by SystemUI, not by this code. `SystemUIToast` reads the
 * posting package's `ApplicationInfo` through `getApplicationInfoAsUser` and
 * loads `IconDrawableFactory.getBadgedIcon(appInfo, user)`, so what appears is
 * `<application android:icon>`. It hides the icon only when the posting app
 * targets below Android 12; Trigly targets 35.
 *
 * Nothing on [Toast] reaches it. The whole public surface of that class is
 * text, duration, gravity, margins and callbacks. `setView` is the one lever
 * that could put an arbitrary icon on screen, it has been deprecated since
 * Android 11, and a custom toast view from a background app is dropped
 * outright, which is the state an automation rule fires in.
 *
 * And the source it is read from cannot be re-pointed at run time.
 * `PackageManager` has `getApplicationIcon` and no setter, and `<application>`
 * is not a component, so the `setComponentEnabledSetting` trick that switches
 * the launcher icon between the nine colour schemes has nothing to switch.
 *
 * So the toast keeps the app's colour-scheme choice out of reach by
 * construction. What it shows instead is the Trigly mark with no plate behind
 * it, which is what `ui/src/main/res/mipmap/ic_app_mark.xml` is and why that
 * file exists. The engine's own notification is left in the same state on
 * purpose: its small icon is the same plateless mark. The difference is that
 * the system tints a notification's small icon with the colour the app asks
 * for, and it tints nothing in a toast.
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
