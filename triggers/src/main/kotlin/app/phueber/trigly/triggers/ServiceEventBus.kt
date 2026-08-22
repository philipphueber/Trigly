package app.phueber.trigly.triggers

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge from a system-instantiated service to the triggers that read it.
 *
 * `NotificationListenerService` and `AccessibilityService` are constructed by
 * the framework, not by us, so there is nowhere to inject a dependency and no
 * instance to hand to a trigger. A process-wide bus is the pragmatic answer:
 * the service publishes, triggers subscribe, and neither knows the other.
 *
 * Buffered and drop-oldest on overflow. Accessibility content events can arrive
 * in bursts of hundreds; blocking the service's callback thread to preserve
 * every one of them would risk the system declaring the service unresponsive
 * and unbinding it. Dropping stale UI events is the right trade.
 *
 * [connected] is the other half of the contract: a trigger whose service is not
 * bound is not "quiet", it is broken, and the UI needs to be able to say so.
 */
class ServiceEventBus<T> {

    private val _events = MutableSharedFlow<T>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<T> = _events.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Non-suspending: called from framework callbacks that must not block. */
    fun publish(value: T) {
        _events.tryEmit(value)
    }

    fun setConnected(connected: Boolean) {
        _connected.value = connected
    }
}
