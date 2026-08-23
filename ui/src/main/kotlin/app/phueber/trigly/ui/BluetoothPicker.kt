package app.phueber.trigly.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A paired device. [address] is stored in the rule; [name] is what the user sees. */
data class PairedDevice(val address: String, val name: String)

/** The devices the picker offers. Empty when unpaired *or* when not allowed to look. */
val LocalPairedDevices = staticCompositionLocalOf { emptyList<PairedDevice>() }

/**
 * The devices this phone is paired with.
 *
 * **Paired, which is not the same as "could ever connect".** A device paired to a
 * different phone, or one the user has since forgotten, will still fire
 * `ACTION_ACL_CONNECTED` — so this list is a convenience, not the set of valid
 * answers, and the field keeps a way to type an address.
 *
 * Returns empty rather than throwing when `BLUETOOTH_CONNECT` is missing. From
 * API 31 both `getBondedDevices` and reading a device's name need it, and the
 * getters throw rather than returning null. The trigger already declares the
 * permission as a `ComponentRequirement`, so the editor explains it — this only
 * has to avoid crashing the picker in the meantime, and to make "not allowed to
 * look" distinguishable from "nothing paired", which is what
 * [canReadPairedDevices] is for.
 */
suspend fun loadPairedDevices(context: Context): List<PairedDevice> =
    withContext(Dispatchers.IO) {
        if (!canReadPairedDevices(context)) return@withContext emptyList()

        runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return@runCatching emptyList()

            adapter.bondedDevices.orEmpty().map { device ->
                PairedDevice(
                    address = device.address,
                    // A device can report no name; its address is the honest label.
                    name = device.name?.takeIf { it.isNotBlank() } ?: device.address,
                )
            }
        }
            .getOrDefault(emptyList())
            .distinctBy { it.address }
            .sortedBy { it.name.lowercase() }
    }

/**
 * Whether the pairing list can be read at all, as opposed to being empty.
 *
 * The difference matters to the person looking at an empty picker: "you have no
 * paired devices" and "Trigly may not see your paired devices" call for different
 * actions, and guessing wrong sends them to the wrong screen.
 */
fun canReadPairedDevices(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun rememberPairedDevices(): State<List<PairedDevice>> {
    val context = LocalContext.current
    val devices = remember { mutableStateOf(emptyList<PairedDevice>()) }
    LaunchedEffect(context) { devices.value = loadPairedDevices(context) }
    return devices
}

/** Name for a stored address, falling back to the address itself. */
fun List<PairedDevice>.nameFor(address: String): String =
    firstOrNull { it.address == address }?.name ?: address

/**
 * Whether [text] could plausibly be a Bluetooth address.
 *
 * Gates the "use what you typed" row, and is deliberately loose in the same way
 * [looksLikeAPackageName] is: the trigger accepts any string and simply never
 * matches a wrong one, so refusing a valid-but-oddly-typed address is worse than
 * offering one that turns out not to exist. It has to reject anything someone
 * would type to *search*, though, because one field serves both purposes — hence
 * requiring the colon-separated hex shape rather than merely "looks technical".
 */
fun looksLikeABluetoothAddress(text: String): Boolean {
    val parts = text.trim().uppercase().split(':')
    return parts.size == 6 &&
        parts.all { part -> part.length == 2 && part.all { it in "0123456789ABCDEF" } }
}

/**
 * What `ConfigField.BluetoothAddress` renders as.
 *
 * Shows the device's name with the address underneath, for the same reason the
 * app field shows the package: the address is what the rule stores, and a rule
 * imported from another phone names a device this one has never seen. Without the
 * raw value on screen that rule would look empty.
 */
@Composable
fun BluetoothAddressField(
    label: String,
    address: String?,
    blankMeaning: String?,
    onPick: (String?) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val devices = LocalPairedDevices.current
    val context = LocalContext.current

    PickerValueBox(
        label = label,
        primary = when {
            address != null -> devices.nameFor(address)
            blankMeaning != null -> blankMeaning
            else -> "Choose a device"
        },
        secondary = address?.takeIf { devices.nameFor(it) != it },
        onClick = { picking = true },
    )

    if (picking) {
        ValuePickerDialog(
            title = label.removeSuffix(" *"),
            searchLabel = "SEARCH OR TYPE AN ADDRESS",
            options = devices.map { PickerOption(it.address, it.name, it.address) },
            clearLabel = blankMeaning,
            placeholder = if (canReadPairedDevices(context)) {
                "No paired devices. Pair one in system settings, or type its address."
            } else {
                "Trigly cannot see your paired devices without the Bluetooth " +
                    "permission. Grant it on the rule, or type an address."
            },
            typedOption = { typed ->
                if (looksLikeABluetoothAddress(typed)) {
                    PickerOption(
                        value = typed.uppercase(),
                        primary = "Use \"${typed.uppercase()}\"",
                        secondary = "not paired with this phone",
                    )
                } else {
                    null
                }
            },
            onPick = { picked ->
                picking = false
                onPick(picked)
            },
            onDismiss = { picking = false },
        )
    }
}
