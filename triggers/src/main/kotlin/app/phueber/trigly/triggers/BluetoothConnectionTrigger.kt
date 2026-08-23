package app.phueber.trigly.triggers

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Whether a connecting device is the one a rule asked for.
 *
 * Pure, and separate from the receiver, because this is the part with a real
 * decision in it — and because an `Intent` full of Bluetooth extras is not
 * something a JVM test can honestly fake.
 *
 * Two independent filters, either of which may have no opinion. **The name filter
 * is not a convenience; it is the only identifier that survives some devices.**
 * A Bluetooth LE accessory rotates a resolvable private address roughly every
 * quarter of an hour, so a rule pinned to an address it advertised once will
 * quietly stop matching. Bonding is what fixes that — the two ends exchange a key
 * that lets the stack resolve a rotating address back to the device it paired
 * with — which is why the editor's picker lists *paired* devices and why pairing
 * is the advice. For anything not paired, a name is the durable thing and an
 * address is not.
 *
 * The address comparison ignores case on purpose. Android reports addresses in
 * upper case and the picker stores them that way, but a rule saved before the
 * picker existed holds whatever was typed, and a MAC is hex either way.
 */
fun bluetoothDeviceMatches(
    wantedAddress: String?,
    nameFilter: TextFilter,
    address: String?,
    name: String?,
): Boolean {
    if (!wantedAddress.isNullOrEmpty() && !wantedAddress.equals(address, ignoreCase = true)) {
        return false
    }
    return nameFilter.matches(name)
}

/**
 * Fires when a Bluetooth device connects or disconnects.
 *
 * [deviceAddress] narrows it to one device and [nameFilter] to a name; either
 * being empty means "no opinion", and both empty fires for any device. See
 * [bluetoothDeviceMatches] for why a name filter exists at all.
 *
 * [onConnect] chooses which of the two broadcasts to listen for, and only that
 * one is registered — the same shape as `power_connection`, whose two broadcasts
 * are likewise already edge-shaped: receiving one *is* the event, so there is no
 * state to deduplicate.
 *
 * The [TYPE] string still says `bluetooth_connected` even though the trigger now
 * does both. It is persisted in every saved rule and in every exported file, so
 * renaming it to match would break the thing it identifies; a type string is an
 * identifier, not a description.
 *
 * The receiver is registered on collection and torn down in [awaitClose], so a
 * disabled rule leaves no receiver behind — that is the contract every [Trigger]
 * owes.
 */
class BluetoothConnectionTrigger(
    private val context: Context,
    private val deviceAddress: String?,
    private val nameFilter: TextFilter = TextFilter.Any,
    private val onConnect: Boolean = true,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                if (intent == null) return
                val device = IntentCompat.getParcelableExtra(
                    intent,
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )

                val (address, name) = identify(device)

                if (!bluetoothDeviceMatches(deviceAddress, nameFilter, address, name)) return

                trySend(
                    TriggerEvent(
                        triggerType = TYPE,
                        firedAtMillis = now(),
                        payload = buildMap {
                            address?.let { put(PAYLOAD_ADDRESS, it) }
                            name?.let { put(PAYLOAD_NAME, it) }
                            put(PAYLOAD_STATE, if (onConnect) CONNECTED else DISCONNECTED)
                        },
                    )
                )
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(
                if (onConnect) {
                    BluetoothDevice.ACTION_ACL_CONNECTED
                } else {
                    // ACTION_ACL_DISCONNECTED, not ACTION_ACL_DISCONNECT_REQUESTED:
                    // the latter fires when a disconnection is about to be
                    // attempted, which is not the same event and can be followed
                    // by the device staying connected.
                    BluetoothDevice.ACTION_ACL_DISCONNECTED
                }
            ),
            // A protected system broadcast, so nothing else can reach this receiver.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose { context.unregisterReceiver(receiver) }
    }

    /**
     * The device's address and name, or nulls.
     *
     * Both getters need `BLUETOOTH_CONNECT` from API 31 and throw without it, so
     * the permission is checked first — and the result is degraded to nulls
     * rather than propagated, because an exception here would kill the engine's
     * collector and take the rule with it. A rule narrowed to an address or a
     * name then cannot match, which is exactly what the factory's declared
     * requirement exists to explain on screen.
     *
     * The suppression is for lint's benefit, not a claim that the check is
     * unnecessary: `hasBluetoothConnectPermission` is that check, and lint cannot
     * follow it across a function boundary. `runCatching` stays as the second
     * line, for the OEM that throws anyway.
     */
    @Suppress("MissingPermission")
    private fun identify(device: BluetoothDevice?): Pair<String?, String?> {
        if (!context.hasBluetoothConnectPermission()) return null to null
        return runCatching { device?.address }.getOrNull() to
            runCatching { device?.name }.getOrNull()
    }

    companion object {
        const val TYPE = "bluetooth_connected"
        const val CONFIG_ADDRESS = "address"
        const val CONFIG_NAME = "name"

        /** Must match `ConfigField.TextPattern.modeKey`, which defaults to key + "Mode". */
        const val CONFIG_NAME_MODE = "nameMode"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_ADDRESS = "address"
        const val PAYLOAD_NAME = "name"
        const val PAYLOAD_STATE = "state"
        const val CONNECTED = "connected"
        const val DISCONNECTED = "disconnected"
    }
}

