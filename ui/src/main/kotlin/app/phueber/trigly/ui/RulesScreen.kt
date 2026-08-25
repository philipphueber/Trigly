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
import app.phueber.trigly.core.checks

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
    onInspectNotifications: () -> Unit,
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
            // Beside the primary action rather than up in the header: the header
            // already carries the two rule-file actions, and a fourth control up
            // there would push the title into an ellipsis on a narrow screen.
            BlockOutlineButton(
                text = stringResource(R.string.rules_inspect),
                onClick = onInspectNotifications,
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
 * **It must describe the whole gate.** It used to read `rule.trigger` — the first
 * edge — and say nothing about conditions, which was accurate only while a rule
 * could have exactly one trigger and no conditions. Once gates arrived, the list
 * could state something the rule does not do: a rule fired by two triggers looked
 * like it had one, and a rule gated on "only at night" looked unconditional. This
 * list is where someone checks what a rule does without opening it, so a summary
 * that misdescribes is worse than one that is merely terse.
 *
 * Conditions are counted, not named. Naming them would run to a paragraph for a
 * nested tree, and the count is what answers the question this line is asked —
 * "is there more to this than the trigger?" The editor shows the rest.
 */
private fun summarise(rule: Rule, describeComponent: (String) -> String): String {
    val triggers = rule.gate.triggers.joinToString(" or ") { describeComponent(it.type) }

    val conditions = rule.gate.conditions?.checks()?.size ?: 0
    val gate = when (conditions) {
        0 -> triggers
        1 -> "$triggers + 1 condition"
        else -> "$triggers + $conditions conditions"
    }

    val actions = if (rule.actions.isEmpty()) {
        "nothing"
    } else {
        rule.actions.joinToString { describeComponent(it.type) }
    }
    return "$gate → $actions"
}
