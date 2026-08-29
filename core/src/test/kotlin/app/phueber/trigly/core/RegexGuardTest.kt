package app.phueber.trigly.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [RegexGuard], the single shared mechanism every bounded regex search in
 * this app runs through. See `RegexBudget.kt` for the design and the
 * measurements behind the real bound, which this file does not repeat: every
 * test here passes [RegexGuard.runBounded] a much shorter timeout than
 * [REGEX_GUARD_TIMEOUT_MILLIS], so what is asserted is the mechanism, not the
 * number. `TextFilterTest`, `ExpressionTest` and `MatchRangesTest` are what
 * prove the real five-second bound against real patterns.
 *
 * [resetRegexGuard] runs before every test, not after: [RegexGuard] no longer
 * has a wait worth doing after a test, since a timed-out search is abandoned
 * at once rather than kept around to drain. What one test can leave behind
 * for the next, in the same JVM, is bookkeeping: an identity marked
 * [RegexRefusal.KNOWN_BAD], or a count of abandoned threads sitting at
 * [MAX_ABANDONED_THREADS]. Resetting before each test, rather than trusting
 * the previous test to have cleaned up after itself, is what keeps this
 * file's own tests independent of what order JUnit happens to run them in.
 */
class RegexGuardTest {

    @Before
    fun resetRegexGuard() {
        RegexGuard.resetForTests()
    }

    private fun identity(label: String) = RegexIdentity(pattern = label, ignoreCase = false)

    @Test
    fun `a search that finishes in time returns its value`() {
        val run = RegexGuard.runBounded(identity("a"), timeoutMillis = 200) { 2 + 2 }

        assertEquals(RegexRun.Completed(4), run)
    }

    @Test
    fun `a search that outlives its timeout is refused as timed out`() {
        val run = RegexGuard.runBounded(identity("b"), timeoutMillis = 100) { Thread.sleep(2_000) }

        assertEquals(RegexRun.Refused(RegexRefusal.TIMED_OUT), run)
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

        val run = RegexGuard.runBounded(identity("c"), timeoutMillis = 100) { Thread.sleep(3_000) }

        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(RegexRun.Refused(RegexRefusal.TIMED_OUT), run)
        assertTrue(
            "a refusal took ${elapsedMs}ms; expected well under the search's own 3000ms",
            elapsedMs < 1_500,
        )
    }

    /**
     * The one thread is what bounds a runaway pattern to one *at a time*,
     * however many events arrive while it is still within its own bound: a
     * second search asked for while the first has not yet timed out is
     * refused at once, without being queued behind it. This is deliberately
     * not the same scenario as the previous two tests: the first search here
     * is given a timeout long enough that it never times out on its own, so
     * what refuses the second search is [RegexRefusal.BUSY], the guard
     * genuinely still working on the first, not [RegexRefusal.TIMED_OUT] and
     * an abandoned thread. Once the first search finishes normally, within
     * its own bound, the guard answers normally again on the same thread.
     */
    @Test(timeout = 10_000)
    fun `a second search is refused as busy while the first is still within its bound, and recovers once it finishes`() {
        val stuckStarted = CountDownLatch(1)
        val releaseStuck = CountDownLatch(1)

        // A generous timeout of its own: this search is released by the test
        // well before it, so it always finishes normally rather than by
        // timing out. That is what keeps this test's "busy" refusal from
        // being the abandonment path instead.
        val first = Thread {
            RegexGuard.runBounded(identity("busy"), timeoutMillis = 5_000) {
                stuckStarted.countDown()
                releaseStuck.await()
            }
        }
        first.isDaemon = true
        first.start()
        stuckStarted.await()

        val second = RegexGuard.runBounded(identity("busy-second"), timeoutMillis = 5_000) { "should not run" }
        assertEquals(RegexRun.Refused(RegexRefusal.BUSY), second)

        releaseStuck.countDown()
        first.join()

        // The shared thread frees up once its finally block runs, which is
        // not guaranteed to have happened the instant releaseStuck opens.
        // Retrying for a couple of seconds proves recovery without assuming
        // exactly how fast that handoff is on this machine.
        var recovered: RegexRun<String>? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (recovered !is RegexRun.Completed && System.nanoTime() < deadline) {
            recovered = RegexGuard.runBounded(identity("busy"), timeoutMillis = 5_000) { "ok" }
            if (recovered !is RegexRun.Completed) Thread.sleep(20)
        }

        assertEquals(RegexRun.Completed("ok"), recovered)
    }

    /**
     * The fix for the fault the connected gate found. A search that times out
     * must not leave the guard unusable for every other pattern: the thread
     * it was on is abandoned, and a fresh one is ready immediately for a
     * search with a different identity. Nothing here waits for the abandoned
     * thread; the two `runBounded` calls happen back to back, on the same
     * thread, with no timing dependency between them.
     */
    @Test(timeout = 10_000)
    fun `a search that times out does not stop a later, unrelated search`() {
        val stuck = RegexGuard.runBounded(identity("stuck-one"), timeoutMillis = 100) { Thread.sleep(30_000) }
        assertEquals(RegexRun.Refused(RegexRefusal.TIMED_OUT), stuck)

        val unrelated = RegexGuard.runBounded(identity("unrelated"), timeoutMillis = 200) { "fine" }
        assertEquals(RegexRun.Completed("fine"), unrelated)
    }

    /**
     * The other half of the fix: the *same* identity must not spend a second
     * five-second wait, and a second abandoned thread, on a pattern already
     * known to run away. Refusing on sight is synchronous and needs no
     * timing at all, unlike the two tests above: the check happens before
     * [RegexGuard.runBounded] ever touches a thread.
     */
    @Test(timeout = 10_000)
    fun `the same pattern is refused on sight the second time, without trying it again`() {
        val badIdentity = identity("known-bad")

        val first = RegexGuard.runBounded(badIdentity, timeoutMillis = 100) { Thread.sleep(30_000) }
        assertEquals(RegexRun.Refused(RegexRefusal.TIMED_OUT), first)

        // A body that would succeed immediately if it were ever tried. It is
        // not: the identity alone is enough to refuse this before submission.
        val second = RegexGuard.runBounded(badIdentity, timeoutMillis = 5_000) { "should not run" }
        assertEquals(RegexRun.Refused(RegexRefusal.KNOWN_BAD), second)
    }

    /**
     * The backstop. [MAX_ABANDONED_THREADS] distinct patterns, each timing
     * out once, use up every slot; the next *different* pattern is refused as
     * [RegexRefusal.EXHAUSTED] without a thread ever being created for it,
     * because creating one would be thread number `MAX_ABANDONED_THREADS + 1`.
     */
    @Test(timeout = 10_000)
    fun `the cap on abandoned threads is honoured`() {
        repeat(MAX_ABANDONED_THREADS) { index ->
            val run = RegexGuard.runBounded(identity("cap-$index"), timeoutMillis = 100) { Thread.sleep(30_000) }
            assertEquals(RegexRun.Refused(RegexRefusal.TIMED_OUT), run)
        }

        val overCap = RegexGuard.runBounded(identity("cap-over"), timeoutMillis = 5_000) { "should not run" }
        assertEquals(RegexRun.Refused(RegexRefusal.EXHAUSTED), overCap)
    }
}
