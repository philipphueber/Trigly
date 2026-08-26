package app.phueber.trigly.ui

import android.text.format.DateUtils
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * One saved app-scope variable, as this screen needs to show it.
 *
 * Carries rule *names*, not [app.phueber.trigly.core.Rule]s: `VariableUse.kt`'s
 * `rulesReading` already knows how to find them from the stored rules and the
 * registry's substitution lookup, and none of that belongs in a screen that has
 * to render from a plain list in a test with no repository behind it. Whoever
 * builds this row has already answered "which rules", so this only has to show
 * the answer.
 */
data class SavedValueRow(
    val name: String,
    val value: String,
    /** Epoch millis. Never shown as a number: see [relativeTime]. */
    val lastChangedMillis: Long,
    /** Display names of the rules that read this value. Empty when none do. */
    val readByRuleNames: List<String> = emptyList(),
)

/**
 * Lets a person see what a rule has saved, and set a value by hand.
 *
 * Before this screen existed the feature was nearly impossible to find: the
 * editor's variable picker had nothing to offer until some rule had already
 * written a value, and nothing on any screen said that writing one was even
 * possible. See "App scope: a value that outlives the run" in
 * `docs/architecture.md`, under "Not built, and visible as a gap".
 *
 * Stateless by design, the same reasoning [RulesScreen] documents for itself:
 * it takes what to show and reports what someone did, so the instrumented
 * tests can drive it with a plain list and no ViewModel, repository, or
 * `VariableStore` behind it.
 *
 * [onAddValue] and [onEditValue] both end up calling
 * `SavedValuesViewModel.setValue`: from the store's side a write is a write,
 * whether or not the name was already taken. Kept as two parameters here
 * anyway, because the screen's own rule is narrower than the store's. A
 * saved value's name is fixed once it exists (see [SavedValueDraftDialog]),
 * so only [onAddValue] is ever offered a name to type.
 *
 * [nameProblem] is a pure function rather than a value carried in some state
 * object, following [RulesScreen]'s `describeComponent`. `variableNameProblem`
 * in `VariableStore.kt` is already synchronous and side-effect free, so there
 * is nothing to wait for and no reason to round-trip a keystroke through a
 * ViewModel before the person sees whether it was accepted.
 */
