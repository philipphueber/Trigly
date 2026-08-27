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
     * Keeps one of a notification's buttons so a rule can press it after the
     * notification is gone, under the name [as].
     *
     * **Why this exists, and it is not a convenience.** [triggerActionButton]
     * finds the notification by key in the live list, so it can only press a
     * button that is still on screen. Some buttons are only worth pressing
     * later: Digital Wellbeing's "Turn off for now" appears when Bedtime mode
     * starts, and the person who wants it pressed wants that at the moment they
     * pick the phone up, by which time the notification may well have been
     * swiped away.
     *
     * `CapturedButtonOutlivesDismissalTest` is why this is possible at all. A
     * `PendingIntent` is a token the system holds on behalf of the app that made
     * it, and its life is not tied to the notification that carried it: the test
     * captures a button, dismisses the notification the way a swipe does, and
     * the captured button still fires.
     *
     * **What a capture cannot survive is Trigly's own process ending.** A
     * `PendingIntent` cannot be written down. It is not a URI or an id; it is a
     * live token, so it cannot be put in a variable, stored in the database, or
     * rebuilt after a restart. A capture therefore lives in memory and dies with
     * the process, which the engine's foreground service makes uncommon but not
     * impossible. [pressCaptured] says so when it happens rather than failing
     * vaguely, and this is stated in the action's own caveat rather than left
     * for somebody to discover the first time it matters.
     *
     * Capturing the same [as] twice replaces the earlier one. A rule that
     * captures on every appearance of a notification is the ordinary case, and
     * the newest copy is the one the owning app has not rebuilt underneath.
     *
     * Defaulted, unlike the three methods above it, for the reason
     * `ComponentFactory.variables` is: there is one real implementation and a
     * handful of test fakes, and requiring every fake to answer a question it
     * has no opinion about is churn that hides the change worth reading. The
     * default is a refusal rather than a no-op, so a build that somehow reaches
     * it says so instead of appearing to keep a button it dropped.
     */
    fun captureActionButton(key: String, actionIndex: Int, `as`: String): ActionResult =
        ActionResult.Failure("This build cannot keep a notification button.")

    /**
     * Presses whatever [captureActionButton] last kept under [name].
     *
     * Three outcomes, each said as itself, because they are fixed in different
     * ways. Nothing captured under that name means the rule that captures has
     * not run, or Trigly restarted since it did. A captured button the owning
     * app has withdrawn throws `PendingIntent.CanceledException`, which is
     * reported as the app's doing rather than as Trigly failing. Anything else
     * is the send itself.
     */
    fun pressCaptured(name: String): ActionResult =
        ActionResult.Failure("This build cannot press a kept notification button.")

    /**
     * No-op implementation for assembling the app before, or without, the
     * listener service. Reports a clear failure rather than pretending to work.
     */
    companion object Unavailable : NotificationController {
        override val isConnected: Boolean = false

        override fun activeNotifications(): List<ActiveNotification> = emptyList()

        override fun dismiss(key: String): ActionResult =
            ActionResult.Failure("Notification access is not available.")

        override fun triggerActionButton(key: String, actionIndex: Int): ActionResult =
            ActionResult.Failure("Notification access is not available.")

        override fun captureActionButton(
            key: String,
            actionIndex: Int,
            `as`: String,
        ): ActionResult = ActionResult.Failure("Notification access is not available.")

        override fun pressCaptured(name: String): ActionResult =
            ActionResult.Failure("Notification access is not available.")
    }
}
