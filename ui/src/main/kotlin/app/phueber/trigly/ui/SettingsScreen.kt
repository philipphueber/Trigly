package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Reached from `RulesScreen`'s overflow beside "Saved values". See
 * [Screen.Settings] for why it lives there.
 *
 * Stateless, the same reasoning [RulesScreen] and [SavedValuesScreen] give for
 * themselves: it takes the current setting and reports what someone did, so
 * the instrumented test can drive it with a plain boolean and no ViewModel or
 * `BackupSettings` behind it.
 *
 * The backup warning is built into [BackupSettingsCard] rather than laid out
 * here as its own block, so it reads as part of that setting and not as a
 * caution about whatever row happens to sit under it. See that composable's
 * KDoc for the shape and for why it is foldable, and it explains there why
 * the warning is offered every time the card is composed, on or off, rather
 * than only when the switch is on: someone who leaves the default alone
 * should still learn what that default does, and someone who has already
 * turned it off should still see why. A warning that only shows for the
 * choice most people will not make is not read by the people who most need
 * the other half of it.
 *
 * [onAttribution] opens [AttributionScreen], the app's second row and its
 * first that is not a switch. See [SettingsRow].
 *
 * [appVersion] and [onCheckForUpdates] are this screen's last block and not
 * one of its rows, because a version is an identity line and not a setting:
 * it answers "what am I running", and no tap on it changes anything. Putting
 * it among the controls would make a reader scanning for something to change
 * rule it out first, and giving it a row's shape would promise a tap that
 * does nothing. Below the rows it is where a person already looks for it,
 * and where it stops competing with them. See [AppVersionCard] for why the
 * update check moved here with it, and for the local state that check keeps.
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
    appVersion: String,
    onCheckForUpdates: suspend () -> UpdateCheckResult,
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
            // Scrolls, the same shape [AttributionScreen] uses: with the
            // version block added there are four blocks here, and the backup
            // caution folds out above them. A short phone in landscape can
            // run out of room, and a version pushed off the bottom edge
            // cannot be read at all.
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackupSettingsCard(
                cloudBackupEnabled = cloudBackupEnabled,
                onCloudBackupEnabledChange = onCloudBackupEnabledChange,
            )

            SettingsRow(
                title = stringResource(R.string.settings_colorscheme_title),
                onClick = { showColorSchemePicker = true },
                trailing = { ColorSchemeValueBadge(colorSchemeChoice) },
            )

            SettingsRow(
                title = stringResource(R.string.settings_attribution_title),
                onClick = onAttribution,
            )

            AppVersionCard(
                appVersion = appVersion,
                onCheckForUpdates = onCheckForUpdates,
            )
        }
    }

    if (showColorSchemePicker) {
        ColorSchemePickerDialog(
            current = colorSchemeChoice,
            // Picking a choice closes the dialog as well as reporting it -
            // this screen owns showColorSchemePicker, so nothing else can
            // close it, and a single-choice picker that stayed open after
            // the choice was made would look like the tap had done nothing.
            onPick = { choice ->
                onColorSchemeChoiceChange(choice)
                showColorSchemePicker = false
            },
            onDismiss = { showColorSchemePicker = false },
        )
    }
}

/**
 * The backup switch and the caution that explains it, read as one setting
 * instead of a switch with an unrelated-looking warning stacked below it.
 *
 * [SettingsRow] does not fit this: it lays out one label and one trailing
 * slot in a plain [BlockCard], and its two other callers - the colour scheme
 * row and the attribution row - need exactly that and nothing more. This row
 * needs a second control next to the switch and content that folds out
 * beneath the row, so stretching [SettingsRow] to carry both shapes would
 * complicate the plain one for callers that never asked for a fold. Instead
 * this borrows the shape [ComponentBlock] already uses for a component's own
 * warning: [CaveatBadge] sits in the header next to the row's main control,
 * and the caution [Surface] it reveals sits beneath a [BlockDivider], inside
 * the same [BlockCard] - one container, so the warning reads as this
 * setting's own caveat rather than as a second, separate block.
 *
 * The warning is offered every time this card is composed, on or off, rather
 * than only when the switch is on. Someone deciding whether to turn backup on
 * is exactly who needs to read what it shares, so gating the caveat on the
 * switch already being on would hide it from the person who most needs it.
 * Collapsing it by default only changes how much of the screen it spends,
 * never whether it can be read.
 *
 * `warningShown` lives here rather than being hoisted to [SettingsScreen],
 * the same reasoning that screen gives for keeping `showColorSchemePicker` to
 * itself: nothing outside this card needs to know whether the caution is
 * open.
 */
