package app.phueber.trigly.triggers.notification

/** Why the watchdog is firing. The distinction matters more than it looks. */
enum class WatchdogAlert {
    /**
     * The notification was there and then stopped being there — the app's
     * service has most likely been killed. This is the alarm the user wants.
     */
    DISAPPEARED,

    /**
     * It was never seen at all, for the whole grace period. That is a
     * *misconfiguration*, not a dead service: wrong package name, or the
     * notification's channel is blocked rather than merely silenced, in which
     * case Android drops it before any listener sees it.
     *
     * Reported rather than swallowed, because a watchdog that quietly watches
     * nothing is worse than no watchdog — it produces silence, and silence
     * reads as "everything is fine".
     */
    NEVER_SEEN,
}

/**
 * Decides when an absence has lasted long enough to be worth alerting about.
 *
 * Pure and time-injected so the whole thing is unit-testable — a watchdog whose
 * logic is only exercisable by waiting five minutes on a device would never be
 * properly tested, and this is exactly the code that must not be wrong.
 *
 * Note what it does *not* do: it never treats "no post event received" as
 * absence. An ongoing notification is posted once and can sit there for hours
 * without a single further callback, so presence has to be established by
 * polling what is currently active. This class is fed [onSeen] by that poll.
 */
class AbsenceWatchdog(
    private val startedAtMillis: Long,
    private val absenceMillis: Long,
) {
    private var lastSeenMillis: Long? = null

    /** Suppresses repeat alarms until the notification comes back. */
    private var alerted = false

    val hasEverBeenSeen: Boolean get() = lastSeenMillis != null

    /** The notification is present right now. */
    fun onSeen(atMillis: Long) {
        lastSeenMillis = atMillis
        // Coming back re-arms the alarm, so a service that dies again alerts again.
        alerted = false
    }

    /** @return the alert to raise, or null if all is well or one was already raised. */
    fun onTick(nowMillis: Long): WatchdogAlert? {
        if (alerted) return null

        val since = lastSeenMillis ?: startedAtMillis
        if (nowMillis - since < absenceMillis) return null

        alerted = true
        return if (lastSeenMillis == null) WatchdogAlert.NEVER_SEEN else WatchdogAlert.DISAPPEARED
    }
}
