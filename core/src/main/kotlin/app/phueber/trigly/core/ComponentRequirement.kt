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
     * stop early.
     *
     * This exists because the alternative was showing a Grant button for a
     * permission the rule as written never touches — and, worse, marking a rule
     * "cannot fire" when it could. A requirement that is sometimes irrelevant
     * teaches people to ignore requirements, which is the opposite of what the
     * model is for.
     *
     * The trap is the other direction, and `bluetooth_connected` fell into it:
     * overriding this needs a *proven* claim that the capability is unused, not
     * a plausible one. A permission that gates the delivery of a broadcast, and
     * not merely a field the receiver reads out of it, is needed by every
     * configuration however little the rule asks of the event. Getting that
     * backwards produces the worse half of the same failure: a rule that cannot
     * fire and a list that says nothing is missing.
     */
    fun requirementsFor(config: Map<String, String>): List<ComponentRequirement> = requirements

    /**
     * Tools this component offers on its own block in the editor, beyond editing
     * its settings.
     *
     * The editor already had two of these and knew both by name: a Test button
     * for actions, and "Add to home screen" for the shortcut trigger, the latter
     * keyed on a config key the screen had to know about. A third — reaching the
     * notification inspector from the components whose filters depend on what a
     * notification actually contains — would have made three special cases, which
     * is where the plugin rule in `CLAUDE.md` says the abstraction is wrong rather
     * than the components.
     *
     * So a component declares what it offers and the editor renders it without
     * knowing any component's name. The pattern matches [configFields] and
     * [requirements] exactly: declared on the factory, consumed by the UI,
     * invisible to the engine.
     *
     * What it deliberately is *not*: a way to run arbitrary UI from a factory.
     * The kinds are a closed set the editor knows how to honour — see [ComponentTool] —
     * because `:core` and `:triggers` must stay free of Compose, and because a
     * component asking for "a button that does anything" would put screen logic
     * in a module that cannot see a screen.
     */
    fun toolsFor(config: Map<String, String>): List<ComponentTool> = emptyList()

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


/**
 * A tool the editor offers on a component's block.
 *
 * A closed set, not a callback: the editor decides how each is drawn and what it
 * does, so a factory in `:triggers` or `:actions` never needs to see Compose or an
 * `Activity`. Adding a kind is a deliberate act — the point is that the editor
 * knows how to honour every one of them.
 */
sealed interface ComponentTool {

    /**
     * Runs the component now, so the sensory half of an action — which sound, how
     * loud, how the spoken text reads — can be judged by ear rather than by
     * saving and waiting for the real trigger.
     *
     * Actions declare this; a trigger cannot, because "run this trigger" means
     * waiting for the world to change.
     */
    data object Test : ComponentTool

    /**
     * Opens the notification inspector: what Trigly can actually see on the
     * notifications currently posted.
     *
     * Declared by the components whose configuration is written *against* that
     * content — a package, a title, a piece of text, a button's name. Those are
     * the fields nobody can fill in correctly by guessing, and where a wrong
     * guess produces a rule that silently never fires.
     */
    data object InspectNotifications : ComponentTool

    /**
     * Asks the launcher to pin a home-screen button for this component.
     *
     * The one tool whose absence makes a component inert: a shortcut trigger with
     * no button on the home screen can never fire, so the affordance is not a
     * convenience but the other half of the feature.
     */
    data object PinShortcut : ComponentTool
}
