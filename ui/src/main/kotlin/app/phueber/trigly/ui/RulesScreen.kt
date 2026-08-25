package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.leaves

/**
 * Stateless by design: it takes the rules and reports actions back out. That is
 * what lets the instrumented test drive it without an Activity, ViewModel, or
 * repository.
 *
 * Laid out as a header slab, a scrolling column of blocks, and a bottom strip,
 * rather than with `Scaffold`: the design wants the orange band painted *behind*
 * the status bar, and `Scaffold`'s job is to keep content out of exactly that
 * area. The insets are handled instead by the two pieces that touch them —
 * [BlockHeader] and [BlockBottomBar].
 */
@Composable
fun RulesScreen(
    statuses: List<RuleStatus>,
    onEnabledChange: (Rule, Boolean) -> Unit,
    onResolve: (ComponentRequirement) -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onExportAll: () -> Unit,
    onExportRule: (Rule) -> Unit,
    onImport: () -> Unit,
    describeComponent: (String) -> String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        BlockHeader(
            title = stringResource(R.string.rules_title),
            actions = {
                BlockTextButton(stringResource(R.string.rules_import), onClick = onImport)
                // Export is pointless with nothing to export.
                if (statuses.isNotEmpty()) {
                    BlockTextButton(
                        text = stringResource(R.string.rules_export_all),
                        onClick = onExportAll,
                    )
                }
            },
        )

        if (statuses.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                BlockCard(fill = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(
                        text = stringResource(R.string.rules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = statuses, key = { it.rule.id }) { status ->
                    RuleBlock(
                        status = status,
                        onEnabledChange = onEnabledChange,
                        onEditRule = onEditRule,
                        onExportRule = onExportRule,
                        onResolve = onResolve,
                        describeComponent = describeComponent,
                    )
                }
            }
        }

        BlockBottomBar {
            BlockButton(
                text = stringResource(R.string.rules_new),
                onClick = onNewRule,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One rule as a block, split into cells by hard lines: what it does on top, the
 * per-rule actions below, and anything stopping it from firing under that.
 */
@Composable
private fun RuleBlock(
    status: RuleStatus,
    onEnabledChange: (Rule, Boolean) -> Unit,
    onEditRule: (String) -> Unit,
    onExportRule: (Rule) -> Unit,
    onResolve: (ComponentRequirement) -> Unit,
    describeComponent: (String) -> String,
) {
    BlockCard(onClick = { onEditRule(status.rule.id) }) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    // Uppercase, because a rule name is a label in this design
                    // rather than a sentence.
                    Text(
                        text = status.rule.name.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = summarise(status.rule, describeComponent).uppercase(),
                        // Monospaced, so a screen of rules lines up into a
                        // column that can be scanned rather than read.
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                BlockToggle(
                    checked = status.rule.enabled,
                    onCheckedChange = { enabled -> onEnabledChange(status.rule, enabled) },
                )
            }

            BlockDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                BlockTextButton(
                    text = stringResource(R.string.rules_share),
                    contentColor = MaterialTheme.extra.accent,
                ) {
                    onExportRule(status.rule)
                }
            }

            RequirementCell(status = status, onResolve = onResolve)
        }
    }
}

/**
 * The point of the whole requirement model: an enabled rule that cannot fire
 * says so, instead of looking identical to one that is simply waiting.
 *
 * Its own cell in the error colour, rather than a line of small red text: this is
 * a fault in the rule, unlike a component's caveat, which is amber and merely
 * informative.
 *
 * Only shown for enabled rules — a disabled rule not firing is not a mystery
 * that needs explaining.
 */
@Composable
private fun RequirementCell(
    status: RuleStatus,
    onResolve: (ComponentRequirement) -> Unit,
) {
    if (status.canFire || !status.rule.enabled) return

    BlockDivider()
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            status.unmet.forEach { requirement ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = requirement.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    if (requirement.isResolvable) {
                        BlockTextButton(stringResource(R.string.requirement_grant)) {
                            onResolve(requirement)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one line that says what a rule does, in display names rather than type
 * strings.
 *
 * **It must describe the whole tree.** It used to read `rule.gate.triggers` — the
 * first-level edges, joined with "or" — and append a bare count of conditions.
 * That was accurate only while "condition" was a separate, flatter thing beside
 * the triggers; once a gate became one [TriggerNode] that can nest to any depth,
 * the old join could state something the rule does not do — a two-deep "all of"
 * read the same as a two-deep "any of", and a rule three levels deep read no
 * differently from one two levels deep, just fewer or more words in an unordered
 * list. This line is where someone checks what a rule does without opening the
 * editor, so a summary that misdescribes is worse than one that is merely terse.
 *
 * A [TriggerNode.Group] is parenthesised and its children joined by "and" or
 * "or" depending on [TriggerNode.Op] — the same mark of grouping the tree itself
 * uses, so "a and (b or c)" reads exactly as nested as it is.
 *
 * **Never fewer triggers than the rule has.** A long tree can run this line past
 * the point of being scannable, but cutting it short must not make the rule read
 * as simpler than it is — that is the bug this replaced. So a cut string is never
 * handed back on its own: it is always suffixed with the true leaf count, however
 * the text before it was truncated, which is what keeps a truncated summary
 * honest about having been truncated rather than looking complete.
 */
private fun summarise(rule: Rule, describeComponent: (String) -> String): String {
    val trigger = describeTrigger(rule.trigger, describeComponent)

    val actions = if (rule.actions.isEmpty()) {
        "nothing"
    } else {
        rule.actions.joinToString { describeComponent(it.type) }
    }
    return "$trigger → $actions"
}

/** Past this many characters the tree is truncated rather than spelled out in full. */
private const val MAX_TRIGGER_SUMMARY_LENGTH = 60

private fun describeTrigger(node: TriggerNode, describeComponent: (String) -> String): String {
    val full = renderTrigger(node, describeComponent)
    if (full.length <= MAX_TRIGGER_SUMMARY_LENGTH) return full

    // A cut mid-tree can drop a whole child, or a whole side of an "or" — which
    // is exactly the shape of the bug this line exists to prevent. Naming the
    // true count after the cut is what makes the truncation honest regardless
    // of where the text happened to break.
    val total = node.leaves().size
    val cut = full.take(MAX_TRIGGER_SUMMARY_LENGTH).trimEnd()
    val noun = if (total == 1) "trigger" else "triggers"
    return "$cut… ($total $noun)"
}

private fun renderTrigger(node: TriggerNode, describeComponent: (String) -> String): String = when (node) {
    is TriggerNode.One -> describeComponent(node.spec.type)

    is TriggerNode.Group -> {
        val joiner = when (node.op) {
            TriggerNode.Op.ALL -> " and "
            TriggerNode.Op.ANY -> " or "
        }
        node.children.joinToString(separator = joiner, prefix = "(", postfix = ")") {
            renderTrigger(it, describeComponent)
        }
    }
}
