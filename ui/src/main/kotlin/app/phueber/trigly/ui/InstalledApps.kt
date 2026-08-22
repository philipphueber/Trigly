package app.phueber.trigly.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One app the user can pick.
 *
 * [label] is what the launcher shows; [packageName] is what gets stored in the
 * rule. Rules always hold the package name — a label is localised, changes when
 * the app is updated, and is not unique.
 */
data class InstalledApp(val packageName: String, val label: String)

/**
 * The apps the picker offers.
 *
 * A `staticCompositionLocal` with an empty default rather than a parameter
 * threaded through every screen: only one branch of `ConfigFieldEditor` needs
 * it, and passing a list from the activity down through two screens and a
 * component block would put it in four signatures that have no other use for it.
 * Empty is a safe default — the picker still offers manual entry — and the
 * instrumented tests provide their own list instead of depending on whatever is
 * installed on the test device.
 */
val LocalInstalledApps = staticCompositionLocalOf { emptyList<InstalledApp>() }

/**
 * Reads the launchable apps once per process, off the main thread.
 *
 * **Launchable only, and that is deliberate.** Listing *every* installed package
 * needs `QUERY_ALL_PACKAGES`, which Google treats as a restricted permission
 * requiring a declared exception — a bad trade for a convenience picker, and one
 * that would make the app harder to publish. Declaring the launcher intent in
 * `<queries>` instead (see the manifest) gets every app with an icon in the
 * launcher, which is what a person means by "an app", without a special
 * permission.
 *
 * The cost: a pure service or a system component with no launcher icon will not
 * be listed. Those can still be typed in by hand, which is why the picker keeps
 * a manual-entry path rather than treating the list as exhaustive.
 *
 * Loaded once and kept: the query plus a label lookup per app takes long enough
 * to be worth not repeating on every editor open, and an app installed while
 * Trigly is running is not worth invalidating a cache over.
 */
suspend fun loadInstalledApps(context: Context): List<InstalledApp> =
    withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        packageManager.queryIntentActivities(launchable, 0)
            .mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = activity.packageName,
                    label = resolved.loadLabel(packageManager).toString(),
                )
            }
            // An app can resolve the launcher intent more than once — a browser
            // with several entry points, a launcher with a settings activity.
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

/** Loads the app list into state, empty until the first read finishes. */
@Composable
fun rememberInstalledApps(): State<List<InstalledApp>> {
    val context = LocalContext.current
    val apps = remember { mutableStateOf(emptyList<InstalledApp>()) }
    LaunchedEffect(context) { apps.value = loadInstalledApps(context) }
    return apps
}

/**
 * An app's launcher icon, loaded lazily.
 *
 * Per row rather than with the list, because holding a bitmap for every
 * installed app would cost megabytes for rows that are mostly off screen. The
 * `LazyColumn` only composes what is visible, so this loads what is actually
 * shown.
 */
@Composable
fun rememberAppIcon(packageName: String): State<ImageBitmap?> {
    val context = LocalContext.current
    val icon = remember(packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        icon.value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = ICON_PX, height = ICON_PX)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
    return icon
}

/**
 * Fixed size rather than the drawable's intrinsic one: adaptive icons report
 * wildly different intrinsic sizes, and a row of mismatched icons looks broken.
 */
private const val ICON_PX = 96

/** Label for a stored package name, falling back to the package itself. */
fun List<InstalledApp>.labelFor(packageName: String): String =
    firstOrNull { it.packageName == packageName }?.label ?: packageName

/**
 * Whether [text] could plausibly be a package name.
 *
 * Used to decide whether to offer "use what you typed" in the picker. Cheap and
 * deliberately loose — the factory validates for real when the rule is saved,
 * and refusing to offer a valid-but-unusual package would be worse than
 * offering one that turns out not to be installed.
 */
fun looksLikeAPackageName(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.contains('.') &&
        !trimmed.contains(' ') &&
        trimmed.first().isLetter() &&
        trimmed.all { it.isLetterOrDigit() || it == '.' || it == '_' }
}
