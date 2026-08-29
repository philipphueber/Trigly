package app.phueber.trigly.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LauncherIconSwitcherTest] proves the enable-before-disable order with a
 * fake recorder; this is what only a device can show - that
 * [PackageManagerComponentEnabler] actually leaves exactly one alias enabled
 * afterwards, on the platform's own component bookkeeping.
 *
 * [restoreOrange] runs after every test: the alias state is real device
 * state, and CLAUDE.md's rule about a test that leaks state into the next run
 * applies to it exactly the way it applies to a granted permission.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconAliasOnDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager: PackageManager = context.packageManager
    private val allIds = ColorPresets.map { it.id }
    private val enabler = PackageManagerComponentEnabler(context)

    private fun componentFor(id: String) =
        ComponentName(context.packageName, context.packageName + launcherAliasName(id))

    /**
     * Orange's `android:enabled="true"` is the manifest default, so a
     * component nobody has ever toggled reports `COMPONENT_ENABLED_STATE_DEFAULT`
     * rather than `_ENABLED` - this reads through to what the manifest says
     * for exactly that case.
     */
    private fun enabledAliases(): List<String> = allIds.filter { id ->
        when (packageManager.getComponentEnabledSetting(componentFor(id))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> id == "orange"
        }
    }

    @After
    fun restoreOrange() {
        switchLauncherIcon("orange", allIds, enabler)
    }

    @Test
    fun switching_to_a_preset_leaves_exactly_that_one_alias_enabled() {
        switchLauncherIcon("lime", allIds, enabler)

        assertEquals(listOf("lime"), enabledAliases())
    }

    /**
     * The very first call this device ever makes: nothing has switched yet,
     * so this exercises the alias still sitting on the manifest's own
     * `android:enabled` rather than on a value this class wrote.
     */
    @Test
    fun the_first_switch_away_from_the_manifest_default_leaves_exactly_one_enabled() {
        assertEquals(listOf("orange"), enabledAliases())

        switchLauncherIcon("azure", allIds, enabler)

        assertEquals(listOf("azure"), enabledAliases())
    }

    @Test
    fun switching_back_to_orange_leaves_exactly_orange_enabled() {
        switchLauncherIcon("violet", allIds, enabler)

        switchLauncherIcon("orange", allIds, enabler)

        assertEquals(listOf("orange"), enabledAliases())
    }
}
