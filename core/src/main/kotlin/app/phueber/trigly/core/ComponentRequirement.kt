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
enum class SpecialAccessKind(val settingsAction: String, val label: String) {
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
