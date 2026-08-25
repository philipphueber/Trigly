package app.phueber.trigly.triggers

/**
 * How a tap on a home-screen shortcut reaches a trigger.
 *
 * A shortcut is registered per rule and carries that rule's `shortcutId` as an
 * intent extra. Whatever component receives the tap — a trampoline activity,
 * most likely — calls [record] exactly once per tap, and does so unconditionally,
 * whether or not the engine happens to be running already. That is the one fact
 * this object is built around: **a tap is not reliably cold or warm.**
 *
 * A boot broadcast is always cold — `BOOT_COMPLETED` is what starts the engine,
 * so no trigger can ever be collecting yet when it arrives, which is why
 * [BootEvents] only needs a pending record. A shortcut tap has no such
 * guarantee: Trigly's engine is commonly already alive as a foreground service,
 * in which case the tapped trigger is already collecting and wants the tap
 * delivered live; but the process can just as easily have been killed by the
 * system, in which case tapping the shortcut is what restarts it, and the tap
 * lands before any trigger exists to collect it. This object covers both:
 * [taps] is the live bus for the first case, [pending] is the freshness-windowed
 * record for the second, and [record] feeds both from the one call site so
 * whoever handles the tap does not have to work out which case it is.
 *
 * Not consume-once, deliberately, and for the same reason as [BootEvents]: two
 * *different* rules could — unusually, but nothing forbids it — share a
 * `shortcutId`, and both must be able to read the same pending tap. [pending] is
 * bounded by a freshness window instead. What is new here, and does not exist
 * on [BootEvents], is that the *same* rule's trigger can see the *same* tap
 * twice within one collection: once from [pending] at collection start, and
 * again moments later from [taps] if that publish is still what the live bus
 * delivers first. [ShortcutTrigger] is what de-duplicates that — see its doc —
 * using [lastTapAtMillis] to recognise "the tap I already reported" rather than
 * relying on anything this object promises about delivery order.
 */
object ShortcutEvents {

    private data class Tap(val id: String, val atMillis: Long)

    @Volatile
    private var last: Tap? = null

    /** Delivered to whichever trigger is already collecting when a tap lands. */
    val taps = ServiceEventBus<String>()

    /**
     * Called once per tap, regardless of whether the engine was already running.
     * Updates the pending record for a cold-starting engine and publishes on the
     * live bus for one that is already up — the caller does not need to know
     * which of those is about to be true.
     */
    fun record(id: String, atMillis: Long) {
        last = Tap(id, atMillis)
        taps.publish(id)
    }

    /**
     * The moment [id] was last tapped, or null if it never has been (in this
     * process). A finer-grained sibling to [pending]: [pending] answers "is
     * there a tap fresh enough to fire for", which is what a trigger needs at
     * collection start, while this answers "which tap, exactly", which is what a
     * trigger needs afterwards to tell a genuinely new tap apart from a replay
     * of the one it already reported.
     */
    fun lastTapAtMillis(id: String): Long? = last?.takeIf { it.id == id }?.atMillis

    /**
     * Whether there is a tap for [id] fresh enough for this collection to fire
     * for.
     *
     * Bounded by [windowMillis] for the same reason [BootEvents.pending] is:
     * the record outlives the moment, and without a bound a rule enabled by
     * hand minutes after the tap would announce a launch that already happened.
     */
    fun pending(
        nowMillis: Long,
        id: String,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): Boolean {
        val tap = last ?: return false
        if (tap.id != id) return false
        val age = nowMillis - tap.atMillis
        return age in 0..windowMillis
    }

    /** Test seam: forget any recorded tap. */
    fun clear() {
        last = null
    }

    /**
     * Shorter than [BootEvents.DEFAULT_WINDOW_MILLIS]. A device boot can
     * genuinely take the better part of a minute; starting Trigly's own engine
     * service in response to a tap is just this app's own activity-to-service
     * handoff, which is ordinarily sub-second even under load. Fifteen seconds
     * is still generous next to that — enough for a phone that is throttling
     * background starts — without being so wide that a stale tap looks fresh.
     */
    const val DEFAULT_WINDOW_MILLIS = 15_000L
}
