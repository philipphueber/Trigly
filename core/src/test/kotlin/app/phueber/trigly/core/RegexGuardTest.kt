package app.phueber.trigly.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RegexGuard], the single shared thread every bounded regex search in this
 * app runs on. See `RegexBudget.kt` for the design and the measurements
 * behind the real bound, which this file does not repeat: every test here
 * passes [RegexGuard.runBounded] a much shorter timeout than
 * [REGEX_GUARD_TIMEOUT_MILLIS], so what is asserted is the mechanism, not the
 * number. `TextFilterTest`, `ExpressionTest` and `MatchRangesTest` are what
 * prove the real five-second bound against real patterns.
 */
class RegexGuardTest {

    /**
     * Every `Thread.sleep` below stands in for a pattern that overran, and it
     * keeps sleeping on the shared thread after its own test has already
     * moved on. See [RegexGuard.awaitIdleForTests]: without this, one test's
     * lingering sleep could still be running when the next test asks the
     * guard its own question. Safe here specifically because every sleep in
     * this file is a few seconds at most, not an unmeasured regex blowup.
     */
    @After
    fun awaitRegexGuardIdle() {
        RegexGuard.awaitIdleForTests()
    }

    @Test
    fun `a search that finishes in time returns its value`() {
        val run = RegexGuard.runBounded(timeoutMillis = 200) { 2 + 2 }

        assertEquals(RegexRun.Completed(4), run)
    }

    @Test
    fun `a search that outlives its timeout is refused`() {
        val run = RegexGuard.runBounded(timeoutMillis = 100) { Thread.sleep(2_000) }

        assertEquals(RegexRun.Refused, run)
    }

    /**
     * The whole reason this is a thread and not a plain timeout: the caller
     * gets an answer at the bound, not whenever the search happens to finish.
     * The search here runs three seconds; the timeout is a tenth of a second.
     * The margin below is generous on purpose, so a slow or loaded machine
     * cannot fail this test: what must never happen, on any machine, is this
     * call taking anywhere near the search's own three seconds.
     */
    @Test(timeout = 10_000)
    fun `a refusal arrives promptly, not after the search finishes`() {
        val start = System.nanoTime()

        val run = RegexGuard.runBounded(timeoutMillis = 100) { Thread.sleep(3_000) }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(RegexRun.Refused, run)
        assertTrue(
            "a refusal took ${elapsedMs}ms; expected well under the search's own 3000ms",
            elapsedMs < 1_500,
        )
    }

    /**
     * The single thread is what bounds a runaway pattern to one, however many
     * events arrive: a second search asked for while the first is still
     * running is refused at once, without being queued behind it. Once the
     * first search actually finishes and frees the thread, the mechanism
     * answers normally again.
     */
    @Test(timeout = 10_000)
    fun `a second search is refused while the thread is busy, and recovers once it frees up`() {
        val stuckStarted = CountDownLatch(1)
        val releaseStuck = CountDownLatch(1)

        // A short timeout of its own: this thread's own wait gives up almost
        // at once, but the search it submitted keeps running on the shared
        // thread regardless, exactly as a real runaway pattern would.
        val first = Thread {
            RegexGuard.runBounded(timeoutMillis = 50) {
                stuckStarted.countDown()
                releaseStuck.await()
            }
        }
        first.isDaemon = true
        first.start()
        stuckStarted.await()

        val second = RegexGuard.runBounded(timeoutMillis = 5_000) { "should not run" }
        assertEquals(RegexRun.Refused, second)

        releaseStuck.countDown()
        first.join()

        // The shared thread frees up once its finally block runs, which is
        // not guaranteed to have happened the instant releaseStuck opens.
        // Retrying for a couple of seconds proves recovery without assuming
        // exactly how fast that handoff is on this machine.
        var recovered: RegexRun<String>? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (recovered !is RegexRun.Completed && System.nanoTime() < deadline) {
            recovered = RegexGuard.runBounded(timeoutMillis = 5_000) { "ok" }
            if (recovered !is RegexRun.Completed) Thread.sleep(20)
        }

        assertEquals(RegexRun.Completed("ok"), recovered)
    }
}
