package app.phueber.trigly.actions

import android.content.Context
import android.media.AudioManager
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ExpressionOutcome
import app.phueber.trigly.core.FieldCondition
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.evaluateExpression
import java.math.BigDecimal
import java.math.RoundingMode

/** Which stream a volume action targets, in the user's words. */
enum class VolumeStream(
    val configValue: String,
    val streamType: Int,
    /** What the picker shows. Separate from [configValue], which rules store. */
    val displayName: String,
) {
    MEDIA("media", AudioManager.STREAM_MUSIC, "Media and video"),
    RING("ring", AudioManager.STREAM_RING, "Ringtone"),
    ALARM("alarm", AudioManager.STREAM_ALARM, "Alarms"),
    NOTIFICATION("notification", AudioManager.STREAM_NOTIFICATION, "Notifications"),
    ;

    companion object {
        const val CONFIG_KEY = "stream"

        fun parse(raw: String?): VolumeStream =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
    }
}

/**
 * Converts a 0–100 percentage to a stream index.
 *
 * Android reports each stream's maximum separately, and the maxima differ by
 * stream and by device — 7 for ring on one phone, 15 for media on another. A
 * percentage is the only unit a rule can specify portably. Pure, so the rounding
 * is tested rather than assumed.
 */
fun volumeIndexFor(percent: Int, maxIndex: Int): Int {
    val clamped = percent.coerceIn(0, 100)
    return Math.round(maxIndex * clamped / 100f)
}

/**
 * Where `set_volume` takes the level from.
 *
 * [FIXED] is a number somebody chose while building the rule. [FROM_VALUE] is
 * a value the rule works out while it runs, which is what makes "save the
 * level, change it, put it back" possible with `get_volume`.
 *
 * One choice and two fields, rather than two fields that are both always on
 * screen. Two boxes that can disagree need a rule for which one wins, and
 * there is no answer to that which a person could guess. See [FieldCondition]
 * and `set_variable`, which pairs a mode with a value field the same way.
 */
enum class VolumeLevelSource(val configValue: String, val displayName: String) {
    FIXED("fixed", "a level I set"),
    FROM_VALUE("value", "a value from the rule"),
    ;

    companion object {
        const val CONFIG_KEY = "source"

        /**
         * An absent value reads as [FIXED], and an unknown one is refused.
         *
         * **The absent case is every rule saved before this field existed.**
         * Those rules hold a `percent` and nothing else, and they meant "a
         * level I set". A strict parse here would stop each of them at the
         * next update, with nothing on screen to say why. `ConfigParsing.kt`
         * in `:triggers` records the same trap and the same answer.
         *
         * An unknown value is the opposite call, for the reason
         * [VariableWriteMode.parse] refuses one: a rule from a newer build
         * names a source this build cannot read, and falling back to the
         * slider would set a level nobody asked for. A wrong volume is worse
         * than a rule that says it cannot run.
         */
        fun parse(raw: String?): VolumeLevelSource {
            val trimmed = raw?.trim()
            if (trimmed.isNullOrEmpty()) return FIXED
            return entries.firstOrNull { it.configValue.equals(trimmed, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$trimmed'"
                )
        }
    }
}

/**
 * The level one `set_volume` action sets, in the two forms a rule can hold it.
 *
 * [Fixed] is known when the rule is built, so [SetVolumeActionFactory.create]
 * parses it there, exactly as it always did. [FromValue] is not known then:
 * the text still holds `{{...}}` references when the rule starts, and the
 * engine only fills them in as each event arrives. See [volumePercentFor] for
 * where that half is parsed instead.
 */
sealed interface VolumeLevel {

    /** A percent somebody chose in the editor. */
    data class Fixed(val percent: Int) : VolumeLevel

    /** Expression source, already substituted by the engine. */
    data class FromValue(val source: String) : VolumeLevel
}

/**
 * What a [VolumeLevel.FromValue] works out to.
 *
 * A returned value and not a thrown exception, for the reason [VolumeReading]
 * is one: the caller turns the outcome into an [ActionResult] anyway, and a
 * value that is not a level is ordinary traffic here rather than a fault in
 * the app.
 */
sealed interface VolumeLevelOutcome {

