package app.phueber.trigly.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * What a subclass makes of one received broadcast.
 *
 * @param payload trigger-specific detail for the emitted [TriggerEvent].
 * @param stateKey the distinct state this reading represents; equal consecutive
 *   keys collapse to nothing. Null for sources that are already edge-shaped.
 * @param emit false when the reading should update the tracked state but not
 *   itself produce an event — how a threshold trigger stays armed. A battery
 *   trigger set to "below 20%" tracks the "above" state so that dropping below
 *   again later still fires, without emitting on the way up.
 */
data class Reading(
    val payload: Map<String, String> = emptyMap(),
    val stateKey: String? = null,
    val emit: Boolean = true,
)

/**
 * Base for every trigger driven by a system broadcast.
 *
 * Handles the parts that are identical and easy to get wrong: registering on
 * collection with `RECEIVER_NOT_EXPORTED`, unregistering on cancellation, and
 * collapsing repeated or replayed states via [StateTracker].
 *
 * Note these receivers are registered at *runtime*, not in the manifest. Since
 * API 26 most implicit broadcasts cannot be declared in a manifest at all, so
 * runtime registration is the only option — which means the hosting process must
 * be alive to receive them. Until the engine runs in a foreground service (see
 * the TODO in `AppContainer`), these triggers stop when the process dies.
 */
abstract class BroadcastTrigger(
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    /** The [TriggerEvent.triggerType] to stamp on emitted events. */
    protected abstract val eventType: String

    /** Intent actions to listen for. Registering only what is needed keeps wakeups down. */
    protected abstract val actions: List<String>

    /** True for sticky broadcasts, which replay current state on registration. */
    protected open val suppressInitialState: Boolean = false

    /** Return null to ignore this broadcast entirely. */
    protected abstract fun read(intent: Intent): Reading?

    final override fun events(): Flow<TriggerEvent> = callbackFlow {
        val tracker = StateTracker(suppressInitialState)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(received: Context?, intent: Intent?) {
                val reading = intent?.let { runCatching { read(it) }.getOrNull() } ?: return
                if (!tracker.accept(reading.stateKey)) return
                if (!reading.emit) return
                trySend(TriggerEvent(eventType, now(), reading.payload))
            }
        }

        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            // These are protected system broadcasts; nothing else can reach us.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose { context.unregisterReceiver(receiver) }
    }

    protected val appContext: Context get() = context
}
