package app.phueber.trigly.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
 * survived, which means no `BLUETOOTH_CONNECT` permission in practice, this
 * declines rather than guesses: a coincidental match here would suppress a real
 * disconnect, which is the failure the debounce exists to avoid, not to cause.
 *
 * `internal` rather than private because [BluetoothEvents] asks the same
 * question of the same pair of fields when it decides whether a second
 * broadcast describes the same device as the first. Two copies of an identity
 * rule is how one of them ends up subtly different from the other, and this one
 * is subtle: the two ends of a comparison do not always carry the same fields.
 */
internal fun isSameDevice(address1: String?, name1: String?, address2: String?, name2: String?): Boolean =
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
 * [onConnect] chooses which of the two edges this instance reports. This is
 * the same shape as `power_connection`, whose two broadcasts are likewise
 * already edge-shaped: seeing one *is* the event, so there is no state to
 * deduplicate. When [onConnect] is false and [disconnectDebounceMillis] is
 * positive, a connect is watched too, but only to detect a reconnect during
 * the settle window. See [events].
 *
 * The [TYPE] string still says `bluetooth_connected` even though the trigger now
 * does both. It is persisted in every saved rule and in every exported file, so
 * renaming it to match would break the thing it identifies; a type string is an
 * identifier, not a description.
 *
 * **The broadcast no longer reaches this class directly.** A receiver
 * registered here, as one used to be, only exists while [events] is being
 * collected, and [events] is not being collected in a process the system has
 * already killed. A device reconnecting after the phone sits idle for a while
 * is exactly the case where that has happened. `ACTION_ACL_CONNECTED` and
 * `ACTION_ACL_DISCONNECTED` are answered the way `BOOT_COMPLETED` and a
 * shortcut tap already are: `BluetoothConnectionReceiver`, a manifest receiver
 * in `:ui`, is the one thing the system can always reach, and [BluetoothEvents]
 * is where it leaves word of what it saw. See [BluetoothEvents] for how one
 * sighting still reaches this exactly once, whether the receiver is what just
 * started this process or this trigger was already collecting when it arrived.
 */
