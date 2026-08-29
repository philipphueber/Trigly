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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * The open source notices: the app's own name, version and licence, then
 * every project it ships code from and the licence text they share.
 *
 * Reached from a row on [SettingsScreen], which is why its own back target is
 * [Screen.Settings] and not the rule list — see [Screen.Attribution].
 *
 * Stateless, the same reasoning [SettingsScreen] gives for itself: nothing on
 * this screen changes while it is open, so there is no ViewModel and the
 * instrumented test can drive it with plain values. [projects] and
 * [licenseText] are parameters rather than reads of [shippedDependencies] and
 * the raw resource here, so a dependency bump cannot break this screen's own
 * test — see `AttributionHost`, in `MainActivity.kt`, for where the real
 * values come from: `shippedDependencies.groupIntoProjects()` and the bundled
 * licence file.
 *
 * A `Column` with `verticalScroll`, the same shape `PatternTester` uses for
 * its own scrollable prose, and not a `LazyColumn`: a dozen static rows plus
 * one block of licence text do not need lazy layout.
 */
@Composable
fun AttributionScreen(
    appVersion: String,
    projects: List<AttributionProject>,
    licenseText: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                }
            }

            BlockCard {
                Column {
                    Text(
                        text = stringResource(R.string.attribution_dependencies_heading).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                    BlockDivider()
                    projects.forEachIndexed { index, project ->
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
                        if (index != projects.lastIndex) BlockDivider()
                    }
                }
            }

            // One copy for every project above, not one per project: they are
            // all very probably the same licence, so a copy each would be
            // several near-identical blocks. See Attribution.kt.
            BlockCard {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.attribution_license_heading).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
