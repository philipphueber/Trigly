package app.phueber.trigly.triggers.accessibility

import app.phueber.trigly.triggers.ServiceEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/** One accessibility event, flattened to what a rule can match on. */
data class UiEvent(
    val eventType: Int,
    val packageName: String?,
    val className: String?,
    val text: String?,
    val atMillis: Long,
)

/**
 * Process-wide bridge between [TriglyAccessibilityService] and the triggers
 * that read it.
 */
object AccessibilityEvents {

    val ui = ServiceEventBus<UiEvent>()

    /**
     * The bound service, for the one action that has to call back into it.
     *
     * A weak reference for the same reason [app.phueber.trigly.triggers.notification.NotificationEvents]
     * holds one: the service is a `Context`, the framework owns its lifetime, and
     * a strong static reference would keep a destroyed instance alive if [detach]
     * were ever missed. Volatile because the framework's callbacks and the
     * engine's coroutines are different threads.
     *
     * Reading the tree is *not* what this is for — triggers get events from the
     * bus. It exists so `press_notification_button`'s screen fallback can reach
     * the shade, which is the only way to touch a button the notification API
     * does not expose.
     */
    @Volatile
    private var serviceRef: WeakReference<TriglyAccessibilityService>? = null

    val service: TriglyAccessibilityService? get() = serviceRef?.get()

    fun attach(service: TriglyAccessibilityService) {
        serviceRef = WeakReference(service)
        ui.setConnected(true)
    }

    fun detach() {
        serviceRef = null
        ui.setConnected(false)
    }

    /**
     * Whether an input-method window is showing. Null until the service has
     * looked, which is not the same as "no keyboard" — a trigger must not treat
     * unknown as false.
     */
    private val _keyboardVisible = MutableStateFlow<Boolean?>(null)
    val keyboardVisible: StateFlow<Boolean?> = _keyboardVisible.asStateFlow()

    fun setKeyboardVisible(visible: Boolean) {
        _keyboardVisible.value = visible
    }
}
