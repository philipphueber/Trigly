package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ConfigField
import org.junit.Assert.assertEquals
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app-package field: a picker rather than a text box.
 *
 * Driven with a supplied app list rather than whatever the test device happens to
 * have installed — the emulator's app set differs by API level and image, and a
 * test that asserts "Calendar is in the list" would be testing the image.
 */
@RunWith(AndroidJUnit4::class)
class AppPickerTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val apps = listOf(
        InstalledApp("com.example.chat", "Chatty"),
        InstalledApp("com.example.maps", "Wander"),
    )

    private val picked = mutableListOf<String?>()

    @Composable
    private fun Field(field: ConfigField.AppPackage, value: String?) {
        CompositionLocalProvider(LocalInstalledApps provides apps) {
            ConfigFieldEditor(field = field, value = value, onValueChange = { picked += it })
        }
    }

    private val optional = ConfigField.AppPackage(
        key = "package",
        label = "App",
        blankMeaning = "Any app",
    )

    private val required = ConfigField.AppPackage(
        key = "package",
        label = "App",
        required = true,
    )

    @Test
    fun an_unset_optional_field_says_what_blank_means() {
        composeRule.setContent { Field(optional, value = null) }

        // The field's *value* is the blank meaning, not a hint below an empty box:
        // "any app" is a real setting for these components, not a missing one.
        composeRule.onNodeWithText("ANY APP").assertIsDisplayed()
    }

    @Test
    fun an_unset_required_field_asks_for_a_choice() {
        composeRule.setContent { Field(required, value = null) }

        composeRule.onNodeWithText("CHOOSE AN APP").assertIsDisplayed()
    }

    @Test
    fun a_stored_package_shows_the_app_name_and_the_package() {
        composeRule.setContent { Field(required, value = "com.example.maps") }

        // The name is what a person recognises; the package is what the rule
        // stores, and hiding it would make a mis-picked app impossible to spot.
        composeRule.onNodeWithText("WANDER").assertIsDisplayed()
        composeRule.onNodeWithText("com.example.maps").assertIsDisplayed()
    }

    @Test
    fun a_package_that_is_not_installed_still_shows_itself() {
        // A rule imported from another phone, or an app since uninstalled.
        composeRule.setContent { Field(required, value = "com.example.gone") }

        composeRule.onNodeWithText("COM.EXAMPLE.GONE").assertIsDisplayed()
    }

    @Test
    fun picking_an_app_reports_its_package_not_its_name() {
        composeRule.setContent { Field(required, value = null) }

        composeRule.onNodeWithText("CHOOSE AN APP").performClick()
        composeRule.onNodeWithText("CHATTY").performClick()

        assertEquals(listOf("com.example.chat"), picked)
    }

    @Test
    fun the_list_can_be_searched_by_name_or_by_package() {
        composeRule.setContent { Field(required, value = null) }
        composeRule.onNodeWithText("CHOOSE AN APP").performClick()

        composeRule.onNodeWithText("SEARCH OR TYPE A PACKAGE").performTextReplacement("wander")
        composeRule.onNodeWithText("WANDER").assertIsDisplayed()
        composeRule.onNodeWithText("CHATTY").assertDoesNotExist()

        composeRule.onNodeWithText("SEARCH OR TYPE A PACKAGE").performTextReplacement("chat")
        composeRule.onNodeWithText("CHATTY").assertIsDisplayed()
    }

    @Test
    fun a_typed_package_that_is_not_listed_can_still_be_used() {
        // The list is launcher apps only, so a service with no icon — a plausible
        // watchdog target — has to be reachable by typing it.
        composeRule.setContent { Field(required, value = null) }
        composeRule.onNodeWithText("CHOOSE AN APP").performClick()

        composeRule
            .onNodeWithText("SEARCH OR TYPE A PACKAGE")
            .performTextReplacement("com.alerting.service")
        composeRule.onNodeWithText("USE \"COM.ALERTING.SERVICE\"").performClick()

        assertEquals(listOf("com.alerting.service"), picked)
    }

    @Test
    fun a_search_phrase_is_not_offered_as_a_package() {
        composeRule.setContent { Field(required, value = null) }
        composeRule.onNodeWithText("CHOOSE AN APP").performClick()

        composeRule.onNodeWithText("SEARCH OR TYPE A PACKAGE").performTextReplacement("chatty")

        composeRule.onNodeWithText("USE \"CHATTY\"").assertDoesNotExist()
    }

    @Test
    fun an_optional_field_can_be_set_back_to_any_app() {
        // Opening the picker must not be a one-way door for a field whose
        // blankness is a real setting.
        composeRule.setContent { Field(optional, value = "com.example.chat") }

        composeRule.onNodeWithText("CHATTY").performClick()
        composeRule.onNodeWithText("ANY APP").performClick()

        assertEquals(listOf<String?>(null), picked)
    }
}
