package app.phueber.trigly.triggers.notification

import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fires when an app's persistent notification has been gone for too long —
 * a watchdog for an always-on app whose foreground service the system may have
 * killed.
 *
 * **Why this polls rather than listens.** An ongoing notification is posted once
 * and then sits there, sometimes for days, with no further callback. Treating
 * "no `onNotificationPosted` lately" as absence would fire constantly on a
 * perfectly healthy app. Presence can only be established by asking what is
 * *currently* active, so that is what each tick does.
 *
 * **What it cannot see.** If the target notification's channel is *blocked*
 * (importance "none") rather than merely silenced, Android drops the
 * notification before any listener receives it, and this trigger will never see
 * it even while the app is perfectly healthy. That case surfaces as
 * [WatchdogAlert.NEVER_SEEN] rather than as a false all-clear — see
 * [AbsenceWatchdog].
 *
 * **This is only as alive as Trigly is.** The engine currently runs in the
 * application scope, so if the system kills Trigly the watchdog dies silently
 * alongside the app it is watching. Until the engine has a foreground service
 * this is a useful signal, not a guarantee.
 */
class NotificationWatchdogTrigger(
    private val packageName: String,
    private val requireOngoing: Boolean,
    private val absenceMillis: Long,
    private val pollMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = flow {
        val watchdog = AbsenceWatchdog(startedAtMillis = now(), absenceMillis = absenceMillis)

        while (true) {
            // Checked before the first sleep so a notification that is already
            // present when the rule starts counts immediately, rather than
            // looking absent for one poll interval.
            if (isPresent()) watchdog.onSeen(now())

            watchdog.onTick(now())?.let { alert ->
                emit(
                    TriggerEvent(
                        triggerType = TYPE,
                        firedAtMillis = now(),
                        payload = mapOf(
                            PAYLOAD_PACKAGE to packageName,
                            PAYLOAD_REASON to alert.name.lowercase(),
                        ),
                    )
                )
            }

            delay(pollMillis)
        }
    }

    /**
     * Null service means notification access is off or the listener is not bound
     * yet. Treated as "not present", which is the safe reading: the watchdog
     * should complain when it cannot see, not assume the best.
     */
    private fun isPresent(): Boolean {
        val service = NotificationEvents.service ?: return false

        val active = runCatching { service.activeNotifications }.getOrNull() ?: return false

        return active.any { notification ->
            notification.packageName == packageName &&
                (!requireOngoing || notification.isOngoing)
        }
    }

    companion object {
        const val TYPE = "notification_watchdog"
        const val CONFIG_PACKAGE = "package"
        const val CONFIG_ABSENCE_MILLIS = "absenceMillis"
        const val CONFIG_POLL_MILLIS = "pollMillis"
        const val CONFIG_REQUIRE_ONGOING = "requireOngoing"

        const val PAYLOAD_PACKAGE = "package"
        const val PAYLOAD_REASON = "reason"

        const val DEFAULT_ABSENCE_MILLIS = 5 * 60_000L
        const val DEFAULT_POLL_MILLIS = 60_000L
    }
}

class NotificationWatchdogTriggerFactory : TriggerFactory {
    override val type = NotificationWatchdogTrigger.TYPE

    override val requirements = listOf(
        ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_LISTENER),
    )

    override fun create(config: Map<String, String>): Trigger {
        val packageName = config[NotificationWatchdogTrigger.CONFIG_PACKAGE]
            ?: error("$type needs '${NotificationWatchdogTrigger.CONFIG_PACKAGE}'")

        val absence = config[NotificationWatchdogTrigger.CONFIG_ABSENCE_MILLIS]?.toLongOrNull()
            ?: NotificationWatchdogTrigger.DEFAULT_ABSENCE_MILLIS
        val poll = config[NotificationWatchdogTrigger.CONFIG_POLL_MILLIS]?.toLongOrNull()
            ?: NotificationWatchdogTrigger.DEFAULT_POLL_MILLIS

        require(absence > 0) { "absenceMillis must be positive, was $absence" }
        require(poll > 0) { "pollMillis must be positive, was $poll" }
        // Polling less often than the absence window would delay every alarm by
        // up to a full poll interval, which is not what the user asked for.
        require(poll <= absence) {
            "pollMillis ($poll) must not exceed absenceMillis ($absence), or alerts " +
                "would arrive late"
        }

        return NotificationWatchdogTrigger(
            packageName = packageName,
            requireOngoing = config[NotificationWatchdogTrigger.CONFIG_REQUIRE_ONGOING]
                ?.toBoolean() ?: true,
            absenceMillis = absence,
            pollMillis = poll,
        )
    }
}
