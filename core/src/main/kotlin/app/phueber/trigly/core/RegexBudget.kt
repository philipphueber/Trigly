package app.phueber.trigly.core

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
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
 * there. `docs/todo.md` T24 has the full story. What is here now is a
 * wall-clock bound instead of a count of work, on one shared thread, because
 * that is the one thing that is true on both platforms.
 *
 * **The first version of that wall-clock bound had a second bug, worse than
 * the one it fixed.** It kept the same worker thread for the life of the
 * process and cleared its one "busy" flag only when the search running on it
 * truly finished. A pattern that never finishes therefore never clears that
 * flag, so every later search of any kind, on any pattern, saw the thread as
 * occupied and was refused, forever, until the process died. One bad pattern
 * in one rule silently turned off regular expressions for every other rule on
 * the device. This was found the same way T24 itself was found: an
 * instrumented test failed and named the cause exactly. `RegexOnDeviceTest`'s
 * `contains("abc123", "\d+", "regex")`, a search over six characters that has
 * never once been slow, failed with "took too long against 6 characters of
 * text", because a different test earlier in the same run had already run a
 * pattern that never finishes and poisoned the one shared thread for good.
 * The KDoc this replaced argued "a pattern that is stuck is stuck for good"
 * as the cost of this design. That sentence is true of the stuck pattern and
 * silent about everyone else: every *other* pattern was stuck too.
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
 * time: a second search asked for while the first is still within its own
 * bound is refused at once rather than queued behind it, because a queue in
 * front of a stuck search would grow without end, since `screen_content` can
 * hand this a new pattern to run every hundred milliseconds.
 *
 * ### What happens when a search does not finish in time
 *
 * The thread that ran it is not this guard's thread any more. [runBounded]
 * abandons it: the thread keeps burning CPU for as long as the pattern's own
 * backtracking takes, since it cannot be stopped, but nothing here waits for
 * it, tracks it as busy, or ever asks it for anything again. A fresh thread
 * is created for the next search, which runs normally. **At most one
 * abandoned thread is created per distinct pattern that turns out to be too
 * slow**, not one per event: see the next section for why a second event with
 * the same pattern does not create a second one, and [MAX_ABANDONED_THREADS]
 * for the backstop that bounds how many distinct bad patterns can be doing
 * this at once.
 *
 * ### Remembering a pattern that ran away
 *
 * Abandoning the thread is not enough by itself. `screen_content` can hand
 * this the same bad pattern again a hundred milliseconds later, and without
 * memory that would abandon a second thread, then a third, one per event,
 * for as long as the rule stays on screen. [RegexGuard] keeps [RegexIdentity]
 * for every pattern that has already timed out once, in [knownBad], and
 * refuses a search on sight, before ever touching a thread, when its identity
 * is in that set. A pattern that is stuck is stuck for good, and now that is
 * true only of that one pattern: it costs this app one long wait, once, and
 * nothing else pays for it, in either direction, forever after. See
 * [MAX_KNOWN_BAD_PATTERNS] for the bound on how many distinct patterns this
 * remembers.
 *
 * ### The backstop
 *
 * Memory only helps once a pattern has already been seen and paid for once.
 * Several *distinct* bad patterns, arriving close together, before any of
 * them has had the chance to become known, would each abandon their own
 * thread, and nothing about remembering one bad pattern stops a different one
 * from doing the same. [MAX_ABANDONED_THREADS] caps how many abandoned
 * threads may exist at once; past that, [runBounded] refuses every search,
 * including an honest one, rather than create thread number `N + 1`. See that
 * constant's own KDoc for the number and why a refusal there is the right
 * trade rather than an unbounded pile of threads each pinning a core.
 *
 * ### Reentrancy hazard
 *
 * Nothing that runs inside a [RegexGuard.runBounded] block may call
 * [RegexGuard.runBounded] again. A nested call while the outer one is still
 * within its own bound sees the guard as busy and is simply refused, which is
 * silent and easy to miss in review. Nothing in `:core` does this today:
 * `contains`'s regex mode and `TextFilter`'s regex mode are both leaves,
 * neither calls the other, and neither calls itself.
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
 * A pattern refused because it is already [knownBad], or because the guard is
 * [busy] with another search, answers in effectively zero time, whatever
 * [REGEX_GUARD_TIMEOUT_MILLIS] is. The five-second number only ever governs
 * the *first* time a given pattern turns out to be too slow, never again
 * after that: see "Remembering a pattern that ran away" above.
 */
