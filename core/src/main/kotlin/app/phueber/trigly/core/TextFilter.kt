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
 */
class TextFilter private constructor(
    private val predicate: (String?) -> Boolean,
    /** What the user typed, kept for error messages and equality. */
    val pattern: String?,
    val mode: TextMatchMode,
) {

    fun matches(candidate: String?): Boolean = predicate(candidate)

    /** True when this filter has no opinion — an empty pattern. */
    val isEmpty: Boolean get() = pattern.isNullOrEmpty()

    override fun toString(): String =
        if (isEmpty) "any text" else "${mode.configValue} '$pattern'"

    override fun equals(other: Any?): Boolean =
        other is TextFilter && other.pattern == pattern && other.mode == mode

    override fun hashCode(): Int = 31 * (pattern?.hashCode() ?: 0) + mode.hashCode()

    companion object {

        /** A filter that lets everything through. */
        val Any: TextFilter = TextFilter({ true }, null, TextMatchMode.CONTAINS)

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
                TextMatchMode.CONTAINS -> TextFilter(
                    predicate = { it?.contains(pattern, ignoreCase = true) == true },
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
                        // containsMatchIn, not matches: a regex filter reads like
                        // grep, finding the pattern anywhere. Anchoring with ^ and
                        // $ is available to anyone who wants the whole string, and
                        // requiring it by default would surprise everyone else.
                        predicate = { it != null && compiled.containsMatchIn(it) },
                        pattern = pattern,
                        mode = mode,
                    )
                }
            }
        }

        /** Builds from raw config, the form a factory has. */
        fun fromConfig(pattern: String?, rawMode: String?): TextFilter =
            of(pattern, TextMatchMode.parse(rawMode))
    }
}

/**
 * Whether [pattern] compiles, for an editor that wants to say so while typing.
 *
 * Returns null when it is fine, and the engine's complaint when it is not. Only
 * meaningful for [TextMatchMode.REGEX]; a substring is always valid.
 */
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
 * from the verdict on the first capital letter.
 *
 * Zero-width matches are dropped. A pattern like `a*` matches "b" and matches it
 * *nowhere*, so there is no span to draw; the verdict still says it matched,
 * which is the honest pair of answers.
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

        TextMatchMode.REGEX -> runCatching {
            Regex(pattern, RegexOption.IGNORE_CASE)
                .findAll(candidate)
                .map { it.range }
                .filterNot { it.isEmpty() }
                .toList()
        }.getOrDefault(emptyList())
    }
}

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
