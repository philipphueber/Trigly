package app.phueber.trigly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.NodePath
import app.phueber.trigly.core.TriggerNode

/**
 * The "When" section's whole tree, one node at a time: a leaf gets the exact
 * [ComponentBlock] a trigger has always used, and a group gets its own block
 * whose header is the AND/OR choice and whose body is its children — each
 * rendered by a recursive call to this same function.
 *
 * This is what replaced the old two-region editor: a fixed row of trigger
 * edges above a separately-captioned tree of conditions beneath it. That split
 * was never true of the model — a condition was a trigger asked instead of
 * watched — and the UI said otherwise anyway, which is what the "gate is a
 * trigger" redesign fixes. There is one slot now, addressed by [NodePath]
 * rather than a flat index, because [TriggerDraft] is a tree of arbitrary
 * depth and a tree has no flat index to give it.
 *
 * A node's own affordances are addressed by [NodePath]'s scheme: the empty
 * path is the root, `[0]` its first child, `[0, 1]` that child's second child,
 * and so on. [onChangeTypeRequested] and [onAddTriggerRequested] only ask the
 * screen to open a picker — the actual edit happens once something is picked,
 * back in the ViewModel — which is why they take no component type of their
 * own, unlike [onSetOp], [onRemove] and [onConfigChange], which have nothing to
 * pick from a list. That picker is also where a *group* comes from: "All of
 * these" is one of its rows, so this file needs no separate notion of adding
 * one.
 */
@Composable
internal fun TriggerNodeBlock(
    node: TriggerDraft,
    path: NodePath,
    descriptorFor: (String) -> ComponentDescriptor?,
    /**
     * Whatever tools the component in a slot offers, drawn into its footer.
     *
     * A composable lambda rather than a list of tools, so this file needs to
     * know neither what tools exist nor how one is rendered — a nested
     * notification trigger wants the same "Inspect" button its root form has,
     * and that should not be two implementations.
     */
    tools: @Composable (String, Map<String, String>) -> Unit,
    onChangeTypeRequested: (NodePath) -> Unit,
    onAddTriggerRequested: (NodePath) -> Unit,
    onSetOp: (NodePath, TriggerNode.Op) -> Unit,
    onRemove: (NodePath) -> Unit,
    onConfigChange: (NodePath, String, String?) -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    isRequirementSatisfied: (ComponentRequirement) -> Boolean,
    isExpanded: (String) -> Boolean,
    onToggleExpanded: (String) -> Unit,
    isCaveatShown: (String) -> Boolean,
    onToggleCaveat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (node) {
        is TriggerDraft.One -> {
            val key = triggerKey(path)
            // The same block a trigger has always rendered with.
            //
            // "Add trigger" appears only on the *root* leaf, and only there, for
            // a reason worth stating: on the root it is the one way a group comes
            // into existence without a container having to be chosen first — the
            // leaf is promoted into one. Inside a group it would be a second
            // button meaning something subtly different from the group's own
            // "Add trigger" a few dp below it ("wrap this one child" rather than
            // "add to this group"), and a group of three then showed four
            // identically-labelled buttons. Nesting deeper is not lost: "All of
            // these" is a row in the picker, so a sub-group is added through the
            // group's own button like anything else.
            //
            // "Remove" sits on every leaf, root included: removing the root leaf
            // is exactly what clears the "When" section back to its unchosen
            // state — see `TriggerNode.removeAt` — which is a real transition and
            // not a dead end this block has to guard against.
            ComponentBlock(
                chosenType = node.component.type,
                descriptor = descriptorFor(node.component.type),
                config = node.component.config,
                emptyLabel = "Choose a trigger",
                onChoose = { onChangeTypeRequested(path) },
                onConfigChange = { fieldKey, value -> onConfigChange(path, fieldKey, value) },
                onResolveRequirement = onResolveRequirement,
                modifier = modifier,
                footer = {
                    tools(node.component.type, node.component.config)
                    if (path.isEmpty()) {
                        BlockTextButton("Add trigger") { onAddTriggerRequested(path) }
                    }
                    BlockTextButton("Remove") { onRemove(path) }
                },
                expanded = isExpanded(key),
                onToggleExpanded = { onToggleExpanded(key) },
                caveatShown = isCaveatShown(key),
                onToggleCaveat = { onToggleCaveat(key) },
                isRequirementSatisfied = isRequirementSatisfied,
            )
        }

        is TriggerDraft.Group -> TriggerGroupBlock(
            group = node,
            path = path,
            descriptorFor = descriptorFor,
            tools = tools,
            onChangeTypeRequested = onChangeTypeRequested,
            onAddTriggerRequested = onAddTriggerRequested,
            onSetOp = onSetOp,
            onRemove = onRemove,
            onConfigChange = onConfigChange,
            onResolveRequirement = onResolveRequirement,
            isRequirementSatisfied = isRequirementSatisfied,
            isExpanded = isExpanded,
            onToggleExpanded = onToggleExpanded,
            isCaveatShown = isCaveatShown,
            onToggleCaveat = onToggleCaveat,
            modifier = modifier,
        )
    }
}

