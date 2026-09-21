package app.phueber.trigly.actions

import android.content.Context
import android.media.AudioManager
import android.os.Build
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.RuleVariableStore
import app.phueber.trigly.core.RunScope
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableScope
import app.phueber.trigly.core.VariableSpec
import app.phueber.trigly.core.VariableWriteSpec
import app.phueber.trigly.core.normalizeVariableName
import app.phueber.trigly.core.variableNameProblem
import kotlin.coroutines.coroutineContext

// ---------------------------------------------------------------------------
// The port
// ---------------------------------------------------------------------------

/**
 * What one stream's volume reads as.
 *
 * A returned value and not a thrown exception, for the reason [TorchResult] is
 * one: the caller has to turn the outcome into an [ActionResult] anyway, and a
 * stream that cannot be read is ordinary traffic here rather than a fault in
 * the app.
 */
sealed interface VolumeReading {

    /**
     * The stream's own numbers, as the platform reports them.
     *
     * Three numbers and not one percent, because the percent is arithmetic and
     * the arithmetic is the part that must be testable without a device. See
     * [volumePercentOf].
     */
    data class Level(val current: Int, val min: Int, val max: Int) : VolumeReading

    /** Nothing was read. [reason] is shown to the person who built the rule. */
    data class Failed(val reason: String) : VolumeReading
}

/**
 * The device's volume levels, as `get_volume` needs them.
 *
 * **A port, and it stays in `:actions`**, for the reason [Torch] gives: no
 * other module has to read a volume, `:actions` may name an Android type and
 * already does, and a port in `:core` would buy only one more hop through
 * `AppContainer`.
 *
 * The port exists because an emulator reports whatever step count its own
 * audio policy holds. A test that asked a real [AudioManager] could not choose
 * the numbers, so it could not reach the cases that matter: a maximum of zero,
 * a minimum above zero, and a read that throws.
 *
 * **Read only, and it does not replace what `set_volume` does.**
 * [SetVolumeAction] talks to [AudioManager] itself. Moving it behind this port
 * would change an action that is released and works, which is a separate piece
 * of work from adding one.
 */
interface VolumeLevels {

    /** Reads [stream] now. Nothing here suspends: each call is one binder call. */
    fun read(stream: VolumeStream): VolumeReading
}

/**
 * [VolumeLevels] over the platform's own [AudioManager].
 *
 * **[AudioManager.getStreamMinVolume] is API 28 and this module's minimum is
 * API 26**, so the minimum is read only from API 28 and reads as zero below
 * that. That is the honest answer for the older releases: before API 28 an app
 * had no way to ask, and every stream this action offers starts at zero on
 * them.
 *
 * A [RuntimeException] is caught and reported rather than left to escape. The
 * audio service lives in another process, so a dead binder arrives here as a
 * runtime exception on a call that normally cannot fail. An action that throws
 * is a bug in the action; one that reports is a rule the person can read.
 */
class AudioManagerVolumeLevels(private val context: Context) : VolumeLevels {

