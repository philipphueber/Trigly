package app.phueber.trigly.triggers

import app.phueber.trigly.core.AlarmScheduler
import kotlinx.coroutines.delay

/**
 * [AlarmScheduler] for a test on virtual time.
 *
 * Both methods delay, so `kotlinx-coroutines-test`'s virtual clock drives
 * them exactly like the plain coroutine `delay` this port replaced. A test
 * built on [runTest][kotlinx.coroutines.test.runTest] and
 * [advanceTimeBy][kotlinx.coroutines.test.advanceTimeBy] needs no other
 * change to keep working. Each call is also recorded, so a test can assert
 * what a trigger asked the port for, which a plain `delay` never let a test
 * see at all.
 *
 * [now] backs [waitUntil]'s translation to a duration; pass the same clock a
 * test gives the trigger under test, so the two agree on what "now" is.
 */
class FakeAlarmScheduler(private val now: () -> Long = { 0L }) : AlarmScheduler {

    /** Every [AlarmScheduler.waitFor] duration this fake was asked to wait, in order. */
    val waitForCalls = mutableListOf<Long>()

    /** Every [AlarmScheduler.waitUntil] instant this fake was asked to wait for, in order. */
    val waitUntilCalls = mutableListOf<Long>()

    override suspend fun waitFor(durationMillis: Long) {
        waitForCalls += durationMillis
        delay(durationMillis)
    }

    override suspend fun waitUntil(atMillis: Long) {
        waitUntilCalls += atMillis
        delay((atMillis - now()).coerceAtLeast(0))
    }
}