/**
 * A group's own block: folded to one summary line by default, or open to the
 * AND/OR choice and its children, indented beneath.
 *
 * Folding is what keeps a rule with several nested gates readable — see
 * [RuleEditorScreen]'s `collapsed` and `initiallyCollapsedTriggerGroups` for
 * when a group starts shut versus open. What the fold hides is the tree
 * itself; what it keeps, on the summary line, is enough to identify the group
 * and reopen it: the operator, a count of the triggers underneath, and a mark
 * — see [hasDescendantCaveat] — if one of them has something to say that this
 * fold is currently hiding.
 *
 * The AND/OR choice is spelled out as the two words in a [BlockToggleChip]
 * pair rather than a single switch — [BlockToggle] is the right idiom for a
 * two-state on/off, but AND and OR are "one of a small set of choices," which
 * is exactly what [BlockToggleChip] already means elsewhere. Shown only while
 * expanded, unlike the old two-region editor's group block, which showed it
 * always: that block never folded at all, so there was nothing to choose
 * between showing it or not.
 *
 * Nesting reads from the block borders and the indent alone, per
 * `docs/architecture.md`'s "Blocks, not cards" — no connecting lines, no
 * icons, nothing this design's vocabulary does not already have.
 */
@Composable
private fun TriggerGroupBlock(
    group: TriggerDraft.Group,
    path: NodePath,
    descriptorFor: (String) -> ComponentDescriptor?,
    tools: @Composable (String, Map<String, String>) -> Unit,
    onChangeTypeRequested: (NodePath) -> Unit,
    onAddTriggerRequested: (NodePath) -> Unit,
    onSetOp: (NodePath, TriggerNode.Op) -> Unit,
    onRemove: (NodePath) -> Unit,
    onConfigChange: (NodePath, String, String?) -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    isRequirementSatisfied: (ComponentRequirement) -> Boolean,
    isExpanded: (String) -> Boolean,
    onToggleExpanded: (String) -> Unit,
    isCaveatShown: (String) -> Boolean,
    onToggleCaveat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = triggerKey(path)
    val expanded = isExpanded(key)
    val opLabel = if (group.op == TriggerNode.Op.ALL) "ALL OF" else "ANY OF"

    // A group has no caveat of its own — see the class KDoc — so this is never
    // "does this group have a warning", only "is one hidden behind this fold
    // right now". Once the fold is open the children carry their own badges,
    // and this one has nothing left to add.
    val hasHiddenCaveat = !expanded && group.hasDescendantCaveat(descriptorFor)

    BlockCard(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasHiddenCaveat) {
                    // Tapping this opens the group rather than any prose of its
                    // own — there is none to show — so the badge and the
                    // chevron beside it end up doing the same thing.
                    // That overlap is deliberate: someone scanning a folded
                    // rule for the "!" should be able to tap the mark itself
                    // and land on what it was warning about.
                    GroupCaveatBadge(
                        onClick = { onToggleExpanded(key) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    // Uppercased explicitly, the same convention every other
                    // heading in this design follows — `BlockTextButton`,
                    // `SectionLabel`, `BlockHeader` — rather than a plain
                    // `Text` that would be the one heading in the screen
                    // reading in sentence case.
                    text = (
                        if (expanded) opLabel else "$opLabel · ${group.leafCount()} triggers"
                        ).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.extra.accent,
                    modifier = Modifier.weight(1f),
                )
                BlockExpandButton(
                    expanded = expanded,
                    onToggleExpanded = { onToggleExpanded(key) },
                )
            }

            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BlockToggleChip(
                        text = "AND",
                        selected = group.op == TriggerNode.Op.ALL,
                        onClick = { onSetOp(path, TriggerNode.Op.ALL) },
                    )
                    BlockToggleChip(
                        text = "OR",
                        selected = group.op == TriggerNode.Op.ANY,
                        onClick = { onSetOp(path, TriggerNode.Op.ANY) },
                    )
                    Box(modifier = Modifier.weight(1f))
                    BlockTextButton("Remove") { onRemove(path) }
                }

                Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp)) {
                    group.children.forEachIndexed { index, child ->
                        TriggerNodeBlock(
                            node = child,
                            path = path + index,
                            descriptorFor = descriptorFor,
                            tools = tools,
                            onChangeTypeRequested = onChangeTypeRequested,
                            onAddTriggerRequested = onAddTriggerRequested,
                                            onSetOp = onSetOp,
                            onRemove = onRemove,
                            onConfigChange = onConfigChange,
                            onResolveRequirement = onResolveRequirement,
                            isRequirementSatisfied = isRequirementSatisfied,
                            isExpanded = isExpanded,
                            onToggleExpanded = onToggleExpanded,
                            isCaveatShown = isCaveatShown,
                            onToggleCaveat = onToggleCaveat,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // One button, not two. A group used to be added from
                        // here, which made it a different kind of thing from a
                        // trigger — the mistake this whole model exists to undo.
                        // "All of these" and "Any of these" are rows in the
                        // picker this opens, so adding a sub-group is the same
                        // gesture as adding a trigger. See [GROUP_OPTIONS].
                        BlockTextButton("Add trigger") { onAddTriggerRequested(path) }
                    }
                }
            }
        }
    }
}

