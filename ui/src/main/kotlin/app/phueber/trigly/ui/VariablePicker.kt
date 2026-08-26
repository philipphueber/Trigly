package app.phueber.trigly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ScopedVariable
import app.phueber.trigly.core.VariableScope

/**
 * Lists what this rule can offer a `{{variable}}` reference, and inserts the
 * one picked. See `docs/variables.md` section 12.
 *
 * Grouped and ordered the way that section asks: the shared `trigger` group
 * first, since that is the form to reach for, then `event` and `rule`, then any
 * type-qualified group. A type-qualified group is offered only when the tree
 * has more than one leaf of a kind. That is [Registry.availableVariables]'s
 * decision, not this dialog's, so [available] is simply drawn in the order it
 * arrives within each scope.
 *
 * No search box, unlike [ComponentPickerDialog]: a rule offers at most a
 * few dozen variables, never the four dozen component types that dialog has to
 * help someone find.
 */
@Composable
internal fun VariablePickerDialog(
    available: List<ScopedVariable>,
    onPick: (ScopedVariable) -> Unit,
    onDismiss: () -> Unit,
) {
    val grouped = available.groupBy { it.scope }
    val ordered = buildList {
        listOf(VariableScope.TRIGGER, VariableScope.EVENT, VariableScope.RULE).forEach { scope ->
            grouped[scope]?.let { add(scope to it) }
        }
        val reserved = setOf(VariableScope.TRIGGER, VariableScope.EVENT, VariableScope.RULE)
        grouped.keys
            .filterNot { it in reserved }
            .sorted()
            .forEach { scope -> add(scope to grouped.getValue(scope)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "INSERT A VARIABLE", style = MaterialTheme.typography.titleMedium) },
        confirmButton = { BlockTextButton("Cancel", onClick = onDismiss) },
        text = {
            if (ordered.isEmpty()) {
                Text(
                    text = "This rule has no trigger yet, so it has no variables to offer.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    ordered.forEach { (scope, specs) ->
                        item(key = "header-$scope") {
                            // A solid bar, matching how `ComponentPickerDialog`
                            // cuts its own list into categories.
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = scopeHeading(scope).uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                        items(items = specs, key = { it.reference }) { scoped ->
                            VariableRow(scoped = scoped, onClick = { onPick(scoped) })
                            BlockDivider()
                        }
                    }
                }
            }
        },
    )
}

/**
 * What a scope reads as, above the variables it groups. The three reserved
 * scopes get the plain words a person would use; a type-qualified scope is a
 * trigger's own type string, which is the honest label for "only when this
 * exact leaf is the one that fired". See `docs/variables.md` section 3.
 */
private fun scopeHeading(scope: String): String = when (scope) {
    VariableScope.TRIGGER -> "The trigger that fired"
    VariableScope.EVENT -> "This run"
    VariableScope.RULE -> "This rule"
    else -> scope.replace('_', ' ')
}

/**
 * One variable's row: its label, the reference itself so a person learns the
 * syntax by reading it, its sample, whatever the declaration had to say, and a
 * mark when the value can be absent.
 *
 * The declared help is drawn because several triggers use it to name the words a
 * state variable can hold, built from the trigger's own constants so the list
 * cannot drift. Declaring that and never showing it would make it exactly the
 * kind of unreachable declaration `ConfigSchemaContractTest` exists to catch.
 *
 * The mark is the reason [VariableSpec.alwaysPresent] exists at all. A
 * Bluetooth device with no name and a notification with no title are both
 * common, and a rule that reads one without a fallback fails when it happens.
 * Saying so here, at the moment the reference is picked, is cheaper than a
 * failed run explaining it later.
 */
@Composable
private fun VariableRow(scoped: ScopedVariable, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(text = scoped.spec.label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = scoped.reference,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.extra.accent,
        )
        Text(
            text = "Sample: ${scoped.spec.sample}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        scoped.spec.help?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!scoped.spec.alwaysPresent) {
            Text(
                text = "This value is sometimes empty. Add a fallback if that must not " +
                    "break the rule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extra.caution,
            )
        }
    }
}
