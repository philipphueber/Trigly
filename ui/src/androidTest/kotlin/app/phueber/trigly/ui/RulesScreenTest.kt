package app.phueber.trigly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RulesScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    @Test
    fun shows_each_rule_with_its_trigger_and_action_types() {
        composeRule.setContent {
            RulesScreen(rules = listOf(sampleRule), onEnabledChange = { _, _ -> })
        }

        composeRule.onNodeWithText("Ping every minute").assertIsDisplayed()
        composeRule.onNodeWithText("interval → post_notification").assertIsDisplayed()
    }

    @Test
    fun empty_list_shows_the_empty_state() {
        composeRule.setContent {
            RulesScreen(rules = emptyList(), onEnabledChange = { _, _ -> })
        }

        composeRule.onNodeWithText("No rules yet.").assertIsDisplayed()
    }

    @Test
    fun toggling_a_disabled_rule_reports_enabled() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent {
            RulesScreen(
                rules = listOf(sampleRule),
                onEnabledChange = { rule, enabled -> toggles += rule.id to enabled },
            )
        }

        composeRule.onNode(isToggleable()).performClick()

        assertEquals(1, toggles.size)
        assertEquals(sampleRule.id, toggles.single().first)
        assertTrue("expected the switch to report the new state", toggles.single().second)
    }
}

private val sampleRule = Rule(
    id = "sample-interval",
    name = "Ping every minute",
    trigger = ComponentSpec("interval"),
    actions = listOf(ComponentSpec("post_notification")),
    enabled = false,
)
