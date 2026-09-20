package app.phueber.trigly.actions

import app.phueber.trigly.core.WakeGuard

/**
 * [WakeGuard] for a JVM test, scoped to `:actions`.
 *
 * A second small copy rather than a shared fixture, for the reason
 * [FakeAlarmScheduler]'s KDoc gives about its own: another module's `test`
 * source set is not on this module's test classpath, and `:actions` must not
 * depend on `:triggers`.
 *
 * It runs the block like the real guard does, and counts the two things the
 * real one can get wrong. A partial wake lock that is taken and never given
 * back is a battery fault with no visible cause, so [acquired] and [released]
 * balancing is the property worth asserting after every path, and [isHeld] is
 * what a test checks after a cancellation.
 */
class FakeWakeGuard : WakeGuard {

    /** Every `(reason, timeoutMillis)` this fake was asked for, in order. */
    val calls = mutableListOf<Pair<String, Long>>()

    var acquired = 0
        private set

    var released = 0
        private set

    /** True while a block is running, and after one that failed to release. */
    val isHeld: Boolean get() = acquired > released

    override suspend fun <T> keepingAwake(
        reason: String,
        timeoutMillis: Long,
        block: suspend () -> T,
    ): T {
        calls += reason to timeoutMillis
        acquired++
        try {
            return block()
        } finally {
            // A `finally`, matching the real implementation, so a test can
            // tell the difference between a guard that releases on
            // cancellation and one that only releases on the happy path.
            released++
        }
    }
}