/** Every trigger leaf under this node, however deep — what a folded group's
 * summary line counts. Counts leaves rather than direct children so a group
 * that nests a sub-group still reports the true number of triggers inside it.
 */
private fun TriggerDraft.leafCount(): Int = when (this) {
    is TriggerDraft.One -> 1
    is TriggerDraft.Group -> children.sumOf { it.leafCount() }
}

/**
 * Whether any leaf under this node carries a caveat — what decides whether a
 * folded group earns the "!" mark on its summary line.
 *
 * A group has no caveat of its own: this walks down to the leaves and asks
 * each one's own descriptor, the same warning each leaf's own [CaveatBadge]
 * would otherwise be sitting on top of, if the fold were not hiding it.
 */
private fun TriggerDraft.hasDescendantCaveat(
    descriptorFor: (String) -> ComponentDescriptor?,
): Boolean = when (this) {
    is TriggerDraft.One -> descriptorFor(component.type)?.warning != null
    is TriggerDraft.Group -> children.any { it.hasDescendantCaveat(descriptorFor) }
}

/**
 * The "!" a folded group wears when a child hidden behind that fold has
 * something to say. Visually the same mark [CaveatBadge] draws, but a
 * different control underneath: there is no prose of the group's own to
 * reveal, so tapping this opens the group instead of toggling anything in
 * place — see [TriggerGroupBlock].
 */
@Composable
private fun GroupCaveatBadge(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = GROUP_CAVEAT_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(BlockShape)
                .border(2.dp, MaterialTheme.extra.caution, BlockShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "!",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.extra.caution,
            )
        }
    }
}

/** Read by the accessibility layer and by the instrumented test. */
internal const val GROUP_CAVEAT_DESCRIPTION = "A hidden trigger has a caveat"
