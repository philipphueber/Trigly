package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/** Fires when airplane mode is switched on or off. */
class AirplaneModeTrigger(
    context: Context,
    private val onEnabled: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(Intent.ACTION_AIRPLANE_MODE_CHANGED)

    override fun read(intent: Intent): Reading {
        val enabled = intent.getBooleanExtra(EXTRA_STATE, false)
        val key = if (enabled) ENABLED else DISABLED
        return Reading(
            payload = mapOf(PAYLOAD_STATE to key),
            stateKey = key,
            emit = enabled == onEnabled,
        )
    }

    // There is no manager for this one — it's a plain system setting, absent
    // only in the (unheard of) case a device ships without it.
    override suspend fun currentlyHolds(): Boolean? = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON)
    }.getOrNull()?.let { (it != 0) == onEnabled }

    companion object {
        const val TYPE = "airplane_mode"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val ENABLED = "enabled"
        const val DISABLED = "disabled"

        /** The documented extra name for this broadcast. */
        private const val EXTRA_STATE = "state"
    }
}

class AirplaneModeTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = AirplaneModeTrigger.TYPE

    override val displayName = "Airplane mode"
    override val category = Category.RADIOS

    override val configFields = listOf(
        stateChoice("Fires when airplane mode is", "enabled", "turned on", "disabled", "turned off"),
    )

    override val supportsCondition = true

    override fun create(config: Map<String, String>): Trigger = AirplaneModeTrigger(
        context = context,
        onEnabled = parseTarget(
            config = config,
            key = AirplaneModeTrigger.CONFIG_STATE,
            onWord = AirplaneModeTrigger.ENABLED,
            offWord = AirplaneModeTrigger.DISABLED,
        ),
    )
}
