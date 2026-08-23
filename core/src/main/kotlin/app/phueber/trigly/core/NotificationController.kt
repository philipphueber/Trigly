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
     * Everything currently on screen, with its buttons.
     *
     * Serves two callers with the same snapshot. The editor's picker uses it so a
     * button can be *chosen* rather than counted — nobody knows that Snooze is
     * index 1 — and the acting action uses it to find its target and resolve
     * which button that choice now refers to.
     *
     * Empty when the listener is not bound, which is indistinguishable from
     * "nothing is posted" and deliberately so: [isConnected] is how a caller asks
     * the other question, and conflating them into an exception would make the
     * common case throw.
     */
    fun activeNotifications(): List<ActiveNotification>

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

        override fun activeNotifications(): List<ActiveNotification> = emptyList()

        override fun dismiss(key: String): ActionResult =
            ActionResult.Failure("notification access is not available")

        override fun triggerActionButton(key: String, actionIndex: Int): ActionResult =
            ActionResult.Failure("notification access is not available")
    }
}
