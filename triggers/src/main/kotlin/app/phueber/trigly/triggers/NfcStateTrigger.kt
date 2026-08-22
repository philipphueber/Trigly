package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/**
 * Fires when NFC is turned on or off.
 *
 * The action and extra are referenced as string literals rather than through
 * `NfcAdapter`: the constants have moved in and out of the public SDK across
 * versions, while the string values are stable and are what the framework
 * actually broadcasts.
 */
class NfcStateTrigger(
    context: Context,
    private val onEnabled: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(ACTION_ADAPTER_STATE_CHANGED)

    override fun read(intent: Intent): Reading? {
        val enabled = when (intent.getIntExtra(EXTRA_ADAPTER_STATE, -1)) {
            STATE_ON -> true
            STATE_OFF -> false
            else -> return null // TURNING_ON / TURNING_OFF: wait for the settled state
        }

        val key = if (enabled) ENABLED else DISABLED
        return Reading(
            payload = mapOf(PAYLOAD_STATE to key),
            stateKey = key,
            emit = enabled == onEnabled,
        )
    }

    companion object {
        const val TYPE = "nfc_state"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val ENABLED = "enabled"
        const val DISABLED = "disabled"

        private const val ACTION_ADAPTER_STATE_CHANGED =
            "android.nfc.action.ADAPTER_STATE_CHANGED"
        private const val EXTRA_ADAPTER_STATE = "android.nfc.extra.ADAPTER_STATE"
        private const val STATE_OFF = 1
        private const val STATE_ON = 3
    }
}

class NfcStateTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = NfcStateTrigger.TYPE

    override val displayName = "NFC"
    override val category = Category.RADIOS

    override val configFields = listOf(
        stateChoice("Fires when NFC is", "enabled", "turned on", "disabled", "turned off"),
    )

    override val requirements = listOf(
        ComponentRequirement.SystemFeature(PackageManager.FEATURE_NFC),
    )

    override fun create(config: Map<String, String>): Trigger = NfcStateTrigger(
        context = context,
        onEnabled = parseTarget(
            config = config,
            key = NfcStateTrigger.CONFIG_STATE,
            onWord = NfcStateTrigger.ENABLED,
            offWord = NfcStateTrigger.DISABLED,
        ),
    )
}
