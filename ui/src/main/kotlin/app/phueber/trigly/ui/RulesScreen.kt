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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.Rule

/**
 * Stateless by design: it takes the rules and reports toggles back out. That is
 * what lets the instrumented test drive it without an Activity, a ViewModel, or
 * a repository.
 */
@Composable
fun RulesScreen(
    rules: List<Rule>,
    onEnabledChange: (Rule, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = stringResource(R.string.rules_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        if (rules.isEmpty()) {
            Text(
                text = stringResource(R.string.rules_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
            items(items = rules, key = { it.id }) { rule ->
                RuleRow(rule = rule, onEnabledChange = onEnabledChange)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: Rule,
    onEnabledChange: (Rule, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(text = rule.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summarise(rule),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = rule.enabled,
            onCheckedChange = { enabled -> onEnabledChange(rule, enabled) },
        )
    }
}

/** Type identifiers, not display names — a rule editor would resolve these properly. */
private fun summarise(rule: Rule): String =
    "${rule.trigger.type} → ${rule.actions.joinToString { it.type }}"
