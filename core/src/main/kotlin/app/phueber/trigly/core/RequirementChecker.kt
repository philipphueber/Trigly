package app.phueber.trigly.core

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Whether a granted [ComponentRequirement.SpecialAccess] requirement is
 * actually live right now, as opposed to merely granted.
 *
 * Android lets the two drift apart for the two kinds backed by a bindable
 * service (see [SpecialAccessKind.bindsAService]): the setting stays on
 * across an app update or a process kill, but nothing rebinds the service on
 * its own, so a rule can be enabled, granted, and dead all at once. This is
 * the axis [RequirementChecker.isSatisfied] cannot see.
 *
 * Three states rather than two, because the honest answer is sometimes "I
 * cannot tell":
 *
 *  - [LIVE] the service is bound right now, or this requirement has nothing
 *    to bind in the first place.
 *  - [NOT_LIVE] granted, but the service is confirmed not bound right now.
 *  - [UNKNOWN] nobody has wired a real answer in for this call. Reported
 *    exactly like [LIVE] would be for the purpose of blocking a rule, because
 *    a requirement model only earns trust by never accusing a service of
 *    being dead on silence rather than on evidence.
 */
enum class Liveness {
    LIVE,
    NOT_LIVE,
    UNKNOWN,
}

/**
 * Answers whether the service behind a [SpecialAccessKind] is bound right now.
 *
 * The fact itself lives outside `:core`: the notification listener and the
 * accessibility service are both constructed by the framework in `:triggers`,
 * and `:core` must not depend on that module. So this is a port in the same
 * shape as [NotificationController] and [UiController], which already answer
 * the identical question for their own callers through `isConnected`. `:ui` is
 * the one place that can see both a probe implementation and
 * [RequirementChecker], so wiring happens there.
 */
interface LivenessProbe {

    /**
     * Null when nobody has wired a real answer in yet. Never guessed at: a
     * caller with nothing to go on must say so rather than assume the worst,
     * which is what keeps [RequirementChecker.liveness] from reporting
     * [Liveness.NOT_LIVE] on a service it never actually asked about.
     */
    fun isBound(kind: SpecialAccessKind): Boolean?

    /** The safe default: no information, so nothing is ever reported dead. */
    companion object Unknown : LivenessProbe {
        override fun isBound(kind: SpecialAccessKind): Boolean? = null
    }
}

/**
 * A [LivenessProbe] built from the two controller ports the app already
 * wires, rather than a third way of asking `:triggers` the same question.
 *
 * [NotificationController.isConnected] and [UiController.isConnected] already
 * read the live state of the notification listener and the accessibility
 * service, because the actions behind those ports need to know before calling
 * into either one. This reuses that exact fact instead of adding a second path
 * to it, which is also why it needs no dependency `:core` does not already
 * have.
 */
class ControllerLivenessProbe(
    private val notifications: NotificationController,
    private val ui: UiController,
) : LivenessProbe {
    override fun isBound(kind: SpecialAccessKind): Boolean? = when (kind) {
        SpecialAccessKind.NOTIFICATION_LISTENER -> notifications.isConnected
        SpecialAccessKind.ACCESSIBILITY_SERVICE -> ui.isConnected
        SpecialAccessKind.USAGE_STATS,
        SpecialAccessKind.NOTIFICATION_POLICY,
        SpecialAccessKind.OVERLAY,
        -> null
    }
}

/**
 * The decision behind [RequirementChecker.liveness], pulled out as a pure
 * function so it is unit-testable on the JVM without an Android `Context`.
 * [RequirementChecker.isSatisfied] needs one; this does not, because whether
 * the requirement is [granted] is passed in rather than read from the device.
 *
 * @param granted whether the requirement this [kind] backs is currently
 *   satisfied, as [RequirementChecker.isSatisfied] would report it.
 */
fun livenessOf(kind: SpecialAccessKind, granted: Boolean, probe: LivenessProbe): Liveness {
    if (!kind.bindsAService) return Liveness.LIVE
    if (!granted) return Liveness.LIVE

    return when (probe.isBound(kind)) {
        true -> Liveness.LIVE
        false -> Liveness.NOT_LIVE
        null -> Liveness.UNKNOWN
    }
}

/**
 * Whether a component's [ComponentRequirement]s are actually met on this device.
 *
 * The counterpart to declaring them: `ComponentRequirement` says what is needed,
 * this says what is missing, and together they let the UI answer "why isn't my
 * rule firing?" — the question Android itself gives an app no way to answer.
 */
class RequirementChecker(private val context: Context) {

