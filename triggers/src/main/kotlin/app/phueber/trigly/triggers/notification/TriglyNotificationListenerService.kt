package app.phueber.trigly.triggers.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Receives notifications from the system and republishes them on
 * [NotificationEvents].
 *
 * Deliberately thin. The system binds and unbinds this at will and kills it if a
 * callback is slow, so it does no matching, no rule evaluation and no I/O —
 * every callback flattens its argument and hands it to a non-blocking bus.
 * Filtering happens in the triggers, off this thread.
 *
 * Requires the user to grant notification access in system settings; see
 * `SpecialAccessKind.NOTIFICATION_LISTENER`.
 */
class TriglyNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationEvents.attach(this)
        NotificationEvents.setInterruptionFilter(currentInterruptionFilter)
    }

    override fun onListenerDisconnected() {
        // The system unbinds and rebinds this freely — on an update, on low
        // memory, when the user toggles access. Dropping the reference here is
        // what keeps actions from calling into a dead service.
        NotificationEvents.detach()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val extras = notification.extras

        NotificationEvents.posted.publish(
            PostedNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                postedAtMillis = sbn.postTime,
                // Ongoing notifications are progress bars and media controls;
                // most rules want to ignore them.
                ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            )
        )
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        NotificationEvents.setInterruptionFilter(interruptionFilter)
    }
}
