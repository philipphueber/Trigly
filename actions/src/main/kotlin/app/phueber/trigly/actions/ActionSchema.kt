package app.phueber.trigly.actions

import app.phueber.trigly.core.ConfigField

/** Categories used to group the action picker. */
internal object ActionCategory {
    const val NOTIFY = "Tell me something"
    const val OPEN = "Open something"
    const val HAND_OFF = "Hand off to an app"
    const val DEVICE = "Device settings"
    const val NETWORK = "Network"
    const val NOTIFICATIONS = "Other apps' notifications"
}

/** The message field that several actions share. */
internal fun messageText(
    key: String,
    label: String,
    required: Boolean = true,
    help: String? = null,
): ConfigField.Text = ConfigField.Text(
    key = key,
    label = label,
    required = required,
    help = help,
    multiline = true,
)

/**
 * Shared by every action that opens something.
 *
 * Stated once here because it is the same platform restriction each time, and
 * because it is invisible in normal use: the system drops a background activity
 * start silently, so the action reports success and nothing happens.
 */
internal const val BACKGROUND_START_WARNING: String =
    "Android 10 and later block apps from opening things while in the background, " +
        "with no error. This works while you are using the phone and is unreliable " +
        "when the screen is off."
