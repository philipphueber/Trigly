package app.phueber.trigly.core

/**
 * Control over other apps' notifications, for actions that need it.
 *
 * This interface exists to resolve a module problem rather than to abstract for
 * its own sake. Dismissing another app's notification requires the
 * `NotificationListenerService`, which lives in `:triggers` — but the actions
 * that want it live in `:actions`, and `:actions` must never depend on
 * `:triggers`. So `:core` declares the port, `:triggers` implements it over the
 * live service, and `:ui` — the one module that knows everything — wires the two
 * together. The same shape as the factory lists handed to [Registry].
 *
 * Every method returns [ActionResult] rather than throwing, because "the user
 * turned notification access off" is ordinary traffic here, not an exception.
 */
interface NotificationController {

    /** Whether the listener service is currently bound. */
    val isConnected: Boolean

    /**
     * Dismisses a notification by its `StatusBarNotification` key, as carried in
     * the `notification_posted` trigger's payload.
     */
    fun dismiss(key: String): ActionResult

    /**
     * Fires one of a notification's action buttons — "Reply", "Snooze", "Mark as
     * read" — by its position in the notification's own action list.
     */
    fun triggerActionButton(key: String, actionIndex: Int): ActionResult

    /**
     * No-op implementation for assembling the app before, or without, the
     * listener service. Reports a clear failure rather than pretending to work.
     */
    companion object Unavailable : NotificationController {
        override val isConnected: Boolean = false

        override fun dismiss(key: String): ActionResult =
            ActionResult.Failure("notification access is not available")

        override fun triggerActionButton(key: String, actionIndex: Int): ActionResult =
            ActionResult.Failure("notification access is not available")
    }
}