@Composable
fun SavedValuesScreen(
    values: List<SavedValueRow>,
    onAddValue: (name: String, value: String) -> Unit,
    /** [name] is the value being changed; a saved value's name cannot be edited. */
    onEditValue: (name: String, value: String) -> Unit,
    onDeleteValue: (name: String) -> Unit,
    /** Why a typed name cannot be stored, or null when it can. */
    nameProblem: (String) -> String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Both dialogs are transient view state, the same way `RuleEditorScreen`
    // keeps which picker is open in a plain `remember`: neither describes a
    // saved value, only how this screen is being looked at right now.
    var draft by remember { mutableStateOf<SavedValueDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedValueRow?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        BlockHeader(
            title = "Saved values",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        if (values.isEmpty()) {
            SavedValuesEmptyState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = values, key = { it.name }) { row ->
                    SavedValueBlock(
                        row = row,
                        onEdit = {
                            draft = SavedValueDraft(
                                originalName = row.name,
                                name = row.name,
                                value = row.value,
                            )
                        },
                        onDeleteRequest = {
                            // "Do not manufacture ceremony" for a value nothing
                            // reads: the same treatment `RuleEditorScreen` gives
                            // "Delete rule", a plain button with no dialog at
                            // all. A value with readers gets the dialog instead
                            // of the direct call, because that is the one case
                            // where deleting quietly breaks something else.
                            if (row.readByRuleNames.isEmpty()) {
                                onDeleteValue(row.name)
                            } else {
                                pendingDelete = row
                            }
                        },
                    )
                }
            }
        }

        BlockBottomBar {
            BlockButton(
                text = "Add value",
                onClick = { draft = SavedValueDraft(originalName = null, name = "", value = "") },
                modifier = Modifier.weight(1f),
            )
        }
    }

    draft?.let { current ->
        SavedValueDraftDialog(
            draft = current,
            nameProblem = nameProblem,
            onNameChange = { draft = current.copy(name = it) },
            onValueChange = { draft = current.copy(value = it) },
            onConfirm = {
                val isNew = current.originalName == null
                // Asked again here rather than trusted from composition: this
                // is the gate that keeps an invalid name from being stored, not
                // merely from looking accepted. A disabled button is only a
                // suggestion once a test, or a differently-behaving future
                // Compose version, taps it anyway.
                if (!isNew || nameProblem(current.name) == null) {
                    if (isNew) {
                        onAddValue(current.name, current.value)
                    } else {
                        onEditValue(current.originalName, current.value)
                    }
                    draft = null
                }
            },
            onDismiss = { draft = null },
        )
    }

    pendingDelete?.let { row ->
        DeleteSavedValueDialog(
            row = row,
            onConfirm = {
                onDeleteValue(row.name)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * The whole reason this screen exists: a person arriving here before any rule
 * has written a value must learn what a saved value is, not stare at a blank
 * list. Names the action by its own display name, `Set an app variable`
 * (`SetVariableAction.displayName`), rather than a paraphrase that could drift
 * from what the editor's picker actually shows.
 */
@Composable
private fun SavedValuesEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        BlockCard(fill = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(
                text = "No values are saved yet. A rule writes one with the Set an app " +
                    "variable action. Any rule can then read it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * One saved value as a block: its name and value on top, then a footer to
 * delete it. Tapping the card opens it for editing, the same as [RuleBlock]
 * does for a rule.
 */
@Composable
private fun SavedValueBlock(
    row: SavedValueRow,
    onEdit: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    BlockCard(onClick = onEdit) {
        Column {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                // Uppercase: the name is this value's label, the same role a
                // rule's own name plays in `RuleBlock`.
                Text(
                    text = row.name.uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                )
                // Not uppercased and not the same colour as the name: this is
                // the data itself, exactly as stored, and forcing its case
                // would misrepresent a value nobody typed in capitals. Monospace
                // and `extra.accent` are `Palette.kt`'s own words for this job:
                // "a value readout" is named there beside the regex escapes as
                // the one other place the ink orange belongs.
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extra.accent,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = savedValueMeta(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            BlockDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                BlockTextButton(
                    text = "Delete",
                    contentColor = MaterialTheme.extra.accent,
                    onClick = onDeleteRequest,
                )
            }
        }
    }
}

/**
 * "Changed 5 minutes ago", and, only when a rule reads this value, "Read by 2
 * rules" beside it.
 *
 * The read count is ordinary information, not a caution. See "Warnings are
 * not errors" and "A value that rules read is normal, not a warning", which is
 * why it renders in the same muted colour as the change time in
 * [SavedValueBlock] rather than in `TriglyExtraColors.caution`. Colouring "this
 * is being used" as a warning would teach people to worry about the ordinary
 * case, which is every value a working rule ever reads.
 */
private fun savedValueMeta(row: SavedValueRow): String {
    val changed = "Changed ${relativeTime(row.lastChangedMillis)}"
    val readBy = when (row.readByRuleNames.size) {
        0 -> null
        1 -> "Read by 1 rule"
        else -> "Read by ${row.readByRuleNames.size} rules"
    }
    return listOfNotNull(changed, readBy).joinToString(" · ").uppercase()
}

/**
 * "How long ago", not a number. The question this line answers is "did my rule
 * actually run", which someone answers by comparing against their memory of
 * the morning, not by subtracting two epoch timestamps in their head. No other
 * screen in this app renders a moment this way: every existing time control
 * is for *setting* a timestamp, not reading one back. So this is the first
 * use of `DateUtils.getRelativeTimeSpanString`, the platform's own answer to
 * exactly this question.
 */
private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/**
 * The add/edit dialog's own draft. [originalName] is null while adding a new
 * value and holds the value's own name while editing one. A saved value's
 * name is its identity, so editing changes what is stored under it rather
 * than what it is called.
 */
private data class SavedValueDraft(
    val originalName: String?,
    val name: String,
    val value: String,
)

/**
 * Add or edit one value. The name field only appears while adding: renaming a
 * saved value is not offered, because a rule reading the old name would start
 * failing on a reference that no longer resolves, exactly the situation
 * [DeleteSavedValueDialog] exists to warn about before a delete. Silently
 * doing the same thing on a rename would be worse, not better.
 */
@Composable
private fun SavedValueDraftDialog(
    draft: SavedValueDraft,
    nameProblem: (String) -> String?,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = draft.originalName == null
    val problem = if (isNew) nameProblem(draft.name) else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isNew) "ADD VALUE" else "EDIT VALUE",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column {
                if (isNew) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = onNameChange,
                        label = { Text("NAME", style = MaterialTheme.typography.labelMedium) },
                        singleLine = true,
                        isError = problem != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Shown live, as the person types, the same as
                    // `PatternTesterDialog`'s compile error. A message that
                    // only appeared after a confirm tap would let someone type
                    // a whole name before finding out it cannot be saved.
                    problem?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    Text("NAME", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = draft.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                }
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = onValueChange,
                    label = { Text("VALUE", style = MaterialTheme.typography.labelMedium) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = if (isNew) 12.dp else 0.dp),
                )
            }
        },
        confirmButton = {
            BlockTextButton(text = if (isNew) "Add" else "Save", onClick = onConfirm)
        },
        dismissButton = { BlockTextButton(text = "Cancel", onClick = onDismiss) },
    )
}

/**
 * "Deleting must say what it will break." Only reached for a value at least
 * one rule reads. [SavedValuesScreen] deletes a value nothing reads directly,
 * with no dialog at all, because that delete is ordinary and this one is not.
 */
@Composable
private fun DeleteSavedValueDialog(
    row: SavedValueRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "DELETE ${row.name.uppercase()}?",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = { Text(readByRulesSentence(row.readByRuleNames)) },
        confirmButton = { BlockTextButton(text = "Delete value", onClick = onConfirm) },
        dismissButton = { BlockTextButton(text = "Cancel", onClick = onDismiss) },
    )
}

/**
 * Names every rule that reads the value about to be deleted, rather than a
 * bare count: a count answers "is anything using this", and the question
 * someone facing a delete button is actually asking is "what will I break".
 */
private fun readByRulesSentence(ruleNames: List<String>): String {
    val subject = joinNaturally(ruleNames)
    val verb = if (ruleNames.size == 1) "reads" else "read"
    val pronoun = if (ruleNames.size == 1) "It" else "They"
    return "$subject $verb this value. $pronoun will fail once it is gone."
}

/** "A" · "A and B" · "A, B and C". */
private fun joinNaturally(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}