internal const val REGEX_GUARD_TIMEOUT_MILLIS: Long = 5_000L

/**
 * How many distinct patterns [RegexGuard] may abandon a thread for before it
 * starts refusing everything, including an honest search, rather than create
 * one more.
 *
 * Each abandoned thread pins one CPU core for as long as its pattern's own
 * backtracking takes, which is unmeasured and may be forever. A phone has few
 * cores to begin with, and this app is not the only thing asking for them.
 * Four is small enough that even a device with as few as four cores keeps at
 * least one free for everything else, including the engine's own collector
 * thread, however many distinct bad patterns a badly built or imported rule
 * set throws at it, and it is not one: [MAX_KNOWN_BAD_PATTERNS] means the
 * overwhelming majority of repeats of the *same* bad pattern never reach this
 * cap at all, so four is a backstop against several different bad patterns
 * arriving close together, not the steady-state cost of one.
 */
internal const val MAX_ABANDONED_THREADS: Int = 4

/**
 * How many distinct [RegexIdentity] values [RegexGuard] remembers as
 * [RegexGuard.knownBad] before it starts forgetting the oldest one to make
 * room for a new one.
 *
 * Forgetting is not silently safe: a forgotten identity is tried again on its
 * next event, which abandons another thread and spends another five-second
 * wait to relearn what this already knew. Sixty-four is chosen to make that
 * cost theoretical rather than expected. A rule set with sixty-four distinct
 * catastrophically backtracking patterns, all of them exercised in the same
 * process lifetime, is far past anything this app's own test patterns or a
 * plausible rule set describe; the true daily cost is at most a small handful
 * of entries, one per pattern someone genuinely wrote badly. Eviction is
 * oldest-first, not least-recently-refused: a pattern refused a hundred times
 * a second, the case this cache exists for, stays at the front of the queue
 * regardless, since it keeps getting refused rather than reinserted, and a
 * true least-recently-used policy would cost more to maintain than the
 * difference is worth at this size.
 */
internal const val MAX_KNOWN_BAD_PATTERNS: Int = 64

/**
 * What one regular expression search is, for [RegexGuard.runBounded] to tell
 * two searches apart or recognise them as the same one.
 *
 * [pattern] and [ignoreCase] are the two things that decide whether a search
 * is "the same search" for this purpose: same text, same casing, same cost.
 * Nothing else varies between the three call sites this app has. See
 * [asRegexIdentity] for how one is built from an already-compiled [Regex],
 * which is how every real caller gets one.
 */
internal data class RegexIdentity(val pattern: String, val ignoreCase: Boolean)

/**
 * [RegexIdentity] for a [Regex] compiled at one of this app's regex call
 * sites. Reading [Regex.pattern] and [Regex.options] back off the compiled
 * object itself, rather than asking each call site to restate them, is what
 * keeps a call site from ever describing its own search two different ways:
 * `Expression.kt`'s `contains(a, b, "regex")` compiles case-sensitively;
 * `TextFilter`'s `regex` mode and [matchRangesIn] both compile with
 * [RegexOption.IGNORE_CASE].
 */
internal fun Regex.asRegexIdentity(): RegexIdentity =
    RegexIdentity(pattern = pattern, ignoreCase = RegexOption.IGNORE_CASE in options)

/**
 * Why [RegexGuard.runBounded] refused a search, since the four causes are
 * four different true statements and a caller that shows or reports the
 * refusal to a person must not say the wrong one of them.
 *
 * Public, unlike the rest of this file: [TextFilter.Outcome.REFUSED] carries
 * one of these across the `:core`/`:ui` boundary so the pattern tester can
 * tell a person which of the four happened, rather than saying "took too
 * long" for a search that never even ran.
 */
