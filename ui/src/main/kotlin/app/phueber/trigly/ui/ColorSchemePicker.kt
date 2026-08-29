package app.phueber.trigly.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Reached from [SettingsRow]'s colour scheme row.
 *
 * Two chips for the choices that are not a hue - Default, and the system
 * palette where the phone can honour it - then a grid of [ColorPresets],
 * each swatch filled in that preset's own `primary` so the choice is shown
 * rather than described. Six already crowds [BlockToggleChip]'s pill shape,
 * which is why presets get a grid of their own instead of a longer chip row;
 * the grid is what a seventh and eighth preset grow into.
 *
 * "Follow the system" is left off the row entirely below API 31, rather than
 * shown and disabled: [effectiveChoice] would silently fall back to Default
 * the moment it was picked, and a choice that cannot be honoured is not
 * offered at all - see this file's own reasoning in `PresetSchemes.kt`.
 *
 * There is no notice about the launcher icon. Picking a preset does switch the
 * icon, through `LauncherIconSwitcher.kt`, and that can briefly leave the home
 * screen or need a launcher restart on some launchers. That was said here in
 * an amber block and has been removed: it is a one time surprise about a
 * choice a person made on purpose, and it spent the top of a dialog whose job
 * is to show colours. The behaviour is unchanged and is documented in
 * `LauncherIconSwitcher.kt`, where somebody debugging a missing icon will
 * look.
 */
@Composable
fun ColorSchemePickerDialog(
    current: ColorSchemeChoice,
    onPick: (ColorSchemeChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.settings_colorscheme_dialog_title).uppercase(),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        confirmButton = {
            BlockTextButton(stringResource(R.string.settings_colorscheme_done), onClick = onDismiss)
        },
        text = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BlockToggleChip(
                        text = stringResource(R.string.settings_colorscheme_default),
                        selected = current == ColorSchemeChoice.Default,
                        onClick = { onPick(ColorSchemeChoice.Default) },
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BlockToggleChip(
                            text = stringResource(R.string.settings_colorscheme_system),
                            selected = current == ColorSchemeChoice.System,
                            onClick = { onPick(ColorSchemeChoice.System) },
                        )
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 260.dp).padding(top = 12.dp),
                ) {
                    items(ColorPresets, key = { it.id }) { preset ->
                        PresetSwatch(
                            preset = preset,
                            selected = current is ColorSchemeChoice.Preset && current.id == preset.id,
                            onClick = { onPick(ColorSchemeChoice.Preset(preset.id)) },
                        )
                    }
                }
            }
        },
    )
}

/**
 * One preset, filled in its own `primary` rather than a neutral swatch with a
 * label beside it: the colour *is* the choice. The name is drawn on top in
 * that preset's own `onPrimary`, which is guaranteed to clear WCAG AA there
 * by construction - see `PresetSchemes.kt`.
 *
 * `selectable` with `Role.RadioButton`, the same convention [BlockToggleChip]
 * uses: these six are mutually exclusive, so a screen reader should say
 * "selected, 1 of 6" rather than announcing six independent switches.
 */
@Composable
private fun PresetSwatch(preset: ColorPreset, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = preset.light.primary,
        contentColor = preset.light.onPrimary,
        border = BorderStroke(
            if (selected) 4.dp else 2.dp,
            MaterialTheme.colorScheme.outline,
        ),
        shape = BlockShape,
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .hardShadow(BlockShape, visible = selected)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null)
            } else {
                Text(text = preset.displayName.uppercase(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * What [SettingsRow]'s trailing edge shows for the colour scheme: the mode's
 * own word for Default and System, or a small swatch of the preset's
 * `primary` - the same "show, don't describe" choice [PresetSwatch] makes,
 * just too small here for the name to fit legibly.
 *
 * Falls back to the Default word for a [ColorSchemeChoice.Preset] that no
 * longer names anything in [ColorPresets], the same case
 * [ColorSchemeChoice.fromStoredName] already answers on read - this only
 * has to agree with that answer, not repeat its reasoning.
 */
@Composable
fun ColorSchemeValueBadge(choice: ColorSchemeChoice) {
    val preset = (choice as? ColorSchemeChoice.Preset)?.let { picked ->
        ColorPresets.find { it.id == picked.id }
    }
    if (choice is ColorSchemeChoice.Preset && preset != null) {
        Surface(
            color = preset.light.primary,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline),
            shape = BlockShape,
            modifier = Modifier.size(28.dp),
        ) {}
    } else {
        val word = if (choice == ColorSchemeChoice.System) {
            R.string.settings_colorscheme_system
        } else {
            R.string.settings_colorscheme_default
        }
        Text(text = stringResource(word).uppercase(), style = MaterialTheme.typography.labelMedium)
    }
}
