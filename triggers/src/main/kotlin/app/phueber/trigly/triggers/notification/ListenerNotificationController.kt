package app.phueber.trigly.triggers.notification

import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.NotificationButton
import app.phueber.trigly.core.NotificationController

/**
 * Implements [NotificationController] over the live listener service.
 *
 * Holds no reference of its own — it reads the current service from
 * [NotificationEvents] on every call, so it stays correct across the unbind and
 * rebind cycles the system puts the service through. A controller cached at
 * assembly time would otherwise pin the first service instance forever.
 */
class ListenerNotificationController : NotificationController {

    override val isConnected: Boolean get() = NotificationEvents.service != null

    /**
     * Flattens the live notifications into Android-free snapshots.
     *
     * `getSemanticAction()` is API 28, so below that every button reports no
     * meaning and matching falls back to the label — which is the honest outcome
     * rather than a shim, since the platform genuinely has nothing to offer there.
     *
     * A `SecurityException` is possible on every call, because notification access
     * can be revoked between the picker opening and the action running. It reads
     * as "nothing posted" here; [isConnected] is how a caller distinguishes the
     * two, and throwing would make an ordinary revocation crash a rule.
     */
    override fun activeNotifications(): List<ActiveNotification> {
        val service = NotificationEvents.service ?: return emptyList()

        return runCatching {
            service.activeNotifications.orEmpty().map { sbn ->
                val notification = sbn.notification
                val extras = notification?.extras
                ActiveNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    postedAtMillis = sbn.postTime,
                    ongoing = notification != null &&
                        (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                    // Null, not empty, when a notification has no buttons.
                    buttons = notification?.actions.orEmpty().mapIndexed { index, action ->
                        NotificationButton(
                            index = index,
                            label = action?.title?.toString(),
                            semanticAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                action?.semanticAction
                            } else {
                                null
                            },
                            // A reply box. Firing this intent without text does
                            // not send a reply, so it has to be visible.
                            takesText = action?.remoteInputs?.isNotEmpty() == true,
                        )
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun dismiss(key: String): ActionResult {
        val service = NotificationEvents.service ?: return notConnected()

        return try {
            service.cancelNotification(key)
            ActionResult.Success()
        } catch (denied: SecurityException) {
            ActionResult.Failure("Notification access was revoked.", denied)
        }
    }

    override fun triggerActionButton(key: String, actionIndex: Int): ActionResult =
        when (val found = buttonIntent(key, actionIndex)) {
            is ButtonLookup.Missing -> found.result
            is ButtonLookup.Found -> send(found.pending)
        }

    override fun captureActionButton(key: String, actionIndex: Int, `as`: String): ActionResult {
        val name = `as`.trim()
        if (name.isEmpty()) {
            return ActionResult.Failure("A captured button needs a name to keep it under.")
        }

        return when (val found = buttonIntent(key, actionIndex)) {
            is ButtonLookup.Missing -> found.result
            is ButtonLookup.Found -> {
                CapturedButtons.keep(name, found.pending)
                ActionResult.Success()
            }
        }
    }

    override fun pressCaptured(name: String): ActionResult {
        val wanted = name.trim()
        if (wanted.isEmpty()) {
            return ActionResult.Failure("This action has no captured button named.")
        }

        // Deliberately not routed through the listener service. A captured
        // button is a token this process already holds, so pressing it needs
        // neither notification access nor the notification it came from. That is
        // the whole reason capturing is worth doing.
        val pending = CapturedButtons.get(wanted) ?: return ActionResult.Failure(
            "Nothing is captured under '$wanted'. Either the rule that captures " +
                "it has not run yet, or Trigly restarted since it did: a captured " +
                "button cannot be saved and does not survive a restart."
        )

        return send(pending)
    }

    /**
     * The one place a `PendingIntent` is sent, so the three callers report the
     * same three outcomes in the same words.
     */
    private fun send(pending: PendingIntent): ActionResult = try {
        pending.send()
        ActionResult.Success()
    } catch (cancelled: PendingIntent.CanceledException) {
        // The owning app withdrew the intent. Common once a notification is
        // stale, and not something the user did wrong. Loud rather than silent:
        // `CapturedButtonOutlivesDismissalTest` pins that `send` throws here
        // instead of quietly doing nothing, which is what lets this be reported.
        ActionResult.Failure("The app withdrew that button.", cancelled)
    }

    /** What [buttonIntent] found, or the failure to report instead. */
    private sealed interface ButtonLookup {
        data class Found(val pending: PendingIntent) : ButtonLookup

        data class Missing(val result: ActionResult) : ButtonLookup
    }

    /**
     * The button at [actionIndex] of the live notification [key], or why not.
     *
     * Shared by pressing and capturing because they differ only in what they do
     * with the token: both need notification access, both need the notification
     * to still be listed, and both have to tell a missing notification apart
     * from a missing button apart from a button with nothing behind it. Two
     * copies of that would drift, and the failure text is the product here.
     */
    private fun buttonIntent(key: String, actionIndex: Int): ButtonLookup {
        val service = NotificationEvents.service
            ?: return ButtonLookup.Missing(notConnected())

        val notification = try {
            service.activeNotifications?.firstOrNull { it.key == key }
        } catch (denied: SecurityException) {
            return ButtonLookup.Missing(
                ActionResult.Failure("Notification access was revoked.", denied)
            )
        } ?: return ButtonLookup.Missing(
            ActionResult.Failure(
                "There is no active notification with key '$key'. " +
                    "It may already be dismissed."
            )
        )

        // A notification with no buttons has a null actions array, not an empty one.
        val actions = notification.notification?.actions
        if (actions == null || actionIndex !in actions.indices) {
            return ButtonLookup.Missing(
                ActionResult.Failure(
                    "That notification has ${actions?.size ?: 0} buttons. " +
                        "Index $actionIndex does not exist."
                )
            )
        }

        val pending = actions[actionIndex].actionIntent
            ?: return ButtonLookup.Missing(
                ActionResult.Failure("Button $actionIndex has nothing to fire.")
            )

        return ButtonLookup.Found(pending)
    }

    private fun notConnected(): ActionResult = ActionResult.Failure(
        "Notification access is not granted, or the listener is not bound yet."
    )
}
