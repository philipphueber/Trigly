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

    // Same static getter the listener above reads back after being told
    // *something* changed — there is no separate "current value" API to
    // disagree with it. Wrapped defensively; nothing in the platform docs
    // promises this can never throw.
    override suspend fun currentlyHolds(): Boolean? =
        runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrNull()
            ?.let { it == onEnabled }

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

    override val displayName = "Auto-sync setting"
    override val category = Category.DEVICE

    override val configFields = listOf(
        stateChoice("Fires when auto-sync is", "enabled", "turned on", "disabled", "turned off"),
    )

    override val supportsCondition = true

    override fun create(config: Map<String, String>): Trigger = AutoSyncTrigger(
        onEnabled = parseTarget(
            config = config,
            key = AutoSyncTrigger.CONFIG_STATE,
            onWord = AutoSyncTrigger.ENABLED,
            offWord = AutoSyncTrigger.DISABLED,
        ),
    )
}
