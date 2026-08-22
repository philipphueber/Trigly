package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Rule

/**
 * Stateless by design: it takes the rules and reports actions back out. That is
 * what lets the instrumented test drive it without an Activity, ViewModel, or
 * repository.
 */
@Composable
fun RulesScreen(
    statuses: List<RuleStatus>,
    onEnabledChange: (Rule, Boolean) -> Unit,
    onResolve: (ComponentRequirement) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = stringResource(R.string.rules_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        if (statuses.isEmpty()) {
            Text(
                text = stringResource(R.string.rules_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(items = statuses, key = { it.rule.id }) { status ->
                RuleRow(status = status, onEnabledChange = onEnabledChange)
                RequirementWarnings(status = status, onResolve = onResolve)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RuleRow(
    status: RuleStatus,
    onEnabledChange: (Rule, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = status.rule.name, style = MaterialTheme.typography.bodyLarge)
            Text(text = summarise(status.rule), style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = status.rule.enabled,
            onCheckedChange = { enabled -> onEnabledChange(status.rule, enabled) },
        )
    }
}

/**
 * The point of the whole requirement model: an enabled rule that cannot fire
 * says so, instead of looking identical to one that is simply waiting.
 *
 * Only shown for enabled rules — a disabled rule not firing is not a mystery
 * that needs explaining.
 */
@Composable
private fun RequirementWarnings(
    status: RuleStatus,
    onResolve: (ComponentRequirement) -> Unit,
) {
    if (status.canFire || !status.rule.enabled) return

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        status.unmet.forEach { requirement ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = requirement.describe(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (requirement.isResolvable) {
                    TextButton(onClick = { onResolve(requirement) }) {
                        Text(stringResource(R.string.requirement_grant))
                    }
                }
            }
        }
    }
}

/** Type identifiers, not display names — a rule editor would resolve these properly. */
private fun summarise(rule: Rule): String =
    "${rule.trigger.type} → ${rule.actions.joinToString { it.type }}"
