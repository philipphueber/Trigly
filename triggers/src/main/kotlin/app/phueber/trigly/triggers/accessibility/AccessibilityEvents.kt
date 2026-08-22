package app.phueber.trigly.triggers.accessibility

import app.phueber.trigly.triggers.ServiceEventBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
