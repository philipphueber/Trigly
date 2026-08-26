package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec

/**
 * Fires when the screen turns on or off.
 *
 * `ACTION_SCREEN_ON`/`OFF` have never been manifest-registerable, so runtime
 * registration is not a limitation here, it is the only mechanism. Note "screen
 * on" is not "unlocked" — for that, `ACTION_USER_PRESENT` is the broadcast, left
 * for its own trigger.
 */
class ScreenStateTrigger(
    context: Context,
    private val onScreenOn: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE

    override val actions = listOf(
        if (onScreenOn) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF
    )

    override fun read(intent: Intent) = Reading(
        payload = mapOf(PAYLOAD_STATE to if (onScreenOn) ON else OFF),
    )

    // No sticky broadcast exists for ACTION_SCREEN_ON/OFF, so the current state
    // has to come from the manager itself rather than a replayed Intent.
    // isInteractive is the platform's own name for the same "screen is on" the
    // broadcasts report.
    override suspend fun currentlyHolds(): Boolean? {
        val interactive = runCatching {
            appContext.getSystemService(PowerManager::class.java)?.isInteractive
        }.getOrNull() ?: return null
        return interactive == onScreenOn
    }

    companion object {
        const val TYPE = "screen_state"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val ON = "on"
        const val OFF = "off"
    }
}

class ScreenStateTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = ScreenStateTrigger.TYPE

    override val displayName = "Screen on or off"
    override val category = Category.DEVICE

    override val configFields = listOf(
        stateChoice(
            label = "Fires when the screen turns",
            onValue = "on", onLabel = "on",
            offValue = "off", offLabel = "off",
            help = "Screen on is not the same as unlocked.",
        ),
    )

    override fun create(config: Map<String, String>): Trigger = ScreenStateTrigger(
        context = context,
        onScreenOn = parseTarget(
            config = config,
            key = ScreenStateTrigger.CONFIG_STATE,
            onWord = ScreenStateTrigger.ON,
            offWord = ScreenStateTrigger.OFF,
        ),
    )

    override val supportsCondition = true

    override val variables = listOf(
        VariableSpec(
            key = ScreenStateTrigger.PAYLOAD_STATE,
            label = "State",
            kind = VariableKind.STATE,
            sample = ScreenStateTrigger.ON,
            help = "One of '${ScreenStateTrigger.ON}' or '${ScreenStateTrigger.OFF}'.",
        ),
    )
}
