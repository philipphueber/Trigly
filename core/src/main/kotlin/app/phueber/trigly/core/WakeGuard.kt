package app.phueber.trigly.core

/**
 * Holds the device's CPU awake while a piece of work runs.
 *
 * A port, for the same reason [AlarmScheduler] is one: `PowerManager` is an
 * Android type and `:core` may not name one. `:triggers` supplies the real
 * implementation and `:ui` wires it, which is the same route the scheduler
 * already takes.
 *
 * **Why this exists beside [AlarmScheduler], which already answers "wait".**
 * The two solve opposite halves of one problem. An alarm survives the CPU
 * suspending and cannot be accurate: every method in the `AlarmManager` family
 * that is not exact carries a window, and this app's own floor for that window
 * is five seconds. A wake lock is accurate to the millisecond and does not
 * survive anything: it prevents the suspend instead of tolerating it. A wait
 * of a few seconds wants the second of those, and a wait of an hour wants the
 * first. See `DelayAction`, which picks between them by duration.
 *
 * **A block, not an acquire and a release.** A partial wake lock that is taken
 * and not given back holds the CPU until the process dies, which drains a
 * battery with no visible cause and which no test in this repo could catch.
 * Making the span a block means a caller cannot forget the release, cannot
 * return early past it, and cannot lose it to a thrown exception or to a
 * cancelled coroutine. The cost is that a caller cannot hold a lock across two
 * calls, which is deliberate: a span that does not fit in one block is a span
 * whose owner should be the thing that spans it.
 */
interface WakeGuard {

    /**
     * Runs [block] with the CPU held awake, and releases the hold however
     * [block] ends: normally, by throwing, or by the coroutine being cancelled.
     *
     * [reason] names the holder for `dumpsys power` and for battery stats,
     * where a lock with no name is a complaint nobody can answer.
     *
     * [timeoutMillis] is a backstop and not the mechanism. The hold ends when
     * [block] ends; this only bounds the damage if some path ever escapes that,
     * so it should be the work's own expected length plus a margin, never a
     * round number chosen for comfort.
     */
    suspend fun <T> keepingAwake(reason: String, timeoutMillis: Long, block: suspend () -> T): T

    /**
     * No lock at all: runs the block and holds nothing.
     *
     * A real state rather than a test double, the same way
     * [NotificationController.Unavailable] is. A JVM test has no
     * `PowerManager`, and a device that refuses a partial lock leaves the app
     * with exactly this behaviour, which is what the app did before this port
     * existed: the work still runs, and it runs late if the device suspends
     * underneath it.
     */
    object None : WakeGuard {
        override suspend fun <T> keepingAwake(
            reason: String,
            timeoutMillis: Long,
            block: suspend () -> T,
        ): T = block()
    }
}
