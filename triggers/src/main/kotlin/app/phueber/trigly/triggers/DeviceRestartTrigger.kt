package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fires once, when the engine has come up because the device restarted — or
 * because Trigly was updated, which kills the process the same way.
 *
 * The unusual shape here is that there is nothing to *wait* for. The boot
 * broadcast is what started the engine, so it has already been delivered before
 * any trigger can be collecting; [BootEvents] is where the manifest receiver
 * leaves the record, and this trigger's whole job is to read it at collection
 * time. That makes the flow a single-shot: it emits at most one event and
 * completes, rather than staying registered for a broadcast that will not come
 * again in this process's life.
 *
 * A completing flow is fine for the engine — the rule's collector simply ends —
 * and it is more honest than a flow that idles forever pretending it might fire.
 */
class DeviceRestartTrigger(
    private val reason: BootReason,
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = BootEvents.DEFAULT_WINDOW_MILLIS,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = flow {
        if (BootEvents.pending(now(), reason, windowMillis)) {
            emit(
                TriggerEvent(
                    triggerType = TYPE,
                    firedAtMillis = now(),
                    payload = mapOf(PAYLOAD_REASON to reason.configValue),
                )
            )
        }
    }

    companion object {
        const val TYPE = "device_restart"
        const val CONFIG_REASON = "reason"
        const val PAYLOAD_REASON = "reason"
    }
}

class DeviceRestartTriggerFactory : TriggerFactory {
    override val type = DeviceRestartTrigger.TYPE

    override val displayName = "Device restarted"
    override val category = Category.DEVICE

    override val configFields = listOf(
        ConfigField.Choice(
            key = DeviceRestartTrigger.CONFIG_REASON,
            label = "Fires after",
            options = listOf(
                ConfigField.Option(BootReason.RESTART.configValue, "a restart"),
                ConfigField.Option(BootReason.APP_UPDATED.configValue, "Trigly updating"),
            ),
            required = false,
            default = BootReason.RESTART.configValue,
            help = "Both events end the app's process without any action from you. " +
                "That is why they share one trigger with two settings.",
        ),
    )

    // Two things a rule author would otherwise discover the hard way, and the
    // location caveat is the one that has actually bitten: since Android 12 a
    // service started from the background — which a boot is — permanently loses
    // while-in-use location access, so a restart rule cannot be *combined* with
    // anything that reads a location.
    override val warning: String =
        "This trigger fires soon after Trigly restarts. It does not fire at the " +
            "exact instant the phone starts. Android decides the exact time. An " +
            "action that needs a location will not work in a rule that runs this " +
            "early. The phone may still need time to start."

    override fun create(config: Map<String, String>): Trigger = DeviceRestartTrigger(
        reason = when (config[DeviceRestartTrigger.CONFIG_REASON]) {
            null, BootReason.RESTART.configValue -> BootReason.RESTART
            BootReason.APP_UPDATED.configValue -> BootReason.APP_UPDATED
            else -> error(
                "${DeviceRestartTrigger.CONFIG_REASON} must be one of " +
                    BootReason.entries.joinToString { it.configValue } +
                    ", was '${config[DeviceRestartTrigger.CONFIG_REASON]}'"
            )
        },
    )
}
