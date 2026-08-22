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
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Fires when a Bluetooth device connects.
 *
 * [deviceAddress] narrows it to one device; null fires for any. The receiver is
 * registered on collection and torn down in [awaitClose], so a disabled rule
 * leaves no receiver behind — that is the contract every [Trigger] owes.
 */
class BluetoothConnectionTrigger(
    private val context: Context,
    private val deviceAddress: String?,
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

                // Reading address/name needs BLUETOOTH_CONNECT on API 31+. Without
                // it the getters throw, so degrade to an address-less event rather
                // than crashing the engine's collector.
                val address = if (context.hasBluetoothConnectPermission()) {
                    runCatching { device?.address }.getOrNull()
                } else {
                    null
                }

                if (deviceAddress != null && address != deviceAddress) return

                trySend(
                    TriggerEvent(
                        triggerType = TYPE,
                        firedAtMillis = now(),
                        payload = buildMap { address?.let { put(PAYLOAD_ADDRESS, it) } },
                    )
                )
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED),
            // A protected system broadcast, so nothing else can reach this receiver.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose { context.unregisterReceiver(receiver) }
    }

    companion object {
        const val TYPE = "bluetooth_connected"
        const val CONFIG_ADDRESS = "address"
        const val PAYLOAD_ADDRESS = "address"
    }
}

private fun Context.hasBluetoothConnectPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

class BluetoothConnectionTriggerFactory(
    private val context: Context,
) : TriggerFactory {
    override val type: String = BluetoothConnectionTrigger.TYPE

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
        )
}
