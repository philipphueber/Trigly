package app.phueber.trigly.core

/**
 * A declaration of one setting a trigger or action accepts.
 *
 * Config is stored as `Map<String, String>`, which the engine is happy with and a
 * form cannot be drawn from. This is the missing half: each factory declares its
 * fields, and the editor renders them. Same pattern as [ComponentRequirement]:
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
     * KDoc (battery cost, platform restrictions, "this only works while the
     * screen is on") finally reach the person building the rule.
     */
    val help: String?

    /**
     * When this field applies at all, or null for always.
     *
     * Some settings are made irrelevant by a sibling: `play_alert`'s "keep
     * sounding for" means nothing once the tone is set to play once. Before this
     * existed the only honest option was to say so in [help] and leave the field
     * on screen, which asks someone to read a sentence explaining why the box
     * they are looking at does nothing.
     *
     * Declared on the field rather than computed by the editor so the rule lives
     * with the schema that owns it. A new field's author decides when it
     * applies, and no screen has to learn about it.
     */
    val shownWhen: FieldCondition?
        get() = null

    /**
     * Whether this field accepts `{{variable}}` references, and how a
     * substituted value is escaped for where it lands. See [Substitution] and
     * `docs/variables.md`.
     *
     * Declared per field rather than decided by the resolver, for the reason
     * [Substitution] gives: the same value needs different treatment in a
     * notification, in a URL and in a JSON body. Defaults to
     * [Substitution.NONE], so a field accepts variables only when its author
     * said it does, and `{{...}}` in any other field stays literal text.
     *
     * This is the *declaration*, which is what the editor renders a picker
     * from. The engine asks [ComponentFactory.substitutionsFor] instead, which
     * defaults to this and can narrow it by configuration.
     */
    val substitution: Substitution
        get() = Substitution.NONE

    /** Free text. */
    data class Text(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val placeholder: String? = null,
        /**
         * What leaving this empty *means*, when empty is a real setting rather
         * than a missing value: "any device", "all apps".
         *
         * Load-bearing: several components treat an absent value as "match
         * anything", so an editor that helpfully substituted a default would
         * silently narrow the rule.
         */
        val blankMeaning: String? = null,
        val multiline: Boolean = false,
        override val substitution: Substitution = Substitution.NONE,
        /**
         * Values to offer beside the box, when a person cannot be expected to
         * remember the right one and a closed picker would be wrong.
         *
         * This is the middle ground between [Text] and a kind of its own like
         * [AppPackage]. A dedicated kind means the value can *only* be picked,
         * which is right for a UUID or a MAC address and wrong here: the name of
         * a kept button can also be a `{{...}}` reference, and it can name
         * something a rule in another list keeps that nothing has kept yet. So
         * the box stays a box, and this adds a way to find out what the
         * candidates are.
         *
         * Null for every field that has no such list, which is all of them but
         * one, so nothing else changes.
         */
        val suggests: TextSuggestions? = null,
        /**
         * Extra [help] sentences, each shown only while a sibling field has one
         * of the listed values. Appended, in order, after [help] itself.
         *
         * `shownWhen` generalised "show this field" into a declared condition
         * instead of a per-screen special case; this is the same move for one
         * *sentence* of help rather than the whole field. `set_variable`'s value
         * field is the case that needed it: the same box means three different
         * things depending on the sibling `mode` field, and printing all three
         * explanations regardless of mode is what grew its help to four topics
         * and 300 characters. Declaring the mode-specific sentences here instead
         * keeps the editor itself ignorant of `mode`, `set_variable`, or any
         * other factory's vocabulary. It only ever asks "does this sibling's
         * value match", the same question [shownWith] already asks.
         */
        val helpWhen: List<ConditionalHelp> = emptyList(),
    ) : ConfigField

    /** A closed set of values. Covers both two-word toggles and wider enums. */
    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        override val required: Boolean = true,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val default: String? = null,
    ) : ConfigField

    /** A whole number. [unit] is display only, e.g. "ms", "%", "°C". */
    data class Number(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val min: Long? = null,
        val max: Long? = null,
        val default: Long? = null,
        val unit: String? = null,
    ) : ConfigField

    /** A number with decimals: coordinates, radii. */
    data class Decimal(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val min: Double? = null,
        val max: Double? = null,
        val default: Double? = null,
        val unit: String? = null,
    ) : ConfigField

    /**
     * An opaque identifier the *editor* mints when the component is added, and
     * nobody ever types or reads.
     *
     * `shortcut` needs one: a launcher shortcut has to name the rule it fires,
     * and a trigger is never told its own rule id, so the identity has to live
     * in the trigger's own config. Before this kind existed it was a required
     * `Text` field whose help said "generated automatically" while nothing
     * generated it, which is the worst shape a field can have: mandatory,
     * unfillable, and silently fatal to the rule.
     *
     * Declared rather than special-cased so the editor stays free of
     * per-component knowledge, the same reason [ConfigField] exists at all. The
     * editor seeds it once at creation and draws no control for it, because
     * there is nothing here for a person to decide.
     */
    data class GeneratedId(
        override val key: String,
        override val label: String,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
    ) : ConfigField {
        /** Always present once seeded, so never a required *prompt*. */
        override val required: Boolean get() = false
    }

    /** A switch. Stored as "true"/"false". */
    data class Flag(
        override val key: String,
        override val label: String,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val default: Boolean = false,
    ) : ConfigField {
        override val required: Boolean get() = false
    }

    /**
     * One of the user's own rules, stored as its **id**.
     *
     * The id and not the name, for the same reason a trigger's `type` string is
     * an identifier rather than a description: a rule can be renamed, and a rule
     * that stops being found because someone tidied up its title would be a
     * silent failure of exactly the kind this app keeps trying not to have. The
     * editor shows the name and stores the id. That is the trade [AppPackage]
     * already makes.
     *
     * A picker, necessarily: an id is a UUID nobody can type or recognise.
     */
    data class RuleRef(
        override val key: String,
        override val label: String,
        override val required: Boolean = true,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val blankMeaning: String? = null,
    ) : ConfigField

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
        override val shownWhen: FieldCondition? = null,
        val blankMeaning: String? = null,
    ) : ConfigField

    /**
     * A sound on this device, stored as its `content:` or `file:` URI. Stored and
     * validated exactly like [Text]; separate for the same reason [AppPackage] is:
     * nobody can produce `content://media/internal/audio/media/54` from
     * memory, and nobody should have to go looking for it.
     *
     * Blankness carries the same weight it does on [AppPackage]: an alert with no
     * custom sound uses the device's own tone, which is the sensible default and
     * has to stay reachable once the picker has been opened.
     */
    data class SoundUri(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val blankMeaning: String? = null,
    ) : ConfigField

    /**
     * A Bluetooth device, stored as its MAC address. Stored and validated exactly
     * like [Text], and separate so the editor can list the devices this phone is
     * actually paired with rather than asking for `00:11:22:33:44:55`.
     *
     * Paired devices are not the same set as devices that could ever connect, so
     * the editor keeps a way to type an address. That is the same escape hatch
     * [AppPackage] keeps for an app with no launcher icon. Reading the pairing
     * list needs `BLUETOOTH_CONNECT` from API 31, so an empty list can mean "not
     * allowed to look" rather than "nothing paired"; that is a distinction for the
     * editor to state, not to hide.
     */
    data class BluetoothAddress(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val blankMeaning: String? = null,
    ) : ConfigField

    /**
     * An emoji, stored as the literal character(s). Stored and validated exactly
     * like [Text]; separate for the same reason [AppPackage] and [SoundUri] are:
     * the editor can offer a curated grid instead of sending someone off to
     * find their keyboard's emoji tab and remember which one they used last time.
     *
     * Blankness carries the same weight it does on [AppPackage] and [SoundUri]:
     * a shortcut with no chosen icon has to fall back to something (its target's
     * own icon, a generic one), and that fallback needs to stay reachable once
     * the picker has been opened.
     */
    data class Emoji(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val blankMeaning: String? = null,
    ) : ConfigField

    /**
     * A length of time. Stored in **milliseconds**, entered in whatever unit
     * suits.
     *
     * Every duration in the app used to be a raw `Number` in ms, because that is
     * what the engine wants: defensible for a vibration and absurd for a
     * watchdog, where half an hour reads as `1800000`. This keeps the storage and
     * changes the control, which is the same trade [AppPackage] and [Slider]
     * make.
     *
     * [maxMillis] is a real cap rather than a hint where one exists: `vibrate`
     * and `play_alert` both bound their duration because a mistyped one is
     * otherwise unstoppable from inside the app. The factory still enforces it.
     * This only stops the editor offering what will be refused.
     */
    data class Duration(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val defaultMillis: Long? = null,
        val maxMillis: Long? = null,
        /** The unit the editor opens on, for a field usually set in minutes. */
        val preferred: DurationUnit = DurationUnit.SECONDS,
    ) : ConfigField

    /**
     * A moment in time, stored as epoch milliseconds.
     *
     * Rendered as a date control and a time control over one stored value,
     * because that is how a person holds "next Tuesday at 09:00" and
     * `1787900400000` is how nobody does.
     *
     * **Worth knowing what this cannot fix.** An absolute instant is a poor fit
     * for a rule that fires repeatedly: after the first run the moment is in the
     * past. The editor cannot rescue that (only an offset from the firing time
     * could), so a component using this should say what it means for a rule that
     * fires twice.
     */
    data class Timestamp(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val blankMeaning: String? = null,
    ) : ConfigField

    /**
     * A time of day, as an hour and a minute.
     *
     * The second kind after [TextPattern] to own **two** config keys, and for the
     * same reason: they are one decision. Splitting 09:00 across two number boxes
     * makes the user do a conversion their phone already has a picker for.
     *
     * Two keys rather than one "HH:mm" string so that nothing stored has to
     * change: [minuteKey] defaults to the hour key plus `Minute`, matching the
     * convention [TextPattern.modeKey] set.
     */
    data class TimeOfDay(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val minuteKey: String = "${key}Minute",
    ) : ConfigField

    /**
     * A point on the earth, as a latitude and a longitude.
     *
     * Two keys, like [TimeOfDay], because they are one answer to one question and
     * a rule with only one of them set is meaningless. Kept as two stored values
     * so nothing saved has to change.
     *
     * Still typeable (a place you are not currently standing has to be enterable
     * somehow), but the editor also offers the device's own position, which is the
     * answer for "home" and "work" and removes the copy-two-numbers-from-a-map
     * errand that a sixth decimal place makes easy to get silently wrong.
     */
    data class Coordinates(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val longitudeKey: String = "longitude",
    ) : ConfigField

    /**
     * A whole number on a bounded scale, set by feel rather than by typing.
     * Stored and validated exactly like [Number]; separate for the same reason
     * [AppPackage] is separate from [Text]: the editor can offer a different
     * control.
     *
     * The distinction is not "does it have bounds" but *what the bounds mean*.
     * `Number` bounds are a guard rail on a value you know: a poll interval of
     * 5000 ms is a decision, and a slider would make it fiddly to hit and
     * illegible once set. A `Slider` value is a position (half volume, a
     * quarter brightness) where the exact digits are the least interesting part
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
        override val shownWhen: FieldCondition? = null,
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
     * one decision ("what counts as a match here") and splitting them into a
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
        override val shownWhen: FieldCondition? = null,
        val blankMeaning: String? = null,
        val modeKey: String = "${key}Mode",
    ) : ConfigField

    /**
     * A button on a notification, chosen off one that is currently on screen.
     *
     * The kind that owns the most keys, and each is load-bearing. [key] holds the
     * button's label, [semanticKey] what the button *means* where the app said so,
     * and [packageKey] which app's notification to act on, because **the target
     * is not always the notification that fired the rule.** "When I connect to
     * the car, press play on the music notification" has a Bluetooth trigger and
     * a media target, and an action tied to its own trigger cannot express it.
     * A blank package falls back to the triggering notification, which is the
     * commoner case and stays the default.
     *
     * Three identifiers for the button rather than one because they fail
     * differently: an index breaks when the app reorders, a label breaks when the
     * app translates, and a meaning breaks only when the app stops declaring one.
     * See [chooseButton] for the order they are tried in.
     */
    data class NotificationButton(
        override val key: String,
        override val label: String,
        override val required: Boolean = false,
        override val help: String? = null,
        override val shownWhen: FieldCondition? = null,
        val semanticKey: String = "${key}Semantic",
        val packageKey: String = "package",
    ) : ConfigField

    /** One selectable value: [value] is stored, [label] is shown. */
    data class Option(val value: String, val label: String)
}

