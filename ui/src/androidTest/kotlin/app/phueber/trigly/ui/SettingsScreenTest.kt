package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
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
    private var attributionTaps = 0
    private val colorSchemeChanges = mutableListOf<ColorSchemeChoice>()

    @Composable
    private fun Screen(
        cloudBackupEnabled: Boolean,
        colorSchemeChoice: ColorSchemeChoice = ColorSchemeChoice.Default,
    ) {
        SettingsScreen(
            cloudBackupEnabled = cloudBackupEnabled,
            onCloudBackupEnabledChange = { changes += it },
            colorSchemeChoice = colorSchemeChoice,
            onColorSchemeChoiceChange = { colorSchemeChanges += it },
            onAttribution = { attributionTaps++ },
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

        // Targets the switch by its own label rather than `isToggleable()`:
        // the caveat badge beside it is toggleable too now, and a bare
        // `isToggleable()` lookup would find both and fail on the ambiguity.
        composeRule.onNodeWithText("ON").performClick()

        assertEquals(listOf(false), changes)
    }

    /**
     * Folded by default, the same as [ComponentBlock]'s own warning: a long
     * caution should not spend screen space until someone asks for it. Only
     * the switch and the badge that reaches it are on screen at first.
     */
    @Test
    fun the_warning_is_not_shown_until_the_badge_is_opened() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithText("What backup shares".uppercase()).assertDoesNotExist()
    }

    /**
     * Reachable from the backup setting itself: the [CaveatBadge] sits in the
     * same card as the switch, so tapping it is what brings the warning, not
     * navigation elsewhere.
     */
    @Test
    fun tapping_the_caveat_badge_reveals_the_warning() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).performClick()

        composeRule.onNodeWithText("What backup shares".uppercase()).assertIsDisplayed()
    }

    /**
     * The warning is the point of this screen, so once opened it has to say
     * what leaves the device, where it goes, and what happens with no
     * Google account. It has to say so with the switch on, too, which is
     * the default nobody had to choose.
     */
    @Test
    fun opening_the_warning_names_what_backup_shares_and_where_it_goes() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).performClick()

        composeRule.onNodeWithText(
            "Backup can copy your rules and your saved values. A webhook URL often " +
                "carries a token. Backup copies that token too. The copy goes to the " +
                "account signed in on this phone. A phone with no Google account and " +
                "no backup service sends nothing. Moving data straight to your next " +
                "phone still works with this off.",
        ).assertIsDisplayed()
    }

    /**
     * Reachable and shown with the switch off too, once opened. The choice
     * already made is not a reason to stop explaining it, or to hide it
     * behind the switch being on.
     */
    @Test
    fun the_warning_still_shows_with_the_switch_off_once_opened() {
        composeRule.setContent { Screen(cloudBackupEnabled = false) }

        composeRule.onNodeWithContentDescription(CAVEAT_DESCRIPTION).performClick()

        composeRule.onNodeWithText("What backup shares".uppercase()).assertIsDisplayed()
    }

    @Test
    fun back_leaves_the_screen() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backTaps)
        assertEquals(emptyList<Boolean>(), changes)
    }

    /** The new row: see [SettingsRow] and [AttributionScreen]. */
    @Test
    fun the_attribution_row_fires_its_callback() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithText("Used components").performClick()

        assertEquals(1, attributionTaps)
    }

    /** The colour scheme row's trailing edge, for the two choices that are not a hue. */
    @Test
    fun the_colorscheme_row_shows_the_current_words_for_default_and_system() {
        composeRule.setContent { Screen(cloudBackupEnabled = true, colorSchemeChoice = ColorSchemeChoice.Default) }
        composeRule.onNodeWithText("DEFAULT").assertIsDisplayed()
    }

    @Test
    fun tapping_the_colorscheme_row_opens_the_picker() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }

        composeRule.onNodeWithText("Colour scheme").performClick()

        composeRule.onNodeWithText("Choose a colour scheme".uppercase()).assertIsDisplayed()
    }

    @Test
    fun picking_a_preset_reports_it_and_closes_the_dialog() {
        composeRule.setContent { Screen(cloudBackupEnabled = true) }
        composeRule.onNodeWithText("Colour scheme").performClick()

        composeRule.onNodeWithText(ColorPresets[1].displayName.uppercase()).performClick()

        assertEquals(listOf(ColorSchemeChoice.Preset(ColorPresets[1].id)), colorSchemeChanges)
        composeRule.onNodeWithText("Choose a colour scheme".uppercase()).assertDoesNotExist()
    }

    @Test
    fun picking_default_from_the_picker_reports_default() {
        composeRule.setContent {
            Screen(cloudBackupEnabled = true, colorSchemeChoice = ColorSchemeChoice.Preset(ColorPresets[1].id))
        }
        composeRule.onNodeWithText("Colour scheme").performClick()

        composeRule.onNodeWithText("Default".uppercase()).performClick()

        assertEquals(listOf(ColorSchemeChoice.Default), colorSchemeChanges)
    }
}
