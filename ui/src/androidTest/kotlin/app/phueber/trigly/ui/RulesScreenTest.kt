package app.phueber.trigly.ui

import androidx.compose.runtime.Composable
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
import app.phueber.trigly.core.TriggerNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chrome and rule labels are asserted in capitals on purpose: the design
 * uppercases them, so that is what the accessibility tree — and therefore a
 * screen reader — actually contains. Prose (the empty state, a requirement
 * explanation) stays in sentence case and is asserted that way.
 */
@RunWith(AndroidJUnit4::class)
class RulesScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private val toggles = mutableListOf<Pair<String, Boolean>>()
    private val resolved = mutableListOf<ComponentRequirement>()
    private val edited = mutableListOf<String>()
    private val exported = mutableListOf<String>()
    private var newRuleTaps = 0
    private var importTaps = 0

    /** Display names come from the factories, so the screen is handed a lookup. */
    private val describe: (String) -> String = { type ->
        when (type) {
            "interval" -> "Every so often"
            "post_notification" -> "Show a notification"
            "bluetooth_connected" -> "Bluetooth connected"
            "charger_plugged" -> "Charger in"
            "headset_plugged" -> "Headset in"
            "wifi_connected" -> "Wi-Fi connected"
            "screen_on" -> "Screen turned on"
            "airplane_mode" -> "Airplane mode enabled"
            else -> type
        }
    }

    @Composable
    private fun Screen(statuses: List<RuleStatus>) {
        RulesScreen(
            statuses = statuses,
            onEnabledChange = { rule, enabled -> toggles += rule.id to enabled },
            onResolve = { resolved += it },
            onNewRule = { newRuleTaps++ },
            onEditRule = { edited += it },
            onExportAll = { exported += "all" },
            onExportRule = { exported += it.id },
            onImport = { importTaps++ },
            describeComponent = describe,
        )
    }

    @Test
    fun the_list_offers_no_diagnostic_of_its_own() {
        // The inspector used to sit in this bottom bar. It now opens from the
        // `Inspect` button on the block of whichever component reads
        // notifications, which is where someone is when a notification rule is
        // not behaving — and, unlike navigating away, it cannot cost them a
        // half-written rule. This asserts the old entry point is gone rather than
        // trusting that nobody put it back.
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("WHAT TRIGLY SEES").assertDoesNotExist()
    }

    @Test
    fun shows_each_rule_with_display_names_not_type_strings() {
        composeRule.setContent { Screen(listOf(RuleStatus(sampleRule, unmet = emptyList()))) }

        composeRule.onNodeWithText("PING EVERY MINUTE").assertIsDisplayed()
        composeRule.onNodeWithText("EVERY SO OFTEN → SHOW A NOTIFICATION").assertIsDisplayed()
    }

    /**
     * A group is parenthesised and its operator spelled out, so "all of" and
     * "any of" read differently rather than both collapsing into the same list
     * the pre-tree summary used to produce.
     */
    @Test
    fun a_grouped_trigger_is_parenthesised_with_its_operator() {
        val rule = sampleRule.copy(
            trigger = TriggerNode.Group(
                TriggerNode.Op.ALL,
                listOf(
                    TriggerNode.One(ComponentSpec("bluetooth_connected")),
                    TriggerNode.One(ComponentSpec("interval")),
                ),
            ),
        )
        composeRule.setContent { Screen(listOf(RuleStatus(rule, unmet = emptyList()))) }

        composeRule
            .onNodeWithText("(BLUETOOTH CONNECTED AND EVERY SO OFTEN) → SHOW A NOTIFICATION")
            .assertIsDisplayed()
    }

    /** Nesting reads as nesting: a sub-group keeps its own parentheses and operator. */
    @Test
    fun a_nested_group_shows_both_operators() {
        val rule = sampleRule.copy(
            trigger = TriggerNode.Group(
                TriggerNode.Op.ALL,
                listOf(
                    TriggerNode.One(ComponentSpec("bluetooth_connected")),
                    TriggerNode.Group(
                        TriggerNode.Op.ANY,
                        listOf(
                            TriggerNode.One(ComponentSpec("charger_plugged")),
                            TriggerNode.One(ComponentSpec("headset_plugged")),
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent { Screen(listOf(RuleStatus(rule, unmet = emptyList()))) }

        composeRule
            .onNodeWithText("(BLUETOOTH CONNECTED AND (CHARGER IN OR HEADSET IN)) → SHOW A NOTIFICATION")
            .assertIsDisplayed()
    }

    /**
     * The rule this guards against: a summary that reads as simpler than the rule
     * actually is. A tree long enough to truncate must still say how many
     * triggers it holds, so cutting the text short never understates the count.
     */
    @Test
    fun a_long_tree_truncates_but_still_states_its_true_trigger_count() {
        val rule = sampleRule.copy(
            trigger = TriggerNode.Group(
                TriggerNode.Op.ALL,
                listOf(
                    TriggerNode.One(ComponentSpec("bluetooth_connected")),
                    TriggerNode.One(ComponentSpec("wifi_connected")),
                    TriggerNode.One(ComponentSpec("charger_plugged")),
                    TriggerNode.One(ComponentSpec("headset_plugged")),
                    TriggerNode.One(ComponentSpec("screen_on")),
                    TriggerNode.One(ComponentSpec("airplane_mode")),
                ),
            ),
        )
        composeRule.setContent { Screen(listOf(RuleStatus(rule, unmet = emptyList()))) }

        // Six triggers went in; the summary must say six, not however many of
        // their names happened to fit before the cut.
        composeRule
            .onNodeWithText("(6 TRIGGERS) → SHOW A NOTIFICATION", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("…", substring = true).assertIsDisplayed()
        // The whole tree, spelled out, would not have needed truncating at all —
        // if this is present the cut never happened and the test is not exercising it.
        composeRule
            .onNodeWithText(
                "(BLUETOOTH CONNECTED AND WI-FI CONNECTED AND CHARGER IN AND HEADSET IN AND " +
                    "SCREEN TURNED ON AND AIRPLANE MODE ENABLED) → SHOW A NOTIFICATION",
            )
            .assertDoesNotExist()
    }

    @Test
    fun empty_list_invites_creating_a_rule() {
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("No rules yet. Add one below to get started.").assertIsDisplayed()
        // Export is pointless with nothing to export, so it is not offered.
        composeRule.onNodeWithText("EXPORT ALL").assertDoesNotExist()
    }

    @Test
    fun new_rule_is_reported() {
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("NEW RULE").performClick()

        assertEquals(1, newRuleTaps)
    }

    @Test
    fun tapping_a_rule_opens_it_for_editing() {
        composeRule.setContent { Screen(listOf(RuleStatus(sampleRule, unmet = emptyList()))) }

        composeRule.onNodeWithText("PING EVERY MINUTE").performClick()

        assertEquals(listOf(sampleRule.id), edited)
    }

    @Test
    fun a_rule_can_be_exported_on_its_own_or_with_the_rest() {
        composeRule.setContent { Screen(listOf(RuleStatus(sampleRule, unmet = emptyList()))) }

        composeRule.onNodeWithText("SHARE").performClick()
        composeRule.onNodeWithText("EXPORT ALL").performClick()

        assertEquals(listOf(sampleRule.id, "all"), exported)
    }

    @Test
    fun import_is_offered_even_with_no_rules() {
        // Importing is how a new phone gets its first rule, so it cannot be
        // hidden behind having rules already.
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("IMPORT").performClick()

        assertEquals(1, importTaps)
    }

    @Test
    fun toggling_a_disabled_rule_reports_enabled() {
        composeRule.setContent { Screen(listOf(RuleStatus(sampleRule, unmet = emptyList()))) }

        composeRule.onNode(isToggleable()).performClick()

        assertEquals(1, toggles.size)
        assertTrue("expected the switch to report the new state", toggles.single().second)
    }

    @Test
    fun an_enabled_rule_that_cannot_fire_explains_why() {
        composeRule.setContent {
            Screen(
                listOf(
                    RuleStatus(sampleRule.copy(enabled = true), unmet = listOf(notificationAccess))
                )
            )
        }

        composeRule
            .onNodeWithText("Needs notification access, granted in system settings")
            .assertIsDisplayed()
    }

    @Test
    fun a_disabled_rule_does_not_nag_about_permissions() {
        composeRule.setContent {
            Screen(
                listOf(
                    RuleStatus(sampleRule.copy(enabled = false), unmet = listOf(notificationAccess))
                )
            )
        }

        composeRule
            .onNodeWithText("Needs notification access, granted in system settings")
            .assertDoesNotExist()
    }

    @Test
    fun tapping_grant_reports_the_requirement_to_resolve() {
        composeRule.setContent {
            Screen(
                listOf(
                    RuleStatus(sampleRule.copy(enabled = true), unmet = listOf(notificationAccess))
                )
            )
        }

        composeRule.onNodeWithText("GRANT").performClick()

        assertEquals(listOf(notificationAccess), resolved)
    }

    @Test
    fun an_unresolvable_requirement_offers_no_button() {
        composeRule.setContent {
            Screen(
                listOf(
                    RuleStatus(
                        sampleRule.copy(enabled = true),
                        unmet = listOf(ComponentRequirement.MinApiLevel(31)),
                    )
                )
            )
        }

        composeRule.onNodeWithText("Needs Android 12 (API 31) or newer").assertIsDisplayed()
        composeRule.onNodeWithText("GRANT").assertDoesNotExist()
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