    fun isSatisfied(requirement: ComponentRequirement): Boolean = when (requirement) {
        is ComponentRequirement.RuntimePermission ->
            context.checkSelfPermission(requirement.permission) ==
                PackageManager.PERMISSION_GRANTED

        is ComponentRequirement.SystemFeature ->
            context.packageManager.hasSystemFeature(requirement.feature)

        is ComponentRequirement.MinApiLevel ->
            Build.VERSION.SDK_INT >= requirement.api

        is ComponentRequirement.SpecialAccess -> when (requirement.kind) {
            SpecialAccessKind.NOTIFICATION_LISTENER -> hasEnabledComponent(
                ENABLED_NOTIFICATION_LISTENERS
            )
            SpecialAccessKind.ACCESSIBILITY_SERVICE -> hasEnabledComponent(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            SpecialAccessKind.USAGE_STATS -> hasUsageStatsAccess()
            SpecialAccessKind.NOTIFICATION_POLICY ->
                context.getSystemService(NotificationManager::class.java)
                    ?.isNotificationPolicyAccessGranted == true
            // Its own API rather than a secure setting or an app-op lookup, which
            // is exactly why this enum carries a kind instead of just an intent.
            SpecialAccessKind.OVERLAY -> Settings.canDrawOverlays(context)
        }

        // Not a device condition — no setting the user can change makes an app
        // eligible for a restricted API. Reported as satisfied so it never
        // blocks a sideloaded build, and surfaced separately as a warning.
        is ComponentRequirement.PolicyRestricted -> true
    }

    fun unmet(requirements: List<ComponentRequirement>): List<ComponentRequirement> =
        requirements.filterNot(::isSatisfied)

    /**
     * Whether *anything the user could do* would make this requirement hold.
     *
     * The distinction [isSatisfied] cannot make: a missing permission is a
     * prompt away, while an API that arrived after this phone's Android version
     * and a radio the phone does not have are permanent. Only two of the five
     * requirement kinds can fail permanently, which is why this is a short
     * `when` rather than a flag on the requirement.
     *
     * [ComponentRequirement.PolicyRestricted] is deliberately *not* permanent.
     * It says Google will not publish this on Play, which has nothing to do with
     * whether it works on the device in front of you — and Trigly is meant to be
     * sideloadable, so hiding those would remove working features from the
     * people most likely to want them.
     */
    fun isPossible(requirement: ComponentRequirement): Boolean = when (requirement) {
        is ComponentRequirement.MinApiLevel,
        is ComponentRequirement.SystemFeature,
        -> isSatisfied(requirement)

        is ComponentRequirement.RuntimePermission,
        is ComponentRequirement.SpecialAccess,
        is ComponentRequirement.PolicyRestricted,
        -> true
    }

    /**
     * Whether this component can work on this device at all.
     *
     * What the editor's pickers filter on. Offering a trigger that this phone
     * can never fire is worse than not listing it: the user builds a rule around
     * it, nothing happens, and the app looks broken rather than honest.
     */
    fun isAvailable(descriptor: ComponentDescriptor): Boolean =
        descriptor.requirements.all(::isPossible)

    /** Why a component is unavailable, for the rare case something must explain it. */
    fun impossible(descriptor: ComponentDescriptor): List<ComponentRequirement> =
        descriptor.requirements.filterNot(::isPossible)

    /** Everything standing between [rule] and firing. Empty means nothing is. */
    fun unmet(rule: Rule, registry: Registry): List<ComponentRequirement> =
        unmet(registry.requirementsOf(rule))

    /**
     * Whether [requirement] is live right now, given what [probe] can see.
     *
     * Only a granted [ComponentRequirement.SpecialAccess] whose kind
     * [SpecialAccessKind.bindsAService] can ever come back [Liveness.NOT_LIVE].
     * Everything else is reported [Liveness.LIVE], and deliberately so:
     *
     *  - A requirement that is not granted at all already has its own answer
     *    through [isSatisfied] and [unmet]. Reporting it dead too would say
     *    the same fact twice, once as "you never granted this" and once as
     *    "granted, but not working", which is confusing rather than informative
     *    and is exactly the double-accounting the two-answer model in
     *    [Liveness] exists to avoid.
     *  - A [SpecialAccessKind] with nothing to bind has no liveness question to
     *    ask, so [probe] is never even called for it.
     *  - [probe] defaults to [LivenessProbe.Unknown], and its null answer
     *    passes straight through as [Liveness.LIVE] via [Liveness.UNKNOWN].
     *    A caller that has not wired a real probe in gets silence, never an
     *    accusation.
     */
    fun liveness(
        requirement: ComponentRequirement,
        probe: LivenessProbe = LivenessProbe.Unknown,
    ): Liveness {
        if (requirement !is ComponentRequirement.SpecialAccess) return Liveness.LIVE
        return livenessOf(requirement.kind, isSatisfied(requirement), probe)
    }

    /**
     * Requirements that are granted, but whose service [probe] confirms is not
     * bound right now.
     *
     * The other half of [unmet]. That list is what a settings screen has not
     * yet granted; this is what a settings screen already shows as granted and
     * has nothing left to offer, because the fault is not the grant.
     */
    fun grantedButNotLive(
        requirements: List<ComponentRequirement>,
        probe: LivenessProbe = LivenessProbe.Unknown,
    ): List<ComponentRequirement> = requirements.filter { liveness(it, probe) == Liveness.NOT_LIVE }

    /** [grantedButNotLive], for every requirement [rule] actually needs. */
    fun grantedButNotLive(
        rule: Rule,
        registry: Registry,
        probe: LivenessProbe = LivenessProbe.Unknown,
    ): List<ComponentRequirement> = grantedButNotLive(registry.requirementsOf(rule), probe)

    private fun hasEnabledComponent(secureSetting: String): Boolean =
        isPackageEnabledIn(
            setting = Settings.Secure.getString(context.contentResolver, secureSetting),
            packageName = context.packageName,
        )

    private fun hasUsageStatsAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private companion object {
        /**
         * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` is not public API,
         * but the key string is stable and is what the framework reads.
         */
        const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
    }
}

/**
 * Both "is my listener enabled" settings hold a colon-separated list of
 * flattened `ComponentName`s. Pure, so the parsing is unit-tested rather than
 * assumed — the format has enough edge cases (empty string, trailing colons,
 * another app whose package name merely starts with ours) to be worth it.
 */
fun isPackageEnabledIn(setting: String?, packageName: String): Boolean {
    if (setting.isNullOrBlank()) return false
    return setting.split(':')
        .filter { it.isNotBlank() }
        .any { it.substringBefore('/') == packageName }
}
