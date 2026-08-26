package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec

/**
 * Fires when the Wi-Fi radio is enabled or disabled.
 *
 * This is the *adapter* state, not connectivity and not the SSID. Both of those
 * are separate triggers with heavier requirements — SSID needs location
 * permission from API 27 on, because it can be used to infer position. See
 * `docs/triggers.md`.
 *
 * The broadcast is sticky, so the current state replays on registration.
 * Intermediate ENABLING/DISABLING states are ignored rather than treated as
 * their end state, which would fire the rule early.
 */
class WifiStateTrigger(
    context: Context,
    private val onEnabled: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(WifiManager.WIFI_STATE_CHANGED_ACTION)
    override val suppressInitialState = true

    override fun read(intent: Intent): Reading? {
        val enabled = when (
            intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
        ) {
            WifiManager.WIFI_STATE_ENABLED -> true
            WifiManager.WIFI_STATE_DISABLED -> false
            else -> return null
        }

        val key = if (enabled) ENABLED else DISABLED
        return Reading(
            payload = mapOf(PAYLOAD_STATE to key),
            stateKey = key,
            emit = enabled == onEnabled,
        )
    }

    // WifiManager answers directly what the broadcast can only report a change
    // to — no need to wait for the next transition.
    override suspend fun currentlyHolds(): Boolean? = runCatching {
        appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
    }.getOrNull()?.let { it == onEnabled }

    companion object {
        const val TYPE = "wifi_state"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val ENABLED = "enabled"
        const val DISABLED = "disabled"
    }
}

class WifiStateTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = WifiStateTrigger.TYPE

    override val displayName = "Wi-Fi radio"
    override val category = Category.RADIOS

    override val configFields = listOf(
        stateChoice(
            label = "Fires when Wi-Fi is",
            onValue = "enabled", onLabel = "turned on",
            offValue = "disabled", offLabel = "turned off",
            help = "This trigger watches the Wi-Fi radio, not the connection to a " +
                "network. Matching a network name needs location permission.",
        ),
    )

    override val supportsCondition = true

    override val variables = listOf(
        VariableSpec(
            key = WifiStateTrigger.PAYLOAD_STATE,
            label = "State",
            kind = VariableKind.STATE,
            sample = WifiStateTrigger.ENABLED,
            help = "One of '${WifiStateTrigger.ENABLED}' or '${WifiStateTrigger.DISABLED}'.",
        ),
    )

    override fun create(config: Map<String, String>): Trigger = WifiStateTrigger(
        context = context,
        onEnabled = parseTarget(
            config = config,
            key = WifiStateTrigger.CONFIG_STATE,
            onWord = WifiStateTrigger.ENABLED,
            offWord = WifiStateTrigger.DISABLED,
        ),
    )
}