/**
 * Where a [ConfigField.Text]'s offered values come from.
 *
 * One entry, and it stays a closed set on purpose: each value is a promise that
 * something in the UI knows how to answer it, and an unanswerable entry would
 * draw a button that opens an empty list. See [ConfigField.Text.suggests].
 */
enum class TextSuggestions {

    /**
     * The names buttons are kept under: what is kept in this process right now,
     * and what the rules declare they keep. `press_captured_button` is the field
     * that needs it, because the name it presses was typed into a different
     * action, often in a different rule.
     */
    KEPT_BUTTON_NAMES,
}

/**
 * "Show this field only when a sibling has one of these values."
 *
 * Deliberately just equality against a set of strings. Config is a
 * `Map<String, String>`, the fields that gate others are choices and flags, and
 * an expression language here would be a second, worse validator competing with
 * the `create()` that already owns cross-field rules (see [ConfigField]).
 */
data class FieldCondition(val key: String, val isAnyOf: Set<String>) {
    constructor(key: String, value: String) : this(key, setOf(value))
}

/**
 * One extra sentence of help, shown only while [condition] holds. See
 * [ConfigField.Text.helpWhen].
 */
data class ConditionalHelp(val condition: FieldCondition, val help: String)

/**
 * The fields to draw, given what has been filled in so far.
 *
 * The sibling's *effective* value decides it: what is stored, or failing that
 * what the sibling would show ([defaultValue]). That matters for a rule nobody
 * has touched yet, where the gating key is absent from the config while the
 * editor is plainly displaying its default. Reading only the stored value would
 * hide "keep sounding for" on every new alert, because "repeat" had not been
 * written down yet.
 *
 * A condition naming a key that no sibling declares keeps the field visible. A
 * typo should look like a condition that does nothing, not like a field that
 * vanished.
 */
