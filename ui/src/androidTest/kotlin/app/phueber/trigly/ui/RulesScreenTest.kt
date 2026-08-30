package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * uppercases them, so that is what the accessibility tree (and therefore a
 * screen reader) actually contains. Prose (the empty state, a requirement
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
    private var savedValueCount = 0
    private var settingsTaps = 0
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
            savedValueCount = savedValueCount,
            onSettings = { settingsTaps++ },
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
     *
     * Share itself is not in this list any more. It is a fixed-size glyph now,
     * not a label that a long translation could squeeze onto two lines, so the
     * crushing this test watches for cannot happen to it.
     */
    @Test
    fun a_rules_controls_are_not_crushed_on_a_narrow_screen() {
        composeRule.setContent {
            Box(modifier = Modifier.width(320.dp)) { Screen(statusesOf(sampleRule)) }
        }

        listOf("DUPLICATE").forEach { label ->
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
     * A rule saved before it is finished shows why it cannot be switched on,
     * without anyone tapping its switch. This is a different report from
     * `RuleFault.Kind.COULD_NOT_START` above: nothing has run and nothing has
     * failed, the rule is simply not built yet.
     */
    @Test
    fun an_unfinished_rule_says_why_it_cannot_be_switched_on() {
        val unfinished = RuleStatus(
            rule = sampleRule.copy(enabled = false),
            unmet = emptyList(),
            enableRefusal = "Add a trigger and an action before switching this on.",
        )

        composeRule.setContent { Screen(listOf(unfinished)) }

        composeRule.onNodeWithText(
            "Add a trigger and an action before switching this on.",
        ).performScrollTo().assertExists()
    }

    /** Once a rule can start, the reason disappears, the same as any other cell here. */
    @Test
    fun a_rule_that_can_start_shows_no_unfinished_message() {
        val ready = RuleStatus(rule = sampleRule.copy(enabled = false), unmet = emptyList())

        composeRule.setContent { Screen(listOf(ready)) }

        composeRule.onAllNodesWithText(
            "Add a trigger and an action before switching this on.",
        ).assertCountEquals(0)
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
        // not behaving. And, unlike navigating away, it cannot cost them a
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
        // Asserted on the summary itself rather than on any node containing an
        // ellipsis. The overflow beside "New rule" is labelled with one too, so
        // a screen-wide search matches two nodes and fails for a reason that has
        // nothing to do with truncation.
        composeRule
            .onNodeWithText("(6 TRIGGERS) → SHOW A NOTIFICATION", substring = true)
            .assertTextContains("…", substring = true)
        // The whole tree, spelled out, would not have needed truncating at all.
        // If this is present the cut never happened and the test is not exercising it.
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
        // Export is pointless with nothing to export, so it is not offered,
        // not even inside the overflow it now lives in.
        composeRule.onNodeWithText("\u2026").performClick()
        composeRule.onNodeWithText("Export all").assertDoesNotExist()
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

        // Share is a glyph now, not a label, so it is found by its content
        // description instead of by text.
        composeRule.onNodeWithContentDescription("Share").performClick()
        // Export all moved into the overflow beside "New rule".
        composeRule.onNodeWithText("\u2026").performClick()
        composeRule.onNodeWithText("Export all").performClick()

        assertEquals(listOf(sampleRule.id, "all"), exported)
    }

    /**
     * Share dropped its label for the platform's own share glyph, so the one
     * thing left to prove is that the glyph still carries a name. Several
     * other tests already exercise the click; this one is the one that pins
     * the description itself, the thing a screen reader and an instrumented
     * test both actually key off.
     */
    @Test
    fun the_share_control_is_reachable_by_its_content_description() {
        composeRule.setContent { Screen(listOf(RuleStatus(sampleRule, unmet = emptyList()))) }

        composeRule.onNodeWithContentDescription("Share").assertExists()
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
     * A working feature nobody can reach is the failure this whole screen is
     * fixing, so the door to it is worth one test. It is behind the overflow
     * beside "New rule" now, and it is offered with no rules at all, unlike
     * export: somebody arriving before their first rule is exactly who needs to
     * learn what a saved value is.
     */
    @Test
    fun saved_values_is_offered_even_with_no_rules() {
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("\u2026").performClick()
        composeRule.onNodeWithText("Saved values").performClick()

        assertEquals(1, savedValuesTaps)
    }

    /**
     * "Settings" shares the same overflow as "Saved values", which is the
     * whole point of putting it there: a second whole-app entry costs the menu
     * nothing. Offered with no rules at all, for the same reason saved values
     * is \u2014 a backup switch has nothing to do with whether a rule exists yet.
     */
    @Test
    fun settings_is_reachable_from_the_same_overflow_as_saved_values() {
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("\u2026").performClick()
        composeRule.onNodeWithText("Settings").performClick()

        assertEquals(1, settingsTaps)
    }

    /**
     * The header held two actions and could not hold a third. `BlockHeader`
     * gives the title the remaining width and lays actions after it, so a
     * third one does not wrap or collapse, it runs off the edge of the screen.
     * That is what shipped in 0.0.9: "Saved values" reached the edge with the
     * list empty, and "Export all" was pushed off entirely once there were
     * rules.
     *
     * Export all has since moved into the overflow beside "New rule" (see
     * [MoreMenu]'s KDoc), so the header now holds exactly the one action that
     * needs no rules to exist first: Import. This test pins that the header
     * is left with only what earns a permanent seat, rather than asserting a
     * pixel measurement, which would be the same trap the reorder test fell
     * into, where a bounds comparison silently answered the wrong way round
     * once a block grew.
     */
    @Test
    fun the_header_offers_only_import() {
        composeRule.setContent { Screen(listOf(RuleStatus(sampleRule, unmet = emptyList()))) }

        composeRule.onNodeWithText("IMPORT").assertExists()
        // Not in the header, and not on the screen at all until the overflow
        // is opened.
        composeRule.onNodeWithText("Export all").assertDoesNotExist()
        composeRule.onNodeWithText("Saved values").assertDoesNotExist()

        composeRule.onNodeWithText("\u2026").performClick()

        composeRule.onNodeWithText("Export all").assertExists()
    }

    /**
     * The count is the reason a person finds this screen: it says a rule has
     * written something. A menu entry can carry it, so moving off the row did
     * not have to lose it.
     */
    @Test
    fun the_menu_entry_says_how_many_are_stored() {
        savedValueCount = 2
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("\u2026").performClick()

        composeRule.onNodeWithText("2 values, shared with every rule").assertExists()
    }

    @Test
    fun the_menu_entry_says_when_nothing_is_stored() {
        savedValueCount = 0
        composeRule.setContent { Screen(emptyList()) }

        composeRule.onNodeWithText("\u2026").performClick()

        composeRule.onNodeWithText("Nothing saved yet", substring = true).assertExists()
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
     * before folders existed. No heading, no "Other": nothing to distinguish
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
        // No folder is in use, so "Other" (the bucket every unfoldered rule
        // would otherwise collect under) must not appear either.
        composeRule.onNodeWithText("OTHER", substring = true).assertDoesNotExist()
    }

    /**
     * Two named folders plus a leftover rule become three headed sections,
     * and "Other" sits after both, not where the letter O would otherwise
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
     * The new default this change adds: more than three rules in the
     * database, with at least one of them filed into a folder, and every
     * folder starts closed. The headings still show, so someone can see the
     * folders exist and open the one they want; nothing under them is drawn
     * until they do.
     */
    @Test
    fun more_than_three_rules_across_folders_start_closed() {
        composeRule.setContent {
            Screen(statusesOf(drivingModeRule, nightRule, looseRule, extraLooseRule))
        }

        composeRule.onNodeWithText("CAR (1)").assertExists()
        composeRule.onNodeWithText("NIGHT (1)").assertExists()
        composeRule.onNodeWithText("OTHER (2)").assertExists()

        composeRule.onNodeWithText("DRIVING MODE").assertDoesNotExist()
        composeRule.onNodeWithText("NIGHT OWL").assertDoesNotExist()
        composeRule.onNodeWithText("LOOSE RULE").assertDoesNotExist()
        composeRule.onNodeWithText("EXTRA LOOSE RULE").assertDoesNotExist()
    }

    /**
     * The other side of the same default: three rules is not *more than*
     * three, so nothing closes on its own, exactly as
     * [folders_group_rules_and_put_other_last] already shows for the
     * headings themselves.
     */
    @Test
    fun three_rules_across_folders_start_open() {
        composeRule.setContent { Screen(statusesOf(drivingModeRule, nightRule, looseRule)) }

        composeRule.onNodeWithText("DRIVING MODE").assertExists()
        composeRule.onNodeWithText("NIGHT OWL").assertExists()
        composeRule.onNodeWithText("LOOSE RULE").assertExists()
    }

    /**
     * More than three rules, but no folder anywhere: the closed default
     * never applies, because there is no folder to close. The list renders
     * exactly as [no_folders_in_use_renders_the_plain_list] already shows
     * for the plain-list branch, just with a rule count past the threshold.
     */
    @Test
    fun four_unfoldered_rules_still_render_the_plain_list() {
        composeRule.setContent {
            Screen(
                listOf(
                    RuleStatus(sampleRule, unmet = emptyList()),
                    RuleStatus(sampleRule.copy(id = "second", name = "Second rule"), unmet = emptyList()),
                    RuleStatus(sampleRule.copy(id = "third", name = "Third rule"), unmet = emptyList()),
                    RuleStatus(sampleRule.copy(id = "fourth", name = "Fourth rule"), unmet = emptyList()),
                )
            )
        }

        composeRule.onNodeWithText("PING EVERY MINUTE").assertIsDisplayed()
        composeRule.onNodeWithText("SECOND RULE").assertIsDisplayed()
        composeRule.onNodeWithText("THIRD RULE").assertIsDisplayed()
        composeRule.onNodeWithText("FOURTH RULE").assertIsDisplayed()
        composeRule.onNodeWithText("OTHER", substring = true).assertDoesNotExist()
    }

    /**
     * The trap the closed-by-default rule creates: once the starting value
     * is decided, it must stay out of the way of a person's own choice. A
     * folder opened by hand must not be re-closed just because a new rule
     * pushed the count up again.
     */
    @Test
    fun a_folder_opened_by_hand_stays_open_when_the_rule_count_changes() {
        var current by mutableStateOf(statusesOf(drivingModeRule, nightRule, looseRule, extraLooseRule))
        composeRule.setContent { Screen(current) }

        // Four rules across folders start closed.
        composeRule.onNodeWithText("DRIVING MODE").assertDoesNotExist()

        // Open "Car" by hand.
        composeRule.onNodeWithText("CAR (1)").performScrollTo().performClick()
        composeRule.onNodeWithText("DRIVING MODE").assertExists()

        // A fifth rule arrives. The starting decision was already made and
        // locked, so it does not run again and does not re-close "Car".
        current = statusesOf(drivingModeRule, nightRule, looseRule, extraLooseRule, anotherLooseRule)
        composeRule.onNodeWithText("DRIVING MODE").assertExists()
    }

    /**
     * A rule's name is the obvious match. This is the baseline the next test
     * (matching by a component nobody named the rule after) is contrasted
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
     * reads as "you have no rules": a lie the moment a search is active.
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
 * In the "Car" folder, and named after nothing about its own trigger. This is
 * the fixture the component-search test needs: typing "bluetooth" must find this
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

/** No folder at all. Collects under "Other". */
private val looseRule = Rule(
    id = "loose-rule",
    name = "Loose rule",
    trigger = ComponentSpec("screen_on"),
    actions = listOf(ComponentSpec("post_notification")),
    enabled = true,
)

/**
 * A second unfoldered rule, alongside [looseRule]: the fixture the
 * more-than-three-rules tests need to cross the threshold without adding a
 * fourth folder.
 */
private val extraLooseRule = Rule(
    id = "extra-loose-rule",
    name = "Extra loose rule",
    trigger = ComponentSpec("wifi_connected"),
    actions = listOf(ComponentSpec("post_notification")),
    enabled = true,
)

/** A fifth rule, used only to push a list past the threshold a second time. */
private val anotherLooseRule = Rule(
    id = "another-loose-rule",
    name = "Another loose rule",
    trigger = ComponentSpec("airplane_mode"),
    actions = listOf(ComponentSpec("post_notification")),
    enabled = true,
)
