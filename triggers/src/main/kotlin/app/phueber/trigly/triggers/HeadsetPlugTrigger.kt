package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/**
 * Fires when a wired headset is plugged in or unplugged.
 *
 * `ACTION_HEADSET_PLUG` is **sticky**, so registering delivers the current state
 * immediately — hence [suppressInitialState]. Without it, enabling a rule while
 * headphones are already in would fire it at once.
 *
 * Wired only. Bluetooth audio arrives through the Bluetooth connection trigger.
 */
class HeadsetPlugTrigger(
    context: Context,
    private val onPlugged: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(Intent.ACTION_HEADSET_PLUG)
    override val suppressInitialState = true

    override fun read(intent: Intent): Reading? {
        val state = intent.getIntExtra(EXTRA_STATE, -1)
        if (state < 0) return null

        val plugged = state == 1
        val key = if (plugged) PLUGGED else UNPLUGGED
        return Reading(
            payload = mapOf(PAYLOAD_STATE to key),
            stateKey = key,
            emit = plugged == onPlugged,
        )
    }

    companion object {
        const val TYPE = "headset_plug"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val PLUGGED = "plugged"
        const val UNPLUGGED = "unplugged"

        /** `Intent.EXTRA_*` has no constant for this one; the key is "state". */
        private const val EXTRA_STATE = "state"
    }
}

class HeadsetPlugTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = HeadsetPlugTrigger.TYPE

    override fun create(config: Map<String, String>): Trigger = HeadsetPlugTrigger(
        context = context,
        onPlugged = parseTarget(
            config = config,
            key = HeadsetPlugTrigger.CONFIG_STATE,
            onWord = HeadsetPlugTrigger.PLUGGED,
            offWord = HeadsetPlugTrigger.UNPLUGGED,
        ),
    )
}
