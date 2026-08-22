package app.phueber.trigly.triggers

import android.content.ContentResolver
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Fires when the master auto-sync setting is switched on or off.
 *
 * The cheapest trigger in Tier 2 and arguably Tier 1: no permission, no service,
 * no manifest entry. The listener reports only *that* a sync setting changed, so
 * the current value is read back and compared — same edge-detection problem the
 * broadcast triggers have, solved locally because this is not a broadcast.
 */
class AutoSyncTrigger(
    private val onEnabled: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = callbackFlow {
        var last = ContentResolver.getMasterSyncAutomatically()

        val handle = ContentResolver.addStatusChangeListener(
            ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS
        ) {
            val current = ContentResolver.getMasterSyncAutomatically()
            if (current != last) {
                last = current
                if (current == onEnabled) {
                    trySend(
                        TriggerEvent(
                            triggerType = TYPE,
                            firedAtMillis = now(),
                            payload = mapOf(
                                PAYLOAD_STATE to if (current) ENABLED else DISABLED
                            ),
                        )
                    )
                }
            }
        }

        awaitClose { ContentResolver.removeStatusChangeListener(handle) }
    }

    companion object {
        const val TYPE = "auto_sync"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val ENABLED = "enabled"
        const val DISABLED = "disabled"
    }
}

class AutoSyncTriggerFactory : TriggerFactory {
    override val type = AutoSyncTrigger.TYPE

    override fun create(config: Map<String, String>): Trigger = AutoSyncTrigger(
        onEnabled = parseTarget(
            config = config,
            key = AutoSyncTrigger.CONFIG_STATE,
            onWord = AutoSyncTrigger.ENABLED,
            offWord = AutoSyncTrigger.DISABLED,
        ),
    )
}
