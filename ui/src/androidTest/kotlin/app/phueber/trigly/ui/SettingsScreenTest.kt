package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [SettingsScreen] the way [SavedValuesScreenTest] drives
 * [SavedValuesScreen]: a plain boolean and stub callbacks, no ViewModel and no
 * `BackupSettings` behind it.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val changes = mutableListOf<Boolean>()
    private var backTaps = 0

    @Composable
    private fun Screen(cloudBackupEnabled: Boolean) {
        SettingsScreen(
            cloudBackupEnabled = cloudBackupEnabled,
            onCloudBackupEnabledChange = { changes += it },
            onBack = { backTaps++ },
        )
    }

    @Test
    fun the_switch_shows_on_by_default() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithText("ON").assertIsDisplayed()
    }

    @Test
    fun the_switch_shows_off_once_the_user_turned_it_off() {
        composeRule.setContent { Screen(cloudBackupEnabled = false) }

        composeRule.onNodeWithText("OFF").assertIsDisplayed()
    }

    @Test
    fun toggling_reports_the_new_state() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNode(isToggleable()).performClick()

        assertEquals(listOf(false), changes)
    }

    /**
     * The warning is the point of this screen, so it has to say what leaves
     * the device, where it goes, and what happens with no Google account.
     * It has to say so with the switch on, too, which is the default nobody
     * had to choose.
     */
    @Test
    fun the_warning_names_what_backup_shares_and_where_it_goes() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithText(
            "Backup can copy your rules and your saved values. A webhook URL often " +
                "carries a token. Backup copies that token too. The copy goes to the " +
                "account signed in on this phone. A phone with no Google account and " +
                "no backup service sends nothing. Moving data straight to your next " +
                "phone still works with this off.",
        ).assertIsDisplayed()
    }

    /** Shown with the switch off too. The choice already made is not a reason to stop explaining it. */
    @Test
    fun the_warning_still_shows_with_the_switch_off() {
        composeRule.setContent { Screen(cloudBackupEnabled = false) }

        composeRule.onNodeWithText("What backup shares".uppercase()).assertIsDisplayed()
    }

    @Test
    fun back_leaves_the_screen() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backTaps)
        assertEquals(emptyList<Boolean>(), changes)
    }
}
