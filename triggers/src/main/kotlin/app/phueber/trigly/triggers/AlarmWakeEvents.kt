package app.phueber.trigly.triggers

/**
 * How a durable wait's own alarm reaches a trigger once it has already fired.
 *
 * [AlarmManagerScheduler.waitForDurable] and [AlarmManagerScheduler.waitUntilDurable]
 * each arrange two alarms for the same instant: an `OnAlarmListener` bound to
 * this process, for the ordinary case that the process is still alive when
 * the wait is due, and a `PendingIntent` alarm aimed at `AlarmWakeReceiver`
 * in `:ui`, for the case `docs/todo.md`'s T17 records: the platform has
 * already deleted the listener alarm because the process holding it is gone.
 * Whichever fires first cancels the other; see `AlarmManagerScheduler`'s
 * KDoc.
 *
 * The listener form resumes the very same suspended call and needs nothing
 * from here. The receiver form starts a brand new collection of whichever
 * trigger asked for the wait, in a process that may be minutes old by the
 * time that collection begins, and [record] is how that fresh collection
 * learns a durable wait just fired somewhere, so it can treat "now" as
 * caught up rather than search forward past the very occurrence it was woken
 * for.
 *
 * **No identity is recorded for which wait fired**, unlike [BootEvents]'s
 * reason or [BluetoothEvents]'s address. A durable wait exists on
 * [IntervalTrigger] and [SolarTrigger] only, and each copes with "some
 * durable wait fired recently, not provably mine" the same honest way an
 * ordinary restart already forces it to. [IntervalTrigger] always restarts
 * its own count from now, kill or no kill, so it has no use for an identity
 * here at all. [SolarTrigger] is the one that needs [pending], and it needs
 * only a yes-or-no: widening its search a few minutes into the past when
 * *some* durable wait recently fired changes nothing for a rule whose own
 * occurrence is not inside that window, so being generous about which rule
 * gets to call itself caught up costs nothing. A rule enabled by hand at the
 * same unlucky moment a different rule's durable wait fires is the one case
 * this is generous towards by mistake, and the cost of that mistake is a
 * sunrise or sunset counted from a few minutes earlier than strictly
 * accurate, the same drift every caller of this port already promises.
 */
object AlarmWakeEvents {

    @Volatile
    private var last: Long? = null

    /** Called by `AlarmWakeReceiver`, before it starts the engine. */
    fun record(atMillis: Long) {
        last = atMillis
    }

    /**
     * Whether a durable wait's alarm fired recently enough that a fresh
     * collection should treat itself as caught up rather than as a plain
     * cold start. See the class KDoc for why this answers yes-or-no and
     * names no particular wait.
     *
     * Bounded by [windowMillis] for the same reason [BootEvents.pending] is:
     * the record outlives the moment. Receiver to engine start to trigger
     * collection is ordinarily sub-second; a rule enabled by hand long after
     * that would otherwise borrow a wake-up that has nothing to do with it.
     */
    fun pending(nowMillis: Long, windowMillis: Long = DEFAULT_WINDOW_MILLIS): Boolean {
        val firedAt = last ?: return false
        val age = nowMillis - firedAt
        return age in 0..windowMillis
    }

    /** Test seam: forget any recorded wake. */
    fun clear() {
        last = null
    }

    /**
     * Same magnitude as [BootEvents.DEFAULT_WINDOW_MILLIS] and for the same
     * reason: this app's own handoff from receiver to collecting trigger is
     * ordinarily sub-second, and this is generous next to that without being
     * wide enough for a stale wake to read as fresh.
     */
    const val DEFAULT_WINDOW_MILLIS = 15_000L

    /**
     * The action `AlarmManagerScheduler` puts on the `PendingIntent` half of
     * a durable wait, and the action `AlarmWakeReceiver` in `:ui` declares an
     * intent filter for.
     *
     * Defined here rather than read off that receiver's class, because
     * `:triggers` must not depend on `:ui`: `AlarmManagerScheduler` builds an
     * implicit intent carrying this action and scoped to this app's own
     * package, and the manifest in `:ui` is where the literal string has to
     * agree with this constant, the same way a broadcast trigger and its
     * manifest entry already have to agree on a platform action string.
     */
    const val ACTION_ALARM_WAKE = "app.phueber.trigly.action.ALARM_WAKE"
}
