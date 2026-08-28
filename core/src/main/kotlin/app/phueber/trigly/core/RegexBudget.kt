package app.phueber.trigly.core

/**
 * The bound one regular expression search may spend, and the pieces that
 * enforce it. Shared by every path in `:core` that runs a pattern someone
 * typed against text this app collects:
 *
 * - `contains(a, b, "regex")` in `Expression.kt`. That file's own KDoc,
 *   "Safety is exactly three numbers", has the reasoning for why a regular
 *   expression needs a bound at all and why the bound is a rate rather than a
 *   timeout or a flat number. Read that section first; it is not repeated
 *   here.
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
 * **This bound holds on the JVM and not on Android. See `docs/todo.md` T24.**
 * The platform's `Matcher` converts its input to a `String` when it is handed
 * anything else, so [BudgetedText.get] is never called on a phone and no read
 * is ever counted. Every test of this file passes, because they run on the JVM.
 * That is the trap, not a reassurance. T24 holds the three ways out.
 *
 * **Characters read, not milliseconds.** A bound has to mean the same thing on
 * a fast phone and a slow one. A timeout would let a rule work on one device
 * and fail on another, which is the failure this project spends most of its
 * effort avoiding. The engine reads its input one character at a time through
 * [BudgetedText], and backtracking reads the same characters again, so
 * counting reads counts work.
 *
 * Ten thousand is between three and four times what the most expensive honest
 * pattern measured needs (`.*b` over 1800 characters: 4.9 million reads, so
 * 2700 per character), and far below the cheapest bad one measured
 * (`.*.*.*b` over sixty characters: 31000 per character).
 */
internal const val MAX_REGEX_READS_PER_CHARACTER: Int = 10_000

/**
 * What one regular expression may read in total, whatever the length of the
 * text. The ceiling on [MAX_REGEX_READS_PER_CHARACTER], and the reason a
 * single search stays bounded even if it is ever handed more text than
 * whatever sized the rate for it fed it. See `Expression.kt`'s
 * "Safety is exactly three numbers" for `round` (`docs/todo.md` T22) as the
 * one function that can do that today.
 *
 * Twenty million reads is about eighty milliseconds on a desktop JVM, and it
 * is a ceiling on the pathological case rather than a cost anything ordinary
 * comes near. Over a notification-sized piece of text every pattern measured
 * cost a few thousand reads, and the most expensive honest case measured, an
 * unanchored pattern missing over 1800 characters, cost 4.9 million.
 */
internal const val MAX_REGEX_READS: Int = 20_000_000

/**
 * The read allowance for a search over text [candidateLength] characters long:
 * [MAX_REGEX_READS_PER_CHARACTER] for every character, capped at
 * [MAX_REGEX_READS]. Grows with the text, because the honest cost of a search
 * does too. The `+ 1` keeps an empty candidate from being given an allowance
 * of nothing, since a search over zero characters still starts the engine
 * once.
 */
internal fun regexReadAllowance(candidateLength: Int): Int {
    val allowance = MAX_REGEX_READS_PER_CHARACTER.toLong() * (candidateLength + 1)
    return minOf(allowance, MAX_REGEX_READS.toLong()).toInt()
}

/**
 * Thrown out of the middle of the regex engine when a search has read
 * everything it is allowed to. Its own type rather than a general exception,
 * because it is thrown from [CharSequence.get], through code this project
 * does not own: something in there catching a passing [Exception] would
 * swallow the bound, and a distinct unchecked type keeps that from happening
 * by accident.
 *
 * Every caller of [BudgetedText] must catch this on purpose. What it means
 * when caught is the caller's decision: `Expression.kt`'s `regexFinds` turns
 * it into a refused expression, [TextFilter.matches] turns it into "did not
 * match" because it has no channel to say anything else, and [matchRangesIn]
 * turns it into "nothing to highlight" for the same reason.
 */
internal class RegexBudgetSpent : RuntimeException()

/**
 * [text], with a hard cap on how many characters a regular expression may read
 * from it.
 *
 * The engine reads its input through [CharSequence.get] one character at a
 * time, and a backtracking pattern reads the same character over and over. So
 * counting reads counts the *work*, which is the only way to bound a pattern
 * that does four hundred million reads over eighteen hundred characters.
 *
 * [subSequence] is not counted, and does not need to be: it is how a match
 * group would be read, and every caller here only ever asks whether a match
 * exists, or where one starts and ends.
 */
internal class BudgetedText(private val text: String, private var allowance: Int) : CharSequence {

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (allowance <= 0) throw RegexBudgetSpent()
        allowance--
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        text.subSequence(startIndex, endIndex)

    /**
     * **Load-bearing for correctness, not a convenience.** Android's
     * `java.util.regex.Matcher` converts its input to a `String` when it is
     * handed anything that is not already one. Without this override that
     * conversion produced `Object.toString()`, so a pattern searched
     * "app.phueber.trigly.core.BudgetedText@1a2b3c4d" rather than the text, and
     * matched or missed on the hex digits of a hash code. Found on a device: a
     * six-character sample reported a match at index 37.
     *
     * The same conversion is why the read counting in [get] does nothing on
     * Android. [get] is never called there. See `docs/todo.md` T24: the bound
     * these classes describe holds on the JVM, where the tests run, and not on
     * the phone, where it matters.
     */
    override fun toString(): String = text
}