enum class RegexRefusal {
    /** This exact call did not finish inside its own bound, just now. */
    TIMED_OUT,

    /** This exact pattern already timed out once before and is refused without being tried again. */
    KNOWN_BAD,

    /** A different search is already running on the guard's one thread right now. */
    BUSY,

    /** Too many distinct patterns are already abandoned; see [MAX_ABANDONED_THREADS]. */
    EXHAUSTED,
}

/**
 * What one call to [RegexGuard.runBounded] came back with.
 *
 * A sealed type rather than a nullable [T], so a caller cannot mistake
 * "refused" for "ran and produced nothing": [Refused] carries no value at
 * all, only [RegexRefusal.reason] naming which of the four things happened.
 */
internal sealed interface RegexRun<out T> {

    /** The search finished inside its bound. [value] is what it produced. */
    data class Completed<T>(val value: T) : RegexRun<T>

    /** No answer. [reason] says which of [RegexRefusal]'s cases this was. */
    data class Refused(val reason: RegexRefusal) : RegexRun<Nothing>
}

/**
 * The shared mechanism every bounded regex search in this app runs through.
 * See this file's own KDoc for why one thread, why it refuses rather than
 * queues, what happens when a search does not finish in time, and where the
 * number comes from.
 */
internal object RegexGuard {

    /** Guards every field below. Cheap and rarely contended: see this file's KDoc for why a search is refused rather than queued in the first place. */
    private val lock = Any()

    /**
     * The thread a search would run on next, or null when there is none yet,
     * either because nothing has asked for one, or because the last search
     * on it ran away and it was abandoned. Only ever read or replaced under
     * [lock].
     */
    private var executor: ExecutorService? = null

    /** Whether [executor]'s one thread currently has a search running on it that has not yet timed out. */
    private var busy = false

    /** How many threads are currently abandoned, running a search nothing here is waiting for any more. */
    private var abandonedThreads = 0

    /**
     * Every [RegexIdentity] that has already timed out once. Checked before
     * a search ever touches a thread. Insertion-ordered so the oldest entry
     * can be dropped first when [MAX_KNOWN_BAD_PATTERNS] is reached: see that
     * constant's KDoc for why oldest-first is the right eviction policy here.
     */
    private val knownBad = LinkedHashSet<RegexIdentity>()

