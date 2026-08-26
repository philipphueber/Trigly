package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec

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

    // ACTION_HEADSET_PLUG is sticky, same as the battery broadcasts, so a null
    // receiver registration hands back the last plug state without waiting for
    // the next event.
    override suspend fun currentlyHolds(): Boolean? {
        val intent = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_HEADSET_PLUG))
        }.getOrNull() ?: return null
        val state = intent.getIntExtra(EXTRA_STATE, -1)
        if (state < 0) return null
        return (state == 1) == onPlugged
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

    override val displayName = "Wired headset"
    override val category = Category.DEVICE

    override val configFields = listOf(
        stateChoice(
            label = "Fires when a headset is",
            onValue = "plugged", onLabel = "plugged in",
            offValue = "unplugged", offLabel = "unplugged",
            help = "This trigger detects a wired headset only. For Bluetooth " +
                "headphones, use the Bluetooth device trigger.",
        ),
    )

    override fun create(config: Map<String, String>): Trigger = HeadsetPlugTrigger(
        context = context,
        onPlugged = parseTarget(
            config = config,
            key = HeadsetPlugTrigger.CONFIG_STATE,
            onWord = HeadsetPlugTrigger.PLUGGED,
            offWord = HeadsetPlugTrigger.UNPLUGGED,
        ),
    )

    override val supportsCondition = true

    override val variables = listOf(
        VariableSpec(
            key = HeadsetPlugTrigger.PAYLOAD_STATE,
            label = "State",
            kind = VariableKind.STATE,
            sample = HeadsetPlugTrigger.PLUGGED,
            help = "One of '${HeadsetPlugTrigger.PLUGGED}' or '${HeadsetPlugTrigger.UNPLUGGED}'.",
        ),
    )
}
