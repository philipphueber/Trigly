package app.phueber.trigly.core

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bound one regular expression search may spend, and the pieces that
 * enforce it. Shared by every path in `:core` that runs a pattern someone
 * typed against text this app collects:
 *
 * - `contains(a, b, "regex")` in `Expression.kt`. That file's own KDoc,
 *   "Safety is exactly three numbers", has the reasoning for why a regular
 *   expression needs a bound at all. Read that section first; it is not
 *   repeated here.
 * - [TextFilter]'s `regex` mode, which needs the same bound for a harder
 *   reason. `screen_content` can be asked to run its pattern against on-screen
 *   text on every accessibility event Android delivers, which the service
 *   config caps at every hundred milliseconds, on the engine's own collector
 *   thread. A pattern that occupies a core with no end there is not a slow
 *   evaluation. It is a trigger that never resolves, on a thread other
 *   triggers need.
 *
 * One bound, in one place, so a person fixing a number or the mechanism does
 * it once and both paths change together, rather than one of them drifting
 * into allowing what the other refuses.
 *
 * **This file used to count characters read, and that mechanism did nothing on
 * Android.** `java.util.regex.Matcher` converts its input to a `String` when
 * it is handed anything else, so a counting `CharSequence` was never read
 * there. Every JVM test of the counting passed, because they run on the JVM.
 * `docs/todo.md` T24 has the full story, including the correctness bug the
 * same conversion caused, and the two rejected alternatives. What is here now
 * is the third option: bound the wall clock instead of the work, on one
 * shared thread, because that is the one thing that is true on both
 * platforms.
 *
 * ### Why one thread, and why it refuses rather than waits
 *
 * A regular expression search cannot be interrupted. `Matcher.find()` does
 * not check for cancellation anywhere in its loop, so a thread that calls it
 * on a pathological pattern keeps burning CPU until the pattern's own
 * backtracking finishes, whatever anyone waiting for the answer decides to
 * do. That rules out `withTimeout` and any other cooperative cancellation:
 * see `docs/todo.md`'s R5 for the same fact defeating a timeout elsewhere in
 * this codebase.
 *
 * So a timeout can bound how long the *caller* waits, but not how long the
 * search itself runs. [RegexGuard] answers that by giving every bounded
 * search the same single background thread, and never more than one at a
 * time. A pattern that runs away occupies that one thread for as long as it
 * takes to finish on its own; every other search, however many arrive, is
 * refused immediately rather than queued behind it. **At most one runaway
 * thread can ever exist, however many events arrive.** A queue in front of a
 * stuck search would grow without end, since `screen_content` can hand this a
 * new pattern to run every hundred milliseconds, so refusing at once is not
 * a shortcut: it is the only design that does not run out of memory under a
 * pattern that never finishes.
 *
 * A pattern that is stuck is stuck for good. Refusing the next attempt
 * without even trying it means a bad pattern costs this app one long wait,
 * once, and nothing more: every later event sees an occupied thread and gets
 * an immediate answer, not a second multi-second wait.
 *
 * **Reentrancy hazard.** Nothing that runs inside a [RegexGuard.runBounded]
 * block may call [RegexGuard.runBounded] again. The occupancy flag is set
 * before the search is submitted and cleared only when that exact search
 * finishes, so a nested call while the flag is still set is simply refused,
 * which is silent and easy to miss in review. Worse, if this mechanism is
 * ever rewritten to wait for the thread instead of checking a flag and
 * returning, a nested call would deadlock: the one thread would be waiting
 * for itself to finish. Nothing in `:core` does this today. `contains`'s
 * regex mode and `TextFilter`'s regex mode are both leaves; neither calls the
 * other, and neither calls itself.
 *
 * ### The number
 *
 * [REGEX_GUARD_TIMEOUT_MILLIS] is a measurement, not a guess, taken on the
 * `trigly-api35` emulator, an API 35 device whose CPU is the host machine's
 * and is likely faster than a mid-range phone this app ships to:
 *
 * - An anchored pattern and a plain alternation, over a forty-character
 *   notification-sized string: 0.08 ms and 0.04 ms. This is what almost every
 *   real rule costs.
 * - The most expensive *honest* pattern known: an unanchored `.*b` or
 *   `.*Alice.*`, over 1800 characters that do not match. 18 to 46 ms.
 * - The same two patterns over 20000 characters. `screen_content`'s haystack
 *   is `visibleScreenText`, which flattens the whole visible accessibility
 *   tree and has no length cap, so this is not a hypothetical: 2.3 to 2.8
 *   seconds.
 * - `.*.*b` over 1800 characters, one of the shapes that must be refused. It
 *   is not actually infinite on this device: it finished in 11.9 seconds, and
 *   the same search finished in 5.9 seconds on the desktop JVM these tests
 *   also run on, too close to a five-second bound to make a reliable test.
 *   `.*.*.*b` over the same 1800 characters is well clear of it instead: 10
 *   seconds was not enough to finish it on this emulator, nor was 15 seconds
 *   on the JVM. `.*.*.*.*b` over only 200 characters is the same story, so
 *   this is not a bound a short adversarial input can slip under: 10 seconds
 *   was not enough on this emulator either. A slower phone would take longer
 *   on all four, never shorter, so refusing well before any of them finish
 *   costs nothing an honest pattern needed.
 *
 * [REGEX_GUARD_TIMEOUT_MILLIS] is 5000, chosen as at least a hundred times
 * the 46 ms measured for the most expensive honest pattern at 1800
 * characters: a phone would have to be more than a hundred times slower than
 * this emulator, on that exact search, before device speed decided the
 * answer instead of the pattern. That is the headroom the file's old rate
 * bound never had; its own KDoc said its ceiling was three to four times the
 * worst honest cost it knew of, not a hundred.
 *
 * **The 20000-character measurement is the honest limit of this design, not
 * a number the bound was sized around.** 5000 ms clears both measurements
 * (2358 ms and 2767 ms) with room to spare on this device, but only about
 * twice over, not a hundred times. `visibleScreenText` has no length cap, so
 * a haystack considerably larger than 20000 characters is not ruled out, and
 * on one slower than this emulator the same honest, unanchored pattern could
 * be refused. A bound in characters read does not have this failure mode,
 * because it grows with the text; a bound in milliseconds cannot, because
 * wall-clock time is exactly what it was chosen to ignore for every other
 * number in this app. See "Say the bound is not there" in `docs/todo.md`'s
 * T24 for the alternative that has no such limit and costs a different thing
 * instead. This file's KDoc says which one the project chose and why.
 *
 * A pattern refused because the thread was already occupied answers in
 * effectively zero time, whatever [REGEX_GUARD_TIMEOUT_MILLIS] is: the
 * five-second number only ever governs the *first* time a given pattern
 * turns out to be too slow, not every occurrence of it. That is what keeps
 * five seconds from reading as "screen_content can go five seconds between
 * answers": it cannot, because a stuck pattern occupies the thread and every
 * later event is refused at once rather than waiting its own five seconds.
 */