fun List<ConfigField>.shownWith(config: Map<String, String>): List<ConfigField> =
    filter { field ->
        val condition = field.shownWhen ?: return@filter true
        val sibling = firstOrNull { it.key == condition.key } ?: return@filter true
        val effective = config[condition.key] ?: sibling.defaultValue()
        effective in condition.isAnyOf
    }

/**
 * The required fields, among the ones currently shown, that still have no
 * value.
 *
 * This is the line between two different problems a component can have.
 * *Absent* is this: a required field nobody has typed anything into yet, on
 * a trigger or action a person picked and has not finished configuring. It
 * is not wrong, only not done. *Wrong* is everything else `create()` can
 * still refuse: a value that is present but malformed, or a combination of
 * present values that breaks a cross-field rule such as
 * `notification_watchdog`'s "poll must not exceed absence". Only a field
 * this function reports is read as "not done"; anything `create()` throws
 * once every field here is filled in is read as broken, exactly as before.
 *
 * [required] alone is not the question, twice over:
 *
 * - A field a sibling currently hides is not something a person left blank,
 *   it is something the form is not asking for right now. [shownWith] is
 *   what already answers that, consulted here rather than duplicated.
 * - A field with a declared default is not blank either, the same reasoning
 *   [shownWith] uses for a gating key nobody has touched: absent means the
 *   form is already showing a real value, not that a person left it empty.
 *   `defaultConfigFor` fills such a default into a component's config the
 *   moment it is added in the editor, so this mostly guards a different
 *   entry point: a rule from an older export, or one written by hand, whose
 *   `play_alert` never wrote down "Tone" at all. Reading only the stored
 *   config would call that one unfinished over a field it has always
 *   effectively answered, the alarm tone, and refuse to enable a rule
 *   nothing is actually wrong with.
 */
