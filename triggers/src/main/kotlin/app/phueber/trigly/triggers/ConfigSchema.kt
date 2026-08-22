package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField

/**
 * Shorthand for the commonest field in the project.
 *
 * Fourteen triggers take a single `state` setting that is a choice between two
 * words — `enabled`/`disabled`, `plugged`/`unplugged`, `entered`/`exited`. The
 * word pairs differ per trigger, which is why they cannot be inferred from the
 * key name, but the shape never does.
 */
internal fun stateChoice(
    label: String,
    onValue: String,
    onLabel: String,
    offValue: String,
    offLabel: String,
    help: String? = null,
): ConfigField.Choice = ConfigField.Choice(
    key = "state",
    label = label,
    options = listOf(
        ConfigField.Option(onValue, onLabel),
        ConfigField.Option(offValue, offLabel),
    ),
    help = help,
)

/** The `package` field, which several triggers use as an optional filter. */
internal fun packageFilter(
    label: String = "App",
    blankMeaning: String? = "Leave blank for any app",
    required: Boolean = false,
    help: String? = null,
): ConfigField.AppPackage = ConfigField.AppPackage(
    key = "package",
    label = label,
    required = required,
    blankMeaning = blankMeaning,
    help = help,
)

/** Categories used to group the trigger picker. Mirrors `triggerFactories()`. */
internal object Category {
    const val TIME = "Time"
    const val POWER = "Power"
    const val RADIOS = "Connectivity"
    const val DEVICE = "Device state"
    const val APPS = "Apps"
    const val NOTIFICATIONS = "Notifications"
    const val SCREEN = "Screen content"
    const val TELEPHONY = "Calls & messages"
    const val LOCATION = "Location"
}
