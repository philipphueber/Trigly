package app.phueber.trigly.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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

/**
 * The parts every "pick, don't type" field is made of.
 *
 * Three config kinds now store a string nobody can produce from memory — a
 * package name, a sound URI, a MAC address — and each renders as a picker for
 * that reason alone. What they need is identical: a searchable list, a row that
 * restores blankness when blankness is a setting, an escape hatch for a value the
 * list does not contain, and a box showing what is currently chosen. Written once
 * here so a fourth costs a list and a label rather than another copy of the
 * behaviour, and so a fix to any of it is a fix to all of it.
 *
 * What stays with each field is only what genuinely differs: where the list comes
 * from, how a stored value is labelled, and what "type it yourself" means.
 */

/** One offered row. [value] is stored; [primary] and [secondary] are shown. */
data class PickerOption(
    val value: String,
    val primary: String,
    val secondary: String? = null,
)

/**
 * A searchable list in a dialog.
 *
 * @param searchLabel doubles as the manual-entry prompt, so it says both jobs.
 * @param clearLabel first row when blankness is a real setting; null hides it.
 *   Without it, opening a picker on an optional field would be a one-way door.
 * @param typedOption what to offer for text that matches nothing. Returning null
 *   means the list is the only answer — which is right for sounds, where a URI is
 *   not something anyone types, and wrong for packages and addresses.
 * @param placeholder shown when there is nothing to list: "still reading" and
 *   "not allowed to look" are different sentences, and the caller knows which.
 */
@Composable
fun ValuePickerDialog(
    title: String,
    searchLabel: String,
    options: List<PickerOption>,
    clearLabel: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String? = null,
    typedOption: (String) -> PickerOption? = { null },
    leading: (@Composable (PickerOption) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }

    val matches = remember(query, options) {
        if (query.isBlank()) {
            options
        } else {
            options.filter {
                it.primary.contains(query, ignoreCase = true) ||
                    it.secondary?.contains(query, ignoreCase = true) == true
            }
        }
    }

    val typed = query.trim()
    val offered = typedOption(typed)?.takeIf { candidate ->
        matches.none { it.value == candidate.value }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.uppercase(), style = MaterialTheme.typography.titleMedium) },
        confirmButton = { BlockTextButton("Cancel", onClick = onDismiss) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = {
                        Text(searchLabel, style = MaterialTheme.typography.labelMedium)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 12.dp)) {
                    // Hidden while searching: it is not a search result, and
                    // leaving it at the top means a stray tap clears the field.
                    if (clearLabel != null && query.isBlank()) {
                        item(key = "clear") {
                            PickerRow(primary = clearLabel, secondary = null) { onPick(null) }
                            BlockDivider()
                        }
                    }

                    offered?.let { candidate ->
                        item(key = "typed") {
                            PickerRow(
                                primary = candidate.primary,
                                secondary = candidate.secondary,
                            ) { onPick(candidate.value) }
                            BlockDivider()
                        }
                    }

                    if (options.isEmpty() && offered == null && placeholder != null) {
                        item(key = "placeholder") {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }

                    items(items = matches, key = { it.value }) { option ->
                        PickerRow(
                            primary = option.primary,
                            secondary = option.secondary,
                            icon = leading?.let { { it(option) } },
                        ) { onPick(option.value) }
                        BlockDivider()
                    }
                }
            }
        },
    )
}

@Composable
private fun PickerRow(
    primary: String,
    secondary: String?,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Column(modifier = Modifier.padding(start = if (icon == null) 4.dp else 12.dp)) {
            Text(text = primary.uppercase(), style = MaterialTheme.typography.labelMedium)
            // Identifiers stay lowercase: a package name or a MAC address is not a
            // label, and uppercasing one makes it wrong rather than merely loud.
            secondary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The field's own control: a bordered box saying what is chosen, which opens the
 * picker when tapped.
 *
 * [secondary] is what the rule actually stores, shown under the friendly name on
 * purpose. Hiding it would make a mis-picked value impossible to notice — and for
 * a value the device no longer knows about, an imported rule naming an uninstalled
 * app or an unpaired device, it is the only thing there is to show.
 */
@Composable
fun PickerValueBox(
    label: String,
    primary: String,
    secondary: String?,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            shape = BlockShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .hardShadow(BlockShape),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke()
                Column(modifier = Modifier.padding(start = if (leading == null) 0.dp else 12.dp)) {
                    Text(
                        text = primary.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.extra.accent,
                    )
                    secondary?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
