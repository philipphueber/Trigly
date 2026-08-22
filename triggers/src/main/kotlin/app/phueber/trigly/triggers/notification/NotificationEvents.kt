package app.phueber.trigly.triggers.notification

import app.phueber.trigly.triggers.ServiceEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** One notification, flattened to the fields a rule can match on. */
data class PostedNotification(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAtMillis: Long,
    val ongoing: Boolean,
)

/**
 * Process-wide bridge between [TriglyNotificationListenerService] and the
 * triggers that read it. A singleton because the framework owns the service's
 * lifetime — see [ServiceEventBus].
 */
object NotificationEvents {

    val posted = ServiceEventBus<PostedNotification>()

    /**
     * The bound service, for actions that must call back into it.
     *
     * A weak reference on purpose: the service is a `Context`, and a strong
     * static reference would keep a destroyed one alive if [detach] were ever
     * missed. Volatile because the framework's callbacks and the engine's
     * coroutines are different threads.
     */
    @Volatile
    private var serviceRef: WeakReference<TriglyNotificationListenerService>? = null

    val service: TriglyNotificationListenerService? get() = serviceRef?.get()

    fun attach(service: TriglyNotificationListenerService) {
        serviceRef = WeakReference(service)
        posted.setConnected(true)
    }

    fun detach() {
        serviceRef = null
        posted.setConnected(false)
    }

    /**
     * Current Do Not Disturb filter, as `NotificationManager.INTERRUPTION_FILTER_*`.
     * A [StateFlow] rather than an event stream because DND is a state: a
     * trigger needs to know the value, not just that it changed.
     */
    private val _interruptionFilter = MutableStateFlow(FILTER_UNKNOWN)
    val interruptionFilter: StateFlow<Int> = _interruptionFilter.asStateFlow()

    fun setInterruptionFilter(filter: Int) {
        _interruptionFilter.value = filter
    }

    /** Before the listener connects there is no way to know the filter. */
    const val FILTER_UNKNOWN = 0
}
