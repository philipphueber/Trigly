package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.LeafOutcome
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.TriggerTrace

/**
 * What actually decided this rule's last trigger evaluation: which leaf of an
 * `ALL` held it back, which leaf of an `ANY` carried it, and which leaves the
 * tree never even asked.
 *
 * The gap this closes. `RuleFaultLog`'s `UNDECIDED` kind only ever covers a
 * leaf that could not be *read*; a tree that answers "no" because a leaf
 * plainly said no produced no fault, no log line, and nothing on screen. A
 * rule with a condition in it that simply is not true right now looked
 * identical to one silently broken, and the only way to tell them apart used
 * to be a cable and `adb logcat`. `TriggerEngine.onEvaluated` is the hook that
 * closes it, and this screen is where the tree it carries becomes readable.
 *
 * **It renders [trace] as it was recorded, structure and all**, rather than
 * collapsing it back into a sentence: `RulesScreen`'s `LastFaultCell` is
 * already the sentence, for the cases that are a fault. A tree is not a
 * sentence, so it gets a screen of its own, the same way the notification
 * inspector got one for values nobody could otherwise see. Every leaf is
 * marked as one of five distinct facts (fired, yes, no, could not answer, not
 * consulted), never collapsed to a plain yes or no: see [LeafOutcome] and, for
 * why "not consulted" has to be its own colour rather than reading as a silent
 * "no", `TriggerTrace`'s own KDoc in `:core`.
 *
 * No string resources, for the same reason `NotificationInspectorScreen`
 * has none: this is a diagnostic screen for a person debugging one rule, in
 * the same developer-facing register the inspector already uses for "Title",
 * "Text" and "Buttons".
 *
 * Stateless, like every other screen here, so an instrumented test can drive
 * it against a hand-built [TriggerTrace] rather than a real evaluation.
 */
@Composable
fun TriggerTraceScreen(
    trace: TriggerTrace,
    onBack: () -> Unit,
    describeComponent: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BlockHeader(title = "Last check")

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            BlockCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    TraceRow(trace = trace, depth = 0, describeComponent = describeComponent)
                }
            }
        }

        BlockBottomBar {
            BlockButton(text = "Back", onClick = onBack, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One node of the tree, indented by [depth]. A [TriggerTrace.Group] draws its
 * own heading and then recurses into its children one level deeper; a
 * [TriggerTrace.Leaf] is the recursion's base case.
 *
 * Indentation by padding rather than `TriggerTree.kt`'s rail: that file's rail
 * is measured against an editable draft with remove buttons, AND/OR toggles
 * and a fold state of its own, none of which exists here. A read-only tree
 * three or four levels deep, which is what a rule ever nests to, does not need
 * that machinery to stay legible.
 */
@Composable
private fun TraceRow(
    trace: TriggerTrace,
    depth: Int,
    describeComponent: (String) -> String,
) {
    when (trace) {
        is TriggerTrace.Group -> {
            GroupRow(group = trace, depth = depth)
            trace.children.forEach { child ->
                TraceRow(trace = child, depth = depth + 1, describeComponent = describeComponent)
            }
        }

        is TriggerTrace.Leaf -> LeafRow(leaf = trace, depth = depth, describeComponent = describeComponent)
    }
}

@Composable
private fun GroupRow(group: TriggerTrace.Group, depth: Int) {
    val opLabel = if (group.op == TriggerNode.Op.ALL) "ALL OF" else "ANY OF"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, top = if (depth == 0) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = opLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.extra.accent,
            modifier = Modifier.weight(1f),
        )
        HeldBadge(held = group.held)
    }
}

@Composable
private fun LeafRow(
    leaf: TriggerTrace.Leaf,
    depth: Int,
    describeComponent: (String) -> String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = describeComponent(leaf.spec.type),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutcomeBadge(outcome = leaf.outcome)
    }
}

/**
 * A group's own answer, in the same five-colour vocabulary [OutcomeBadge]
 * uses for a leaf: yes, no, or not consulted. A group has no "fired" or
 * "could not answer" of its own, those are leaf-only facts, so this only ever
 * shows the three that can be true of a whole subgroup's held answer.
 *
 * `maxLines = 1, softWrap = false`, the same guard `Blocks.kt`'s own KDoc
 * documents for every button and chip label: this badge sits at the end of a
 * [Row] beside a component name that is free to run long and wrap onto a
 * second line, at whatever indentation a deep tree has pushed the row to. A
 * badge left to wrap under a squeeze reads as a page of letters, one under
 * the next; clipped, at least, it still reads as the one word it is.
 */
@Composable
private fun HeldBadge(held: Boolean?) {
    val (text, color) = when (held) {
        true -> "YES" to MaterialTheme.colorScheme.tertiary
        false -> "NO" to MaterialTheme.colorScheme.error
        null -> "NOT CHECKED" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false)
}

/** Same reasoning as [HeldBadge]: a fixed word must not wrap under a squeeze. */
@Composable
private fun OutcomeBadge(outcome: LeafOutcome) {
    val (text, color) = outcome.label()
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1, softWrap = false)
}

/**
 * The word and colour for each [LeafOutcome], kept together so the two can
 * never drift apart into a badge that reads "no" in the colour that means
 * "not consulted" or the other way round.
 *
 * [LeafOutcome.FIRED] and [LeafOutcome.YES] share a colour: both mean this leaf
 * is why its side of the tree is satisfied, and the distinction between "it
 * happened" and "it was asked and said yes" is carried by the word, not the
 * colour. [LeafOutcome.NOT_CONSULTED] gets its own colour, distinct from both
 * "no" and "yes", because reading it as a quiet "no" is the exact honesty gap
 * this whole screen exists to close.
 */
@Composable
private fun LeafOutcome.label(): Pair<String, Color> = when (this) {
    LeafOutcome.FIRED -> "FIRED" to MaterialTheme.colorScheme.tertiary
    LeafOutcome.YES -> "YES" to MaterialTheme.colorScheme.tertiary
    LeafOutcome.NO -> "NO" to MaterialTheme.colorScheme.error
    LeafOutcome.UNREADABLE -> "COULD NOT ANSWER" to MaterialTheme.extra.caution
    LeafOutcome.NOT_CONSULTED -> "NOT CHECKED" to MaterialTheme.colorScheme.onSurfaceVariant
}
