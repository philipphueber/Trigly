package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ComponentDescriptor

/**
 * Picks one component type from the 28 triggers or 18 actions.
 *
 * Grouped by category and searchable, because a flat alphabetical list of 28
 * items is unusable and because people arrive knowing the domain ("something to
 * do with battery") rather than the name. Search matches the display name and
 * the raw type string, so someone who has read the docs can type
 * `battery_level` directly.
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
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        TextButton(onClick = { onPick(option.type) }, modifier = Modifier.fillMaxWidth()) {
            Text(text = option.displayName, modifier = Modifier.fillMaxWidth())
        }

        // Shown before the choice is made, not after — the point is to warn
        // someone off a trigger that will drain their battery or silently never
        // fire, while they can still pick something else.
        option.warning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            )
        }
    }
}
