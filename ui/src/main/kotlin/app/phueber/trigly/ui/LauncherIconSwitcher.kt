package app.phueber.trigly.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Keeps exactly one launcher-icon `activity-alias` enabled, matching whichever
 * colour scheme is chosen.
 *
 * **Why an alias per preset costs its own file, but not its own case here.**
 * An `activity-alias` cannot be declared at runtime, so the manifest holds six
 * of them - one `enabled="true"` (Orange, the install default) and five
 * `enabled="false"`. Everything in this file is a pure function of a
 * [ColorPreset.id], so a seventh preset costs one more manifest entry and
 * nothing here - see `PresetSchemes.kt`'s own note on growing the list.
 *
 * **"Follow the system" and Default both mean the orange icon.** Neither has
 * an icon of its own: Default *is* the orange preset, and no pre-built asset
 * matches an arbitrary wallpaper palette, so inventing one for System would
 * be guessing. [aliasIdFor] is where that collapses to one answer.
 */

/** The alias shown for a choice with no icon of its own - see the file doc. */
private const val ORANGE_ALIAS_ID = "orange"

/**
 * The manifest name of the `activity-alias` for a preset id: `.LauncherAliasOrange`,
 * `.LauncherAliasLime`, and so on. A pure function of [id] rather than a
 * lookup table, so the six existing entries never need touching for a
 * seventh - only the manifest, which must name it in exactly this shape.
 */
fun launcherAliasName(id: String): String = ".LauncherAlias" + id.replaceFirstChar { it.uppercaseChar() }

/** Which alias a [ColorSchemeChoice] should leave enabled. */
fun aliasIdFor(choice: ColorSchemeChoice): String = (choice as? ColorSchemeChoice.Preset)?.id ?: ORANGE_ALIAS_ID

/**
 * One alias, enabled or disabled. The seam [switchLauncherIcon] is tested
 * through, so the ordering it guarantees is checked with a fake recorder and
 * no device or real `PackageManager`.
 */
interface ComponentEnabler {
    fun enable(aliasId: String)
    fun disable(aliasId: String)
}

/**
 * Enables [target]'s alias, then disables every other id in [allIds] - in
 * that order, never the reverse, and never both at once.
 *
 * **Why this order.** The platform allows more than one enabled alias at
 * once; nothing but this ordering keeps it to one. If the process dies
 * between the two calls - and [PackageManagerComponentEnabler]'s own doc is
 * why that is a real possibility here - enabling first leaves *two* icons on
 * the home screen: visible, but harmless and self-correcting on the next
 * successful switch. Disabling first and dying before the enable call leaves
 * *zero* enabled aliases, which some launchers render as a missing or broken
 * icon rather than the one it used to show, and nothing in this app can
 * detect or repair that from here. Recoverable beats unrecoverable, so
 * enabling always happens first.
 */
fun switchLauncherIcon(target: String, allIds: List<String>, enabler: ComponentEnabler) {
    enabler.enable(target)
    allIds.filter { it != target }.forEach(enabler::disable)
}

/**
 * The real [ComponentEnabler], through `PackageManager.setComponentEnabledSetting`.
 *
 * `DONT_KILL_APP` on both calls, not just the disabling one. Without it on
 * the *enabling* call, that first call alone restarts the process before the
 * disabling call ever runs - which is exactly the "zero enabled aliases"
 * failure [switchLauncherIcon]'s own doc explains, and the reason this
 * function is the one place that ordering has to be trusted to matter.
 *
 * No permission is required: an app may toggle its own components freely.
 */
class PackageManagerComponentEnabler(private val context: Context) : ComponentEnabler {
    override fun enable(aliasId: String) = setEnabled(aliasId, enabled = true)
    override fun disable(aliasId: String) = setEnabled(aliasId, enabled = false)

    private fun setEnabled(aliasId: String, enabled: Boolean) {
        val component = ComponentName(context.packageName, context.packageName + launcherAliasName(aliasId))
        context.packageManager.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }
}
