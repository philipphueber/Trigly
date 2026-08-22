package app.phueber.trigly.triggers

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/**
 * Fires when the Bluetooth radio is turned on or off.
 *
 * Distinct from [BluetoothConnectionTrigger], which is about a device
 * connecting. Intermediate TURNING_ON/TURNING_OFF states are ignored so the rule
 * fires once the radio has actually settled.
 */
class BluetoothAdapterStateTrigger(
    context: Context,
    private val onEnabled: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(BluetoothAdapter.ACTION_STATE_CHANGED)

    override fun read(intent: Intent): Reading? {
        val enabled = when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
            BluetoothAdapter.STATE_ON -> true
            BluetoothAdapter.STATE_OFF -> false
            else -> return null
        }

        val key = if (enabled) ENABLED else DISABLED
        return Reading(
            payload = mapOf(PAYLOAD_STATE to key),
            stateKey = key,
            emit = enabled == onEnabled,
        )
    }

    companion object {
        const val TYPE = "bluetooth_adapter_state"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val ENABLED = "enabled"
        const val DISABLED = "disabled"
    }
}

class BluetoothAdapterStateTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = BluetoothAdapterStateTrigger.TYPE

    // Receiving ACTION_STATE_CHANGED requires BLUETOOTH_CONNECT from API 31.
    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.BLUETOOTH_CONNECT),
    )

    override fun create(config: Map<String, String>): Trigger = BluetoothAdapterStateTrigger(
        context = context,
        onEnabled = parseTarget(
            config = config,
            key = BluetoothAdapterStateTrigger.CONFIG_STATE,
            onWord = BluetoothAdapterStateTrigger.ENABLED,
            offWord = BluetoothAdapterStateTrigger.DISABLED,
        ),
    )
}
