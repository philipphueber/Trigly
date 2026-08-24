package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentRequirement

/**
 * The "Only if" section: an optional tree of checks and AND/OR groups that
 * must hold when one of the gate's triggers fires — see `docs/conditions.md`
 * for the model, [ConditionDraft] for its editable mirror.
 *
 * Kept out of [RuleEditorScreen] rather than folded into its single scrolling
 * `Column` the way the trigger and action sections are. Those two are each a
 * flat list with one position per item, so a string key like `"action-2"` is
 * enough to address one for folding or a caveat. A condition is a tree, so an
 * item's address is a path of child indices through nested groups — a
 * different enough addressing scheme, and a recursive one, that it earns its
 * own file rather than becoming one more shape the trigger/action rendering
 * has to anticipate.
 *
 * A node's own affordances are addressed by [ConditionDraft]'s path scheme:
 * the empty path is the root, `[0]` its first child, `[0, 1]` that child's
 * second child, and so on. [onAddCheckRequested] and [onChangeTypeRequested]
 * only ask the screen to open a picker — the actual edit happens once
 * something is picked, back in the ViewModel — which is why they, unlike
 * [onAddGroup] (nothing to choose) and [onRemove]/[onSetOp]/[onConfigChange]
 * (nothing to pick from a list), take no component type of their own.
 */
@Composable
internal fun GateEditor(
    conditions: ConditionDraft?,
    descriptorFor: (String) -> ComponentDescriptor?,
    onAddCheckRequested: (List<Int>) -> Unit,
    onChangeTypeRequested: (List<Int>) -> Unit,
    onAddGroup: (List<Int>) -> Unit,
    onRemove: (List<Int>) -> Unit,
    onSetOp: (List<Int>, ConditionDraft.Op) -> Unit,
    onConfigChange: (List<Int>, String, String?) -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    isRequirementSatisfied: (ComponentRequirement) -> Boolean,
    isExpanded: (String) -> Boolean,
    onToggleExpanded: (String) -> Unit,
    isCaveatShown: (String) -> Boolean,
    onToggleCaveat: (String) -> Unit,
) {
    SectionLabel("Only if")

    if (conditions == null) {
        // The one affordance an empty section gets — see `docs/conditions.md`:
        // a rule with no conditions must not gain visual weight, so there is no
        // group card, no toggle, nothing to fold here. Just the same "add the
        // first thing" button the trigger and action sections open with.
        BlockOutlineButton(
            text = "Add a condition",
            onClick = { onAddCheckRequested(emptyList()) },
            fillWidth = true,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        return
    }

    ConditionNodeBlock(
        node = conditions,
        path = emptyList(),
        descriptorFor = descriptorFor,
        onAddCheckRequested = onAddCheckRequested,
        onChangeTypeRequested = onChangeTypeRequested,
        onAddGroup = onAddGroup,
        onRemove = onRemove,
        onSetOp = onSetOp,
        onConfigChange = onConfigChange,
        onResolveRequirement = onResolveRequirement,
        isRequirementSatisfied = isRequirementSatisfied,
        isExpanded = isExpanded,
        onToggleExpanded = onToggleExpanded,
        isCaveatShown = isCaveatShown,
        onToggleCaveat = onToggleCaveat,
    )

    // A lone top-level check has no group of its own to add a sibling to — see
    // [ConditionDraft]'s KDoc on promotion. Once it is a group, the same two
    // affordances live inside that group's own block instead (below), and
    // repeating them here would let someone add a second, sibling group at the
    // very top that nothing then combines with the first.
    if (conditions is ConditionDraft.Check) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BlockTextButton("Add a check") { onAddCheckRequested(emptyList()) }
            BlockTextButton("Add a group") { onAddGroup(emptyList()) }
        }
    }
}

