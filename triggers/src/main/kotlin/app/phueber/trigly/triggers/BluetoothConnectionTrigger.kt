package app.phueber.trigly.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import kotlin.coroutines.resume
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

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
     * [BluetoothManager.getConnectedDevices] with [BluetoothProfile.GATT] answers
     * synchronously, but only for LE: a classic profile (A2DP, HEADSET — a car
     * head unit's profile, typically) keeps no GATT link, so it never appears on
     * that list even while genuinely connected. An earlier version of this
     * function stopped there and treated every classic device as permanently
     * unreadable, which was backwards for exactly the debounce above: a car
     * stereo is *the* device that flickers on and off, and a check that can never
     * see it as connected can never catch the flicker either — the debounce
     * silently did nothing for the one case it was built for.
     *
     * `BluetoothAdapter.getBondedDevices()` cannot fill the gap: bonded only
     * means paired, and a paired device is routinely sitting disconnected in a
     * pocket. What does fill it is [connectedDevicesForProfile], asked once for
     * [BluetoothProfile.A2DP] and once for [BluetoothProfile.HEADSET] — the two
     * profiles a car head unit or a classic headset actually uses. See that
     * function for the bind/timeout cost this adds on top of the free GATT read.
     *
     * The three lists — GATT, A2DP, HEADSET — are unioned: a match on any one of
     * them is a confirmed connection, so a GATT failure no longer forces null by
     * itself if a classic profile still answers. An absence across all three is
     * *not* the mirror image and still returns null, not false: two different
     * situations produce that absence and nothing here can tell them apart — a
     * genuinely disconnected device, or one connected on a profile this function
     * never asks about (HID, a hearing aid, LE Audio, anything else
     * `BluetoothProfile` defines beyond these three). Reporting "not connected"
     * for the second case would be exactly the wrong false `docs/conditions.md`
     * warns about. So a positive match is trustworthy full stop, and a rule gated
     * on this can still fire on a *connect*, or on the permission and read
     * failures below; only "this specific device just disconnected" is a claim
     * this function can never back up for a device living outside GATT, A2DP and
     * HEADSET.
     */
    // BLUETOOTH_CONNECT is checked on the first line and null returned without
    // it; lint does not follow the helper, and the file's other reads are
    // suppressed the same way.
    @SuppressLint("MissingPermission")
    override suspend fun currentlyHolds(): Boolean? {
        if (!context.hasBluetoothConnectPermission()) return null

        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null

        fun matches(devices: List<BluetoothDevice>?): Boolean =
            devices?.any { device ->
                val (address, name) = identify(device)
                bluetoothDeviceMatches(deviceAddress, nameFilter, address, name)
            } ?: false

        val gattDevices = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull()
        val a2dpDevices = connectedDevicesForProfile(adapter, BluetoothProfile.A2DP)
        val headsetDevices = connectedDevicesForProfile(adapter, BluetoothProfile.HEADSET)

        val connectedMatch = matches(gattDevices) || matches(a2dpDevices) || matches(headsetDevices)

        return if (connectedMatch) onConnect else null
    }

    /**
     * The devices currently connected on one classic Bluetooth profile —
     * [BluetoothProfile.A2DP] or [BluetoothProfile.HEADSET] — or null if the
     * profile's service never answered in time.
     *
     * Unlike GATT, a classic profile is not a list [BluetoothManager] can be
     * asked about directly; it is a system *service* an app has to bind to via
     * [BluetoothAdapter.getProfileProxy]. That call is fire-and-forget — its
     * `Boolean` return says whether the bind was *requested*, not whether it
     * succeeded — and the real answer arrives later, on
     * [BluetoothProfile.ServiceListener.onServiceConnected]. [suspendCancellableCoroutine]
     * turns that one-shot callback back into an ordinary return value.
     *
     * The wait is capped at [PROFILE_PROXY_TIMEOUT_MILLIS], 1.5 seconds. In the
     * ordinary case this binds to a service that is already running — the same
     * A2DP/HEADSET process every other Bluetooth-using app on the phone shares —
     * so the callback is fast; the cap exists for the adapter that is busy or
     * wedged. [currentlyHolds] runs synchronously inside a rule's gate
     * evaluation, so a bind that simply never answered would otherwise hang that
     * evaluation forever. 1.5 seconds is long enough to survive a cold bind (the
     * profile process occasionally has to start rather than merely reply) and
     * short enough that one flaky device does not make a condition check feel
     * broken; there is no measurement behind the exact number beyond that, only
     * the shape of the trade-off.
     *
     * The proxy is closed the moment its answer is read — [closeProfileProxy],
     * right there in the listener, whether or not the timeout above has already
     * fired. An unclosed proxy is a live binder connection to a system service
     * for the rest of the process's life, and avoiding exactly that is this
     * function's reason to exist: it asks once and lets go, rather than holding
     * a profile connection open between checks the way a trigger's [events] would.
     * If the timeout wins the race, the listener is not told to stop listening —
     * there is no API to cancel a [BluetoothAdapter.getProfileProxy] request in
     * flight — so a very late answer can still arrive after this has already
     * returned null; it still gets closed then, just too late to be used.
     */
    // Same reasoning as currentlyHolds' own suppression: BLUETOOTH_CONNECT is
    // checked by that caller before this is ever reached, and lint cannot see
    // across the call.
    @SuppressLint("MissingPermission")
    private suspend fun connectedDevicesForProfile(
        adapter: BluetoothAdapter,
        profile: Int,
    ): List<BluetoothDevice>? = withTimeoutOrNull(PROFILE_PROXY_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(connectedProfile: Int, proxy: BluetoothProfile) {
                    val devices = runCatching { proxy.connectedDevices }.getOrNull()
                    adapter.closeProfileProxy(profile, proxy)
                    if (continuation.isActive) continuation.resume(devices)
                }

                override fun onServiceDisconnected(disconnectedProfile: Int) = Unit
            }
            val requested = runCatching { adapter.getProfileProxy(context, listener, profile) }.getOrDefault(false)
            if (!requested && continuation.isActive) {
                // getProfileProxy said the bind was never even attempted — there is
                // no service connection pending and therefore nothing to close.
                continuation.resume(null)
            }
        }
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

        /**
         * The editor's "identify the device by" choice. A third key, never read by
         * [BluetoothConnectionTrigger] itself — [CONFIG_ADDRESS] and [CONFIG_NAME]
         * keep being read independently and ANDed exactly as they always were, see
         * [bluetoothDeviceMatches] — this only decides which of the two fields the
         * editor draws. See [BluetoothConnectionTriggerFactory.configFields] for why
         * that split is safe for a rule saved before this key existed.
         */
        const val CONFIG_IDENTIFY_BY = "identifyBy"
        const val IDENTIFY_BY_ADDRESS = "address"
        const val IDENTIFY_BY_NAME = "name"
        const val PAYLOAD_ADDRESS = "address"
        const val PAYLOAD_NAME = "name"
        const val PAYLOAD_STATE = "state"
        const val CONNECTED = "connected"
        const val DISCONNECTED = "disconnected"
    }
}

