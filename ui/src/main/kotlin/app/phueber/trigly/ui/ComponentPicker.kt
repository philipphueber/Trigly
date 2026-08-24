package app.phueber.trigly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The list is names and a marker. It used to print each component's full warning
 * under its name, on the reasoning that a caveat is most useful *before* the
 * choice is made — but two thirds of the triggers carry one, so the list became a
 * wall of prose in which no single item could be read. A caveat is now the
 * one-glyph `CaveatBadge`, and tapping it opens the sentence in place under that
 * row rather than picking the component; the badge is consulted by the same
 * gesture in the editor, so the caveat has one behaviour everywhere.
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
        title = {
            Text(text = title.uppercase(), style = MaterialTheme.typography.titleMedium)
        },
        confirmButton = {
            BlockTextButton("Cancel", onClick = onDismiss)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("SEARCH", style = MaterialTheme.typography.labelMedium) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (matches.isEmpty()) {
                    Text(
                        text = "Nothing matches \"$query\".",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    return@Column
                }

                LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 12.dp)) {
                    grouped.forEach { (category, inCategory) ->
                        item(key = "header-$category") {
                            // A solid bar, so the categories cut the list into
                            // blocks instead of merely labelling runs of it.
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = category.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(
                                        horizontal = 12.dp,
                                        vertical = 6.dp,
                                    ),
                                )
                            }
                        }
                        items(items = inCategory, key = { it.type }) { option ->
                            ComponentRow(option = option, onPick = onPick)
                            BlockDivider()
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ComponentRow(option: ComponentDescriptor, onPick: (String) -> Unit) {
    // Keyed by type, so scrolling a caveat out of the lazy list and back does not
    // silently close it — and so two components' caveats never share one flag.
    // Closed by default: the whole point of the badge is that the sentence stays
    // out of the list until it is asked for.
    var caveatShown by rememberSaveable(option.type) { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(option.type) }
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = option.displayName.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            // The badge's tap is consumed by its own toggle, so opening the
            // caveat does not also pick the component out from under the reader.
            if (option.warning != null) {
                CaveatBadge(shown = caveatShown, onToggle = { caveatShown = !caveatShown })
            }
        }

        // Revealed in place, below the name it belongs to, and only when opened.
        option.warning?.takeIf { caveatShown }?.let { warning ->
            Surface(
                color = MaterialTheme.extra.cautionContainer,
                contentColor = MaterialTheme.extra.onCautionContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}
