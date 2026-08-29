package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The used-components notices: the app's own name, version and licence, a
 * link to Trigly's own repository, every project it ships code from with a
 * link to that project's own page, and a link to the licence text they
 * share.
 *
 * Reached from a row on [SettingsScreen], which is why its own back target is
 * [Screen.Settings] and not the rule list — see [Screen.Attribution].
 *
 * Stateless, the same reasoning [SettingsScreen] gives for itself: nothing on
 * this screen changes while it is open, so there is no ViewModel and the
 * instrumented test can drive it with plain values. [projects], [licenseUrl]
 * and [repositoryUrl] are parameters rather than reads of
 * [shippedDependencies] and a hardcoded string here, so a dependency bump
 * cannot break this screen's own test — see `AttributionHost`, in
 * `MainActivity.kt`, for where the real values come from:
 * `shippedDependencies.groupIntoProjects()` and the two fixed URLs.
 *
 * [onOpenUrl] is one callback for every link this screen offers, not one per
 * link kind: a project's row, the licence row and the repository row all do
 * the same thing, open a URL in a browser, and the host is what knows how
 * (see `MainActivity.openUrl`). Nothing on this screen needs to tell those
 * three apart.
 *
 * [onCheckForUpdates] is the one exception to "stateless": pressing the
 * button below the version holds a `checking`/result pair in local
 * `remember`ed state, the same shape `TextPatternField`'s own `testing` flag
 * uses for its "Test" button. That is not a ViewModel, because there is
 * nothing here to survive a configuration change for: a stale "checking…" on
 * rotation is a re-press away from correct, and the result is not data this
 * app keeps. See `UpdateCheck.kt` for why a button press is the only thing
 * that ever calls this, and `AttributionHost`, in `MainActivity.kt`, for
 * where the real [onCheckForUpdates] comes from: `checkForUpdate`.
 *
 * A `Column` with `verticalScroll`, the same shape `PatternTester` uses for
 * its own scrollable prose, and not a `LazyColumn`: a dozen static rows do
 * not need lazy layout.
 */
@Composable
fun AttributionScreen(
    appVersion: String,
    projects: List<AttributionProject>,
    licenseUrl: String,
    repositoryUrl: String,
    onOpenUrl: (String) -> Unit,
    onCheckForUpdates: suspend () -> UpdateCheckResult,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var checking by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        BlockHeader(
            title = stringResource(R.string.attribution_title),
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BlockCard {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.attribution_version, appVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = stringResource(R.string.attribution_license),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    BlockTextButton(
                        text = stringResource(R.string.attribution_check_for_updates),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        checking = true
                        scope.launch {
                            updateCheckResult = onCheckForUpdates()
                            checking = false
                        }
                    }
                    val resultText = if (checking) {
                        stringResource(R.string.attribution_update_checking)
                    } else {
                        when (val result = updateCheckResult) {
                            null -> null
                            is UpdateCheckResult.UpToDate -> stringResource(R.string.attribution_up_to_date)
                            is UpdateCheckResult.UpdateAvailable ->
                                stringResource(R.string.attribution_update_available, result.latestVersion)
                            is UpdateCheckResult.CheckFailed ->
                                stringResource(R.string.attribution_update_check_failed, result.reason)
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

            // Trigly is Apache 2.0 itself, the same as every project below;
            // its own row rather than a line inside the card above, so it is
            // as tappable as every other link on this screen.
            SettingsRow(
                title = stringResource(R.string.attribution_repository_title),
                onClick = { onOpenUrl(repositoryUrl) },
            )

            BlockCard {
                Column {
                    Text(
                        text = stringResource(R.string.attribution_dependencies_heading).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    BlockDivider()
                    projects.forEachIndexed { index, project ->
                        // enabled = false rather than omitting the click
                        // handler when a project has no URL: groupIntoProjects
                        // can still return one, from the soft-degrade path in
                        // its own KDoc, and a disabled row says so instead of
                        // silently doing nothing on tap.
                        Surface(
                            onClick = { project.url?.let(onOpenUrl) },
                            enabled = project.url != null,
                            color = Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = project.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = pluralStringResource(
                                            R.plurals.attribution_artifact_count,
                                            project.artifactCount,
                                            project.artifactCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = project.license,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (index != projects.lastIndex) BlockDivider()
                    }
                }
            }

            // One licence row for every project above, not one per project:
            // they are all Apache 2.0, licensee's own allow-list in
            // ui/build.gradle.kts enforces that, so a copy each would be
            // several identical links. See Attribution.kt.
            SettingsRow(
                title = stringResource(R.string.attribution_license_link_title),
                onClick = { onOpenUrl(licenseUrl) },
            )
        }
    }
}
