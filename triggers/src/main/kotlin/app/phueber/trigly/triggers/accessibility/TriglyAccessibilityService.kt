package app.phueber.trigly.triggers.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Republishes accessibility events on [AccessibilityEvents].
 *
 * **Read before extending.** This is the most invasive permission Android has:
 * granted, it can observe the content of every app on the device. Two rules
 * follow from that and are not negotiable:
 *
 *  - Nothing here is persisted, logged, or sent anywhere. Events are flattened
 *    to a few fields and handed to an in-memory bus that only local triggers
 *    read.
 *  - The service is declared but does nothing until the user enables it in
 *    system settings, and it should be presented as optional.
 *
 * Google restricts accessibility-API use on Play to genuine accessibility
 * purposes, and automation apps have been removed for it. See
 * `docs/triggers.md` — the distribution question is unresolved.
 *
 * Like the notification listener, this stays thin: flatten and publish, never
 * evaluate rules on the service's callback thread.
 */
class TriglyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // attach() rather than setConnected(true): the instance is also what the
        // screen-press fallback needs, and the two must never disagree about
        // whether a service is available.
        AccessibilityEvents.attach(this)
        refreshKeyboardVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        AccessibilityEvents.ui.publish(
            UiEvent(
                eventType = event.eventType,
                packageName = event.packageName?.toString(),
                className = event.className?.toString(),
                text = event.text.takeIf { it.isNotEmpty() }?.joinToString(" "),
                atMillis = System.currentTimeMillis(),
            )
        )

        // Window changes are the only signal that the keyboard may have moved.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            refreshKeyboardVisibility()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityEvents.detach()
        return super.onUnbind(intent)
    }

    /**
     * Best effort. Android exposes no "is the keyboard up" API, so this looks
     * for an input-method window — which needs `flagRetrieveInteractiveWindows`
     * and is still not reliable across every keyboard and OEM.
     */
    private fun refreshKeyboardVisibility() {
        val visible = runCatching {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrNull() ?: return

        AccessibilityEvents.setKeyboardVisible(visible)
    }
}
