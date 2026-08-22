package app.phueber.trigly.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
 *
 * The `Scaffold` is not decoration. Since Android 15 an app targeting API 35
 * draws behind the status and navigation bars whether it asks to or not, so a
 * plain `Column` put the title under the clock. `Scaffold` consumes those insets
 * for the top bar and reports the rest as content padding, which is the one
 * place the numbers are known.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                actions = {
                    TextButton(onClick = onImport) {
                        Text(stringResource(R.string.rules_import))
                    }
                    // Export is pointless with nothing to export.
                    if (statuses.isNotEmpty()) {
                        TextButton(onClick = onExportAll) {
                            Text(stringResource(R.string.rules_export_all))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        floatingActionButton = {
            // Extended rather than an icon: "New rule" is the one thing a first
            // run must find, and a bare "+" makes the reader guess.
            ExtendedFloatingActionButton(
                onClick = onNewRule,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(stringResource(R.string.rules_new))
            }
        },
    ) { insets ->
        if (statuses.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = stringResource(R.string.rules_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            // The bottom inset is already in `insets`; this is clearance for the
            // floating button, which sits above it.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = statuses, key = { it.rule.id }) { status ->
                RuleCard(
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
}

@Composable
private fun RuleCard(
    status: RuleStatus,
    onEnabledChange: (Rule, Boolean) -> Unit,
    onEditRule: (String) -> Unit,
    onExportRule: (Rule) -> Unit,
    onResolve: (ComponentRequirement) -> Unit,
    describeComponent: (String) -> String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEditRule(status.rule.id) }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text(text = status.rule.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = summarise(status.rule, describeComponent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onExportRule(status.rule) }) {
                        Text(stringResource(R.string.rules_share))
                    }
                    Switch(
                        checked = status.rule.enabled,
                        onCheckedChange = { enabled -> onEnabledChange(status.rule, enabled) },
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
            }
            RequirementWarnings(status = status, onResolve = onResolve)
        }
    }
}

/**
 * The point of the whole requirement model: an enabled rule that cannot fire
 * says so, instead of looking identical to one that is simply waiting.
 *
 * Red, unlike a component's caveat: this rule is not doing the thing it was
 * built to do, which is a fault rather than a footnote.
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

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        status.unmet.forEach { requirement ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp),
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

/** Display names now that factories declare them, rather than raw type strings. */
private fun summarise(rule: Rule, describeComponent: (String) -> String): String {
    val actions = if (rule.actions.isEmpty()) {
        "nothing"
    } else {
        rule.actions.joinToString { describeComponent(it.type) }
    }
    return "${describeComponent(rule.trigger.type)} → $actions"
}
