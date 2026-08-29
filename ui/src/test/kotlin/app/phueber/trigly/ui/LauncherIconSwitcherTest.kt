package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [switchLauncherIcon]'s whole job is the order it calls [ComponentEnabler]
 * in - enable, then disable the rest - so this drives it with a fake
 * recorder rather than a device, the same way [BackupPolicyTest] tests a
 * decision instead of the `SharedPreferences` behind it.
 */
class LauncherIconSwitcherTest {

    private class RecordingEnabler : ComponentEnabler {
        val calls = mutableListOf<Pair<String, Boolean>>()
        override fun enable(aliasId: String) {
            calls += aliasId to true
        }
        override fun disable(aliasId: String) {
            calls += aliasId to false
        }
    }

    private val allIds = ColorPresets.map { it.id }

    @Test
    fun `the target is enabled before anything is disabled`() {
        val enabler = RecordingEnabler()

        switchLauncherIcon("lime", allIds, enabler)

        assertEquals("lime" to true, enabler.calls.first())
    }

    @Test
    fun `every other preset is disabled, and only those`() {
        val enabler = RecordingEnabler()

        switchLauncherIcon("lime", allIds, enabler)

        val disabled = enabler.calls.filter { !it.second }.map { it.first }
        assertEquals((allIds - "lime").toSet(), disabled.toSet())
        assertEquals("no double-disable and the target never disabled", disabled.size, disabled.toSet().size)
    }

    @Test
    fun `the target is never also disabled`() {
        val enabler = RecordingEnabler()

        switchLauncherIcon("lime", allIds, enabler)

        assertEquals(1, enabler.calls.count { it.first == "lime" })
    }

    /**
     * The install default: Orange's alias is already the one enabled by the
     * manifest, and picking a colour scheme for the first time still has to
     * leave exactly one alias enabled - this is that first switch, away from
     * whatever the manifest shipped.
     */
    @Test
    fun `the first switch away from the manifest default still enables before disabling`() {
        val enabler = RecordingEnabler()

        switchLauncherIcon("orange", allIds, enabler)

        assertEquals("orange" to true, enabler.calls.first())
        assertEquals((allIds - "orange").toSet(), enabler.calls.filter { !it.second }.map { it.first }.toSet())
    }

    @Test
    fun `aliasIdFor names the orange alias for Default and System`() {
        assertEquals("orange", aliasIdFor(ColorSchemeChoice.Default))
        assertEquals("orange", aliasIdFor(ColorSchemeChoice.System))
    }

    @Test
    fun `aliasIdFor names the preset's own id for a preset`() {
        assertEquals("lime", aliasIdFor(ColorSchemeChoice.Preset("lime")))
    }

    @Test
    fun `the alias name is derived from the id, not looked up`() {
        assertEquals(".LauncherAliasOrange", launcherAliasName("orange"))
        assertEquals(".LauncherAliasLime", launcherAliasName("lime"))
        assertEquals(".LauncherAliasMagenta", launcherAliasName("magenta"))
    }
}
