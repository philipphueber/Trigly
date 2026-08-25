package app.phueber.trigly.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.FieldCondition
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

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
 * Whether two sightings of a device are the same physical device — a different
 * question from [bluetoothDeviceMatches], and the one the disconnect debounce's
 * reconnect check actually needs answered.
 *
 * [bluetoothDeviceMatches] asks "does this device satisfy the rule's filter",
 * which for a rule with no address or name set is yes for every device there
 * is. That is the right question when deciding whether to emit, but the wrong
 * one when deciding whether *this* connect is the reconnect of the device that
 * *just* disconnected: answering it with the rule's filter would let an
 * unrelated device connecting — a watch, a stray BLE beacon, anything else
 * nearby — cancel the disconnect of a completely different device.
 *
 * Compared by address when both sightings report one, since a MAC is otherwise
 * unique; by name only when an address is missing on either side. When neither
 * survived — no `BLUETOOTH_CONNECT` permission, most commonly — this declines
 * rather than guesses: a coincidental match here would suppress a real
 * disconnect, which is the failure the debounce exists to avoid, not to cause.
 */
private fun isSameDevice(address1: String?, name1: String?, address2: String?, name2: String?): Boolean =
    when {
        address1 != null && address2 != null -> address1.equals(address2, ignoreCase = true)
        name1 != null && name2 != null -> name1 == name2
        else -> false
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
 * state to deduplicate. When [onConnect] is false and [disconnectDebounceMillis]
 * is positive, the connect broadcast is registered too, but only to detect a
 * reconnect during the settle window — see [events].
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
    private val disconnectDebounceMillis: Long = 0L,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = callbackFlow {
        // The disconnect edge currently waiting out its settle window, if any, and
        // the identity of the device it belongs to. A reconnect *of that same
        // device* cancels it outright — the cheap, deterministic half of the
        // debounce. [currentlyHolds] backs it up for the case where no reconnect
        // broadcast ever arrives to cancel it (a genuinely classic-profile device,
        // for instance), by re-checking state instead of relying only on having
        // seen every edge.
        //
        // The identity is tracked separately from the rule's own filter on
        // purpose. [bluetoothDeviceMatches] answers "does this device satisfy the
        // rule", which for a rule with no device or name set — "any device" — is
        // yes for every device there is. Reusing that answer to decide whether a
        // *reconnect* happened would let an unrelated device connecting (a watch
        // reconnecting, a stray BLE beacon, anything) cancel the disconnect of a
        // completely different device, starving the rule of a disconnect it
        // should have reported.
        var pendingDisconnect: Job? = null
        var pendingDisconnectAddress: String? = null
        var pendingDisconnectName: String? = null

        fun emit(address: String?, name: String?, state: String) {
            trySend(
                TriggerEvent(
                    triggerType = TYPE,
                    firedAtMillis = now(),
                    payload = buildMap {
                        address?.let { put(PAYLOAD_ADDRESS, it) }
                        name?.let { put(PAYLOAD_NAME, it) }
                        put(PAYLOAD_STATE, state)
                    },
                )
            )
        }

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

                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        if (onConnect) {
                            // A connection that appears is real — nothing to debounce
                            // on this side, a flicker of *missing* connects is not a
                            // thing a broadcast receiver can even observe.
                            emit(address, name, CONNECTED)
                        } else if (
                            isSameDevice(pendingDisconnectAddress, pendingDisconnectName, address, name)
                        ) {
                            // The registration below only adds this broadcast when a
                            // disconnect debounce is running, so reaching here means
                            // the device that just disconnected — specifically that
                            // one, not merely some device the rule's filter would
                            // also accept — came back before the settle window
                            // elapsed, so the disconnect we were about to report
                            // never really happened.
                            pendingDisconnect?.cancel()
                            pendingDisconnect = null
                            pendingDisconnectAddress = null
                            pendingDisconnectName = null
                        }
                        // Any other connect — a different device, or one this
                        // receiver cannot identify as the same — leaves the pending
                        // disconnect running. [isSameDevice] declining rather than
                        // guessing is what keeps that safe: a coincidental match
                        // here would suppress a real disconnect.
                    }

                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        if (disconnectDebounceMillis <= 0) {
                            emit(address, name, DISCONNECTED)
                        } else {
                            pendingDisconnect?.cancel()
                            pendingDisconnectAddress = address
                            pendingDisconnectName = name
                            pendingDisconnect = launch {
                                delay(disconnectDebounceMillis)
                                // null means the state could not be re-read (missing
                                // permission, or a classic-profile device this API
                                // cannot see at all — see currentlyHolds). Suppressing
                                // a real disconnect because it could not be verified
                                // is the worse failure, so anything but a confirmed
                                // reconnect (== false) still emits.
                                if (currentlyHolds() != false) {
                                    emit(address, name, DISCONNECTED)
                                }
                                pendingDisconnect = null
                                pendingDisconnectAddress = null
                                pendingDisconnectName = null
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(
            if (onConnect) {
                BluetoothDevice.ACTION_ACL_CONNECTED
            } else {
                // ACTION_ACL_DISCONNECTED, not ACTION_ACL_DISCONNECT_REQUESTED:
                // the latter fires when a disconnection is about to be
                // attempted, which is not the same event and can be followed
                // by the device staying connected.
                BluetoothDevice.ACTION_ACL_DISCONNECTED
            }
        )
        if (!onConnect && disconnectDebounceMillis > 0) {
            // Only registered to notice a reconnect inside the settle window; it is
            // never itself the event this trigger fires in this direction.
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            // A protected system broadcast, so nothing else can reach this receiver.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose {
            pendingDisconnect?.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    /**
     * Whether the configured device's connection state matches [onConnect] right
     * now — the condition seam from `docs/conditions.md`, and the recheck the
     * disconnect debounce leans on.
     *
     * [BluetoothManager.getConnectedDevices] with [BluetoothProfile.GATT] is the
     * only public, synchronous way to ask "is this device connected" — no
     * reflection, no proxy. It is also only ever right about LE: a classic
     * profile (A2DP, HEADSET — a car head unit's profile, typically) keeps no GATT
     * link, so it never appears in that list even while genuinely connected.
     * `BluetoothAdapter.getBondedDevices()` cannot fill that gap either — bonded
     * only means paired, and a paired device is routinely sitting disconnected in
     * a pocket. The classic answer exists (`BluetoothProfile.ServiceListener` via
     * `getProfileProxy`), but it is asynchronous and per-profile, not a fit for a
     * suspend function meant to answer quickly.
     *
     * So a positive match is trustworthy — the device is on the GATT list, it is
     * connected, full stop. An absence is not: it is either a genuinely
     * disconnected device, or a classic-profile device this call cannot see at
     * all, and there is no honest way here to tell those apart. Reporting "not
     * connected" anyway would be exactly the wrong false `docs/conditions.md`
     * warns about, so an absence returns null — a rule gated on this can still
     * fire, it just cannot fire on the strength of a classic device having
     * *disconnected*, only of one currently seen connected, or of the permission
     * and read failures below.
     */
    // BLUETOOTH_CONNECT is checked on the first line and null returned without
    // it; lint does not follow the helper, and the file's other reads are
    // suppressed the same way.
    @SuppressLint("MissingPermission")
    override suspend fun currentlyHolds(): Boolean? {
        if (!context.hasBluetoothConnectPermission()) return null

        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val connectedMatch = runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT).any { device ->
                val (address, name) = identify(device)
                bluetoothDeviceMatches(deviceAddress, nameFilter, address, name)
            }
        }.getOrNull() ?: return null

        return if (connectedMatch) onConnect else null
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
        const val CONFIG_DISCONNECT_DEBOUNCE_MILLIS = "disconnectDebounceMillis"
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

    // See BluetoothConnectionTrigger.currentlyHolds: it answers for real when the
    // device is seen connected, and honestly declines (null) rather than guess
    // when it is not — which is enough to satisfy the contract this flag makes,
    // just not on every configuration.
    override val supportsCondition = true

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
        // Only shown for the disconnect direction: a connection that appears is
        // real, so there is nothing to debounce on the connect side. Defaults to
        // off so a rule saved before this existed keeps firing on the raw edge.
        ConfigField.Duration(
            key = BluetoothConnectionTrigger.CONFIG_DISCONNECT_DEBOUNCE_MILLIS,
            label = "Wait before firing",
            shownWhen = FieldCondition(
                key = BluetoothConnectionTrigger.CONFIG_STATE,
                value = BluetoothConnectionTrigger.DISCONNECTED,
            ),
            defaultMillis = 0L,
            preferred = DurationUnit.SECONDS,
            help = "A car head unit, in particular, can drop and reconnect within " +
                "seconds. Waiting this long after a disconnect and re-checking " +
                "before firing absorbs that flicker, at the cost of the same " +
                "delay on every genuine disconnect. Zero fires immediately, as " +
                "before.",
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
            // Absent, or on the connect direction where the field is hidden, means
            // off — the raw edge, exactly what every rule saved before this existed
            // already gets.
            disconnectDebounceMillis = config[BluetoothConnectionTrigger.CONFIG_DISCONNECT_DEBOUNCE_MILLIS]
                ?.toLongOrNull() ?: 0L,
        )
}
