package app.phueber.trigly.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
 * which is why presets get a grid of their own instead of a longer chip row.
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
 *
 * **The grid does not size itself against a fixed number of presets.** It once
 * did: `LazyVerticalGrid` inside `Modifier.heightIn(max = 260.dp)`, a number
 * that happened to hold two rows of six presets and nothing more. Nine
 * presets is a third row, 260.dp is not tall enough for it, and a `Lazy` grid
 * with no visible scrollbar just clips the row that does not fit - a person
 * sees a half-finished tile and a `DONE` button that never moved, with
 * nothing on screen to say there is more below. That is what shipping Red,
 * Stone and Slate surfaced, not a fault in the three of them.
 *
 * The fix does not swap one fixed number for a bigger one, because twelve
 * presets would only hit the same wall later. It asks Compose the question
 * that actually holds at any count instead: **how much room is there
 * actually left**, given whatever the title, the chip row and the buttons
 * already used at whatever font size the phone is set to. Material 3's own
 * `AlertDialog` already answers that question - its `text` slot sits in a
 * `Column` behind `Modifier.weight(1f, fill = false)`, so it is handed
 * exactly the space left over after everything else in the dialog, already
 * bounded by the screen. The one thing missing was something *inside* that
 * slot willing to scroll within whatever room it was handed, and something
 * to make that scrolling visible. Both are added here:
 *
 * 1. The three-per-row grid is no longer `Lazy` - a plain `Column` of `Row`s
 *    built from `ColorPresets.chunked(3)`. A preset list this size (nine
 *    today, a dozen tomorrow) does not need virtualised scrolling, and a
 *    plain layout can sit inside a scrollable parent without the height
 *    `LazyVerticalGrid` would otherwise have to be told up front - which was
 *    the 260.dp number's whole reason for existing.
 * 2. The chip row and the grid are wrapped in one
 *    `Modifier.verticalScroll(rememberScrollState())` `Column`, so the
 *    dialog's already-correct bound decides how much shows, not a guess.
 * 3. A small down arrow is drawn under the grid whenever
 *    `ScrollState.canScrollForward` is true - true exactly when, and only
 *    when, a preset is still hidden below, regardless of how many presets
 *    there are. It disappears once everything has been scrolled into view.
 *
 * Checked at nine presets and at a padded-out twelve, on a short-screen
 * emulator profile and with the system font size turned up: the arrow
 * appears exactly when a row is cut off in each case and the last row is
 * always fully reachable by scrolling, never clipped.
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
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.verticalScroll(scrollState)) {
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

                Column(modifier = Modifier.padding(top = 12.dp)) {
                    ColorPresets.chunked(3).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            row.forEach { preset ->
                                PresetSwatch(
                                    preset = preset,
                                    selected = current is ColorSchemeChoice.Preset && current.id == preset.id,
                                    onClick = { onPick(ColorSchemeChoice.Preset(preset.id)) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // The last row of an item count that is not a multiple of three -
                            // twelve is, nine is, but this must not assume either - is padded
                            // with empty weighted space so its tiles stay the same size as
                            // every full row's, rather than stretching to fill the gap.
                            repeat(3 - row.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                if (scrollState.canScrollForward) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
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
 * uses: these nine are mutually exclusive, so a screen reader should say
 * "selected, 1 of 9" rather than announcing nine independent switches.
 *
 * [modifier] carries the incoming `RowScope.weight(1f)` from
 * [ColorSchemePickerDialog]'s own grid row, so every tile in a row - a full
 * one or the last, padded-out one - takes the same width. Named first among
 * the optional parameters, not because any parameter after it has a default,
 * but because that is where a `Modifier` belongs regardless.
 */
@Composable
private fun PresetSwatch(preset: ColorPreset, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = preset.light.primary,
        contentColor = preset.light.onPrimary,
        border = BorderStroke(
            if (selected) 4.dp else 2.dp,
            MaterialTheme.colorScheme.outline,
        ),
        shape = BlockShape,
        modifier = modifier
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