    /** A percent from 0 to 100, ready for [volumeIndexFor]. */
    data class Percent(val value: Int) : VolumeLevelOutcome

    /** Nothing was set. [reason] is shown to the person who built the rule. */
    data class Failed(val reason: String) : VolumeLevelOutcome
}

/** The top of the percent range, as [volumePercentFor] clamps against it. */
private val HUNDRED_PERCENT: BigDecimal = BigDecimal(100)

/**
 * Works [source] out as a percent, for `set_volume`'s "a value from the rule".
 *
 * **This runs at execute time, not in `create()`, and it has to.** The engine
 * builds the action once from the raw config, where the level still reads
 * `{{mine.volume}} - 20`, and rebuilds it as each event fills those references
 * in. A parse in `create()` would refuse the raw form and stop the rule from
 * starting at all. See `TriggerEngine.ActionSlot`.
 *
 * [source] is expression source, the same language `set_variable`'s compute
 * mode and `run_rule`'s "only if" run, because a plain variable is already a
 * valid expression: `{{mine.volume}}` arrives as `40` and works out to 40,
 * while `{{mine.volume}} - 20` needs the evaluator. One field covers both, and
 * a person does not have to know which kind they typed.
 *
 * Four answers, and each one is a sentence somebody can act on:
 *
 * - Empty, before or after the references are filled in: refused. **A silent
 *   0 would put the phone to silent**, which is a real setting nobody asked
 *   for here.
 * - Source that does not parse, or a function that fails: refused, with the
 *   evaluator's own reason.
 * - A result that is not a number, such as a variable holding `high`:
 *   refused, naming what it found.
 * - A number outside 0 to 100: clamped to the nearest end, and set. This is
 *   arithmetic that ran off the end of a range, `{{mine.volume}} + 20` at 90,
 *   and the nearest end is what it asked for. [volumeIndexFor] clamps the
 *   fixed path the same way and always has, so the two sources cannot answer
 *   differently. The clamp is done here as well as there because a percent in
 *   the millions must not reach [Int] arithmetic at all.
 *
 * A result with decimals is rounded to the nearest whole percent, half up,
 * because `{{mine.volume}} / 2` is an ordinary thing to write and there is no
 * volume step between two percents anyway. That is how [volumeIndexFor] and
 * `volumePercentOf` both round, so the whole feature rounds one way.
 */
fun volumePercentFor(source: String): VolumeLevelOutcome {
    val trimmed = source.trim()
    if (trimmed.isEmpty()) return emptyLevel()

    val computed = when (val outcome = evaluateExpression(trimmed)) {
        is ExpressionOutcome.Failed -> return VolumeLevelOutcome.Failed(
            "The volume level could not be worked out. ${outcome.reason}"
        )

        is ExpressionOutcome.Ok -> outcome.value.trim()
    }

    if (computed.isEmpty()) return emptyLevel()

    val number = computed.toBigDecimalOrNull() ?: return VolumeLevelOutcome.Failed(
        "The volume level is '$computed', which is not a number. A level is a " +
            "number from 0 to 100."
    )

    val percent = number
        .setScale(0, RoundingMode.HALF_UP)
        .coerceIn(BigDecimal.ZERO, HUNDRED_PERCENT)
    return VolumeLevelOutcome.Percent(percent.toInt())
}

/**
 * One sentence for both ways a level can come up empty: a field nobody filled
 * in, and a variable that holds nothing. They are the same problem to the
 * person reading the fault log, so they read the same.
 */
private fun emptyLevel(): VolumeLevelOutcome = VolumeLevelOutcome.Failed(
    "The volume level is empty, so the volume was not changed."
)

/**
 * Whether the level field holds a reference to fill in, given [config]. See
 * [SetVolumeActionFactory.substitutionsFor], which is the one caller.
 *
 * Never throws, where [VolumeLevelSource.parse] does. This answer is asked for
 * stored config, by the editor as somebody types and by the engine as a rule
 * starts, and neither has anywhere to put a refusal. A rule naming a source
 * this build cannot read is refused by `create()`, which is the one place that
 * can report it, so this reads it as the source it has always been instead.
 */
fun volumeLevelAcceptsVariables(config: Map<String, String>): Boolean =
    runCatching { VolumeLevelSource.parse(config[VolumeLevelSource.CONFIG_KEY]) }
        .getOrDefault(VolumeLevelSource.FIXED) == VolumeLevelSource.FROM_VALUE

/**
 * The level [SetVolumeActionFactory] builds one action from.
 *
 * Separate from `create()` so the two config shapes can be checked without a
 * `Context`, which a JVM test cannot supply. A rule saved before the source
 * field existed holds a `percent` and no `source`, and lands on
 * [VolumeLevel.Fixed] through [VolumeLevelSource.parse]'s default.
 */
fun volumeLevelFrom(config: Map<String, String>): VolumeLevel =
    when (VolumeLevelSource.parse(config[VolumeLevelSource.CONFIG_KEY])) {
        VolumeLevelSource.FIXED -> {
            val raw = config[SetVolumeAction.CONFIG_PERCENT]
                ?: error("${SetVolumeAction.TYPE} needs '${SetVolumeAction.CONFIG_PERCENT}'")
            VolumeLevel.Fixed(
                raw.toIntOrNull()
                    ?: error(
                        "${SetVolumeAction.CONFIG_PERCENT} must be a number 0-100, was '$raw'"
                    )
            )
        }

        VolumeLevelSource.FROM_VALUE ->
            VolumeLevel.FromValue(config[SetVolumeAction.CONFIG_LEVEL].orEmpty())
    }

/**
 * Sets the volume of one audio stream.
 *
 * The level is a [VolumeLevel] rather than an [Int] because half of one is not
 * known until the action runs. See [volumePercentFor], which is where a
 * [VolumeLevel.FromValue] is parsed and clamped, and why it cannot happen
 * sooner.
 */
class SetVolumeAction(
    private val context: Context,
    private val stream: VolumeStream,
    private val level: VolumeLevel,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        // Before the audio service, so a level that cannot be worked out
        // reports what is really wrong with it rather than whatever the
        // device says next.
        val percent = when (level) {
            is VolumeLevel.Fixed -> level.percent
            is VolumeLevel.FromValue -> when (val outcome = volumePercentFor(level.source)) {
                is VolumeLevelOutcome.Failed -> return ActionResult.Failure(outcome.reason)
                is VolumeLevelOutcome.Percent -> outcome.value
            }
        }

        val audio = context.getSystemService(AudioManager::class.java)
            ?: return ActionResult.Failure("This device has no audio service.")

        val maxIndex = audio.getStreamMaxVolume(stream.streamType)
        val index = volumeIndexFor(percent, maxIndex)

        return try {
            audio.setStreamVolume(stream.streamType, index, 0)
            ActionResult.Success()
        } catch (denied: SecurityException) {
            // Dropping a stream to zero counts as entering Do Not Disturb, which
            // needs notification-policy access.
            ActionResult.Failure(
                "Changing this volume needs Do Not Disturb access. ${denied.message}",
                denied,
            )
        }
    }