@Composable
private fun BackupSettingsCard(
    cloudBackupEnabled: Boolean,
    onCloudBackupEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var warningShown by remember { mutableStateOf(false) }

    BlockCard(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_backup_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                // Reserves its full 48dp here rather than overhanging it, unlike
                // every other [CaveatBadge] caller. [BlockExpandButton]'s KDoc
                // in Blocks.kt names the rule this follows: two overhanging
                // targets a few dp apart claim the same pixels, and whichever
                // is drawn later wins the tap. Overhanging is still the right
                // call for the badge's other callers - a 28-item picker row and
                // a block header - because reserving there would grow every
                // row that carries a caveat. Neither reason applies here: this
                // row has exactly one neighbour, [BlockToggle], and the two
                // together are worse than [BlockExpandButton]'s case, because
                // the neighbour they used to swallow the tap into flips a real
                // setting rather than folding a block shut.
                //
                // Measured on a device before this fix: the badge's overhanging
                // 48dp target and the toggle's own 48dp target had centres only
                // 34.3dp apart, so they overlapped by 13.7dp. Two 48dp targets
                // need at least 48dp between centres to avoid that. Reserving
                // the badge's box makes its centre exactly 24dp from its own
                // trailing edge, and the explicit 8dp gap below adds margin
                // past that minimum rather than sitting exactly on it, so
                // per-device pixel rounding cannot reopen the overlap.
                CaveatBadge(
                    shown = warningShown,
                    onToggle = { warningShown = !warningShown },
                    modifier = Modifier.size(48.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                BlockToggle(
                    checked = cloudBackupEnabled,
                    onCheckedChange = onCloudBackupEnabledChange,
                )
            }

            // Amber, the same convention `BatteryOptimizationNotice` and
            // `LastFaultCell`'s amber rows use: "worth knowing", not a fault in
            // front of the reader right now. This is information about a
            // choice, on either side of it, not an accusation. Fixed amber in
            // every colour scheme by design - see
            // `MaterialTheme.extra.cautionContainer` - so the warning reads
            // the same whichever preset is chosen.
            if (warningShown) {
                BlockDivider()
                Surface(
                    color = MaterialTheme.extra.cautionContainer,
                    contentColor = MaterialTheme.extra.onCautionContainer,
                    modifier = Modifier.fillMaxWidth(),
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
            }
        }
    }
}

/**
 * The app's version, and the one button that finds out whether a newer one
 * exists.
 *
 * These two used to sit on [AttributionScreen], the "Used components" screen
 * one row above. They moved together on purpose. A version is what someone
 * reads to answer "what am I running", and that question is asked in
 * settings, not in a credit list for other people's projects. The update
 * check had to come with it: a version number on one screen, and the only
 * way to learn a newer one exists on a different screen, is worse than
 * either arrangement, because the person who just read the number is exactly
 * the person who wants the check.
 *
 * Not a [SettingsRow], although it sits under three of them. Every row on
 * this screen changes something when it is tapped, and this block changes
 * nothing: the version is a fact about the install. Giving it a row's shape
 * would promise a tap that does nothing, so it keeps the plain [BlockCard]
 * shape instead, with the button as the only target in it.
 *
 * `checking` and the last result live here in local `remember`ed state, the
 * same shape `TextPatternField`'s own `testing` flag uses for its "Test"
 * button, and the same reasoning [SettingsScreen] gives for keeping
 * `showColorSchemePicker` to itself: nothing outside this card needs to know,
 * and there is nothing worth surviving a configuration change, since a stale
 * "checking…" after a rotation is one press away from correct and the result
 * is not data this app keeps. See `UpdateCheck.kt` for why a button press is
 * the only thing that ever calls this, and `SettingsHost`, in
 * `MainActivity.kt`, for where the real [onCheckForUpdates] comes from:
 * `checkForUpdate`.
 */
@Composable
private fun AppVersionCard(
    appVersion: String,
    onCheckForUpdates: suspend () -> UpdateCheckResult,
    modifier: Modifier = Modifier,
) {
    var checking by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val scope = rememberCoroutineScope()

    BlockCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_version, appVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
            BlockTextButton(
                text = stringResource(R.string.settings_check_for_updates),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                checking = true
                scope.launch {
                    updateCheckResult = onCheckForUpdates()
                    checking = false
                }
            }
            val resultText = if (checking) {
                stringResource(R.string.settings_update_checking)
            } else {
                when (val result = updateCheckResult) {
                    null -> null
                    is UpdateCheckResult.UpToDate -> stringResource(R.string.settings_up_to_date)
                    is UpdateCheckResult.UpdateAvailable ->
                        stringResource(R.string.settings_update_available, result.latestVersion)
                    is UpdateCheckResult.CheckFailed ->
                        stringResource(R.string.settings_update_check_failed, result.reason)
                }
            }
            resultText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
