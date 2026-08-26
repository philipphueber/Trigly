package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec

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

    // Unlike the broadcast action/extra above, NfcAdapter itself is the stable,
    // documented way to get the default adapter — null on a device with no NFC
    // hardware, which is a different fact from "NFC is off" and must not read as
    // either enabled or disabled.
    override suspend fun currentlyHolds(): Boolean? = runCatching {
        NfcAdapter.getDefaultAdapter(appContext)?.isEnabled
    }.getOrNull()?.let { it == onEnabled }

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

    override val supportsCondition = true

    override val variables = listOf(
        VariableSpec(
            key = NfcStateTrigger.PAYLOAD_STATE,
            label = "State",
            kind = VariableKind.STATE,
            sample = NfcStateTrigger.ENABLED,
            help = "One of '${NfcStateTrigger.ENABLED}' or '${NfcStateTrigger.DISABLED}'.",
        ),
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