internal const val REGEX_GUARD_TIMEOUT_MILLIS: Long = 5_000L

/**
 * What one call to [RegexGuard.runBounded] came back with.
 *
 * A sealed type rather than a nullable [T], so a caller cannot mistake
 * "refused" for "ran and produced nothing": [Refused] carries no value at
 * all, and the two cases have to be handled separately by the compiler
 * rather than by a convention a future edit could quietly break.
 */
internal sealed interface RegexRun<out T> {

    /** The search finished inside [REGEX_GUARD_TIMEOUT_MILLIS]. [value] is what it produced. */
    data class Completed<T>(val value: T) : RegexRun<T>

    /**
     * No answer. Either the shared thread was already running another
     * search and this one was refused before it started, or it was
     * submitted and did not finish inside [REGEX_GUARD_TIMEOUT_MILLIS].
     * [RegexGuard] does not tell the two apart, because every caller here
     * treats them the same way: there is no verdict, so there is nothing a
     * caller could do differently for one case that it would not also do for
     * the other.
     */
    data object Refused : RegexRun<Nothing>
}

/**
 * The one shared thread every bounded regex search in this app runs on. See
 * this file's own KDoc for why one thread, why it refuses rather than
 * queues, and where the number comes from.
 */
internal object RegexGuard {

