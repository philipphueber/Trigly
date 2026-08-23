package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import app.phueber.trigly.core.ConfigField

/**
 * Create or edit one rule.
 *
 * Everything on one scrolling screen rather than a wizard: the intended user
 * builds rules repeatedly and knows what they want, so paging through steps
 * costs them time on every rule.
 *
 * Save and Delete live in a bottom strip rather than at the end of the scroll. A
 * rule with six actions is taller than a screen, and "where did Save go" is not
 * a question a dense power-user UI should ask. The strip also owns the
 * navigation-bar inset, and the whole screen takes `imePadding` so the keyboard
 * pushes it up instead of covering it.
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
    onTestAction: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<Picking?>(null) }
    val draft = state.draft

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        BlockHeader(
            title = if (draft.isNew) "New rule" else "Edit rule",
            leading = {
                // Discoverable back, for the half of Android that navigates by
                // gesture and never learned the edge swipe.
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text("NAME *", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlockToggle(checked = draft.enabled, onCheckedChange = onEnabledChange)
                Text(
                    text = "ENABLED",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            // A refused save, unlike a component caveat, is a fault: it gets the
            // error colour and a block of its own rather than a line of text.
            state.error?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        2.dp,
                        MaterialTheme.colorScheme.error,
                    ),
                    shape = BlockShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .hardShadow(BlockShape),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            // What a test run reported. Deliberately not the error colour: a
            // test that fails is information, not a fault in the rule — and one
            // that succeeds still needs saying, or pressing Test on a silent
            // action looks like nothing happened.
            state.testResult?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(
                        2.dp,
                        MaterialTheme.colorScheme.outline,
                    ),
                    shape = BlockShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .hardShadow(BlockShape),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            SectionLabel("When")
            ComponentBlock(
                chosenType = draft.trigger?.type,
                descriptor = draft.trigger?.let { descriptorFor(Slot.TRIGGER, it.type) },
                config = draft.trigger?.config.orEmpty(),
                emptyLabel = "Choose a trigger",
                onChoose = { picking = Picking.Trigger },
                onConfigChange = { key, value -> onConfigChange(Slot.TRIGGER, 0, key, value) },
                onResolveRequirement = onResolveRequirement,
            )

            SectionLabel("Then")
            draft.actions.forEachIndexed { index, action ->
                ComponentBlock(
                    chosenType = action.type,
                    descriptor = descriptorFor(Slot.ACTION, action.type),
                    config = action.config,
                    emptyLabel = "Choose an action",
                    onChoose = { picking = Picking.ActionType(index) },
                    onConfigChange = { key, value ->
                        onConfigChange(Slot.ACTION, index, key, value)
                    },
                    onResolveRequirement = onResolveRequirement,
                    modifier = Modifier.padding(bottom = 12.dp),
                    footer = {
                        // Runs it now, because half of what an action does is
                        // sensory — which sound, how loud, how the spoken text
                        // reads — and the alternative is saving, waiting for the
                        // real trigger, and guessing. Doubles as a stop button
                        // while running: `play_alert` loops for up to a minute.
                        BlockTextButton(
                            if (state.testing == index) "Stop" else "Test"
                        ) { onTestAction(index) }
                        // Order matters — actions run in sequence.
                        if (index > 0) {
                            BlockTextButton("Up") { onMoveAction(index, index - 1) }
                        }
                        if (index < draft.actions.lastIndex) {
                            BlockTextButton("Down") { onMoveAction(index, index + 1) }
                        }
                        BlockTextButton("Remove") { onRemoveAction(index) }
                    },
                )
            }

            // Full width, like the trigger block above it: both are the "pick a
            // component" affordance for their section, and a shrink-wrapped box
            // floating at the left read as a stray control rather than the
            // counterpart to "Choose a trigger".
            BlockOutlineButton(
                text = "Add action",
                onClick = { picking = Picking.NewAction },
                fillWidth = true,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }

        BlockBottomBar {
            BlockButton(text = "Save", onClick = onSave, modifier = Modifier.weight(1f))
            if (!draft.isNew) {
                BlockOutlineButton(
                    text = "Delete rule",
                    onClick = onDelete,
                    contentColor = MaterialTheme.colorScheme.error,
                )
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

/** A solid tag rather than a heading: the section names are part of the blocks. */
@Composable
private fun SectionLabel(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = BlockShape,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * One trigger or action, as a block split into cells: what it is, what to know
 * about it, its settings, what it needs, and what can be done to it.
 */
@Composable
private fun ComponentBlock(
    chosenType: String?,
    descriptor: ComponentDescriptor?,
    config: Map<String, String>,
    emptyLabel: String,
    onChoose: () -> Unit,
    onConfigChange: (String, String?) -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    BlockCard(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The block's main affordance, so it takes the accent.
                BlockTextButton(
                    text = descriptor?.displayName ?: chosenType ?: emptyLabel,
                    modifier = Modifier.weight(1f),
                    contentColor = MaterialTheme.extra.accent,
                    onClick = onChoose,
                )
            }

            if (descriptor == null) {
                // A stored rule can name a component this build does not have —
                // after a downgrade, or an import from a newer version.
                chosenType?.let {
                    BlockDivider()
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "\"$it\" is not available in this version of Trigly.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
                Footer(footer)
                return@Column
            }

            // The full caveat, at the one moment it is actionable: the component
            // is chosen, the fields are in front of you, and swapping it out is
            // still a tap away. The picker only marks that this exists.
            descriptor.warning?.let {
                BlockDivider()
                Surface(
                    color = MaterialTheme.extra.cautionContainer,
                    contentColor = MaterialTheme.extra.onCautionContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            if (descriptor.configFields.isNotEmpty()) {
                BlockDivider()
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    descriptor.configFields.forEach { field ->
                        // Two kinds own a second config key — a TextPattern's
                        // match mode and a TimeOfDay's minute — because in both
                        // cases the pair is one decision. Every other kind
                        // ignores these two arguments.
                        val secondKey = when (field) {
                            is ConfigField.TextPattern -> field.modeKey
                            is ConfigField.TimeOfDay -> field.minuteKey
                            is ConfigField.Coordinates -> field.longitudeKey
                            else -> null
                        }
                        ConfigFieldEditor(
                            field = field,
                            value = config[field.key],
                            onValueChange = { onConfigChange(field.key, it) },
                            secondValue = secondKey?.let { config[it] },
                            onSecondChange = { second ->
                                secondKey?.let { onConfigChange(it, second) }
                            },
                        )
                    }
                }
            }

            // Stated while the rule is being built, so nobody saves something
            // that cannot fire and then wonders why.
            if (descriptor.requirements.isNotEmpty()) {
                BlockDivider()
                Column(modifier = Modifier.fillMaxWidth()) {
                    descriptor.requirements.forEach { requirement ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = requirement.describe(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            if (requirement.isResolvable) {
                                BlockTextButton("Grant") { onResolveRequirement(requirement) }
                            }
                        }
                    }
                }
            }

            Footer(footer)
        }
    }
}

@Composable
private fun Footer(footer: (@Composable () -> Unit)?) {
    if (footer == null) return
    BlockDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        footer()
    }
}
