package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentRequirement

/**
 * Create or edit one rule.
 *
 * Everything on one scrolling screen rather than a wizard: the intended user
 * builds rules repeatedly and knows what they want, so paging through steps
 * costs them time on every rule.
 *
 * Stateless, like [RulesScreen] — it takes the draft and emits intents, which is
 * what lets the instrumented tests drive it without a ViewModel or a database.
 */
@Composable
fun RuleEditorScreen(
    state: EditorState,
    triggerOptions: List<ComponentDescriptor>,
    actionOptions: List<ComponentDescriptor>,
    descriptorFor: (Slot, String) -> ComponentDescriptor?,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onChooseTrigger: (String) -> Unit,
    onAddAction: (String) -> Unit,
    onChangeActionType: (Int, String) -> Unit,
    onRemoveAction: (Int) -> Unit,
    onMoveAction: (Int, Int) -> Unit,
    onConfigChange: (Slot, Int, String, String?) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<Picking?>(null) }
    val draft = state.draft

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = if (draft.isNew) "New rule" else "Edit rule",
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = draft.name,
            onValueChange = onNameChange,
            label = { Text("Name *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = draft.enabled, onCheckedChange = onEnabledChange)
            Text(text = "Enabled", modifier = Modifier.padding(start = 12.dp))
        }

        state.error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }

        SectionHeader("When")
        ComponentCard(
            chosenType = draft.trigger?.type,
            descriptor = draft.trigger?.let { descriptorFor(Slot.TRIGGER, it.type) },
            config = draft.trigger?.config.orEmpty(),
            emptyLabel = "Choose a trigger",
            onChoose = { picking = Picking.Trigger },
            onConfigChange = { key, value -> onConfigChange(Slot.TRIGGER, 0, key, value) },
            onResolveRequirement = onResolveRequirement,
        )

        SectionHeader("Then")
        draft.actions.forEachIndexed { index, action ->
            ComponentCard(
                chosenType = action.type,
                descriptor = descriptorFor(Slot.ACTION, action.type),
                config = action.config,
                emptyLabel = "Choose an action",
                onChoose = { picking = Picking.ActionType(index) },
                onConfigChange = { key, value -> onConfigChange(Slot.ACTION, index, key, value) },
                onResolveRequirement = onResolveRequirement,
                trailing = {
                    Row(horizontalArrangement = Arrangement.End) {
                        // Order matters — actions run in sequence.
                        if (index > 0) {
                            TextButton(onClick = { onMoveAction(index, index - 1) }) { Text("Up") }
                        }
                        if (index < draft.actions.lastIndex) {
                            TextButton(onClick = { onMoveAction(index, index + 1) }) { Text("Down") }
                        }
                        TextButton(onClick = { onRemoveAction(index) }) { Text("Remove") }
                    }
                },
            )
        }

        OutlinedButton(
            onClick = { picking = Picking.NewAction },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Add action")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(onClick = onSave) { Text("Save") }
            if (!draft.isNew) {
                TextButton(onClick = onDelete) { Text("Delete rule") }
            }
        }
    }

    when (val target = picking) {
        null -> Unit

        Picking.Trigger -> ComponentPickerDialog(
            title = "Choose a trigger",
            options = triggerOptions,
            onPick = { picking = null; onChooseTrigger(it) },
            onDismiss = { picking = null },
        )

        Picking.NewAction -> ComponentPickerDialog(
            title = "Add an action",
            options = actionOptions,
            onPick = { picking = null; onAddAction(it) },
            onDismiss = { picking = null },
        )

        is Picking.ActionType -> ComponentPickerDialog(
            title = "Change action",
            options = actionOptions,
            onPick = { picking = null; onChangeActionType(target.index, it) },
            onDismiss = { picking = null },
        )
    }
}

private sealed interface Picking {
    data object Trigger : Picking
    data object NewAction : Picking
    data class ActionType(val index: Int) : Picking
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ComponentCard(
    chosenType: String?,
    descriptor: ComponentDescriptor?,
    config: Map<String, String>,
    emptyLabel: String,
    onChoose: () -> Unit,
    onConfigChange: (String, String?) -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            TextButton(onClick = onChoose) {
                Text(descriptor?.displayName ?: chosenType ?: emptyLabel)
            }

            if (descriptor == null) {
                // A stored rule can name a component this build does not have —
                // after a downgrade, or an import from a newer version.
                chosenType?.let {
                    Text(
                        text = "\"$it\" is not available in this version of Trigly.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                trailing?.invoke()
                return@Column
            }

            descriptor.warning?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            descriptor.configFields.forEach { field ->
                ConfigFieldEditor(
                    field = field,
                    value = config[field.key],
                    onValueChange = { onConfigChange(field.key, it) },
                )
            }

            // Stated while the rule is being built, so nobody saves something
            // that cannot fire and then wonders why.
            descriptor.requirements.forEach { requirement ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = requirement.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    if (requirement.isResolvable) {
                        TextButton(onClick = { onResolveRequirement(requirement) }) {
                            Text("Grant")
                        }
                    }
                }
            }

            trailing?.invoke()
        }
    }
}