    /**
     * Set before a search is submitted, cleared only when that exact search
     * finishes. A plain `Boolean` behind a lock would work the same way;
     * this is an [AtomicBoolean] so the check-and-set in [runBounded] is one
     * operation rather than two, with no window where two callers could both
     * see the thread as free.
     */
    private val busy = AtomicBoolean(false)

    /**
     * Created on first use, not at class load, so a device that never
     * builds a regex rule never starts this thread. A single daemon thread:
     * daemon because a search that ran away must never hold up a JVM test
     * run or an app process trying to exit, and single because that is what
     * bounds the number of runaway threads to one however many searches are
     * asked for.
     */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "regex-guard").apply { isDaemon = true }
        }
    }

    /**
     * Runs [search] on the shared thread and waits up to [timeoutMillis] for
     * it to finish. Every real caller in this app takes the default, which is
     * [REGEX_GUARD_TIMEOUT_MILLIS]; [RegexGuardTest] is the only caller that
     * passes anything else, so its tests of the mechanism run in well under a
     * second instead of needing several times the real bound each.
     *
     * Refuses immediately, without touching the executor at all, when a
     * search is already running: see this file's KDoc for why a queue is not
     * the safer alternative here. A caller that gets [RegexRun.Refused] back
     * has no way to tell whether that was the fast path or the slow one, on
     * purpose: both mean the same thing to every caller in this file.
     *
     * Any exception [search] throws, other than the timeout this function
     * handles itself, comes back out of this function unchanged: a bounded
     * search that throws for a reason of its own is a bug to see, not a
     * budget to spend.
     */
    fun <T> runBounded(timeoutMillis: Long = REGEX_GUARD_TIMEOUT_MILLIS, search: () -> T): RegexRun<T> {
        if (!busy.compareAndSet(false, true)) return RegexRun.Refused

        val future = executor.submit(
            Callable {
                try {
                    search()
                } finally {
                    // Cleared by the thread that ran the search, once it is
                    // truly done, not by whoever gave up waiting for it. A
                    // stuck search must keep the thread marked busy for its
                    // whole real duration, or a second stuck search could be
                    // submitted behind it and queue after all.
                    busy.set(false)
                }
            },
        )

        return try {
            RegexRun.Completed(future.get(timeoutMillis, TimeUnit.MILLISECONDS))
        } catch (timedOut: TimeoutException) {
            RegexRun.Refused
        } catch (wrapped: ExecutionException) {
            throw wrapped.cause ?: wrapped
        }
    }

    /**
     * Test-only. Blocks until the shared thread has nothing left to finish,
     * up to [timeoutMillis].
     *
     * Safe only for a search whose real duration a test controls, such as
     * [RegexGuardTest]'s synthetic `Thread.sleep` calls: there, [timeoutMillis]
     * only has to clear a known, short sleep, so waiting for it back is cheap
     * and bounded. **Not safe for a genuinely pathological regex pattern.**
     * Its real completion time is not measured and is not assumed to be
     * short: `TextFilterRegexRefusalTest`'s own pattern was still running
     * after ten seconds in the measurement that sized this file's bound, with
     * no known ceiling above that. A test that asked this to wait for one of
     * those to finish could itself hang for as long as the pattern does,
     * which is exactly the failure mode a bound is supposed to remove, not
     * reintroduce into the test suite. Those tests get a class of their own
     * instead, with nothing else in it to protect, and this module's
     * `forkEvery = 1` is what cleans up after them: see
     * `TextFilterRegexRefusalTest`'s KDoc.
     *
     * Submitting a no-op to this same single-thread executor and waiting for
     * it is exactly "wait for the queue to empty": the executor runs
     * submissions in order, so whatever is already running finishes first,
     * then the no-op runs, then this call returns.
     */
    internal fun awaitIdleForTests(timeoutMillis: Long = 120_000) {
        executor.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
