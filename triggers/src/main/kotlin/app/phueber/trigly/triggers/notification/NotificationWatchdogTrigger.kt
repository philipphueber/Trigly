package app.phueber.trigly.triggers.notification

import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentTool
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.triggers.Category
import app.phueber.trigly.triggers.packageFilter
import app.phueber.trigly.triggers.stateChoice
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
 * **This is only as alive as Trigly is**, and a dead watchdog reports "all
 * fine" by saying nothing — the one failure mode a watchdog must not have. The
 * engine's foreground service (`EngineService` in `:ui`) is what moves that
 * from routine to unlikely: the process now has the strongest survival claim
 * Android offers. It is still not a guarantee. A force-stop ends both apps, and
 * so does an OEM battery manager that disregards the promise.
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
     * Null when there is no way to know — access is off, the listener has not
     * bound yet, or the call throws. What "no information" should mean depends on
     * who is asking, so this stays neutral and lets the two callers narrow it
     * differently: [isPresent] folds it to false, because the watchdog should
     * complain when it cannot see, not assume the best; [currentlyHolds] keeps it
     * as null, because a condition must not report the watched notification
     * absent just because the listener is unbound — that would be a rule failing
     * closed with nothing to say why.
     */
    private fun isPresentOrNull(): Boolean? {
        val service = NotificationEvents.service ?: return null

        val active = runCatching { service.activeNotifications }.getOrNull() ?: return null

        return active.any { notification ->
            notification.packageName == packageName &&
                (!requireOngoing || notification.isOngoing)
        }
    }

    private fun isPresent(): Boolean = isPresentOrNull() ?: false

    /**
     * The edge fires on absence; the passive form asks the opposite question —
     * "is the watched notification present" — using the exact same presence
     * check the poll loop already relies on, so the two cannot disagree about
     * what "present" means.
     */
    override suspend fun currentlyHolds(): Boolean? = isPresentOrNull()

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

    override val displayName = "App's notification goes missing"
    override val category = Category.NOTIFICATIONS
    override val supportsCondition = true

    override val configFields = listOf(
        packageFilter(
            label = "Watch this app",
            blankMeaning = null,
            required = true,
            help = "The app whose ongoing notification should always be present.",
        ),
        ConfigField.Duration(
            key = NotificationWatchdogTrigger.CONFIG_ABSENCE_MILLIS,
            label = "Alert after it has been gone for",
            defaultMillis = NotificationWatchdogTrigger.DEFAULT_ABSENCE_MILLIS,
            preferred = DurationUnit.MINUTES,
        ),
        ConfigField.Duration(
            key = NotificationWatchdogTrigger.CONFIG_POLL_MILLIS,
            label = "Check every",
            defaultMillis = NotificationWatchdogTrigger.DEFAULT_POLL_MILLIS,
            preferred = DurationUnit.MINUTES,
            help = "Must not be longer than the absence window, or alerts arrive late.",
        ),
        ConfigField.Flag(
            key = NotificationWatchdogTrigger.CONFIG_REQUIRE_ONGOING,
            label = "Only count ongoing notifications",
            default = true,
            help = "An always-on app keeps an ongoing notification. Turn this off " +
                "to watch for any notification from the app instead.",
        ),
    )

    override val warning: String =
        "If the app's notification channel is blocked rather than silenced, Android " +
            "hides it from Trigly too and this reports 'never seen'. Set the channel " +
            "to Silent instead. Also note Trigly itself can be killed, in which case " +
            "the watchdog stops without telling you."

    override val requirements = listOf(
        ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_LISTENER),
    )

    // Its filters are written against what a notification actually contains — a
    // package, a title, a piece of text — which is exactly what nobody can fill
    // in by guessing, and where a wrong guess yields a rule that silently never
    // fires. The inspector is the answer, so it is offered here rather than only
    // from the rule list, where you would have to know it exists.
    override fun toolsFor(config: Map<String, String>): List<ComponentTool> =
        listOf(ComponentTool.InspectNotifications)

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
