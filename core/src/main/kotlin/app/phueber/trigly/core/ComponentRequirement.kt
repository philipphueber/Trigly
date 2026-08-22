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
     * stats. [settingsAction] is the `Settings.ACTION_*` intent action that
     * takes the user there.
     */
    data class SpecialAccess(
        val settingsAction: String,
        val label: String,
    ) : ComponentRequirement

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

    /** Empty means "works on any supported device with no user action". */
    val requirements: List<ComponentRequirement>
        get() = emptyList()
}
