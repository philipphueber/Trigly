package app.phueber.trigly.actions

import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.Substitution

/** Categories used to group the action picker. */
internal object ActionCategory {
    const val NOTIFY = "Tell me something"
    const val OPEN = "Open something"
    const val HAND_OFF = "Hand off to an app"
    const val DEVICE = "Device settings"
    const val NETWORK = "Network"
    const val NOTIFICATIONS = "Other apps' notifications"

    /**
     * Actions whose subject is Trigly itself rather than the device. Kept
     * separate because "turn a rule off" belongs with neither the settings it
     * does not touch nor the apps it does not reach.
     */
    const val RULES = "Trigly's own rules"
}

/**
 * The message field that several actions share.
 *
 * [substitution] defaults to [Substitution.TEXT] because every field built
 * from this is prose: a notification body, a toast, spoken text, an email or
 * SMS body, clipboard text. A caller that is not prose, such as
 * `http_request`'s body, passes its own value or declares it through
 * `ComponentFactory.substitutionsFor` instead.
 */
internal fun messageText(
    key: String,
    label: String,
    required: Boolean = true,
    help: String? = null,
    substitution: Substitution = Substitution.TEXT,
): ConfigField.Text = ConfigField.Text(
    key = key,
    label = label,
    required = required,
    help = help,
    multiline = true,
    substitution = substitution,
)

/**
 * Shared by every action that opens something.
 *
 * Stated once here because it is the same platform restriction each time, and
 * because it is invisible in normal use: the system drops a background activity
 * start silently, so the action reports success and nothing happens.
 */
internal const val BACKGROUND_START_WARNING: String =
    "Android blocks an app from opening things while in the background. It " +
        "reports no error when it does this. Turn on \"Display over other " +
        "apps\" below to remove this block. Without that permission, this " +
        "action works only while you use the phone. It is unreliable when the " +
        "screen is off."

/**
 * Appended to the help of a [Substitution.URL] field.
 *
 * Shared because `open_url` and `http_request` both carry one, and this is the
 * one property of the encoding a person cannot see from the picker alone: an
 * inserted value is percent-encoded, so a query string does not break.
 */
internal const val URL_SUBSTITUTION_HELP: String =
    "A variable used here is percent-encoded, so it cannot break the address."

/**
 * Appended to the help of a recipient field that accepts a variable.
 *
 * The recipient is the reason these two fields accept one at all: "text back
 * whoever just texted me" is the most obvious rule the whole feature makes
 * possible, and `sms_received` already carries the sender.
 *
 * It is plain [Substitution.TEXT] rather than [Substitution.URL], even though
 * the value is pasted into a `mailto:` or `smsto:` URI. Percent-encoding a
 * recipient would be the wrong trade: it would turn the `+` of an international
 * number into `%2B` and the `@` of an address into `%40` for every rule,
 * including every rule that types the recipient by hand, to guard against a
 * value that only an odd variable could produce. And these two actions *compose*
 * rather than send, by the design decision at the top of `ComposeActions.kt`, so
 * an odd value arrives on a screen the person is looking at before anything
 * leaves the phone.
 */
internal const val RECIPIENT_SUBSTITUTION_HELP: String =
    "A variable works here, so a rule can answer whoever contacted you."

/**
 * What every action that calls `launchForRule` has to declare.
 *
 * A list rather than six copies of the same line, so "the actions that open
 * something" is one fact stated in one place — the same reason
 * [BACKGROUND_START_WARNING] exists. An action added later gets the requirement
 * by using this, and an action that forgets it is a rule that silently does
 * nothing with the screen off.
 *
 * **Why the overlay permission for an action that draws nothing.** Holding
 * `SYSTEM_ALERT_WINDOW` is one of the few exemptions from the background
 * activity-start ban, and it is the only one an automation app can reach: the
 * others are having a visible window, being the input method, or the user
 * tapping a notification. Measured on Android 15, not assumed — the system logs
 * the allowed launch as `BAL_ALLOW_SAW_PERMISSION`, and blocks it outright
 * without the permission even while the engine's foreground service is running.
 *
 * It is a requirement rather than only a warning because for automation the
 * background case *is* the case. The warning above still carries the nuance that
 * these actions do work while the phone is in use, so a rule that reports this as
 * unmet is not lying about being useless.
 */
internal val ACTIVITY_START_REQUIREMENTS: List<ComponentRequirement> = listOf(
    ComponentRequirement.SpecialAccess(SpecialAccessKind.OVERLAY),
)
