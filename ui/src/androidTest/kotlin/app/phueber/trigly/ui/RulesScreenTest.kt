package app.phueber.trigly.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.SpecialAccessKind
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
            RulesScreen(
                statuses = listOf(RuleStatus(sampleRule, unmet = emptyList())),
                onEnabledChange = { _, _ -> },
                onResolve = {},
            )
        }

        composeRule.onNodeWithText("Ping every minute").assertIsDisplayed()
        composeRule.onNodeWithText("interval → post_notification").assertIsDisplayed()
    }

    @Test
    fun empty_list_shows_the_empty_state() {
        composeRule.setContent {
            RulesScreen(statuses = emptyList(), onEnabledChange = { _, _ -> }, onResolve = {})
        }

        composeRule.onNodeWithText("No rules yet.").assertIsDisplayed()
    }

    @Test
    fun toggling_a_disabled_rule_reports_enabled() {
        val toggles = mutableListOf<Pair<String, Boolean>>()
        composeRule.setContent {
            RulesScreen(
                statuses = listOf(RuleStatus(sampleRule, unmet = emptyList())),
                onEnabledChange = { rule, enabled -> toggles += rule.id to enabled },
                onResolve = {},
            )
        }

        composeRule.onNode(isToggleable()).performClick()

        assertEquals(1, toggles.size)
        assertEquals(sampleRule.id, toggles.single().first)
        assertTrue("expected the switch to report the new state", toggles.single().second)
    }

    @Test
    fun an_enabled_rule_that_cannot_fire_explains_why() {
        composeRule.setContent {
            RulesScreen(
                statuses = listOf(
                    RuleStatus(sampleRule.copy(enabled = true), unmet = listOf(notificationAccess))
                ),
                onEnabledChange = { _, _ -> },
                onResolve = {},
            )
        }

        composeRule
            .onNodeWithText("Needs notification access, granted in system settings")
            .assertIsDisplayed()
    }

    @Test
    fun a_disabled_rule_does_not_nag_about_permissions() {
        // A disabled rule not firing needs no explanation, so the warning would
        // be noise on every rule the user has switched off.
        composeRule.setContent {
            RulesScreen(
                statuses = listOf(
                    RuleStatus(sampleRule.copy(enabled = false), unmet = listOf(notificationAccess))
                ),
                onEnabledChange = { _, _ -> },
                onResolve = {},
            )
        }

        composeRule
            .onNodeWithText("Needs notification access, granted in system settings")
            .assertDoesNotExist()
    }

    @Test
    fun tapping_grant_reports_the_requirement_to_resolve() {
        val resolved = mutableListOf<ComponentRequirement>()
        composeRule.setContent {
            RulesScreen(
                statuses = listOf(
                    RuleStatus(sampleRule.copy(enabled = true), unmet = listOf(notificationAccess))
                ),
                onEnabledChange = { _, _ -> },
                onResolve = { resolved += it },
            )
        }

        composeRule.onNodeWithText("Grant").performClick()

        assertEquals(listOf(notificationAccess), resolved)
    }

    @Test
    fun an_unresolvable_requirement_offers_no_button() {
        // Nothing the user can do about an old Android version; a button would lie.
        composeRule.setContent {
            RulesScreen(
                statuses = listOf(
                    RuleStatus(
                        sampleRule.copy(enabled = true),
                        unmet = listOf(ComponentRequirement.MinApiLevel(31)),
                    )
                ),
                onEnabledChange = { _, _ -> },
                onResolve = {},
            )
        }

        composeRule.onNodeWithText("Needs Android API 31 or newer").assertIsDisplayed()
        composeRule.onNodeWithText("Grant").assertDoesNotExist()
    }
}

private val notificationAccess =
    ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_LISTENER)

private val sampleRule = Rule(
    id = "sample-interval",
    name = "Ping every minute",
    trigger = ComponentSpec("interval"),
    actions = listOf(ComponentSpec("post_notification")),
    enabled = false,
)
