package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec

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

    // ACTION_POWER_CONNECTED/DISCONNECTED are edge-only, not sticky, so there is
    // nothing to register a null receiver against. ACTION_BATTERY_CHANGED is
    // sticky and carries EXTRA_PLUGGED, which is the same fact this trigger
    // watches for — a nonzero value means some charger is attached.
    override suspend fun currentlyHolds(): Boolean? {
        val intent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        if (plugged < 0) return null
        return (plugged != 0) == onConnect
    }

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

    override val displayName = "Charger"
    override val category = Category.POWER

    override val configFields = listOf(
        stateChoice("Fires when the charger is", "connected", "plugged in", "disconnected", "unplugged"),
    )

    override fun create(config: Map<String, String>): Trigger = PowerConnectionTrigger(
        context = context,
        onConnect = parseTarget(
            config = config,
            key = PowerConnectionTrigger.CONFIG_STATE,
            onWord = PowerConnectionTrigger.CONNECTED,
            offWord = PowerConnectionTrigger.DISCONNECTED,
        ),
    )

    override val supportsCondition = true

    override val variables = listOf(
        VariableSpec(
            key = PowerConnectionTrigger.PAYLOAD_STATE,
            label = "State",
            kind = VariableKind.STATE,
            sample = PowerConnectionTrigger.CONNECTED,
            help = "One of '${PowerConnectionTrigger.CONNECTED}' or " +
                "'${PowerConnectionTrigger.DISCONNECTED}'.",
        ),
    )
}
