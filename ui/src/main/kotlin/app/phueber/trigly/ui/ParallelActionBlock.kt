package app.phueber.trigly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ActionPath

/**
 * A parallel block in the "Then" section: branches that run at the same time,
 * each an ordinary list of actions edited in place.
 *
 * Drawn the way a trigger group is drawn (see `TriggerGroupBlock` in
 * `TriggerTree.kt`), so the two inner nodes of a rule look and behave as one
 * family: a card, an accent heading, a fold that keeps the heading and a count,
 * a "!" on the folded heading when an action inside has a caveat, and a rail
 * beside the children instead of an indent. Where a trigger group has its
 * AND/OR choice, a block has nothing to choose: every branch runs. So the row
 * under the heading holds only the block's own controls.
 *
 * Each branch has a small heading of its own with "Remove branch", then its
 * actions beside a rail, then "Add action" for that branch. "Add branch" ends
 * the block. An action inside a branch is the same [leaf] the top level draws,
 * so it has the same fields, tools, Test, arrows and Remove. Its arrows move it
 * inside its branch only.
 *
 * This file knows nothing about what an action is. [leaf] draws one, and the
 * screen passes the controls in, the same split `TriggerNodeBlock` uses with its
 * `tools` lambda.
 */
@Composable
internal fun ParallelActionBlock(
    block: ActionDraft.Parallel,
    /** Where the block sits in the rule's action list. Its path is `[index]`. */
    index: Int,
    /** How many entries the action list has, for the block's own arrows. */
    siblings: Int,
    leaf: @Composable (ActionPath, ComponentDraft, Int, Modifier) -> Unit,
    onAddActionRequested: (branchPath: ActionPath) -> Unit,
    onAddBranch: (Int) -> Unit,
    onRemove: (ActionPath) -> Unit,
    onMove: (ActionPath, ActionPath) -> Unit,
    orderButton: @Composable (ImageVector, String, () -> Unit) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    /** Whether an action in the block has a caveat, for the folded heading. */
    hasHiddenCaveat: Boolean,
    modifier: Modifier = Modifier,
) {
    val actionCount = block.branches.sumOf { it.size }

    BlockCard(modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!expanded && hasHiddenCaveat) {
                    GroupCaveatBadge(
                        onClick = onToggleExpanded,
                        description = PARALLEL_CAVEAT_DESCRIPTION,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Text(
                    text = (
                        if (expanded) {
                            PARALLEL_HEADING
                        } else {
                            "$PARALLEL_HEADING · ${block.branches.size} branches · $actionCount actions"
                        }
                        ).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.extra.accent,
                    modifier = Modifier.weight(1f),
                )
                BlockExpandButton(expanded = expanded, onToggleExpanded = onToggleExpanded)
            }

            if (expanded) {
                // The block's own controls, on the line where a trigger group
                // has its AND/OR choice. Arrows move the whole block in the
                // list, with the same rule a plain action follows: omitted, not
                // disabled, at either end.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 14.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "The next action starts when all branches are finished.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (index > 0) {
                        orderButton(Icons.Filled.KeyboardArrowUp, "Move up") {
                            onMove(listOf(index), listOf(index - 1))
                        }
                    }
                    if (index < siblings - 1) {
                        orderButton(Icons.Filled.KeyboardArrowDown, "Move down") {
                            onMove(listOf(index), listOf(index + 1))
                        }
                    }
                    BlockTextButton("Remove") { onRemove(listOf(index)) }
                }

                block.branches.forEachIndexed { branchIndex, branch ->
                    val branchPath = listOf(index, branchIndex)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, start = 14.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "BRANCH ${branchIndex + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.extra.accent,
                            modifier = Modifier.weight(1f),
                        )
                        BlockTextButton("Remove branch") { onRemove(branchPath) }
                    }

                    // The rail, for the reasons `TriggerGroupBlock` gives:
                    // a level costs the width of a rail, not of an indent, and
                    // `IntrinsicSize.Min` is what lets `fillMaxHeight` measure.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = BranchRailCardInset)
                            .height(IntrinsicSize.Min),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(BranchRailWidth)
                                .fillMaxHeight()
                                .clip(BlockShape)
                                .background(MaterialTheme.extra.accent),
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = BranchRailGap)) {
                            branch.forEachIndexed { position, action ->
                                leaf(
                                    branchPath + position,
                                    action,
                                    branch.size,
                                    Modifier.padding(bottom = 12.dp),
                                )
                            }
                            BlockTextButton("Add action") { onAddActionRequested(branchPath) }
                        }
                    }
                }

                Row(modifier = Modifier.padding(top = 8.dp, start = 14.dp, end = 14.dp)) {
                    BlockTextButton("Add branch") { onAddBranch(index) }
                }
            }
        }
    }
}

/** The block's heading, and the name its picker row is known by in the docs. */
internal const val PARALLEL_HEADING = "At the same time"

/** Read by the accessibility layer and by the instrumented test. */
internal const val PARALLEL_CAVEAT_DESCRIPTION = "A hidden action has a caveat"

/** The same widths a trigger group's rail uses. See `TriggerTree.kt`. */
private val BranchRailWidth = 2.dp
private val BranchRailGap = 4.dp
private val BranchRailCardInset = 4.dp
