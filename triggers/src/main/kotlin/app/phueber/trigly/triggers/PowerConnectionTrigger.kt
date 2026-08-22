package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/**
 * Fires when the charger is plugged in or unplugged.
 *
 * These two broadcasts are already edge-shaped — receiving one *is* the event —
 * so there is no state to deduplicate, and only the chosen action is registered.
 */
class PowerConnectionTrigger(
    context: Context,
    private val onConnect: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE

    override val actions = listOf(
        if (onConnect) Intent.ACTION_POWER_CONNECTED else Intent.ACTION_POWER_DISCONNECTED
    )

    override fun read(intent: Intent) = Reading(
        payload = mapOf(PAYLOAD_STATE to if (onConnect) CONNECTED else DISCONNECTED),
    )

    companion object {
        const val TYPE = "power_connection"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val CONNECTED = "connected"
        const val DISCONNECTED = "disconnected"
    }
}

class PowerConnectionTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = PowerConnectionTrigger.TYPE

    override fun create(config: Map<String, String>): Trigger = PowerConnectionTrigger(
        context = context,
        onConnect = parseTarget(
            config = config,
            key = PowerConnectionTrigger.CONFIG_STATE,
            onWord = PowerConnectionTrigger.CONNECTED,
            offWord = PowerConnectionTrigger.DISCONNECTED,
        ),
    )
}
