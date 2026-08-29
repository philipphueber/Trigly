package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Reached from `RulesScreen`'s overflow beside "Saved values". See
 * [Screen.Settings] for why it lives there.
 *
 * Stateless, the same reasoning [RulesScreen] and [SavedValuesScreen] give for
 * themselves: it takes the current setting and reports what someone did, so
 * the instrumented test can drive it with a plain boolean and no ViewModel or
 * `BackupSettings` behind it.
 *
 * The warning is drawn every time this screen opens, on or off, rather than
 * only when the switch is on. Someone who leaves the default alone should
 * still learn what that default does, and someone who has already turned it
 * off should still see why. A warning that only shows for the choice most
 * people will not make is not read by the people who most need the other
 * half of it.
 *
 * [onAttribution] opens [AttributionScreen], the app's second row and its
 * first that is not a switch — see [SettingsRow].
 *
 * [colorSchemeChoice] and [onColorSchemeChoiceChange] follow the same shape:
 * the current choice in, what someone picked out. The picker itself is
 * `ColorSchemePickerDialog`; whether its dialog is open is the one piece of
 * state this screen keeps for itself, because nothing outside it needs to know.
 */
@Composable
fun SettingsScreen(
    cloudBackupEnabled: Boolean,
    onCloudBackupEnabledChange: (Boolean) -> Unit,
    colorSchemeChoice: ColorSchemeChoice,
    onColorSchemeChoiceChange: (ColorSchemeChoice) -> Unit,
    onAttribution: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Dialog visibility only - the choice itself always lives one level up,
    // the same split ComponentPickerDialog and every other picker in this
    // app makes between "what is picked" and "is the picker open".
    var showColorSchemePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        BlockHeader(
            title = stringResource(R.string.settings_title),
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsRow(
                title = stringResource(R.string.settings_backup_title),
                trailing = {
                    BlockToggle(
                        checked = cloudBackupEnabled,
                        onCheckedChange = onCloudBackupEnabledChange,
                    )
                },
            )

            SettingsRow(
                title = stringResource(R.string.settings_colorscheme_title),
                onClick = { showColorSchemePicker = true },
                trailing = { ColorSchemeValueBadge(colorSchemeChoice) },
            )

            // Amber, the same convention `BatteryOptimizationNotice` and
            // `LastFaultCell`'s amber rows use: "worth knowing", not a fault in
            // front of the reader right now. This is information about a
            // choice, on either side of it, not an accusation.
            Surface(
                color = MaterialTheme.extra.cautionContainer,
                contentColor = MaterialTheme.extra.onCautionContainer,
                shape = BlockShape,
                modifier = Modifier.fillMaxWidth().hardShadow(BlockShape),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.settings_backup_warning_title).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = stringResource(R.string.settings_backup_warning_body),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_attribution_title),
                onClick = onAttribution,
            )
        }
    }

    if (showColorSchemePicker) {
        ColorSchemePickerDialog(
            current = colorSchemeChoice,
            onPick = onColorSchemeChoiceChange,
            onDismiss = { showColorSchemePicker = false },
        )
    }
}
