package app.phueber.trigly.core

import kotlinx.coroutines.flow.Flow

/**
 * Something that happened on the device and that a [Rule] can react to.
 *
 * [payload] carries trigger-specific detail (the address of the Bluetooth
 * device that connected, the package that posted a notification). Keys are
 * defined by the trigger that emits them and are documented on that trigger.
 */
data class TriggerEvent(
    val triggerType: String,
    val firedAtMillis: Long,
    val payload: Map<String, String> = emptyMap(),
)

/**
 * A configured source of [TriggerEvent]s.
 *
 * Implementations live in `:triggers`, never here. [events] is cold: it should
 * register its listener or receiver on collection and tear it down on
 * cancellation, so that a disabled rule holds no system resources.
 */
interface Trigger {
    fun events(): Flow<TriggerEvent>
}

/**
 * Builds a [Trigger] of one type from stored configuration.
 *
 * This is the plugin seam. A new trigger type ships a new [TriggerFactory] and
 * adds it to its own module's factory list — adding one must not require
 * editing `:core` or any sibling trigger.
 */
interface TriggerFactory {
    /** Stable identifier, persisted in rules. Renaming it breaks saved rules. */
    val type: String

    fun create(config: Map<String, String>): Trigger
}