    override fun read(stream: VolumeStream): VolumeReading {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: return VolumeReading.Failed("This device has no audio service.")

        return try {
            VolumeReading.Level(
                current = audio.getStreamVolume(stream.streamType),
                min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audio.getStreamMinVolume(stream.streamType)
                } else {
                    0
                },
                max = audio.getStreamMaxVolume(stream.streamType),
            )
        } catch (failed: RuntimeException) {
            VolumeReading.Failed(
                "This device did not report the ${stream.displayName} volume. " +
                    "${failed.message}"
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The arithmetic
// ---------------------------------------------------------------------------

/**
 * [current] as a percent of what [min] to [max] allows, or null when that
 * stream has no range to measure.
 *
 * **The percent is measured across the usable span, not from zero.** Android
 * reports a minimum per stream, and it is not always zero: a stream whose
 * minimum is 1 of 15 steps is at its quietest setting at index 1, and a percent
 * of the raw maximum would call that 7%. A rule that tests for 0 would then
 * never be true, and the person would have no way to see why. So the span is
 * `max - min`, and the quietest setting the stream allows reads as 0.
 *
 * **Null when the span is not positive.** A maximum of zero, or a maximum equal
 * to the minimum, means the stream has one setting and no percent describes it.
 * The caller reports that. A silent 0, or a silent 100, would be a number a
 * rule can compare and act on, and it would be wrong.
 *
 * **Rounded to the nearest whole percent, with a half going up.** Nearest keeps
 * the largest error at half a step. Half going up is [Math.round], which is
 * what [volumeIndexFor] uses in the other direction, so the two actions round
 * the same way and a rule that sets a percent and reads it back on the same
 * stream gets the same number wherever the steps allow one.
 *
 * [current] is clamped into the span first. A muted stream reports index 0 even
 * when its minimum is above zero, so the raw difference can be negative, and a
 * negative percent is not a number any rule should have to handle.
 *
 * Pure, so the rounding is tested and not assumed. Same shape as
 * [volumeIndexFor], and the two are deliberately next to each other in this
 * catalogue.
 */
fun volumePercentOf(current: Int, min: Int, max: Int): Int? {
    val span = max - min
    if (span <= 0) return null
    val above = (current - min).coerceIn(0, span)
    return Math.round(above * 100f / span)
}

// ---------------------------------------------------------------------------
// The action
// ---------------------------------------------------------------------------

/**
 * Reads one stream's volume and stores it as a percent, under a name the person
 * chose.
 *
 * The read half of [SetVolumeAction], and it speaks the same vocabulary: the
 * streams are [VolumeStream]'s, so "media" means the same thing in both
 * actions, and the unit is a percent for the reason [volumeIndexFor] gives.
 * Step counts differ by stream and by phone, so a raw index means nothing a
 * rule could compare across devices.
 *
 * **The value is stored as bare digits, such as `40`.** No percent sign, and no
 * decimals. A rule compares it as a number, and `40%` would compare as text and
 * fail every numeric test for a reason nothing on screen would explain. The
 * help text says this, because the person choosing the name is the person who
 * will later write the comparison.
 *
 * **It writes to the rule's own scope, `{{mine.*}}`.** The value belongs to one
 * rule, and the main rule this action exists for is "save the volume, change
 * it, put it back later": that pair spans two runs, so a run-only value would
 * be gone before the second half. The shared app scope would work too, and it
 * would put a private working value in a shared namespace where another rule
 * could pick the same name. This scope makes that collision impossible by
 * construction. See [RuleVariableStore].
 *
 * A stream that cannot be read fails and writes nothing. A stale number left in
 * the variable is a number a rule would go on to act on, and it would look
 * exactly like a fresh one.
 */
class GetVolumeAction(
    private val levels: VolumeLevels,
    private val stream: VolumeStream,
    private val name: String,
    private val ruleStore: RuleVariableStore,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        // Which rule this is. The engine puts it on the coroutine, and the
        // rule scope is keyed by it; nothing else can supply it. See RunScope.
        val run = coroutineContext[RunScope]
            ?: return ActionResult.Failure(
                "A rule variable only exists while a rule is running, and this " +
                    "action was not run by a rule."
            )

        // The port reports what it can see. This catch is for what it cannot:
        // a fake, a future implementation, or a binder that died between the
        // port's own try block and here. Reporting beats throwing either way.
        val reading = try {
            levels.read(stream)
        } catch (failed: RuntimeException) {
            return ActionResult.Failure(
                "Reading the ${stream.displayName} volume failed. ${failed.message}",
                failed,
            )
        }

        val level = when (reading) {
            is VolumeReading.Failed -> return ActionResult.Failure(reading.reason)
            is VolumeReading.Level -> reading
        }

        val percent = volumePercentOf(level.current, level.min, level.max)
            ?: return ActionResult.Failure(
                "Android reports no volume steps for ${stream.displayName} on " +
                    "this device, so there is no percent to read."
            )

        val value = percent.toString()
        ruleStore.set(run.ruleId, name, value)
        return ActionResult.Success(outputs = mapOf(OUTPUT_PERCENT to value))
    }

    companion object {
        const val TYPE = "get_volume"
        const val CONFIG_NAME = "name"

        /** The output key the factory declares for what was just read. */
        const val OUTPUT_PERCENT = "percent"
    }
}

class GetVolumeActionFactory(
    private val levels: VolumeLevels,
    /**
     * Where the value goes. The same store the engine reads, or the rule would
     * never see what this wrote.
     */
    private val ruleStore: RuleVariableStore,
) : ActionFactory {
    override val type = GetVolumeAction.TYPE

    override val displayName = "Read the volume"
    override val category = ActionCategory.DEVICE

    override val configFields = listOf(
        // The same choice `set_volume` offers, built from the same enum. Two
        // lists would drift, and a person would then meet two spellings of one
        // set of streams in two actions they use together.
        ConfigField.Choice(
            key = VolumeStream.CONFIG_KEY,
            label = "Which volume",
            options = VolumeStream.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            default = VolumeStream.MEDIA.configValue,
        ),
        ConfigField.Text(
            key = GetVolumeAction.CONFIG_NAME,
            label = "Store it in",
            required = true,
            help = "Read it back as {{mine.name}}. The value is a plain number " +
                "from 0 to 100 with no percent sign, so a rule can compare it. " +
                "It belongs to this rule and it survives until the rule is " +
                "deleted. A name has no spaces and no '|', '{' or '}'.",
        ),
    )

    /**
     * What this action just read, so a later action can say it without a second
     * read: "Volume was {{action.percent}}". Not [VariableSpec.alwaysPresent],
     * because a stream this device cannot report leaves nothing to hand over.
     */
    override val variables = listOf(
        VariableSpec(
            key = GetVolumeAction.OUTPUT_PERCENT,
            label = "Volume percent",
            kind = VariableKind.NUMBER,
            sample = "40",
            help = "What this action just read, from 0 to 100.",
            alwaysPresent = false,
        ),
    )

    /**
     * What this action writes, so the editor can offer the name a person typed
     * here to the fields that read it. See [VariableWriteSpec].
     *
     * No `scopeKey`: this action always writes one scope, so there is no field
     * to read it from. [VariableScope.MINE] matches what [GetVolumeAction]
     * does, and it has to: the editor offering a name under one namespace while
     * the action writes another is a name the picker gets wrong every time.
     *
     * The sample repeats [variables]'s for the reason `set_variable`'s does.
     * One read produces both, so a preview of `{{mine.volume}}` and one of
     * `{{action.percent}}` must not show two different numbers.
     */
    override val variableWrites = listOf(
        VariableWriteSpec(
            nameKey = GetVolumeAction.CONFIG_NAME,
            defaultNamespace = VariableScope.MINE,
            sample = "40",
        ),
    )

    override fun create(config: Map<String, String>): Action {
        val rawName = config[GetVolumeAction.CONFIG_NAME].orEmpty()
        val problem = variableNameProblem(rawName)
        require(problem == null) { problem.orEmpty() }

        return GetVolumeAction(
            levels = levels,
            stream = VolumeStream.parse(config[VolumeStream.CONFIG_KEY]),
            name = normalizeVariableName(rawName),
            ruleStore = ruleStore,
        )
    }
}