    /**
     * A single daemon thread, created fresh each time: daemon because a
     * search that runs away must never hold up a JVM test run or an app
     * process trying to exit, and single because that is what bounds the
     * number of *new* threads any one call to [runBounded] can create to
     * exactly one.
     */
    private fun newExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "regex-guard").apply { isDaemon = true }
        }

    /**
     * Runs [search] and waits up to [timeoutMillis] for it to finish. Every
     * real caller in this app takes the default, which is
     * [REGEX_GUARD_TIMEOUT_MILLIS]; [RegexGuardTest] is the only caller that
     * passes anything else, so its tests of the mechanism run in well under a
     * second instead of needing several times the real bound each.
     *
     * Refuses immediately, without touching a thread at all, when [identity]
     * is already [knownBad], when the guard is already [busy] with another
     * search, or when [abandonedThreads] is already at
     * [MAX_ABANDONED_THREADS]. See [RegexRefusal] for what each case means
     * and this file's KDoc for the reasoning behind each one.
     *
     * A search that does not finish in time is abandoned, not waited for
     * further: the thread it was running on stops being this guard's thread,
     * [identity] is recorded in [knownBad] so it is refused on sight next
     * time, and a fresh thread is ready for the next call. See this file's
     * KDoc, "What happens when a search does not finish in time".
     *
     * Any exception [search] throws, other than the timeout this function
     * handles itself, comes back out of this function unchanged: a bounded
     * search that throws for a reason of its own is a bug to see, not a
     * budget to spend.
     */
    fun <T> runBounded(
        identity: RegexIdentity,
        timeoutMillis: Long = REGEX_GUARD_TIMEOUT_MILLIS,
        search: () -> T,
    ): RegexRun<T> {
        val exec: ExecutorService
        synchronized(lock) {
            if (identity in knownBad) return RegexRun.Refused(RegexRefusal.KNOWN_BAD)
            if (busy) return RegexRun.Refused(RegexRefusal.BUSY)
            val current = executor
            if (current != null) {
                exec = current
            } else {
                if (abandonedThreads >= MAX_ABANDONED_THREADS) {
                    return RegexRun.Refused(RegexRefusal.EXHAUSTED)
                }
                exec = newExecutor()
                executor = exec
            }
            busy = true
        }

        // Whichever side of the race between "the search finished" and "the
        // caller's wait timed out" gets here first decides what happened.
        // The loser must do nothing to the shared state, since the winner
        // already has: without this, a search that finishes in the same
        // instant its timeout fires could be both freed normally by its own
        // finally block and abandoned by the timeout handler, double-counting
        // one outcome as two.
        val settled = AtomicBoolean(false)
        val future = exec.submit(
            Callable {
                try {
                    search()
                } finally {
                    if (settled.compareAndSet(false, true)) {
                        // Finished before anyone declared it abandoned: an
                        // ordinary result, on the thread the next call should
                        // reuse.
                        synchronized(lock) { busy = false }
                    } else {
                        // Finished after being abandoned. Nobody is waiting
                        // for this any more, and the thread that ran it is
                        // not executor's thread any more either; the one
                        // thing left to update is the count that bounds how
                        // many of these can exist at once.
                        synchronized(lock) { abandonedThreads-- }
                    }
                }
            },
        )

        return try {
            RegexRun.Completed(future.get(timeoutMillis, TimeUnit.MILLISECONDS))
        } catch (timedOut: TimeoutException) {
            if (settled.compareAndSet(false, true)) {
                synchronized(lock) {
                    if (knownBad.size >= MAX_KNOWN_BAD_PATTERNS) {
                        val oldest = knownBad.iterator()
                        oldest.next()
                        oldest.remove()
                    }
                    knownBad.add(identity)
                    executor = null
                    busy = false
                    abandonedThreads++
                }
                // Marks the executor as taking no further submissions. Does
                // not stop, and is not expected to stop, the one search
                // already running on its thread: see this file's KDoc.
                exec.shutdown()
            }
            // If the other side of the race won instead, the search actually
            // finished at essentially this exact instant and was freed
            // normally; this call still reports the boundary as a refusal
            // rather than retrieving that value, which is an acceptable
            // approximation at a boundary this narrow and not a case worth
            // the extra code to resolve more precisely.
            RegexRun.Refused(RegexRefusal.TIMED_OUT)
        } catch (wrapped: ExecutionException) {
            throw wrapped.cause ?: wrapped
        }
    }

    /**
     * Test-only. Clears every field back to its startup state: no thread, not
     * busy, no abandoned count, nothing remembered as bad.
     *
     * This replaces the old `awaitIdleForTests`, which no longer means
     * anything once a stuck search stops being waited for at all. That
     * function existed because a timed-out search used to keep the one
     * thread this object had forever, so a test had to wait for that
     * specific search to finish before the guard was usable again, and
     * `TextFilterRegexRefusalTest`'s own pattern was still running after ten
     * seconds with no known ceiling above that, which is exactly the kind of
     * wait a test must never depend on. Now a timed-out search is abandoned
     * at once and the guard is immediately usable again on a fresh thread; the
     * only state left over between tests in the same class is the bookkeeping
     * this resets, not a wait. A test that deliberately abandons a thread,
     * such as one proving [MAX_ABANDONED_THREADS], still leaves that thread
     * physically running in the background afterward, same as before; this
     * function does not and cannot wait for it, it only forgets about it, so
     * that the *next* test does not inherit its share of the cap or its
     * pattern's place in [knownBad].
     */
    internal fun resetForTests() {
        synchronized(lock) {
            executor?.shutdown()
            executor = null
            busy = false
            abandonedThreads = 0
            knownBad.clear()
        }
    }
}
