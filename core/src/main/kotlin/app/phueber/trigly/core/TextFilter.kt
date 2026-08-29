package app.phueber.trigly.core

/** How a text filter decides whether it matches. */
enum class TextMatchMode(val configValue: String) {
    /** Case-insensitive substring, the default and what most rules want. */
    CONTAINS("contains"),

    /** A regular expression, searched anywhere in the candidate. */
    REGEX("regex"),
    ;

    companion object {
        /**
         * Absent or unrecognised reads as [CONTAINS].
         *
         * Lenient on purpose, and the one place in this project that is. Every
         * rule saved before regex existed has no mode key at all, and an import
         * from a newer version might carry a mode this build does not know. In
         * both cases the *pattern* is still meaningful as a substring, so falling
         * back matches something sensible instead of refusing to load a rule the
         * user can see is reasonable.
         */
        fun parse(raw: String?): TextMatchMode =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) } ?: CONTAINS
    }
}

/**
 * One text condition from a rule: "does this text match what the user asked for".
 *
 * Five triggers were each hand-rolling `contains(x, ignoreCase = true)` against
 * six different config keys. This is that decision in one place, so making it
 * understand regular expressions is one change rather than six, and so the next
 * text filter gets the behaviour for free.
 *
 * **Built once, matched many times.** A regex is compiled here, when the rule is
 * constructed, not on each event. That matters twice over: `screen_content` can
 * be asked about every visual change on the device, and a compile error becomes
 * a save-time failure the editor can show rather than an exception thrown deep
 * inside the engine while the phone is in someone's pocket.
 *
 * A blank pattern matches everything. That is the existing meaning of an empty
 * filter throughout the app — `blankMeaning` on these fields says so — and it is
 * why the constructor is private: [of] is the only way in, and it is the thing
 * that turns "nothing entered" into "no opinion".
 *
 * **Matching is also bounded.** `screen_content` can run its `regex` mode
 * against on-screen text on every accessibility event, as often as every
 * hundred milliseconds, on the engine's own collector thread. A pattern that
 * backtracks without end there does not just answer slowly, it occupies that
 * thread forever. [Outcome.REFUSED] and [RegexGuard] in `RegexBudget.kt` are
 * the same bound `Expression.kt`'s `contains(a, b, "regex")` uses, and for
 * the same reason: read that file's "Safety is exactly three numbers" for
 * where the number came from, and `RegexBudget.kt` for the mechanism,
 * including what happens to a pattern that does not finish in time and why
 * that no longer costs any *other* pattern anything.
 *
 * This bound holds on the JVM and on Android alike: it is a wall clock on one
 * shared thread, not a count of characters read, so nothing about how the
 * regex engine reads its input matters to it. See `docs/todo.md` T24 for the
 * mechanism this replaced, which held only on the JVM.
 */
class TextFilter private constructor(
    private val predicate: (String?) -> Outcome,
    /** What the user typed, kept for error messages and equality. */
    val pattern: String?,
    val mode: TextMatchMode,
) {

    /**
     * The three things one match can be. [REFUSED] only happens in
     * [TextMatchMode.REGEX]: a substring search is linear and never asks
     * [RegexGuard] for anything. Its [RegexRefusal] names which of
     * [RegexGuard]'s four reasons this was, for a caller that can use it,
     * such as the pattern tester.
     */
    sealed interface Outcome {
        data object MATCHED : Outcome
        data object NOT_MATCHED : Outcome

        /** [reason] is [RegexGuard]'s own reason for refusing this search. */
        data class REFUSED(val reason: RegexRefusal) : Outcome
    }

    /**
     * Whether this filter matches, for the five triggers that call it per event
     * and cannot handle anything but yes or no.
     *
     * [Outcome.REFUSED] reads as `false` here. That is a real decision, not
     * an oversight: the alternative is throwing out of a trigger's collector on
     * whichever event happened to run into the bound, which this project does
     * not do to its own engine. The honest cost is that a rule built around a
     * pattern that overran then never fires, silently, and this function has no
     * channel to say why. [outcome] is that channel for a caller that can use
     * one, such as the pattern tester. Whether the engine itself needs a way to
     * surface this to the person who wrote the rule is `docs/todo.md`'s
     * question, not this function's.
     */
    fun matches(candidate: String?): Boolean = predicate(candidate) == Outcome.MATCHED

    /** The full answer behind [matches], for a caller that wants to tell the three cases apart. */
    fun outcome(candidate: String?): Outcome = predicate(candidate)

    /** True when this filter has no opinion — an empty pattern. */
    val isEmpty: Boolean get() = pattern.isNullOrEmpty()

    override fun toString(): String =
        if (isEmpty) "any text" else "${mode.configValue} '$pattern'"

    override fun equals(other: Any?): Boolean =
        other is TextFilter && other.pattern == pattern && other.mode == mode

    override fun hashCode(): Int = 31 * (pattern?.hashCode() ?: 0) + mode.hashCode()

    companion object {

        /** A filter that lets everything through. */
        val Any: TextFilter = TextFilter({ Outcome.MATCHED }, null, TextMatchMode.CONTAINS)

        /**
         * Builds a filter, compiling the pattern if it is a regex.
         *
         * Throws [IllegalArgumentException] on a regex that does not compile,
         * with the engine's own message — which names the offending position and
         * is more useful than anything this could invent. That exception is what
         * the editor surfaces when someone saves a broken pattern, the same path
         * every other invalid config takes.
         */
        fun of(pattern: String?, mode: TextMatchMode = TextMatchMode.CONTAINS): TextFilter {
            if (pattern.isNullOrEmpty()) return Any

            return when (mode) {
                // A linear substring search, so there is nothing here for a
                // pattern to spend an unbounded amount of work on: the cost is
                // exactly the length of the candidate, once. No bound needed.
                TextMatchMode.CONTAINS -> TextFilter(
                    predicate = {
                        if (it?.contains(pattern, ignoreCase = true) == true) {
                            Outcome.MATCHED
                        } else {
                            Outcome.NOT_MATCHED
                        }
                    },
                    pattern = pattern,
                    mode = mode,
                )

                TextMatchMode.REGEX -> {
                    val compiled = try {
                        Regex(pattern, RegexOption.IGNORE_CASE)
                    } catch (invalid: IllegalArgumentException) {
                        throw IllegalArgumentException(
                            "'$pattern' is not a valid regular expression: ${invalid.message}",
                            invalid,
                        )
                    }
                    TextFilter(
                        predicate = { candidate ->
                            if (candidate == null) {
                                Outcome.NOT_MATCHED
                            } else {
                                regexOutcome(compiled, candidate)
                            }
                        },
                        pattern = pattern,
                        mode = mode,
                    )
                }
            }
        }

        /** Builds from raw config, the form a factory has. */
        fun fromConfig(pattern: String?, rawMode: String?): TextFilter =
            of(pattern, TextMatchMode.parse(rawMode))

        /**
         * The regex half of [of]'s predicate, pulled out so the closure that
         * builds the filter is not indented past the point of reading its own
         * comments.
         *
         * containsMatchIn, not matches: a regex filter reads like grep,
         * finding the pattern anywhere. Anchoring with ^ and $ is available
         * to anyone who wants the whole string, and requiring it by default
         * would surprise everyone else.
         *
         * RegexGuard bounds the search the same way Expression.kt's regex
         * mode does; see RegexBudget.kt for why and for the number.
         */
        private fun regexOutcome(compiled: Regex, candidate: String): Outcome =
            when (
                val run = RegexGuard.runBounded(compiled.asRegexIdentity()) {
                    compiled.containsMatchIn(candidate)
                }
            ) {
                is RegexRun.Completed -> if (run.value) Outcome.MATCHED else Outcome.NOT_MATCHED
                is RegexRun.Refused -> Outcome.REFUSED(run.reason)
            }
    }
}

