package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [SavedValuesScreen] the way [RulesScreenTest] drives [RulesScreen]: a
 * plain list of rows and stub callbacks, no ViewModel and no `VariableStore`
 * behind any of it.
 *
 * Button labels are asserted in capitals, the same convention [RulesScreenTest]
 * documents for itself: [BlockButton] and [BlockTextButton] both uppercase
 * their own text, so that is what the accessibility tree, and a screen reader,
 * actually contain. The empty state and the delete warning are prose and
 * stay in sentence case.
 */
@RunWith(AndroidJUnit4::class)
class SavedValuesScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val added = mutableListOf<Pair<String, String>>()
    private val edited = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private var backTaps = 0

    /** Rejects only the one name the test cares about, like a real check would. */
    private var invalidName: String? = null

    @Composable
    private fun Screen(values: List<SavedValueRow>) {
        SavedValuesScreen(
            values = values,
            onAddValue = { name, value -> added += name to value },
            onEditValue = { name, value -> edited += name to value },
            onDeleteValue = { deleted += it },
            nameProblem = { name ->
                if (name == invalidName) "'$name' cannot be read back by a rule." else null
            },
            onBack = { backTaps++ },
        )
    }

    @Test
    fun empty_state_explains_how_a_value_comes_to_exist() {
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText(
            "No values are saved yet. A rule writes one with the Set an app " +
                "variable action. Any rule can then read it.",
        ).assertIsDisplayed()
    }

    @Test
    fun a_row_shows_its_name_and_value() {
        val row = SavedValueRow(
            name = "trip_count",
            value = "5",
            lastChangedMillis = System.currentTimeMillis(),
        )
        composeRule.setContent { Screen(listOf(row)) }

        composeRule.onNodeWithText("TRIP_COUNT").assertIsDisplayed()
        composeRule.onNodeWithText("5").assertIsDisplayed()
    }

    @Test
    fun deleting_a_value_nothing_reads_reports_the_delete_with_no_dialog() {
        val row = SavedValueRow(
            name = "trip_count",
            value = "5",
            lastChangedMillis = System.currentTimeMillis(),
            readByRuleNames = emptyList(),
        )
        composeRule.setContent { Screen(listOf(row)) }

        composeRule.onNodeWithText("DELETE").performClick()

        assertEquals(listOf("trip_count"), deleted)
        // No ceremony for a value nothing reads: no dialog, so no confirm
        // button of its own ever appears.
        composeRule.onNodeWithText("DELETE VALUE").assertDoesNotExist()
    }

    @Test
    fun deleting_a_value_two_rules_read_names_them_before_deleting() {
        val row = SavedValueRow(
            name = "trip_count",
            value = "5",
            lastChangedMillis = System.currentTimeMillis(),
            readByRuleNames = listOf("Morning reminder", "Evening summary"),
        )
        composeRule.setContent { Screen(listOf(row)) }

        composeRule.onNodeWithText("DELETE").performClick()

        // Named before anything is deleted.
        composeRule.onNodeWithText(
            "Morning reminder and Evening summary read this value. They will fail once it is gone.",
        ).assertIsDisplayed()
        assertTrue("nothing should be deleted before the dialog is confirmed", deleted.isEmpty())

        composeRule.onNodeWithText("DELETE VALUE").performClick()

        assertEquals(listOf("trip_count"), deleted)
    }

    @Test
    fun cancelling_a_named_delete_deletes_nothing() {
        val row = SavedValueRow(
            name = "trip_count",
            value = "5",
            lastChangedMillis = System.currentTimeMillis(),
            readByRuleNames = listOf("Morning reminder"),
        )
        composeRule.setContent { Screen(listOf(row)) }

        composeRule.onNodeWithText("DELETE").performClick()
        composeRule.onNodeWithText("CANCEL").performClick()

        assertTrue(deleted.isEmpty())
        composeRule.onNodeWithText("DELETE VALUE").assertDoesNotExist()
    }

    @Test
    fun adding_a_value_reports_the_name_and_value_entered() {
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("ADD VALUE").performClick()
        composeRule.onNodeWithText("NAME").performTextInput("trip_count")
        composeRule.onNodeWithText("VALUE").performTextInput("5")
        composeRule.onNodeWithText("ADD").performClick()

        assertEquals(listOf("trip_count" to "5"), added)
    }

    @Test
    fun an_invalid_name_shows_its_message_instead_of_storing_anything() {
        invalidName = "bad name"
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("ADD VALUE").performClick()
        composeRule.onNodeWithText("NAME").performTextInput("bad name")

        composeRule.onNodeWithText("'bad name' cannot be read back by a rule.").assertExists()

        composeRule.onNodeWithText("ADD").performClick()

        assertTrue("an invalid name must not be added", added.isEmpty())
        assertTrue("an invalid name must not be deleted either", deleted.isEmpty())
    }

    @Test
    fun tapping_a_row_opens_it_for_editing_with_the_name_fixed() {
        val row = SavedValueRow(
            name = "trip_count",
            value = "5",
            lastChangedMillis = System.currentTimeMillis(),
        )
        composeRule.setContent { Screen(listOf(row)) }

        composeRule.onNodeWithText("TRIP_COUNT").performClick()
        // Renaming is not offered: only the VALUE field is editable, so there
        // is no NAME box here to type into.
        composeRule.onNodeWithText("NAME").assertExists()
        composeRule.onNodeWithText("VALUE").performTextClearance()
        composeRule.onNodeWithText("VALUE").performTextInput("42")
        composeRule.onNodeWithText("SAVE").performClick()

        assertEquals(listOf("trip_count" to "42"), edited)
    }

    @Test
    fun back_is_reported() {
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backTaps)
    }
}
