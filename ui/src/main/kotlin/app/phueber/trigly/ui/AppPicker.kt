package app.phueber.trigly.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Picks an installed app, in place of typing `com.google.android.dialer` from
 * memory. Which is what `ConfigField.AppPackage` existed for from the start: it
 * stores and validates exactly like a text field, and is a separate kind purely
 * so the editor can offer this.
 *
 * The list and the labelling are all that live here; the searching, the
 * clear-the-field row and the value box are shared with the other pickers in
 * [ValuePickerDialog] and [PickerValueBox].
 *
 * Two things keep it honest rather than merely convenient:
 *
 *  · **Manual entry survives.** The list is launcher apps only (see
 *    [loadInstalledApps] for why), so a service with no launcher icon (a
 *    plausible target for the notification watchdog) would otherwise become
 *    unreachable. Type a package name and the search field offers it directly.
 *  · **Optional stays optional.** Several components read an absent package as
 *    "match anything", so a field whose blankness means something gets a row that
 *    clears it.
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

    ValuePickerDialog(
        title = title,
        searchLabel = "SEARCH OR TYPE A PACKAGE",
        options = apps.map { PickerOption(it.packageName, it.label, it.packageName) },
        clearLabel = clearLabel,
        placeholder = "Reading installed apps…",
        typedOption = { typed ->
            // Deliberately loose: the factory validates at save time, and refusing
            // a valid-but-unusual package would be worse than offering one that
            // turns out not to be installed.
            if (looksLikeAPackageName(typed)) {
                PickerOption(
                    value = typed,
                    primary = "Use \"$typed\"",
                    secondary = "Not in the list, because it has no launcher icon",
                )
            } else {
                null
            }
        },
        leading = { AppIcon(it.value) },
        onPick = onPick,
        onDismiss = onDismiss,
    )
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
 * someone recognises and the package is what the rule actually stores. Hiding
 * the stored value would make a mis-picked app impossible to spot, and an app
 * that is not installed would render as nothing at all.
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

    PickerValueBox(
        label = label,
        primary = when {
            packageName != null -> apps.labelFor(packageName)
            blankMeaning != null -> blankMeaning
            else -> "Choose an app"
        },
        // Only shown when it says something the label doesn't already: an
        // unresolved package falls back to itself for the label too (see
        // `labelFor`), and echoing the identical string twice is worse than
        // omitting the second line. Mirrors `BluetoothAddressField`'s guard.
        secondary = packageName?.takeIf { apps.labelFor(it) != it },
        leading = packageName?.let { { AppIcon(it, size = 28) } },
        onClick = { picking = true },
    )

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
