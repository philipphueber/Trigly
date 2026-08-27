package app.phueber.trigly.actions

import app.phueber.trigly.core.AlarmScheduler
import kotlinx.coroutines.delay

/**
 * [AlarmScheduler] for a test on virtual time, scoped to `:actions`.
 *
 * `:triggers` already has one of these, `triggers/src/test/kotlin/app/phueber/
 * trigly/triggers/FakeAlarmScheduler.kt`, but `:actions` depends on `:core`
 * only and must never depend on `:triggers`; see `ActionFactories.kt`'s build
 * file. A test fixture living in another module's `test` source set is not on
 * this module's test classpath either way, so this is a second, small copy
 * rather than a reachable shared one.
 *
 * All four methods delay, so `kotlinx-coroutines-test`'s virtual clock drives
 * them exactly like the plain coroutine `delay` this port replaced, and a
 * cancelled caller sees the delay cancelled too, which is what a cancellation
 * test needs. Each call is recorded, so a test can assert what an action asked
 * the port for.
 */
class FakeAlarmScheduler : AlarmScheduler {

    /** Every [AlarmScheduler.waitFor] duration this fake was asked to wait, in order. */
    val waitForCalls = mutableListOf<Long>()

    override suspend fun waitFor(durationMillis: Long) {
        waitForCalls += durationMillis
        delay(durationMillis)
    }

    override suspend fun waitUntil(atMillis: Long) {
        delay(atMillis)
    }

    override suspend fun waitForDurable(durationMillis: Long) {
        delay(durationMillis)
    }

    override suspend fun waitUntilDurable(atMillis: Long) {
        delay(atMillis)
    }
}
