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
            ActionResult.Success
        } catch (denied: SecurityException) {
            ActionResult.Failure("notification access was revoked", denied)
        }
    }

    override fun triggerActionButton(key: String, actionIndex: Int): ActionResult {
        val service = NotificationEvents.service ?: return notConnected()

        val notification = try {
            service.activeNotifications?.firstOrNull { it.key == key }
        } catch (denied: SecurityException) {
            return ActionResult.Failure("notification access was revoked", denied)
        } ?: return ActionResult.Failure(
            "no active notification with key '$key' — it may already be dismissed"
        )

        // A notification with no buttons has a null actions array, not an empty one.
        val actions = notification.notification?.actions
        if (actions == null || actionIndex !in actions.indices) {
            return ActionResult.Failure(
                "that notification has ${actions?.size ?: 0} buttons, " +
                    "so index $actionIndex does not exist"
            )
        }

        val pending: PendingIntent = actions[actionIndex].actionIntent
            ?: return ActionResult.Failure("button $actionIndex has nothing to fire")

        return try {
            pending.send()
            ActionResult.Success
        } catch (cancelled: PendingIntent.CanceledException) {
            // The owning app withdrew the intent — common once a notification is
            // stale, and not something the user did wrong.
            ActionResult.Failure("the app withdrew that button", cancelled)
        }
    }

    private fun notConnected(): ActionResult = ActionResult.Failure(
        "notification access is not granted, or the listener is not bound yet"
    )
}
