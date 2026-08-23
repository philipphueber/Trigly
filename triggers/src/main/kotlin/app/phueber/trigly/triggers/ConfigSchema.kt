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

/**
 * The `package` field, which several triggers use as an optional filter.
 *
 * [blankMeaning] reads as a *value* rather than an instruction ("Any app", not
 * "Leave blank for any app") because the editor renders an app-package field as a
 * picker: the blank meaning is what the field shows when nothing is chosen, and
 * the row that sets it back. "Leave blank" would be telling someone to do
 * something the control no longer offers.
 */
internal fun packageFilter(
    label: String = "App",
    blankMeaning: String? = "Any app",
    required: Boolean = false,
    help: String? = null,
): ConfigField.AppPackage = ConfigField.AppPackage(
    key = "package",
    label = label,
    required = required,
    blankMeaning = blankMeaning,
    help = help,
)

/**
 * A "does the text match" filter, which six fields across five triggers need.
 *
 * Declared through one helper so the pair of keys — the pattern and its mode —
 * cannot drift apart, and so a new text filter is regex-capable by construction
 * rather than by remembering to be.
 *
 * The label keeps saying "contains" because that is what it does until someone
 * switches it, and a field labelled "Title or text matches" would be vaguer for
 * the many rules that never touch the mode.
 */
internal fun textFilter(
    key: String,
    label: String,
    blankMeaning: String? = null,
    required: Boolean = false,
    help: String? = null,
): ConfigField.TextPattern = ConfigField.TextPattern(
    key = key,
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
