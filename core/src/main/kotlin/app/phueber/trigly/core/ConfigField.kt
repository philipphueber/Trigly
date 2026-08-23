package app.phueber.trigly.core

/**
 * A declaration of one setting a trigger or action accepts.
 *
 * Config is stored as `Map<String, String>`, which the engine is happy with and a
 * form cannot be drawn from. This is the missing half: each factory declares its
 * fields, and the editor renders them. Same pattern as [ComponentRequirement] —
 * declared on the factory, consumed by the UI, invisible to the engine.
 *
 * **The schema renders; the factory validates.** Nothing here duplicates the
 * `require()` and `error()` checks inside `create()`. Bounds like [Number.min]
 * exist to pick a keyboard and write a hint, not to guarantee anything: the
 * editor validates by calling `create()` and showing what it throws. That is the
 * only approach that catches cross-field rules such as
 * `notification_watchdog`'s "poll must not exceed absence", or the
 * `require(periodMillis > 0)` that lives in `IntervalTrigger`'s constructor
 * rather than its factory.
 */
sealed interface ConfigField {

    val key: String
    val label: String
    val required: Boolean

    /**
     * Shown beneath the field. This is where the caveats that currently live in
     * KDoc — battery cost, platform restrictions, "this only works while the
     * screen is on" — finally reach the person building the rule.
     */
    val help: String?

    /** Free text. */
    data class Text(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val placeholder: String? = null,
        /**
         * What leaving this empty *means*, when empty is a real setting rather
         * than a missing value — "any device", "all apps".
         *
         * Load-bearing: several components treat an absent value as "match
         * anything", so an editor that helpfully substituted a default would
         * silently narrow the rule.
         */
        val blankMeaning: String? = null,
        val multiline: Boolean = false,
    ) : ConfigField

    /** A closed set of values. Covers both two-word toggles and wider enums. */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        override val required: Boolean = true,
        override val help: String? = null,
        val default: String? = null,
    ) : ConfigField

    /** A whole number. [unit] is display only, e.g. "ms", "%", "°C". */
    data class Number(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val min: Long? = null,
        val max: Long? = null,
        val default: Long? = null,
        val unit: String? = null,
    ) : ConfigField

    /** A number with decimals — coordinates, radii. */
    data class Decimal(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val min: Double? = null,
        val max: Double? = null,
        val default: Double? = null,
        val unit: String? = null,
    ) : ConfigField

    /** A switch. Stored as "true"/"false". */
    data class Flag(
        override val key: String,
        override val label: String,
        override val help: String? = null,
        val default: Boolean = false,
    ) : ConfigField {
        override val required: Boolean get() = false
    }

    /**
     * An installed app's package name. Stored and validated exactly like [Text];
     * separate so the editor can offer a picker instead of asking someone to
     * type "com.google.android.dialer" from memory.
     */
    data class AppPackage(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val blankMeaning: String? = null,
    ) : ConfigField

    /**
     * A whole number on a bounded scale, set by feel rather than by typing.
     * Stored and validated exactly like [Number]; separate for the same reason
     * [AppPackage] is separate from [Text] — so the editor can offer a different
     * control.
     *
     * The distinction is not "does it have bounds" but *what the bounds mean*.
     * `Number` bounds are a guard rail on a value you know: a poll interval of
     * 5000 ms is a decision, and a slider would make it fiddly to hit and
     * illegible once set. A `Slider` value is a position — half volume, a
     * quarter brightness — where the exact digits are the least interesting part
     * and dragging is how anyone actually thinks about it.
     *
     * [min] and [max] are required, unlike on [Number]: a scale with an open end
     * is not a scale, and there would be nothing to draw.
     */
    data class Slider(
        override val key: String,
        override val label: String,
        val min: Long,
        val max: Long,
        val default: Long,
        override val help: String? = null,
        val unit: String? = null,
    ) : ConfigField {
        /** A slider always has a position, so it always has a value. */
        override val required: Boolean get() = false

        init {
            require(min < max) { "$key: min ($min) must be below max ($max)" }
            require(default in min..max) {
                "$key: default ($default) is outside $min..$max"
            }
        }
    }

    /**
     * Text to match against, plus how to match it.
     *
     * The only kind that owns **two** config keys: [key] holds the pattern and
     * [modeKey] holds the [TextMatchMode]. They are one field because they are
     * one decision — "what counts as a match here" — and splitting them into a
     * text box and an unrelated dropdown would put the mode somewhere the
     * pattern's own editor cannot see it. It has to see it: a regex is worth
     * syntax-highlighting and worth checking as it is typed, and a substring is
     * neither.
     *
     * [modeKey] defaults to the pattern key plus `Mode`, so a factory declares
     * one name and both keys follow. An older rule with no mode key stored reads
     * as `contains`, which is what it meant before the mode existed.
     */
    data class TextPattern(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        val blankMeaning: String? = null,
        val modeKey: String = "${key}Mode",
    ) : ConfigField

    /** One selectable value: [value] is stored, [label] is shown. */
    data class Option(val value: String, val label: String)
}

/**
 * The value the editor should start this field at — the declared default, or
 * nothing. Deliberately null rather than an empty string for text fields whose
 * blankness is meaningful; see [ConfigField.Text.blankMeaning].
 */
fun ConfigField.defaultValue(): String? = when (this) {
    is ConfigField.Text -> null
    is ConfigField.AppPackage -> null
    // The pattern, like any text whose blankness means "no filter". The mode key
    // is deliberately not defaulted either: absent reads as `contains`, so
    // writing it out would only add noise to every exported rule.
    is ConfigField.TextPattern -> null
    is ConfigField.Choice -> default
    is ConfigField.Number -> default?.toString()
    is ConfigField.Decimal -> default?.toString()
    is ConfigField.Flag -> default.toString()
    // Never null: a slider is drawn at a position whether or not one was stored,
    // so the stored value has to agree with what is on screen from the start.
    is ConfigField.Slider -> default.toString()
}
