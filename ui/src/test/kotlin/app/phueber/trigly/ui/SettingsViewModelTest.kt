package app.phueber.trigly.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one tap on a colour swatch has to set in motion.
 *
 * Three things, not one, and the third is the one that was missing: the
 * stored choice, the launcher-icon alias, and a poke at the engine so its
 * ongoing notification is rebuilt with the new tint. Without the poke the
 * shade kept the previous colour until something unrelated re-posted the
 * notification, which is what "changing the colour does not work on the
 * notification" turned out to be.
 *
 * Driven with fakes and no device, the same way [LauncherIconSwitcherTest]
 * drives the switch itself: every collaborator here is an interface or a
 * function, and what is under test is that the view model calls all of them.
 */
class SettingsViewModelTest {

    private class FakeBackupSettings : BackupSettings {
        var enabled = true
        override fun cloudBackupEnabled(): Boolean = enabled
        override fun setCloudBackupEnabled(enabled: Boolean) {
            this.enabled = enabled
        }
    }

    private class FakeColorSchemeSettings : ColorSchemeSettings {
        var choice: ColorSchemeChoice = ColorSchemeChoice.Default
        override fun colorSchemeChoice(): ColorSchemeChoice = choice
        override fun setColorSchemeChoice(choice: ColorSchemeChoice) {
            this.choice = choice
        }
    }

    private class RecordingEnabler : ComponentEnabler {
        val enabled = mutableListOf<String>()
        override fun enable(aliasId: String) {
            enabled += aliasId
        }
        override fun disable(aliasId: String) = Unit
    }

    private val backup = FakeBackupSettings()
    private val colors = FakeColorSchemeSettings()
    private val enabler = RecordingEnabler()
    private var pokes = 0

    private fun viewModel() = SettingsViewModel(
        backupSettings = backup,
        colorSchemeSettings = colors,
        launcherIconEnabler = enabler,
        pokeEngine = { pokes++ },
    )

    @Test
    fun `a new colour scheme is stored, aliased and poked`() {
        val model = viewModel()

        model.setColorSchemeChoice(ColorSchemeChoice.Preset("lime"))

        assertEquals(ColorSchemeChoice.Preset("lime"), colors.choice)
        assertEquals(listOf("lime"), enabler.enabled)
        assertEquals(1, pokes)
    }

    /**
     * The engine has to be poked for the two choices with no icon of their
     * own as well. Neither changes the alias away from orange, so the icon
     * switch is a no-op for both, and the notification's tint still changes:
     * Default and the wallpaper palette are different colours from whatever
     * preset was chosen before.
     */
    @Test
    fun `the choices with no icon of their own still poke the engine`() {
        val model = viewModel()

        model.setColorSchemeChoice(ColorSchemeChoice.Default)
        model.setColorSchemeChoice(ColorSchemeChoice.System)

        assertEquals(listOf("orange", "orange"), enabler.enabled)
        assertEquals(2, pokes)
    }

    /**
     * Picking the scheme that is already chosen still pokes. The view model
     * deliberately does not compare first, for the reason
     * `setColorSchemeChoice` gives about the alias, and the same answer is
     * right here: a notification that is already the right colour is re-posted
     * with the same colour, which nobody can see.
     */
    @Test
    fun `re-picking the same scheme pokes again rather than deciding for itself`() {
        val model = viewModel()

        model.setColorSchemeChoice(ColorSchemeChoice.Preset("azure"))
        model.setColorSchemeChoice(ColorSchemeChoice.Preset("azure"))

        assertEquals(2, pokes)
    }

    @Test
    fun `the exposed choice follows the one just set`() {
        val model = viewModel()

        model.setColorSchemeChoice(ColorSchemeChoice.Preset("violet"))

        assertEquals(ColorSchemeChoice.Preset("violet"), model.colorSchemeChoice.value)
    }

    /**
     * The backup switch shares this class and must not have grown an engine
     * poke by accident: the engine's notification says how many rules are
     * running, and whether they may be backed up is nothing to do with it.
     */
    @Test
    fun `the backup switch does not poke the engine`() {
        val model = viewModel()

        model.setCloudBackupEnabled(false)

        assertEquals(0, pokes)
        assertTrue("the backup switch touched the launcher icon", enabler.enabled.isEmpty())
    }
}