class BluetoothConnectionTrigger(
    private val context: Context,
    private val deviceAddress: String?,
    private val nameFilter: TextFilter = TextFilter.Any,
    private val onConnect: Boolean = true,
    private val disconnectDebounceMillis: Long = 0L,
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = BluetoothEvents.DEFAULT_WINDOW_MILLIS,
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

        // The timestamp of the sighting this collection has already turned into
        // an emission or a state change, so a replay of that exact sighting is
        // not turned into a second one. [BluetoothEvents.pending] can hand this
        // collection a sighting at start-up, and [BluetoothEvents.sightings] can
        // then redeliver that same sighting a moment later if this collection's
        // subscription below was not yet active when it was first published.
        // This is the same guard [ShortcutTrigger] keeps against [ShortcutEvents]
        // and for the same reason. See [BluetoothEvents] for why nothing upstream
        // of this already guarantees it.
        var lastHandledAtMillis: Long? = null

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

        // What registering an IntentFilter for only the relevant action used to
        // do, this now does at runtime: every sighting reaches every trigger
        // instance through the one shared bus, whatever direction it fires in,
        // so a sighting this instance does not care about has to be recognised
        // and left alone rather than never delivered in the first place.
        fun handle(sighting: BluetoothEvents.Sighting) {
            val address = sighting.address
            val name = sighting.name

            if (!bluetoothDeviceMatches(deviceAddress, nameFilter, address, name)) return

            when (sighting.action) {
                BluetoothEvents.Action.CONNECTED -> {
                    if (onConnect) {
                        // A connection that appears is real. There is nothing to
                        // debounce on this side; a flicker of *missing* connects is
                        // not a thing a broadcast can even observe.
                        emit(address, name, CONNECTED)
                    } else if (
                        isSameDevice(pendingDisconnectAddress, pendingDisconnectName, address, name)
                    ) {
                        // Only meaningful once a disconnect debounce has set the
                        // pending fields below; otherwise isSameDevice declines
                        // (both sides null) and this is a no-op, matching the
                        // registration this instance used to make on its own.
                        // Reaching here with a match means the device that just
                        // disconnected, specifically that one, not merely some
                        // device the rule's filter would also accept, came back
                        // before the settle window elapsed, so the disconnect we
                        // were about to report never really happened.
                        pendingDisconnect?.cancel()
                        pendingDisconnect = null
                        pendingDisconnectAddress = null
                        pendingDisconnectName = null
                    }
                    // Any other connect, a different device, or one this trigger
                    // cannot identify as the same, leaves the pending disconnect
                    // running. [isSameDevice] declining rather than guessing is
                    // what keeps that safe: a coincidental match here would
                    // suppress a real disconnect.
                }

                BluetoothEvents.Action.DISCONNECTED -> {
                    // A connect-direction instance never registered this action
                    // through its own receiver before; it still must not act on
                    // it now that every instance sees every sighting.
                    if (onConnect) return

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
                            // cannot see at all; see currentlyHolds). Suppressing
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

        // Cold-start path: a sighting recorded before this flow started
        // collecting, most likely because it is what started the process this
        // flow is now collecting in. See [BluetoothEvents.pending].
        BluetoothEvents.pending(now(), windowMillis)?.let { sighting ->
            lastHandledAtMillis = sighting.atMillis
            handle(sighting)
        }

        // Warm path: a sighting published while this flow is already
        // collecting. [ServiceEventBus] does not replay, so this never
        // re-delivers what [pending] above already consumed from a process
        // that was not yet collecting when it happened. The guard exists for
        // the narrower race where this subscription starts just late enough to
        // still catch the live publish of the very sighting [pending] already
        // read.
        val subscription = launch {
            BluetoothEvents.sightings.events.collect { sighting ->
                if (sighting.atMillis == lastHandledAtMillis) return@collect
                lastHandledAtMillis = sighting.atMillis
                handle(sighting)
            }
        }

        awaitClose {
            pendingDisconnect?.cancel()
            subscription.cancel()
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
        if (!context.canReadBluetoothDevices()) return null

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
     * The device's address and name, or nulls. Delegates to [bluetoothIdentify],
     * which [BluetoothConnectionReceiver] in `:ui` now also calls. That way a
     * sighting's identity is resolved once, and every reader (this
     * [currentlyHolds] poll, and every trigger reading [BluetoothEvents]) agrees
     * on what it was.
     */
    private fun identify(device: BluetoothDevice?): Pair<String?, String?> =
        bluetoothIdentify(context, device)

    companion object {
        const val TYPE = "bluetooth_connected"
        const val CONFIG_ADDRESS = "address"
        const val CONFIG_NAME = "name"

        /** Must match `ConfigField.TextPattern.modeKey`, which defaults to key + "Mode". */
        const val CONFIG_NAME_MODE = "nameMode"
        const val CONFIG_STATE = "state"
        const val CONFIG_DISCONNECT_DEBOUNCE_MILLIS = "disconnectDebounceMillis"

        /**
         * Which of [CONFIG_ADDRESS] and [CONFIG_NAME] a rule is matching on.
         *
         * This used to be an editor-only key: it decided which of the two fields
         * was drawn, and both were read and ANDed at runtime whatever it said.
         * The reasoning was that a hidden field's value is never cleared, so a
         * legacy rule that matched on a name kept working even when the editor
         * showed it as "Any device". That much was true, and it also produced a
         * rule that showed a device chosen from the paired list, hid a name
         * filter left over from an earlier attempt, and silently never matched
         * anything, because the two were ANDed and the device's advertised name
         * did not contain the leftover text. Nothing on screen could say why. A
         * hidden field that still decides the answer is the exact failure this
         * project exists to avoid.
         *
         * So it is read now, by [bluetoothWantedAddress] and
         * [bluetoothNameFilter], through [bluetoothIdentifyBy]:
         *
         * - `address`: match on the address, ignore any stored name.
         * - `name`: match on the name, ignore any stored address.
         * - absent: derived from what the rule stores, and never both. See
         *   [bluetoothIdentifyBy], which is also where the ANDed reading this
         *   used to have is written down and why it was worse than the trap it
         *   was avoiding.
         *
         * A rule that *has* a value has it because a person saw this control and
         * chose, so the choice is honoured rather than second-guessed.
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

/**
 * Whether this app may read the address and the name of a Bluetooth device.
 *
 * Two eras, and the gate has to know which one it is in. From API 31 the reads
 * need `BLUETOOTH_CONNECT`, a runtime grant, and throw without it. Before API 31
 * they need the legacy `BLUETOOTH` permission, which is install-time and
 * therefore always held; `BLUETOOTH_CONNECT` does not exist as a permission
 * there at all, and `checkSelfPermission` on a name the platform never defined
 * answers "denied" for ever.
 *
 * So a single unconditional check for `BLUETOOTH_CONNECT` refused to read a
 * device on Android 11 and below, where the reads were legal the whole time.
 * Every rule narrowed to a device then failed to match on those versions,
 * silently, because a device this returns nothing for is a device no filter can
 * accept. The version test is the fix, and it belongs here rather than at the
 * two call sites, which both want the same answer to the same question.
 */
private fun Context.canReadBluetoothDevices(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The address and name of a device from an ACL broadcast, or nulls.
 *
 * Both getters need `BLUETOOTH_CONNECT` from API 31 and throw without it, so
 * the permission is checked first, and the result is degraded to nulls rather
 * than propagated. `runCatching` stays as a second line for the OEM that
 * throws anyway.
 *
 * Top-level and public, rather than a private method on
 * [BluetoothConnectionTrigger], because `BluetoothConnectionReceiver` in `:ui`
 * needs the same resolution: it is what records a sighting's identity in
 * [BluetoothEvents] in the first place, and a sighting's identity has to be
 * resolved exactly once for every trigger reading it to agree on what it was.
 * [BluetoothConnectionTrigger.currentlyHolds] is the other caller, for the
 * device lists it polls directly rather than through a sighting.
 *
 * The suppression is for lint's benefit, not a claim that the check is
 * unnecessary: [canReadBluetoothDevices] is that check, and lint cannot follow
 * it across a function boundary.
 */
@SuppressLint("MissingPermission")
fun bluetoothIdentify(context: Context, device: BluetoothDevice?): Pair<String?, String?> {
    if (!context.canReadBluetoothDevices()) return null to null
    return runCatching { device?.address }.getOrNull() to
        runCatching { device?.name }.getOrNull()
}

/**
 * What `bluetooth_connected` needs on a device at [apiLevel].
 *
 * Pure, and separate from the factory, for the reason the filter helpers above
 * are: the factory needs a `Context` and cannot be built in a JVM test, while
 * getting this wrong is a rule that never fires and never says why.
 *
 * From API 31 the answer is `BLUETOOTH_CONNECT`, for **every** configuration.
 * The permission does not merely decide whether an event can name its device;
 * it decides whether the event arrives. `ACTION_ACL_CONNECTED` and
 * `ACTION_ACL_DISCONNECTED` are sent with `BLUETOOTH_CONNECT` as the *receiver*
 * permission, so a receiver that does not hold it is not sent them. Below API 31
 * the sender attaches the legacy `BLUETOOTH` permission instead, which is
 * install-time, so there is nothing for a person to grant and nothing to
 * declare: a row with a button that cannot open a dialog, on a trigger that
 * already works, is worse than no row.
 */
fun bluetoothConnectRequirements(apiLevel: Int): List<ComponentRequirement> =
    if (apiLevel >= Build.VERSION_CODES.S) {
        listOf(ComponentRequirement.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT))
    } else {
        emptyList()
    }

/**
 * Which of the two filters this configuration matches on: an address or a name,
 * never both.
 *
 * **The absent case is the whole reason this function exists.**
 * [BluetoothConnectionTrigger.CONFIG_IDENTIFY_BY] is a key that arrived after
 * rules were already being saved, and the editor seeds a default only when a
 * component is first chosen, so a rule written before it has no value here and
 * editing that rule never adds one. Absence used to mean "read both keys and AND
 * them", which kept an old name-matching rule working and cost far more than it
 * bought: a rule holding an address *and* a name left over from an earlier
 * attempt matched nothing at all, while the editor drew the schema default,
 * showed the paired-device picker, and hid the name. A filter nobody could see
 * decided every match, and the rules list does not name what a trigger matches
 * on either, so there was nowhere at all to find out.
 *
 * Absence is now resolved from what the rule actually stores, and the answer is
 * always one filter:
 *
 * - a stored address wins. It identifies one device, so a name beside it can
 *   only ever subtract, and no rule was ever written to say "this device, but
 *   only while it calls itself that".
 * - failing that, a stored name. This is the legacy promise kept: the name was
 *   the escape hatch for unpaired gear, and a rule using it goes on using it.
 * - failing both, the address, which is "any device" and matches everything.
 *
 * A value the editor could not have written is treated as absent for the same
 * reason: it can only have come from a hand-edited or imported file, and the
 * ANDed reading of it is the behaviour being removed.
 */
fun bluetoothIdentifyBy(config: Map<String, String>): String {
    val stored = config[BluetoothConnectionTrigger.CONFIG_IDENTIFY_BY]
    if (stored == BluetoothConnectionTrigger.IDENTIFY_BY_ADDRESS ||
        stored == BluetoothConnectionTrigger.IDENTIFY_BY_NAME
    ) {
        return stored
    }

    return when {
        !config[BluetoothConnectionTrigger.CONFIG_ADDRESS].isNullOrBlank() ->
            BluetoothConnectionTrigger.IDENTIFY_BY_ADDRESS

        !config[BluetoothConnectionTrigger.CONFIG_NAME].isNullOrBlank() ->
            BluetoothConnectionTrigger.IDENTIFY_BY_NAME

        else -> BluetoothConnectionTrigger.IDENTIFY_BY_ADDRESS
    }
}

/**
 * The same configuration with the identify-by choice written down.
 *
 * What the editor is handed when it opens a rule, per
 * `ComponentFactory.normalise`. The engine does not need it, because its readers
 * resolve an absent key themselves through [bluetoothIdentifyBy]; the editor
 * does, because a `shownWhen` condition can only read a stored value, so without
 * this the form hides the filter that is deciding every match.
 *
 * Idempotent, since [bluetoothIdentifyBy] returns a stored choice unchanged.
 */
fun bluetoothNormalise(config: Map<String, String>): Map<String, String> =
    config + (BluetoothConnectionTrigger.CONFIG_IDENTIFY_BY to bluetoothIdentifyBy(config))

/**
 * The address this configuration matches on, or null for "any address".
 *
 * Null when the rule identifies its device by name, whatever an address key left
 * over from an earlier edit says. See [bluetoothIdentifyBy].
 *
 * Pure, and separate from the factory, for the reason every other helper in this
 * file is: the factory needs a `Context` and cannot be built in a JVM test, while
 * getting this wrong is a rule that never fires and says nothing.
 */
fun bluetoothWantedAddress(config: Map<String, String>): String? =
    if (bluetoothIdentifyBy(config) == BluetoothConnectionTrigger.IDENTIFY_BY_NAME) {
        null
    } else {
        config[BluetoothConnectionTrigger.CONFIG_ADDRESS]
    }

/**
 * The name filter this configuration matches on, or [TextFilter.Any] for
 * "any name".
 *
 * [TextFilter.Any] when the rule identifies its device by address, whatever a
 * name key left over from an earlier edit says.
 */
fun bluetoothNameFilter(config: Map<String, String>): TextFilter =
    if (bluetoothIdentifyBy(config) == BluetoothConnectionTrigger.IDENTIFY_BY_ADDRESS) {
        TextFilter.Any
    } else {
        TextFilter.fromConfig(
            config[BluetoothConnectionTrigger.CONFIG_NAME],
            config[BluetoothConnectionTrigger.CONFIG_NAME_MODE],
        )
    }

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
        // CONFIG_NAME is visible follows from this choice, and so does which one
        // the engine reads. The editor shows exactly the field that decides the
        // match, which is the whole point of the key. See
        // CONFIG_IDENTIFY_BY for what this used to do instead, and what that
        // cost.
        //
        // Defaulted to "address" rather than "name". CONFIG_IDENTIFY_BY is a key
        // no rule saved before this existed has, so for every one of them
        // ConfigField.shownWith falls back to this default to decide what to
        // draw. There is no way to compute a default from what a rule already
        // has stored, only one fixed value that has to serve every legacy shape
        // at once. "address" is the one that does that best: it is correct for
        // the two commonest legacy shapes, no filter at all (shows "Any device",
        // which is exactly what was configured) and an address picked from the
        // paired-device list (shows it), because the address field, not the name
        // filter, is what every rule had before the name filter was added as an
        // escape hatch for unpaired LE gear.
        //
        // A legacy rule that used that escape hatch opens showing "Any device"
        // with its name filter hidden, and it still matches by name, because a
        // rule with no stored value for this key reads both fields exactly as it
        // always did. One tap on this choice reveals the filter again. What has
        // changed is that saving the rule writes a value here, and from then on
        // the visible field is the only one that matches.
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

    // Unconditional, because the permission is not what lets this trigger name
    // the device it heard about; it is what lets it hear anything. See
    // [bluetoothConnectRequirements].
    //
    // This used to be declared through `requirementsFor` for a rule narrowed to
    // a device and withheld from an "any device" rule, on the reasoning that an
    // unnarrowed rule matches the raw ACL broadcast and so needs nothing. The
    // reasoning was sound and the premise was false: there is no raw broadcast
    // to match. That rule could not fire for anybody, and the list said it
    // needed nothing, which is the failure this project exists to avoid rather
    // than one to build a feature on. A requirement that is always relevant is
    // also the one the list can afford to show.
    override val requirements = bluetoothConnectRequirements(Build.VERSION.SDK_INT)

    // Writes down the answer [bluetoothIdentifyBy] would have derived anyway, so
    // the editor draws the filter that actually matches rather than the schema
    // default, and saving ends the question for that rule for good. The engine
    // does not need this, because its readers resolve absence themselves; the
    // editor does, because a `shownWhen` condition can only read a stored value.
    override fun normalise(config: Map<String, String>): Map<String, String> =
        bluetoothNormalise(config)

    override fun create(config: Map<String, String>): Trigger =
        BluetoothConnectionTrigger(
            context = context,
            // Both read through the helpers above, which honour
            // CONFIG_IDENTIFY_BY. Absent values mean "any device", which is a
            // valid configuration.
            deviceAddress = bluetoothWantedAddress(config),
            nameFilter = bluetoothNameFilter(config),
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
