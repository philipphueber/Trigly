package app.phueber.trigly.triggers

import app.phueber.trigly.core.AlarmScheduler
import kotlinx.coroutines.delay

/**
 * [AlarmScheduler] for a test on virtual time.
 *
 * All four methods delay, so `kotlinx-coroutines-test`'s virtual clock drives
 * them exactly like the plain coroutine `delay` this port replaced. A test
 * built on [runTest][kotlinx.coroutines.test.runTest] and
 * [advanceTimeBy][kotlinx.coroutines.test.advanceTimeBy] needs no other
 * change to keep working. Each call is also recorded, so a test can assert
 * what a trigger asked the port for, which a plain `delay` never let a test
 * see at all. The durable pair is recorded separately from the plain pair,
 * on purpose: a JVM test cannot exercise `AlarmManagerScheduler`'s two-alarm
 * mechanics behind [AlarmScheduler.waitForDurable] and
 * [AlarmScheduler.waitUntilDurable] at all, per `docs/architecture.md`'s
 * testing posture, but it can and must prove which callers ask for the
 * durable path and which still ask for the plain one.
 *
 * [now] backs [waitUntil] and [waitUntilDurable]'s translation to a
 * duration; pass the same clock a test gives the trigger under test, so the
 * two agree on what "now" is.
 */
class FakeAlarmScheduler(private val now: () -> Long = { 0L }) : AlarmScheduler {

    /** Every [AlarmScheduler.waitFor] duration this fake was asked to wait, in order. */
    val waitForCalls = mutableListOf<Long>()

    /** Every [AlarmScheduler.waitUntil] instant this fake was asked to wait for, in order. */
    val waitUntilCalls = mutableListOf<Long>()

    /** Every [AlarmScheduler.waitForDurable] duration this fake was asked to wait, in order. */
    val waitForDurableCalls = mutableListOf<Long>()

    /** Every [AlarmScheduler.waitUntilDurable] instant this fake was asked to wait for, in order. */
    val waitUntilDurableCalls = mutableListOf<Long>()

    override suspend fun waitFor(durationMillis: Long) {
        waitForCalls += durationMillis
        delay(durationMillis)
    }

    override suspend fun waitUntil(atMillis: Long) {
        waitUntilCalls += atMillis
        delay((atMillis - now()).coerceAtLeast(0))
    }

    override suspend fun waitForDurable(durationMillis: Long) {
        waitForDurableCalls += durationMillis
        delay(durationMillis)
    }

    override suspend fun waitUntilDurable(atMillis: Long) {
        waitUntilDurableCalls += atMillis
        delay((atMillis - now()).coerceAtLeast(0))
    }
}