fun List<ConfigField>.unfilled(config: Map<String, String>): List<ConfigField> =
    shownWith(config).filter { field ->
        field.required && (config[field.key] ?: field.defaultValue()).isNullOrBlank()
    }

/**
 * The extra config keys a field kind owns beyond [ConfigField.key], plus (for
 * a [ConfigField.Text] with [ConfigField.Text.helpWhen]) the sibling keys
 * it only *reads*.
 *
 * Declared once, here, because three places need the same answer and had grown
 * their own copies: the editor, to hand a field its companion values; the rule
 * editor screen, to read them out of the stored config; and the schema contract
 * test, to build a sample a factory will accept. A fourth copy is how a two-key
 * field ends up half-populated and its factory blamed for it.
 *
 * [ConfigField.Text.helpWhen] stretches this beyond "owns": a `mode` field is
 * not the value field's own key, only one it needs to *see*. It is still the
 * right list to add it to, because the one consumer that cares about the
 * distinction (the schema contract test's synthetic sample) already treats a
 * key that coincides with another field's own key as that field's problem to
 * seed, not this one's. See its `sampleConfig`.
 */
fun ConfigField.companionKeys(): List<String> = when (this) {
    is ConfigField.TextPattern -> listOf(modeKey)
    is ConfigField.TimeOfDay -> listOf(minuteKey)
    is ConfigField.Coordinates -> listOf(longitudeKey)
    is ConfigField.NotificationButton -> listOf(semanticKey, packageKey)
    is ConfigField.Text -> helpWhen.map { it.condition.key }.distinct()
    else -> emptyList()
}