/** Dispatches one node to the block that renders its kind. */
@Composable
private fun ConditionNodeBlock(
    node: ConditionDraft,
    path: List<Int>,
    descriptorFor: (String) -> ComponentDescriptor?,
    onAddCheckRequested: (List<Int>) -> Unit,
    onChangeTypeRequested: (List<Int>) -> Unit,
    onAddGroup: (List<Int>) -> Unit,
    onRemove: (List<Int>) -> Unit,
    onSetOp: (List<Int>, ConditionDraft.Op) -> Unit,
    onConfigChange: (List<Int>, String, String?) -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    isRequirementSatisfied: (ComponentRequirement) -> Boolean,
    isExpanded: (String) -> Boolean,
    onToggleExpanded: (String) -> Unit,
    isCaveatShown: (String) -> Boolean,
    onToggleCaveat: (String) -> Unit,
) {
    when (node) {
        is ConditionDraft.Check -> {
            val key = conditionKey(path)
            // The same block a trigger or action renders with, restricted to
            // whatever [descriptorFor] resolves — which is only ever a
            // `supportsCondition` component, because that is all the picker
            // this block's "choose" reopens was ever given to offer. See
            // `RuleEditorViewModel.conditionOptions`.
            ComponentBlock(
                chosenType = node.component.type,
                descriptor = descriptorFor(node.component.type),
                config = node.component.config,
                emptyLabel = "Choose a condition",
                onChoose = { onChangeTypeRequested(path) },
                onConfigChange = { fieldKey, value -> onConfigChange(path, fieldKey, value) },
                onResolveRequirement = onResolveRequirement,
                modifier = Modifier.padding(bottom = 12.dp),
                footer = { BlockTextButton("Remove") { onRemove(path) } },
                expanded = isExpanded(key),
                onToggleExpanded = { onToggleExpanded(key) },
                caveatShown = isCaveatShown(key),
                onToggleCaveat = { onToggleCaveat(key) },
                isRequirementSatisfied = isRequirementSatisfied,
            )
        }

        is ConditionDraft.Group -> ConditionGroupBlock(
            group = node,
            path = path,
            descriptorFor = descriptorFor,
            onAddCheckRequested = onAddCheckRequested,
            onChangeTypeRequested = onChangeTypeRequested,
            onAddGroup = onAddGroup,
            onRemove = onRemove,
            onSetOp = onSetOp,
            onConfigChange = onConfigChange,
            onResolveRequirement = onResolveRequirement,
            isRequirementSatisfied = isRequirementSatisfied,
            isExpanded = isExpanded,
            onToggleExpanded = onToggleExpanded,
            isCaveatShown = isCaveatShown,
            onToggleCaveat = onToggleCaveat,
        )
    }
}

/**
 * A group's own block: the AND/OR choice, its children indented beneath, and
 * the affordances that add to it.
 *
 * The choice is spelled out as the two words in a [BlockToggleChip] pair
 * rather than a single switch — [BlockToggle] is the right idiom for a
 * two-state on/off, but AND and OR are "one of a small set of choices," which
 * is exactly what [BlockToggleChip] already means elsewhere. Always shown,
 * with no hiding for a group of fewer than two children: a group only exists
 * here because a person deliberately added one, and a group that vanished the
 * moment it was created — before anything was dropped into it — would look
 * like the tap did nothing. That is a different rule from the *root*
 * position's, which stays unwrapped for exactly one condition; see
 * [ConditionDraft] and [GateEditor].
 *
 * Nesting reads from the block borders and the indent alone, per
 * `docs/architecture.md`'s "Blocks, not cards" — no connecting lines, no
 * icons, nothing this design's vocabulary does not already have.
 */
@Composable
private fun ConditionGroupBlock(
    group: ConditionDraft.Group,
    path: List<Int>,
    descriptorFor: (String) -> ComponentDescriptor?,
    onAddCheckRequested: (List<Int>) -> Unit,
    onChangeTypeRequested: (List<Int>) -> Unit,
    onAddGroup: (List<Int>) -> Unit,
    onRemove: (List<Int>) -> Unit,
    onSetOp: (List<Int>, ConditionDraft.Op) -> Unit,
    onConfigChange: (List<Int>, String, String?) -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    isRequirementSatisfied: (ComponentRequirement) -> Boolean,
    isExpanded: (String) -> Boolean,
    onToggleExpanded: (String) -> Unit,
    isCaveatShown: (String) -> Boolean,
    onToggleCaveat: (String) -> Unit,
) {
    BlockCard(modifier = Modifier.padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BlockToggleChip(
                    text = "AND",
                    selected = group.op == ConditionDraft.Op.ALL,
                    onClick = { onSetOp(path, ConditionDraft.Op.ALL) },
                )
                BlockToggleChip(
                    text = "OR",
                    selected = group.op == ConditionDraft.Op.ANY,
                    onClick = { onSetOp(path, ConditionDraft.Op.ANY) },
                )
                Spacer(modifier = Modifier.weight(1f))
                BlockTextButton("Remove") { onRemove(path) }
            }

            Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp)) {
                group.children.forEachIndexed { index, child ->
                    ConditionNodeBlock(
                        node = child,
                        path = path + index,
                        descriptorFor = descriptorFor,
                        onAddCheckRequested = onAddCheckRequested,
                        onChangeTypeRequested = onChangeTypeRequested,
                        onAddGroup = onAddGroup,
                        onRemove = onRemove,
                        onSetOp = onSetOp,
                        onConfigChange = onConfigChange,
                        onResolveRequirement = onResolveRequirement,
                        isRequirementSatisfied = isRequirementSatisfied,
                        isExpanded = isExpanded,
                        onToggleExpanded = onToggleExpanded,
                        isCaveatShown = isCaveatShown,
                        onToggleCaveat = onToggleCaveat,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BlockTextButton("Add a check") { onAddCheckRequested(path) }
                    BlockTextButton("Add a group") { onAddGroup(path) }
                }
            }
        }
    }
}

/** Keyed by path rather than position in a flat list, for the same fold and
 * caveat bookkeeping the trigger and action sections use — see
 * `RuleEditorScreen`'s `collapsed`/`shownCaveats`.
 */
private fun conditionKey(path: List<Int>): String =
    if (path.isEmpty()) "condition" else "condition-" + path.joinToString("-")