/**
 * How long [BluetoothConnectionTrigger.connectedDevicesForProfile] waits for a
 * classic profile service to answer before giving up and returning null. See
 * that function for what the number is trading off.
 */
private const val PROFILE_PROXY_TIMEOUT_MILLIS = 1_500L

private fun Context.hasBluetoothConnectPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

class BluetoothConnectionTriggerFactory(
    private val context: Context,
) : TriggerFactory {
    override val type: String = BluetoothConnectionTrigger.TYPE

    override val displayName = "Bluetooth device"
    override val category = Category.RADIOS

    // See BluetoothConnectionTrigger.currentlyHolds: querying GATT, A2DP and
    // HEADSET together answers for real whenever the device is seen connected
    // on any of the three, and honestly declines (null) rather than guess when
    // it is not — which is enough to satisfy the contract this flag makes, just
    // not for a device that only ever shows up on some other profile.
    override val supportsCondition = true

    // Surfaced here, on the factory, rather than left in currentlyHolds' KDoc,
    // because that KDoc lives on a suspend function nobody choosing a condition
    // in the editor will ever open — this is the one place the caveat reaches
    // the person it is actually for.
    override val warning: String =
        "As a condition, this trigger can confirm that a device is connected. " +
            "This includes a classic audio device like a car stereo. Confirming " +
            "a connected device costs a brief extra check beyond the instant " +
            "check for a Bluetooth LE device. This trigger can confirm that a " +
            "device is disconnected only if the device uses GATT, A2DP or " +
            "HEADSET. On any other profile, such as HID, a hearing aid or LE " +
            "Audio, this check never answers. It does not answer wrong."

    override val configFields = listOf(
        // One decision, not two overlapping filters: which of CONFIG_ADDRESS and
        // CONFIG_NAME is visible follows from this choice, so the editor never
        // shows both at once and never has to explain, in prose, that one is a
        // workaround for the other. bluetoothDeviceMatches still ANDs both if
        // both happen to be set — a rule from before this field existed may
        // carry either, both, or neither, and none of that changes.
        //
        // Defaulted to "address" rather than "name". CONFIG_IDENTIFY_BY is a key
        // no rule saved before this existed has, so for every one of them
        // ConfigField.shownWith falls back to this default to decide what to
        // draw — there is no way to compute a default from what a rule already
        // has stored, only one fixed value that has to serve every legacy shape
        // at once. "address" is the one that does that best: it is correct for
        // the two commonest legacy shapes — no filter at all (shows "Any
        // device", which is exactly what was configured) and an address picked
        // from the paired-device list (shows it) — because the address field,
        // not the name filter, is what every rule had before the name filter
        // was added as an escape hatch for unpaired LE gear. A rule that used
        // that escape hatch instead opens showing "Any device" with its name
        // filter hidden — not lost: a hidden field's stored value is never
        // cleared on save, only left undrawn, so the filter keeps applying —
        // and one tap on this choice reveals it again.
        ConfigField.Choice(
            key = BluetoothConnectionTrigger.CONFIG_IDENTIFY_BY,
            label = "Identify the device by",
            options = listOf(
                ConfigField.Option(BluetoothConnectionTrigger.IDENTIFY_BY_ADDRESS, "Paired device"),
                ConfigField.Option(BluetoothConnectionTrigger.IDENTIFY_BY_NAME, "Name"),
            ),
            default = BluetoothConnectionTrigger.IDENTIFY_BY_ADDRESS,
            help = "A paired device keeps the same address. Picking one from the " +
                "list gives a durable match. An unpaired Bluetooth LE accessory " +
                "rotates its address every few minutes. For that accessory, match " +
                "by the name it advertises instead. Pairing the device removes " +
                "the need for the name match.",
        ),
        // A picker over the phone's paired devices rather than a box asking for
        // 00:11:22:33:44:55. It still stores an address — a paired device is a
        // convenience, not the set of devices that can connect — so an address
        // can also be typed.
        ConfigField.BluetoothAddress(
            key = BluetoothConnectionTrigger.CONFIG_ADDRESS,
            label = "Device",
            blankMeaning = "Any device",
            shownWhen = FieldCondition(
                key = BluetoothConnectionTrigger.CONFIG_IDENTIFY_BY,
                value = BluetoothConnectionTrigger.IDENTIFY_BY_ADDRESS,
            ),
            help = "This field lists the devices paired with this phone. Reading " +
                "that list needs this trigger's Bluetooth permission. Reading " +
                "the address of a connecting device needs it too.",
        ),
        stateChoice(
            label = "Fires when the device",
            onValue = BluetoothConnectionTrigger.CONNECTED,
            onLabel = "connects",
            offValue = BluetoothConnectionTrigger.DISCONNECTED,
            offLabel = "disconnects",
        ),
        // Built directly rather than through the textFilter() helper in
        // ConfigSchema.kt: that helper has no shownWhen parameter, and this is
        // the one text filter in the project that needs one.
        ConfigField.TextPattern(
            key = BluetoothConnectionTrigger.CONFIG_NAME,
            label = "Name contains",
            blankMeaning = "Any name",
            shownWhen = FieldCondition(
                key = BluetoothConnectionTrigger.CONFIG_IDENTIFY_BY,
                value = BluetoothConnectionTrigger.IDENTIFY_BY_NAME,
            ),
            help = "This is the name a Bluetooth LE accessory advertises. It is " +
                "the identifier that survives when the accessory is not paired. " +
                "An unpaired accessory rotates its address every few minutes. " +
                "That rotation is what makes the name the more durable choice " +
                "for an unpaired accessory. Pairing the accessory fixes the " +
                "rotation. After pairing, \"Paired device\" is the better choice " +
                "again.",
        ),
        // Only shown for the disconnect direction: a connection that appears is
        // real, so there is nothing to debounce on the connect side. Defaults to
        // off so a rule saved before this existed keeps firing on the raw edge.
        ConfigField.Duration(
            key = BluetoothConnectionTrigger.CONFIG_DISCONNECT_DEBOUNCE_MILLIS,
            label = "Ignore a reconnect within",
            shownWhen = FieldCondition(
                key = BluetoothConnectionTrigger.CONFIG_STATE,
                value = BluetoothConnectionTrigger.DISCONNECTED,
            ),
            defaultMillis = 0L,
            preferred = DurationUnit.SECONDS,
            help = "A car head unit, in particular, can drop and reconnect within " +
                "seconds. This field sets a wait after a disconnect. Trigly " +
                "rechecks the connection before it fires. This absorbs that " +
                "flicker. It also delays every genuine disconnect by the same " +
                "amount. A value of zero fires immediately, as before.",
        ),
    )

    // The honest worst case: without it the trigger still fires, but events
    // carry no device address or name, so a rule narrowed to a device can never
    // match. Kept unconditional, rather than removed in favour of
    // requirementsFor below, as the answer for any caller that reads
    // `requirements` directly — the rules list is not that caller; see
    // requirementsFor.
    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT),
    )

    // The honest common case, for the rules list specifically: an "any device"
    // rule matches on the raw ACL broadcast alone and needs nothing, because
    // bluetoothDeviceMatches(null, TextFilter.Any, null, null) is true
    // regardless of whether identify() could read an address or a name.
    // Narrowing by CONFIG_ADDRESS or CONFIG_NAME is what turns a missing
    // address or name from a missing detail into a missing match, and that is
    // the point this permission actually starts to matter. Declaring it
    // unconditionally would mark that unnarrowed rule "cannot fire" in the
    // rules list when it fires perfectly well — and a requirement that is
    // sometimes irrelevant teaches people to ignore requirements, which is the
    // opposite of what the list is for.
    //
    // This is deliberately about the *edge* role only. Used as a condition, an
    // unnarrowed "any device" check still needs this permission just to call
    // getConnectedDevices/getProfileProxy at all — currentlyHolds returns null
    // without it regardless of narrowing — and requirementsFor has no way to
    // know which slot a given config ends up in, only what the config says. A
    // rule that puts this unnarrowed in a condition slot without the permission
    // will silently never hold rather than being flagged unfirable; that gap is
    // real and is not fixed here, only left visible in this comment rather than
    // hidden in a decision only this function's author was in a position to make.
    override fun requirementsFor(config: Map<String, String>): List<ComponentRequirement> {
        val narrowed = !config[BluetoothConnectionTrigger.CONFIG_ADDRESS].isNullOrEmpty() ||
            !config[BluetoothConnectionTrigger.CONFIG_NAME].isNullOrEmpty()
        return if (narrowed) requirements else emptyList()
    }

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
