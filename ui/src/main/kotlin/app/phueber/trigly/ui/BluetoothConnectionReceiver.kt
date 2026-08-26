package app.phueber.trigly.ui

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import app.phueber.trigly.triggers.BluetoothEvents
import app.phueber.trigly.triggers.bluetoothIdentify

/**
 * Brings the engine back for a Bluetooth connect or disconnect, the same way
 * [BootReceiver] brings it back for a restart.
 *
 * A device reconnecting after the phone has sat idle for a while is exactly
 * the case where the system has already killed Trigly's process for being
 * quiet. Registering a receiver only while a trigger collects, as
 * `BluetoothConnectionTrigger` used to, means exactly that connect finds
 * nothing listening: the fault is real, but the log for it lives in a process
 * that no longer exists to write one. This is the manifest receiver that
 * exists whether or not Trigly's process does, and [BluetoothEvents] is where
 * it leaves word of what it saw, for `BluetoothConnectionTrigger` to read
 * whichever way the race between this receiver and that trigger's own
 * collection happens to fall. See [BluetoothEvents] for how that stays exactly
 * one event per connect either way.
 *
 * `exported="true"` in the manifest is required for the same reason as
 * `BootReceiver`'s: the sender is the system, a different uid, and a
 * non-exported receiver only hears from its own app. Both ACL actions are
 * `protected-broadcast` in the framework manifest, so nothing but the system
 * can send them regardless.
 *
 * Only the two ACL actions are declared here, deliberately not the
 * profile-level broadcasts (`BluetoothA2dp`/`BluetoothHeadset`
 * `ACTION_CONNECTION_STATE_CHANGED`) that would say whether audio actually
 * routed. Both of those are public API and both need `BLUETOOTH_CONNECT`, but
 * neither is a protected broadcast and neither is exempt from the API 26
 * limits on what a manifest receiver may still hear in the background, so a
 * manifest receiver for either would simply never fire. The two ACL actions
 * are exempt for the opposite reason: AOSP's Bluetooth stack marks them
 * `FLAG_RECEIVER_INCLUDE_BACKGROUND` when it sends them, which is what makes
 * a manifest receiver the right ingress for these two and not for the rest of
 * what Bluetooth can report. An ACL link, an A2DP link and an HFP link are
 * three separate events; ACL fires first and says nothing about audio being
 * routed, which is what `BluetoothConnectionTrigger`'s own warning already
 * tells anyone configuring a condition on it. A second ingress exists for
 * presence rather than the raw link,
 * `CompanionDeviceManager.startObservingDevicePresence`, delivered to a bound
 * `CompanionDeviceService` rather than a broadcast; it is not built, and this
 * receiver is not where it would go.
 *
 * Deliberately not `ACTION_ACL_DISCONNECT_REQUESTED`: that fires when a
 * disconnection is about to be attempted, which is not the same event and can
 * still be followed by the device staying connected.
 *
 * **Starting the engine's foreground service from here is permitted, not
 * merely convenient.** From API 31, starting a foreground service from the
 * background is banned unless the app is exempt. AOSP's Bluetooth stack puts
 * the receiving app on a temporary exemption for exactly this broadcast:
 * `RemoteDevices.aclStateChangeCallback` sends both ACL intents through
 * `Utils.getTempBroadcastOptions()`, which calls
 * `setTemporaryAppAllowlist(..., TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
 * REASON_BLUETOOTH_BROADCAST, "")`. That allowlist entry is the platform
 * naming this exact use as intended, not a gap this receiver happens to fit
 * through.
 *
 * **Nothing slow happens here, and nothing ever should.** A foreground
 * service has five seconds from the moment it is asked to start to call
 * `startForeground`, and that clock is already running by the time
 * [onReceive] is entered. `BootReceiver` already states the rule this
 * follows: no check for "is any rule enabled" first, because that is a
 * database read, and a read that pushes `EngineService` past its own
 * `startForeground` call is a receiver that looks like it worked and a
 * service the system kills anyway. `EngineService.onCreate` already calls
 * `startForeground` before anything slower; recording the sighting and
 * starting the service is all this does, in that order, and the engine
 * answers "was any rule listening" itself, one emission later, the same way
 * it already does for a boot.
 *
 * A user's force-stop is out of scope and is not fixed by this, or by
 * anything else in the app: see `docs/todo.md`'s R1.
 *
 * `EngineService` claims `specialUse` and adds `location` when it can.
 * Whether it should also claim `connectedDevice`, the foreground service type
 * the platform added for this exact case, is a real question and is not
 * settled here.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = ACTIONS[intent.action] ?: return

        val device = IntentCompat.getParcelableExtra(
            intent,
            BluetoothDevice.EXTRA_DEVICE,
            BluetoothDevice::class.java,
        )
        val (address, name) = bluetoothIdentify(context, device)

        // Recorded before the engine is (re)started, and that order is the
        // whole mechanism: see [BluetoothEvents].
        BluetoothEvents.record(action, address, name, System.currentTimeMillis())

        EngineService.start(context)
    }

    private companion object {
        val ACTIONS = mapOf(
            BluetoothDevice.ACTION_ACL_CONNECTED to BluetoothEvents.Action.CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED to BluetoothEvents.Action.DISCONNECTED,
        )
    }
}