private fun Context.hasBluetoothConnectPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

class BluetoothConnectionTriggerFactory(
    private val context: Context,
) : TriggerFactory {
    override val type: String = BluetoothConnectionTrigger.TYPE

    override val displayName = "Bluetooth device"
    override val category = Category.RADIOS

    override val configFields = listOf(
        // A picker over the phone's paired devices rather than a box asking for
        // 00:11:22:33:44:55. It still stores an address — a paired device is a
        // convenience, not the set of devices that can connect — so an address
        // can also be typed.
        ConfigField.BluetoothAddress(
            key = BluetoothConnectionTrigger.CONFIG_ADDRESS,
            label = "Device",
            blankMeaning = "Any device",
            help = "Lists the devices this phone is paired with. Reading that " +
                "list, and the address of a device that connects, both need the " +
                "Bluetooth permission below.",
        ),
        stateChoice(
            label = "Fires when the device",
            onValue = BluetoothConnectionTrigger.CONNECTED,
            onLabel = "connects",
            offValue = BluetoothConnectionTrigger.DISCONNECTED,
            offLabel = "disconnects",
        ),
        // The escape hatch for a rotating address, and the reason the two are
        // separate optional filters rather than a "match on…" choice: they narrow
        // independently, an absent one means "no opinion" exactly as it does
        // everywhere else, and no rule saved before this existed needs migrating.
        textFilter(
            key = BluetoothConnectionTrigger.CONFIG_NAME,
            label = "Device name contains",
            blankMeaning = "Any name",
            help = "Use this instead of the device above for a Bluetooth LE " +
                "accessory: an unpaired one changes its address every few " +
                "minutes, so a rule pinned to an address stops matching. Pairing " +
                "the device also fixes that.",
        ),
    )

    // Without it the trigger still fires, but events carry no device address,
    // so a rule narrowed to one device can never match.
    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT),
    )

    override fun create(config: Map<String, String>): Trigger =
        BluetoothConnectionTrigger(
            context = context,
            // Absent means "any device", which is a valid configuration.
            deviceAddress = config[BluetoothConnectionTrigger.CONFIG_ADDRESS],
            nameFilter = TextFilter.fromConfig(
                config[BluetoothConnectionTrigger.CONFIG_NAME],
                config[BluetoothConnectionTrigger.CONFIG_NAME_MODE],
            ),
            // Absent means connect, because that is the only thing this trigger
            // could do before it learned about disconnection, and every rule
            // saved then has no state key to read.
            onConnect = parseTargetOrDefault(
                config = config,
                key = BluetoothConnectionTrigger.CONFIG_STATE,
                onWord = BluetoothConnectionTrigger.CONNECTED,
                offWord = BluetoothConnectionTrigger.DISCONNECTED,
                default = true,
            ),
        )
}
