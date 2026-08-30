package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.LeafOutcome
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.TriggerTrace
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The read-only trace tree, driven with a hand-built [TriggerTrace] the same
 * way `NotificationInspectorScreenTest` drives its screen with fabricated
 * notifications: what matters here is the shape of a tree several levels
 * deep, not whatever a real evaluation happened to produce.
 */
@RunWith(AndroidJUnit4::class)
class TriggerTraceScreenTest {

    @get:JUnitRule
    val composeRule = createComposeRule()

    private fun show(trace: TriggerTrace, width: androidx.compose.ui.unit.Dp = 220.dp) {
        composeRule.setContent {
            Box(Modifier.size(width, 640.dp)) {
                TriggerTraceScreen(
                    trace = trace,
                    onBack = {},
                    describeComponent = { it },
                )
            }
        }
    }

    /**
     * [LeafOutcome.UNREADABLE]'s own label, "COULD NOT ANSWER", is the longest
     * word this screen ever prints as a badge, so it is the one most likely to
     * wrap if the guard regresses.
     *
     * Nested three levels deep (48dp of indentation, the depth `TraceRow`'s own
     * KDoc names as what a real rule ever reaches) on a narrow, 220dp-wide
     * viewport, indentation alone leaves the badge less width than its own
     * single-line text needs. The component name sharing this row cannot be
     * what does the squeezing here, whatever its own length: a `Row` measures
     * its non-weighted children (the badge, in this row) before it measures
     * a weighted one, regardless of source order, so the badge is never sized
     * down to make room for its neighbour.
     *
     * A single-line label's own height, in this typography, is comfortably
     * under 20dp; a wrapped one is roughly double that. The assertion is
     * generous on purpose. It only has to catch a badge that wrapped at all,
     * not measure the exact line height.
     */
    @Test
    fun the_could_not_answer_badge_does_not_wrap_at_a_deep_indent() {
        var trace: TriggerTrace = TriggerTrace.Leaf(
            spec = ComponentSpec("deep"),
            outcome = LeafOutcome.UNREADABLE,
        )
        repeat(3) {
            trace = TriggerTrace.Group(op = TriggerNode.Op.ALL, children = listOf(trace), held = false)
        }
        show(trace)

        val badgeHeight = composeRule.onNodeWithText("COULD NOT ANSWER").getUnclippedBoundsInRoot()
            .let { it.bottom - it.top }
        assertTrue(
            "The COULD NOT ANSWER badge is $badgeHeight tall, which is tall enough that it has " +
                "wrapped onto a second line instead of staying on one.",
            badgeHeight < 20.dp,
        )
    }

    /**
     * The group-level equivalent of the leaf case above: [HeldBadge]'s own
     * "NOT CHECKED", for a group the tree's short-circuiting skipped
     * entirely. A single, uniquely-labelled group at the deepest position
     * avoids the leaf's own "NOT CHECKED" (a different outcome, [FIRED],
     * keeps that one unambiguous) so the lookup below can only match one node.
     */
    @Test
    fun the_not_checked_group_badge_does_not_wrap_at_a_deep_indent() {
        var trace: TriggerTrace = TriggerTrace.Group(
            op = TriggerNode.Op.ALL,
            children = listOf(TriggerTrace.Leaf(spec = ComponentSpec("leaf"), outcome = LeafOutcome.FIRED)),
            held = null,
        )
        repeat(3) {
            trace = TriggerTrace.Group(op = TriggerNode.Op.ALL, children = listOf(trace), held = true)
        }
        show(trace)

        val badgeHeight = composeRule.onNodeWithText("NOT CHECKED").getUnclippedBoundsInRoot()
            .let { it.bottom - it.top }
        assertTrue(
            "A NOT CHECKED badge is $badgeHeight tall, tall enough that it has wrapped onto a " +
                "second line instead of staying on one.",
            badgeHeight < 20.dp,
        )
    }
}
