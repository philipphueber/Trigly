package app.phueber.trigly.ui

import android.content.Context
import android.media.RingtoneManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One sound the picker can offer. [uri] is stored in the rule; [title] is what
 * the device calls it.
 */
data class DeviceSound(val uri: String, val title: String)

/** The sounds the picker offers. Empty until the first read finishes. */
val LocalDeviceSounds = staticCompositionLocalOf { emptyList<DeviceSound>() }

/**
 * Every alarm, notification and ringtone this device knows about.
 *
 * All three types together and de-duplicated, rather than a picker per type: the
 * alert action already has a separate "Tone" choice for *which default* to fall
 * back on, and what this field is for is picking a specific sound. Someone
 * choosing one by ear does not care which category the system filed it under.
 *
 * `RingtoneManager` needs a cursor per type and each row's URI is built from the
 * cursor position, which is the part that is easy to get wrong — the URI is not a
 * column. Read off the main thread because it touches the media store, and read
 * once per process for the same reason the app list is.
 *
 * A device sound the user cannot see is a real possibility rather than an edge
 * case: sounds added to shared storage may need media permissions this app does
 * not ask for, so the list is what is *visible*, not what exists. That is why the
 * field still renders a stored URI it cannot find a title for.
 */
suspend fun loadDeviceSounds(context: Context): List<DeviceSound> =
    withContext(Dispatchers.IO) {
        val types = listOf(
            RingtoneManager.TYPE_ALARM,
            RingtoneManager.TYPE_NOTIFICATION,
            RingtoneManager.TYPE_RINGTONE,
        )

        types.flatMap { type ->
            runCatching {
                val manager = RingtoneManager(context).apply { setType(type) }
                val cursor = manager.cursor
                buildList {
                    // Indexing rather than iterating the cursor: getRingtoneUri
                    // takes a position, and there is no column holding it.
                    for (position in 0 until cursor.count) {
                        val uri = manager.getRingtoneUri(position) ?: continue
                        val title = cursor.let {
                            it.moveToPosition(position)
                            it.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                        }
                        add(DeviceSound(uri.toString(), title))
                    }
                }
            }.getOrDefault(emptyList())
        }
            // The same file is often filed under more than one type.
            .distinctBy { it.uri }
            .sortedBy { it.title.lowercase() }
    }

@Composable
fun rememberDeviceSounds(): State<List<DeviceSound>> {
    val context = LocalContext.current
    val sounds = remember { mutableStateOf(emptyList<DeviceSound>()) }
    LaunchedEffect(context) { sounds.value = loadDeviceSounds(context) }
    return sounds
}

/** Title for a stored URI, falling back to the URI so an unknown sound still shows. */
fun List<DeviceSound>.titleFor(uri: String): String =
    firstOrNull { it.uri == uri }?.title ?: uri

/**
 * What `ConfigField.SoundUri` renders as.
 *
 * **No typed escape hatch, unlike the app and Bluetooth pickers.** A URI is not
 * something a person composes — offering "use what you typed" would only invite
 * someone to invent one that cannot resolve. A rule carrying a URI this device
 * does not know, from an import or a deleted file, still renders: the box shows
 * the raw URI, so it can be seen and replaced rather than silently ignored.
 */
@Composable
fun SoundUriField(
    label: String,
    uri: String?,
    blankMeaning: String?,
    onPick: (String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val sounds = LocalDeviceSounds.current

    PickerValueBox(
        label = label,
        primary = when {
            uri != null -> sounds.titleFor(uri)
            blankMeaning != null -> blankMeaning
            else -> "Choose a sound"
        },
        // Only when it is not already the primary text, which it is for a sound
        // the device cannot name.
        secondary = uri?.takeIf { sounds.titleFor(it) != it },
        onClick = { picking = true },
    )

    if (picking) {
        ValuePickerDialog(
            title = label.removeSuffix(" *"),
            searchLabel = "SEARCH SOUNDS",
            options = sounds.map { PickerOption(it.uri, it.title) },
            clearLabel = blankMeaning,
            placeholder = "No sounds this app can see. Sounds you added yourself " +
                "may need media access Trigly does not ask for.",
            onPick = { picked ->
                picking = false
                onPick(picked)
            },
            onDismiss = { picking = false },
        )
    }
}
