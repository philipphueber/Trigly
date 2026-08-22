package app.phueber.trigly.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * Picks an installed app, in place of typing `com.google.android.dialer` from
 * memory.
 *
 * Which is what [app.phueber.trigly.core.ConfigField.AppPackage] existed for from
 * the start: it stores and validates exactly like a text field, and is a separate
 * kind purely so the editor can offer this.
 *
 * Two things keep it honest rather than merely convenient:
 *
 *  · **Manual entry survives.** The list is launcher apps only (see
 *    [loadInstalledApps] for why), so a service with no launcher icon — a
 *    plausible target for the notification watchdog — would otherwise become
 *    unreachable. Type a package name and the search field offers it directly.
 *  · **Optional stays optional.** Several components read an absent package as
 *    "match anything", so a field whose blankness means something gets a row that
 *    clears it. Without that, opening the picker would be a one-way door.
 */
@Composable
fun AppPickerDialog(
    title: String,
    /** Shown as the first row when the field is optional; null hides it. */
    clearLabel: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val apps = LocalInstalledApps.current
    var query by remember { mutableStateOf("") }

    val matches = remember(query, apps) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    // Offer what was typed when it looks like a package and is not already in the
    // list — the escape hatch for apps with no launcher icon.
    val typed = query.trim()
    val offerTyped = looksLikeAPackageName(typed) && matches.none { it.packageName == typed }

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
                        Text("SEARCH OR TYPE A PACKAGE", style = MaterialTheme.typography.labelMedium)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(top = 12.dp)) {
                    if (clearLabel != null && query.isBlank()) {
                        item(key = "clear") {
                            PickerRow(
                                primary = clearLabel,
                                secondary = null,
                                onClick = { onPick(null) },
                            )
                            BlockDivider()
                        }
                    }

                    if (offerTyped) {
                        item(key = "typed") {
                            PickerRow(
                                primary = "Use \"$typed\"",
                                secondary = "not in the list — an app with no launcher icon",
                                onClick = { onPick(typed) },
                            )
                            BlockDivider()
                        }
                    }

                    if (apps.isEmpty() && !offerTyped) {
                        item(key = "loading") {
                            Text(
                                text = "Reading installed apps…",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }

                    items(items = matches, key = { it.packageName }) { app ->
                        PickerRow(
                            primary = app.label,
                            secondary = app.packageName,
                            icon = { AppIcon(app.packageName) },
                            onClick = { onPick(app.packageName) },
                        )
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
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
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
            // Package names stay lowercase: they are identifiers, not labels, and
            // uppercasing one makes it wrong rather than merely loud.
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

/** Square, like everything else. A missing icon leaves a hole rather than a guess. */
@Composable
fun AppIcon(packageName: String, size: Int = 32) {
    val icon by rememberAppIcon(packageName)
    val bitmap = icon
    if (bitmap == null) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer)
        )
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(size.dp),
        )
    }
}

/**
 * The field itself: what the app-package config field renders as.
 *
 * Shows the app's *label* with its package underneath, because the label is what
 * someone recognises and the package is what the rule actually stores — hiding
 * the stored value would make a mis-picked app impossible to spot.
 */
@Composable
fun AppPackageField(
    label: String,
    packageName: String?,
    blankMeaning: String?,
    onPick: (String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val apps = LocalInstalledApps.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            onClick = { picking = true },
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (packageName != null) AppIcon(packageName, size = 28)
                Column(
                    modifier = Modifier.padding(start = if (packageName == null) 0.dp else 12.dp)
                ) {
                    Text(
                        text = when {
                            packageName != null -> apps.labelFor(packageName).uppercase()
                            blankMeaning != null -> blankMeaning.uppercase()
                            else -> "CHOOSE AN APP"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    packageName?.let {
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

    if (picking) {
        AppPickerDialog(
            title = label.removeSuffix(" *"),
            // Only offered when blankness is a real setting for this field.
            clearLabel = blankMeaning,
            onPick = { picked ->
                picking = false
                onPick(picked)
            },
            onDismiss = { picking = false },
        )
    }
}