    companion object {
        const val TYPE = "set_volume"
        const val CONFIG_PERCENT = "percent"

        /** The level worked out while the rule runs. See [VolumeLevel.FromValue]. */
        const val CONFIG_LEVEL = "level"
    }
}

class SetVolumeActionFactory(private val context: Context) : ActionFactory {
    override val type = SetVolumeAction.TYPE

    override val displayName = "Set the volume"
    override val category = ActionCategory.DEVICE

    override val configFields = listOf(
        ConfigField.Choice(
            key = VolumeStream.CONFIG_KEY,
            label = "Which volume",
            options = VolumeStream.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
        ),
        // Above the two level fields, because it decides which of them is on
        // screen. Defaulted to the slider, which is what this action did
        // before the choice existed.
        ConfigField.Choice(
            key = VolumeLevelSource.CONFIG_KEY,
            label = "Level",
            options = VolumeLevelSource.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            default = VolumeLevelSource.FIXED.configValue,
        ),
        // A slider, like play_alert's volume: this is a position, not a number
        // anyone decides. The architecture doc argued the case and the rule was
        // only ever applied to the other volume in the app.
        ConfigField.Slider(
            key = SetVolumeAction.CONFIG_PERCENT,
            label = "Set to",
            min = 0,
            max = 100,
            default = 50,
            unit = "%",
            help = "This value is a percentage. The number of volume steps " +
                "differs by phone and by stream.",
            shownWhen = FieldCondition(
                key = VolumeLevelSource.CONFIG_KEY,
                value = VolumeLevelSource.FIXED.configValue,
            ),
        ),
        ConfigField.Text(
            key = SetVolumeAction.CONFIG_LEVEL,
            label = "Set to",
            required = true,
            substitution = Substitution.EXPRESSION,
            help = "A number from 0 to 100, a variable such as {{mine.volume}}, " +
                "or a sum such as {{mine.volume}} - 20. A result above 100 or " +
                "below 0 goes to the nearest end. A result that is not a number " +
                "stops this action, and the volume stays as it is.",
            shownWhen = FieldCondition(
                key = VolumeLevelSource.CONFIG_KEY,
                value = VolumeLevelSource.FROM_VALUE.configValue,
            ),
        ),
    )

