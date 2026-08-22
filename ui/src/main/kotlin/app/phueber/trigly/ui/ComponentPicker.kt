package app.phueber.trigly.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ComponentDescriptor

/**
 * Picks one component type from the triggers or actions this device can run.
 *
 * Grouped by category and searchable, because a flat alphabetical list of 28
 * items is unusable and because people arrive knowing the domain ("something to
 * do with battery") rather than the name. Search matches the display name and
 * the raw type string, so someone who has read the docs can type
 * `battery_level` directly.
 *
 * The list is names only. It used to print each component's full warning under
 * its name, on the reasoning that a caveat is most useful *before* the choice is
 * made — but two thirds of the triggers carry one, so the list became a wall of
 * red prose in which no single item could be read, and scanning for the trigger
 * you wanted meant reading past paragraphs about battery cost. A caveat is now a
 * one-glyph marker here and the full sentence in the editor once the component is
 * chosen, which is the point at which it is actually actionable.
 *
 * [options] is expected to be pre-filtered to what the device supports; see
 * `RuleEditorViewModel.triggerOptions`.
 */
@Composable
fun ComponentPickerDialog(
    title: String,
    options: List<ComponentDescriptor>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val matches = remember(query, options) {
        if (query.isBlank()) {
            options
        } else {
            options.filter { option ->
                option.displayName.contains(query, ignoreCase = true) ||
                    option.type.contains(query, ignoreCase = true) ||
                    option.category.contains(query, ignoreCase = true)
            }
        }
    }

    val grouped = remember(matches) { matches.groupBy { it.category }.toSortedMap() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (matches.isEmpty()) {
                    Text(
                        text = "Nothing matches \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    return@Column
                }

                LazyColumn(modifier = Modifier.heightIn(max = 400.dp).padding(top = 8.dp)) {
                    grouped.forEach { (category, inCategory) ->
                        item(key = "header-$category") {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(items = inCategory, key = { it.type }) { option ->
                            ComponentRow(option = option, onPick = onPick)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ComponentRow(option: ComponentDescriptor, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(option.type) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = option.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (option.warning != null) CaveatMarker()
    }
}

/**
 * "There is something to know about this one." Not a warning in itself — it is a
 * promise that the editor will explain, which is what lets the list stay
 * readable without hiding the fact that a caveat exists.
 */
@Composable
private fun CaveatMarker() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(MaterialTheme.extra.cautionContainer, CircleShape)
            .semantics { contentDescription = CAVEAT_DESCRIPTION },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.extra.onCautionContainer,
            textAlign = TextAlign.Center,
        )
    }
}

/** Read by the accessibility layer and by the instrumented test. */
internal const val CAVEAT_DESCRIPTION = "Has a caveat"
