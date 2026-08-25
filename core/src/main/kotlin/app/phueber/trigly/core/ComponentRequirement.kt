package app.phueber.trigly.core

/**
 * Something that must be true on the device before a trigger or action can work.
 *
 * Declared per *type* on the factory rather than per instance, so the UI can
 * tell the user what a trigger needs **before** they build a rule around it —
 * and so a rule that silently never fires can be explained ("Trigly needs
 * notification access") instead of just looking broken. That explanation is the
 * whole reason this type exists: on Android the difference between "no events
 * yet" and "permanently blocked" is invisible from inside the app unless
 * something states the precondition up front.
 */
/**
 * The forms of special access Trigly asks for, each with the settings screen
 * that grants it. Deliberately a closed set: every entry is a serious ask, and
 * a new one should be a considered decision rather than a string someone types.
 */
enum class SpecialAccessKind(
    val settingsAction: String,
    val label: String,
    /**
     * Whether the settings screen documents a `package:` URI for opening this
     * app's own row. Declared rather than assumed, because most of these screens
     * only offer the global list and handing them a package URI makes the intent
     * unresolvable — which would send the user to the top-level settings app
     * instead of anywhere useful.
     *
     * A request, not a guarantee. Android 15's Settings redirects the overlay
     * screen into its newer implementation and shows the whole app list anyway,
     * so the URI is worth sending and not worth promising.
     */
    val packageScoped: Boolean = false,
) {
    NOTIFICATION_LISTENER(
        settingsAction = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
        label = "Notification access",
    ),
    ACCESSIBILITY_SERVICE(
        settingsAction = "android.settings.ACCESSIBILITY_SETTINGS",
        label = "Accessibility access",
    ),
    USAGE_STATS(
        settingsAction = "android.settings.USAGE_ACCESS_SETTINGS",
        label = "Usage access",
    ),

    /** Needed to change Do Not Disturb, which includes silencing the ringer. */
    NOTIFICATION_POLICY(
        settingsAction = "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
        label = "Do Not Disturb access",
    ),

    /**
     * "Display over other apps", and Trigly wants it for its side effect rather
     * than to draw anything.
     *
     * Holding it is one of the few exemptions from Android's ban on an app in the
     * background starting an activity, which is the entire reason every "open"
     * action is unreliable without it. Measured on Android 15 rather than assumed:
     * with the permission the system logs the launch as
     * `BAL_ALLOW_SAW_PERMISSION` and it succeeds; without it,
     * `Background activity launch blocked!` and the start is dropped silently,
     * even while a foreground service is running.
     *
     * The label says what the settings screen calls it, not what Android calls
     * it internally — nobody is looking for "system alert window".
     */
    OVERLAY(
        settingsAction = "android.settings.action.MANAGE_OVERLAY_PERMISSION",
        label = "Display over other apps",
        packageScoped = true,
    ),
}

sealed interface ComponentRequirement {

    /**
     * A permission requested at runtime, e.g. `Manifest.permission.READ_CALENDAR`.
     * Also covers install-time permissions — checking a granted install-time
     * permission is harmless and keeps callers from special-casing.
     */
    data class RuntimePermission(val permission: String) : ComponentRequirement

    /**
     * Access the user must grant in a system settings screen rather than through
     * a permission dialog: notification listener, accessibility service, usage
     * stats.
     *
     * Carries a [kind] rather than just a settings intent because each one is
     * *checked* differently — a secure setting for two of them, an app-op for
     * the third — and there is no generic "is this special access granted" API
     * to switch on.
     */
    data class SpecialAccess(val kind: SpecialAccessKind) : ComponentRequirement

    /** Hardware or software feature, as named by `PackageManager.FEATURE_*`. */
    data class SystemFeature(val feature: String) : ComponentRequirement

    /** Minimum API level, for triggers whose underlying API arrived after minSdk. */
    data class MinApiLevel(val api: Int) : ComponentRequirement

    /**
     * A restriction no amount of user consent lifts: Play Store policy on SMS
     * and call-log access, for instance. Carried so the UI can hide or mark
     * such a trigger in a Play build rather than offering something that cannot
     * ship.
     */
    data class PolicyRestricted(val reason: String) : ComponentRequirement
}

/**
 * Shared supertype of [TriggerFactory] and [ActionFactory].
 *
 * Both are looked up by type string and both may have preconditions, so the
 * requirement plumbing lives here once.
 */
interface ComponentFactory {
    /** Stable identifier, persisted in rules. Renaming it breaks saved rules. */
    val type: String

    /**
     * Human name for pickers and rule summaries. Defaults to [type] so a factory
     * without one is merely ugly rather than broken.
     */
    val displayName: String
        get() = type

    /** Groups this component in the picker. 28 triggers need grouping to be usable. */
    val category: String
        get() = "Other"

    /** Empty means "works on any supported device with no user action". */
    val requirements: List<ComponentRequirement>
        get() = emptyList()

    /**
     * What this component needs *given how it is configured*.
     *
     * Defaults to [requirements], so a component whose needs do not vary says
     * nothing extra. Overriding it is how a component stops demanding access it
     * will not use: `play_alert` needs notification access only when asked to
     * stop early, and `bluetooth_connected` needs the Bluetooth permission only
     * when it has been narrowed to a particular device.
     *
     * This exists because the alternative was showing a Grant button for a
     * permission the rule as written never touches — and, worse, marking a rule
     * "cannot fire" when it could. A requirement that is sometimes irrelevant
     * teaches people to ignore requirements, which is the opposite of what the
     * model is for.
     */
    fun requirementsFor(config: Map<String, String>): List<ComponentRequirement> = requirements

    /**
     * The settings this component accepts, for the editor to render. Empty means
     * "nothing to configure". See [ConfigField] for why this does not replace the
     * validation inside `create()`.
     */
    val configFields: List<ConfigField>
        get() = emptyList()

    /**
     * A caveat worth showing before someone commits to this component — heavy
     * battery use, a platform restriction that will stop it working. Shown
     * prominently in the editor, not buried.
     */
    val warning: String?
        get() = null
}
