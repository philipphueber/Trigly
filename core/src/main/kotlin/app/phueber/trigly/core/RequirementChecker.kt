package app.phueber.trigly.core

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

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
        }

        // Not a device condition — no setting the user can change makes an app
        // eligible for a restricted API. Reported as satisfied so it never
        // blocks a sideloaded build, and surfaced separately as a warning.
        is ComponentRequirement.PolicyRestricted -> true
    }

    fun unmet(requirements: List<ComponentRequirement>): List<ComponentRequirement> =
        requirements.filterNot(::isSatisfied)

    /** Everything standing between [rule] and firing. Empty means nothing is. */
    fun unmet(rule: Rule, registry: Registry): List<ComponentRequirement> =
        unmet(registry.requirementsOf(rule))

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