    /**
     * The level field carries an expression, and only while the source field
     * asks for one. [HttpRequestActionFactory.substitutionsFor] and
     * [SetVariableActionFactory.substitutionsFor] are the pattern, and both
     * read their sibling with the same fallback `create()` uses for it.
     *
     * Dropped entirely for the slider source, rather than narrowed to
     * [Substitution.TEXT]. A rule that used the level field and then went back
     * to the slider keeps the text it typed, and a reference in that text
     * would still be resolved every time the rule fired. A name that no longer
     * exists would then refuse an action whose level does not even come from
     * there. See `TriggerEngine.ActionSlot.fill`.
     */
    override fun substitutionsFor(config: Map<String, String>): Map<String, Substitution> =
        super.substitutionsFor(config).let { declared ->
            if (volumeLevelAcceptsVariables(config)) {
                declared
            } else {
                declared - SetVolumeAction.CONFIG_LEVEL
            }
        }

    override fun create(config: Map<String, String>): Action = SetVolumeAction(
        context = context,
        stream = VolumeStream.parse(config[VolumeStream.CONFIG_KEY]),
        level = volumeLevelFrom(config),
    )
}

/** Normal, vibrate or silent. */
enum class RingerMode(
    val configValue: String,
    val mode: Int,
    val displayName: String,
) {
    NORMAL("normal", AudioManager.RINGER_MODE_NORMAL, "ring out loud"),
    VIBRATE("vibrate", AudioManager.RINGER_MODE_VIBRATE, "vibrate only"),
    SILENT("silent", AudioManager.RINGER_MODE_SILENT, "silent"),
    ;

    companion object {
        const val CONFIG_KEY = "mode"

        fun parse(raw: String?): RingerMode =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
    }
}

/**
 * Switches between normal, vibrate and silent.
 *
 * From API 23, moving *into* silent or vibrate counts as changing Do Not
 * Disturb state and throws without notification-policy access — which is
 * granted on a settings screen, not through a permission dialog. Declared as a
 * requirement so the UI can send the user there.
 */
class SetRingerModeAction(
    private val context: Context,
    private val mode: RingerMode,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return ActionResult.Failure("This device has no audio service.")

        return try {
            audio.ringerMode = mode.mode
            ActionResult.Success()
        } catch (denied: SecurityException) {
            ActionResult.Failure(
                "Switching to ${mode.configValue} needs Do Not Disturb access.",
                denied,
            )
        }
    }

    companion object {
        const val TYPE = "set_ringer_mode"
    }
}

class SetRingerModeActionFactory(private val context: Context) : ActionFactory {
    override val type = SetRingerModeAction.TYPE

    override val displayName = "Set ringer mode"
    override val category = ActionCategory.DEVICE

    override val configFields = listOf(
        ConfigField.Choice(
            key = RingerMode.CONFIG_KEY,
            label = "Switch to",
            options = RingerMode.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
        ),
    )

    override val requirements = listOf(
        ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_POLICY),
    )

    override fun create(config: Map<String, String>): Action = SetRingerModeAction(
        context = context,
        mode = RingerMode.parse(config[RingerMode.CONFIG_KEY]),
    )
}