/**
 * [ConfigField.help] plus whichever [ConfigField.Text.helpWhen] sentences
 * apply, given the sibling values in [companions].
 *
 * Every kind but [ConfigField.Text] has no [ConfigField.Text.helpWhen] to
 * apply, so every kind but that one answers with [ConfigField.help] unchanged.
 * This only exists at all because the one kind's help can vary.
 *
 * Unlike [shownWith], a sibling with nothing stored is read as *not* matching
 * any condition rather than falling back to that sibling's own default. The
 * two asymmetric failure modes justify the asymmetric rule: [shownWith]
 * guessing wrong hides a field that should be on screen, which is the
 * obviously worse mistake, so it resolves the sibling's default to be safe.
 * Here, guessing wrong prints a sentence that does not apply yet: a rule
 * still being built, with its `mode` not yet chosen, has no evaluate-only or
 * add-only sentence to show, and showing one anyway would describe a mode
 * nobody picked.
 */
fun ConfigField.effectiveHelp(companions: Map<String, String?>): String? {
    if (this !is ConfigField.Text || helpWhen.isEmpty()) return help
    val extra = helpWhen.filter { companions[it.condition.key] in it.condition.isAnyOf }
    return (listOfNotNull(help) + extra.map { it.help }).joinToString(" ").ifEmpty { null }
}

/**
 * The units a [ConfigField.Duration] can be entered in.
 *
 * Deliberately stops at hours. A rule measured in days is a scheduling problem,
 * not a duration one, and would be badly served by a number box either way.
 */
enum class DurationUnit(val millis: Long, val label: String) {
    MILLISECONDS(1, "ms"),
    SECONDS(1_000, "sec"),
    MINUTES(60_000, "min"),
    HOURS(3_600_000, "hours"),
    ;

    companion object {
        /**
         * The largest unit that divides [millis] exactly, so a stored value comes
         * back as the number someone would have typed: 1800000 reads as 30 min,
         * not 1800 sec. Falls back to milliseconds, which divides everything.
         */
        fun bestFor(millis: Long): DurationUnit =
            entries.lastOrNull { millis % it.millis == 0L } ?: MILLISECONDS
    }
}

/**
 * The value the editor should start this field at: the declared default, or
 * nothing. Deliberately null rather than an empty string for text fields whose
 * blankness is meaningful; see [ConfigField.Text.blankMeaning].
 */
fun ConfigField.defaultValue(): String? = when (this) {
    is ConfigField.Text -> null
    is ConfigField.AppPackage -> null
    // Deliberately null: a fresh identifier cannot come from a pure function
    // called on every read. It would differ each time it was asked. The editor
    // mints one when the component is added; see `defaultConfigFor`.
    is ConfigField.GeneratedId -> null
    // Nothing to preselect: which rule is the whole question.
    is ConfigField.RuleRef -> null
    // Both pick something whose absence is itself a setting (the device's own
    // tone, any Bluetooth device), so there is nothing to preselect.
    is ConfigField.SoundUri -> null
    is ConfigField.BluetoothAddress -> null
    // Same reasoning: a chosen icon is a choice, not a starting position, and a
    // curated grid preselecting its first entry would look like someone picked
    // it on purpose.
    is ConfigField.Emoji -> null
    is ConfigField.Duration -> defaultMillis?.toString()
    // Both are "not set" until picked. A timestamp defaulted to now would be a
    // rule that quietly means "the moment you opened the editor", and a time of
    // day defaulted to midnight is a value nobody chose.
    is ConfigField.Timestamp -> null
    is ConfigField.TimeOfDay -> null
    // A default coordinate would be a place. There is no sensible one.
    is ConfigField.Coordinates -> null
    // Nothing to preselect: which button depends on a notification the editor
    // cannot see until one is posted.
    is ConfigField.NotificationButton -> null
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