/**
 * Where [candidate] is matched by [pattern] under [mode] — the spans a tester
 * highlights.
 *
 * Deliberately **not** the verdict. Whether a filter matches is
 * [TextFilter.matches]' business and nothing else's: a tester that decided for
 * itself could disagree with the engine, and a tester that disagrees with the
 * engine is worse than no tester. This only answers "which characters", so the
 * highlight is decoration over an answer computed by the real code path.
 *
 * The two modes are mirrored exactly as [TextFilter.of] builds them, including
 * the case-insensitivity that both use — get that wrong and the highlight drifts
 * from the verdict on the first capital letter. That includes the same bound:
 * `findAll` backtracks the same way `containsMatchIn` does, and this runs it
 * through the same [RegexGuard], so a pattern the filter refuses is refused
 * here too rather than left to search unbounded just because this call only
 * draws a highlight.
 *
 * Zero-width matches are dropped. A pattern like `a*` matches "b" and matches it
 * *nowhere*, so there is no span to draw; the verdict still says it matched,
 * which is the honest pair of answers.
 *
 * An empty list also means "refused" or "does not compile". Both are handled
 * as their own named case rather than folded into a blanket `runCatching`, so
 * that a future change to what this searches cannot make either case silent
 * by accident: a caller that wants to tell those two apart from an honest
 * non-match, such as the pattern tester, reads [TextFilter.outcome] instead.
 */
fun matchRangesIn(pattern: String?, mode: TextMatchMode, candidate: String): List<IntRange> {
    if (pattern.isNullOrEmpty() || candidate.isEmpty()) return emptyList()

    return when (mode) {
        TextMatchMode.CONTAINS -> buildList {
            var from = 0
            while (from <= candidate.length - pattern.length) {
                val at = candidate.indexOf(pattern, from, ignoreCase = true)
                if (at < 0) break
                add(at until at + pattern.length)
                from = at + pattern.length
            }
        }

        TextMatchMode.REGEX -> try {
            val compiled = Regex(pattern, RegexOption.IGNORE_CASE)
            when (
                val run = RegexGuard.runBounded(compiled.asRegexIdentity()) {
                    compiled.findAll(candidate).map { it.range }.toList()
                }
            ) {
                is RegexRun.Completed -> run.value.filterNot { it.isEmpty() }
                is RegexRun.Refused -> {
                    // Refused, whichever of RegexRefusal's four reasons this
                    // was. There is nothing to highlight either way, and
                    // matches() already reads any of them as "no match", so
                    // an empty list here keeps this function agreeing with
                    // the verdict rather than contradicting it. A caller that
                    // wants to tell the reasons apart reads TextFilter.outcome
                    // instead, same as for "refused" versus "does not match".
                    emptyList()
                }
            }
        } catch (invalid: IllegalArgumentException) {
            // Does not compile. The tester shows that separately; this must
            // not blow up while someone is halfway through typing a bracket.
            emptyList()
        }
    }
}

/**
 * Whether [pattern] compiles, for an editor that wants to say so while typing.
 *
 * Returns null when it is fine, and the engine's complaint when it is not. Only
 * meaningful for [TextMatchMode.REGEX]; a substring is always valid.
 */
fun regexErrorOrNull(pattern: String): String? =
    if (pattern.isEmpty()) {
        null
    } else {
        try {
            Regex(pattern)
            null
        } catch (invalid: IllegalArgumentException) {
            invalid.message ?: "not a valid regular expression"
        }
    }
