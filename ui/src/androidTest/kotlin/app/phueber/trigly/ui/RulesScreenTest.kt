package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
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
    private var savedValuesTaps = 0
    private val duplicated = mutableListOf<String>()
    private var batteryFixTaps = 0

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
    private fun Screen(
        statuses: List<RuleStatus>,
        // Defaults to "already excused": every test above this one is about
        // a rule, not about the device, and should not have to state a fact
        // it does not care about to avoid tripping an unrelated notice.
        ignoringBatteryOptimizations: Boolean = true,
    ) {
        RulesScreen(
            statuses = statuses,
            onEnabledChange = { rule, enabled -> toggles += rule.id to enabled },
            onResolve = { resolved += it },
            onNewRule = { newRuleTaps++ },
            onEditRule = { edited += it },
            onExportAll = { exported += "all" },
            onSavedValues = { savedValuesTaps++ },
            onExportRule = { exported += it.id },
            onDuplicateRule = { duplicated += it.id },
            onImport = { importTaps++ },
            describeComponent = describe,
            ignoringBatteryOptimizations = ignoringBatteryOptimizations,
            onFixBatteryOptimization = { batteryFixTaps++ },
        )
    }

    /**
     * The same guarantee the editor's footer has, for the list's own controls.
     *
     * This row gained Duplicate beside Share, and a label is only as short as the
     * language it is translated into. Asserting height rather than presence,
     * because a control squeezed to one letter per line is present and displayed
     * and unreadable.
     */
    @Test
    fun a_rules_controls_are_not_crushed_on_a_narrow_screen() {
        composeRule.setContent {
            Box(modifier = Modifier.width(320.dp)) { Screen(statusesOf(sampleRule)) }
        }

        listOf("SHARE", "DUPLICATE").forEach { label ->
            val height = composeRule.onNodeWithText(label)
                .performScrollTo()
                .getUnclippedBoundsInRoot()
                .height
            assertTrue("$label is $height tall", height < 64.dp)
        }
    }

    /**
     * A rule that fired and whose action failed says so on the list.
     *
     * Before this, that rule was indistinguishable from one whose trigger never
     * fired: both did nothing and neither said anything, and the only record was
     * a logcat line that needs a cable to read.
     */
    @Test
    fun a_rule_whose_action_failed_says_what_the_action_said() {
        val failed = RuleStatus(
            rule = sampleRule.copy(enabled = true),
            unmet = emptyList(),
            lastFault = RuleFault(
                RuleFault.Kind.ACTION_FAILED,
                "Notifications are disabled for this app.",
                "post_notification",
            ),
        )

        composeRule.setContent { Screen(listOf(failed)) }

        // Names the action, so a rule with several is not a guessing game.
        composeRule.onNodeWithText("LAST RUN FAILED: SHOW A NOTIFICATION")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithText("Notifications are disabled for this app.").assertExists()
    }

    @Test
    fun a_rule_with_no_failure_says_nothing_about_one() {
        composeRule.setContent { Screen(statusesOf(sampleRule)) }

        composeRule.onAllNodesWithText("LAST RUN FAILED: SHOW A NOTIFICATION")
            .assertCountEquals(0)
    }

    /**
     * A failure against a rule someone has since switched off is not reported.
     * They stopped asking for that run, so a red or amber cell would be an
     * accusation about something nobody is waiting on.
     */
    @Test
    fun a_disabled_rule_does_not_report_an_old_failure() {
        val failed = RuleStatus(
            rule = sampleRule.copy(enabled = false),
            unmet = emptyList(),
            lastFault = RuleFault(
                RuleFault.Kind.ACTION_FAILED,
                "Notifications are disabled for this app.",
                "post_notification",
            ),
        )

        composeRule.setContent { Screen(listOf(failed)) }

        composeRule.onAllNodesWithText("Notifications are disabled for this app.")
            .assertCountEquals(0)
    }

    /**
     * A rule that was never built says so, and says it differently.
     *
     * The last of the three silences, and the one that reads most like patience:
     * the rule is stored, the switch says on, and nothing anywhere is watching
     * for it. "Failed" and "stopped" both describe a run that happened, so
     * neither sentence fits, and the heading is its own.
     */
    @Test
    fun a_rule_that_could_not_be_built_says_nothing_is_watching() {
        val never = RuleStatus(
            rule = sampleRule.copy(enabled = true),
            unmet = emptyList(),
            lastFault = RuleFault(
                RuleFault.Kind.COULD_NOT_START,
                "Trigly could not build this rule, so nothing is watching for it. " +
                    "No trigger factory for type 'from_the_future'.",
            ),
        )

        composeRule.setContent { Screen(listOf(never)) }

        composeRule.onNodeWithText("RULE NOT STARTED").performScrollTo().assertExists()
        composeRule.onNodeWithText(
            "Trigly could not build this rule, so nothing is watching for it. " +
                "No trigger factory for type 'from_the_future'.",
        ).assertExists()

        // Not the sentence for either kind of run, because there was no run.
        composeRule.onAllNodesWithText("LAST RUN FAILED: SHOW A NOTIFICATION")
            .assertCountEquals(0)
        composeRule.onAllNodesWithText("LAST RUN STOPPED").assertCountEquals(0)
    }

    /**
     * A rule fired and dropped keeps the run heading, which is the case this
     * pins against the one above: three kinds, three sentences, and no sharing.
     */
    @Test
    fun a_rule_dropped_by_an_unreadable_condition_says_the_run_stopped() {
        val stopped = RuleStatus(
            rule = sampleRule.copy(enabled = true),
            unmet = emptyList(),
            lastFault = RuleFault(
                RuleFault.Kind.UNDECIDED,
                "Trigly could not read Is in an area, so the rule did not run.",
            ),
        )

        composeRule.setContent { Screen(listOf(stopped)) }

        composeRule.onNodeWithText("LAST RUN STOPPED").performScrollTo().assertExists()
        composeRule.onAllNodesWithText("RULE NOT STARTED").assertCountEquals(0)
    }

    @Test
    fun each_rule_offers_a_duplicate_control_that_names_the_rule() {
        composeRule.setContent { Screen(statusesOf(sampleRule)) }

        composeRule.onNodeWithText("DUPLICATE").performScrollTo().performClick()

        assertEquals(listOf("sample-interval"), duplicated)
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

    /**
     * The way in to the saved values screen, and the reason it is tested at all:
     * a saved value is written by a rule and read by any rule, so until this
     * entry existed there was no way to find out that saved values are a thing.
     * A working feature nobody can reach is the failure this whole screen is
     * fixing, so the door to it is worth one test.
     *
     * Offered with no rules at all, unlike export. Somebody arriving before
     * their first rule is exactly who needs to learn what a saved value is.
     */
    @Test
    fun saved_values_is_offered_even_with_no_rules() {
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("SAVED VALUES").performClick()

        assertEquals(1, savedValuesTaps)
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

    /**
     * The requirement I care most about, in the spec's own words: a rule list
     * with no folder ever named on any rule must render exactly as it did
     * before folders existed. No heading, no "Other" — nothing to distinguish
     * it from the plain list at all.
     */
    @Test
    fun no_folders_in_use_renders_the_plain_list() {
        composeRule.setContent {
            Screen(
                listOf(
                    RuleStatus(sampleRule, unmet = emptyList()),
                    RuleStatus(sampleRule.copy(id = "second", name = "Second rule"), unmet = emptyList()),
                )
            )
        }

        composeRule.onNodeWithText("PING EVERY MINUTE").assertIsDisplayed()
        composeRule.onNodeWithText("SECOND RULE").assertIsDisplayed()
        // No folder is in use, so "Other" — the bucket every unfoldered rule
        // would otherwise collect under — must not appear either.
        composeRule.onNodeWithText("OTHER", substring = true).assertDoesNotExist()
    }

    /**
     * Two named folders plus a leftover rule become three headed sections,
     * and "Other" sits after both — not where the letter O would otherwise
     * sort it, which here would be between "Car" and "Night".
     */
    @Test
    fun folders_group_rules_and_put_other_last() {
        composeRule.setContent { Screen(statusesOf(drivingModeRule, nightRule, looseRule)) }

        composeRule.onNodeWithText("CAR (1)").assertExists()
        composeRule.onNodeWithText("NIGHT (1)").assertExists()
        composeRule.onNodeWithText("OTHER (1)").assertExists()

        fun topOf(text: String) =
            composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

        assertTrue("Car sorts before Night", topOf("CAR (1)") < topOf("NIGHT (1)"))
        assertTrue(
            "Other sits after both named folders, not where its letter would sort",
            topOf("NIGHT (1)") < topOf("OTHER (1)"),
        )
    }

    /** Folding one section away leaves every other section's rules alone. */
    @Test
    fun collapsing_a_section_hides_its_rules_but_not_others() {
        composeRule.setContent { Screen(statusesOf(drivingModeRule, nightRule)) }

        composeRule.onNodeWithText("DRIVING MODE").assertExists()
        composeRule.onNodeWithText("NIGHT OWL").assertExists()

        composeRule.onNodeWithText("CAR (1)").performScrollTo().performClick()

        composeRule.onNodeWithText("DRIVING MODE").assertDoesNotExist()
        // The other section was not touched.
        composeRule.onNodeWithText("NIGHT OWL").assertExists()
    }

    /**
     * A rule's name is the obvious match. This is the baseline the next test
     * — matching by a component nobody named the rule after — is contrasted
     * against.
     */
    @Test
    fun search_matches_a_rule_by_name() {
        composeRule.setContent { Screen(statusesOf(drivingModeRule, nightRule)) }

        composeRule.onNodeWithText("SEARCH").performTextInput("driving")

        composeRule.onNodeWithText("DRIVING MODE").assertExists()
        composeRule.onNodeWithText("NIGHT OWL").assertDoesNotExist()
    }

    /**
     * The whole reason search looks past the name: "Driving mode" says
     * nothing about Bluetooth, but the rule is built on a Bluetooth trigger,
     * and typing that word must still find it.
     */
    @Test
    fun search_matches_a_rule_by_a_component_it_is_not_named_after() {
        composeRule.setContent { Screen(statusesOf(drivingModeRule, nightRule)) }

        composeRule.onNodeWithText("SEARCH").performTextInput("bluetooth")

        composeRule.onNodeWithText("DRIVING MODE").assertExists()
        composeRule.onNodeWithText("NIGHT OWL").assertDoesNotExist()
    }

    /**
     * A query with no hits must say so, rather than leaving an empty list that
     * reads as "you have no rules" — a lie the moment a search is active.
     */
    @Test
    fun a_search_with_no_matches_says_so() {
        composeRule.setContent { Screen(statusesOf(drivingModeRule, nightRule)) }

        composeRule.onNodeWithText("SEARCH").performTextInput("zzz")

        composeRule.onNodeWithText("No rules match “zzz”.").assertIsDisplayed()
        composeRule.onNodeWithText("DRIVING MODE").assertDoesNotExist()
    }

    /**
     * The one notice on this screen that is about the device, not about a
     * rule. It has to show up with no rules at all, since the point is to be
     * seen before a rule ever goes quiet for want of it, and it has to offer
     * the fix rather than merely name the problem.
     */
    @Test
    fun a_battery_restricted_device_sees_the_notice_and_can_act_on_it() {
        composeRule.setContent { Screen(emptyList(), ignoringBatteryOptimizations = false) }

        composeRule.onNodeWithText("ANDROID CAN STOP TRIGLY").assertIsDisplayed()
        composeRule.onNodeWithText("ALLOW").performClick()

        assertEquals(1, batteryFixTaps)
    }

    /**
     * Pinned against the test above: a row about a thing that is already
     * true trains people to stop reading rows, so this has to actually
     * disappear once Trigly is excused, not merely start hidden.
     */
    @Test
    fun an_excused_device_sees_no_notice() {
        composeRule.setContent { Screen(emptyList(), ignoringBatteryOptimizations = true) }

        composeRule.onNodeWithText("ANDROID CAN STOP TRIGLY").assertDoesNotExist()
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

/** Shorthand for the common case in the folder/search tests: nothing unmet. */
private fun statusesOf(vararg rules: Rule): List<RuleStatus> = rules.map { RuleStatus(it, unmet = emptyList()) }

/**
 * In the "Car" folder, and named after nothing about its own trigger — the
 * fixture the component-search test needs: typing "bluetooth" must find this
 * one by what it does, not by its name.
 */
private val drivingModeRule = Rule(
    id = "driving-mode",
    name = "Driving mode",
    trigger = ComponentSpec("bluetooth_connected"),
    actions = listOf(ComponentSpec("post_notification")),
    enabled = true,
).copy(folder = "Car")

/** In the "Night" folder, and a decoy for the search tests: named differently, built differently. */
private val nightRule = Rule(
    id = "night-owl",
    name = "Night owl",
    trigger = ComponentSpec("charger_plugged"),
    actions = listOf(ComponentSpec("post_notification")),
    enabled = true,
).copy(folder = "Night")

/** No folder at all — collects under "Other". */
private val looseRule = Rule(
    id = "loose-rule",
    name = "Loose rule",
    trigger = ComponentSpec("screen_on"),
    actions = listOf(ComponentSpec("post_notification")),
    enabled = true,
)
