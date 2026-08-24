package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/**
 * Fires when GPS (the device location toggle) is switched on or off.
 *
 * `PROVIDERS_CHANGED_ACTION` says *something* about providers changed but not
 * what, so the current state is read back from [LocationManager]. Checking
 * whether a provider is enabled needs no permission — only reading an actual
 * location does — which is what keeps this in Tier 1.
 */
class GpsProviderTrigger(
    context: Context,
    private val onEnabled: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(LocationManager.PROVIDERS_CHANGED_ACTION)

    override fun read(intent: Intent): Reading? {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null
        val enabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        val key = if (enabled) ENABLED else DISABLED
        return Reading(
            payload = mapOf(PAYLOAD_STATE to key),
            stateKey = key,
            emit = enabled == onEnabled,
        )
    }

    // Same manager call as read() above, reused directly for the condition case —
    // still no permission needed to ask, and still wrapped since some OEMs guard
    // it anyway.
    override suspend fun currentlyHolds(): Boolean? = runCatching {
        appContext.getSystemService(LocationManager::class.java)
            ?.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrNull()?.let { it == onEnabled }

    companion object {
        const val TYPE = "gps_state"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val ENABLED = "enabled"
        const val DISABLED = "disabled"
    }
}

class GpsProviderTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = GpsProviderTrigger.TYPE

    override val displayName = "Location services"
    override val category = Category.RADIOS

    override val configFields = listOf(
        stateChoice("Fires when GPS is", "enabled", "switched on", "disabled", "switched off"),
    )

    override val supportsCondition = true

    override fun create(config: Map<String, String>): Trigger = GpsProviderTrigger(
        context = context,
        onEnabled = parseTarget(
            config = config,
            key = GpsProviderTrigger.CONFIG_STATE,
            onWord = GpsProviderTrigger.ENABLED,
            offWord = GpsProviderTrigger.DISABLED,
        ),
    )
}
