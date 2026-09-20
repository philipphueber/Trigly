package app.phueber.trigly.actions

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.FieldCondition
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.WakeGuard
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// The port
// ---------------------------------------------------------------------------

/** Full brightness, and the value of the brightness setting nobody has moved. */
const val FULL_STRENGTH_PERCENT: Int = 100

/**
 * What one attempt to switch the torch did.
 *
 * A returned value and not a thrown exception. Both callers have to turn the
 * outcome into an [ActionResult] anyway, and [BlinkFlashlightAction] has to ask
 * after every edge whether it still owns the torch. A returned value makes that
 * one `is` check in a loop instead of a `try` around each edge.
 */
sealed interface TorchResult {

    /** The flash unit was switched. */
    data object Ok : TorchResult

    /** Nothing was switched. [reason] is shown to the person who built the rule. */
    data class Failed(val reason: String) : TorchResult
}

/**
 * The device's torch, as the two actions in this file need it.
 *
 * **A port, and it stays in `:actions`.** [WakeGuard] and `AlarmScheduler` live
 * in `:core` because `:core` itself has to wait and may not name an Android
 * type. Nothing outside this module has to switch a torch. `:actions` may name
 * an Android type and already does, as `VibrateAction` does over `Vibrator`, so
 * a port in `:core` would buy nothing but one more hop through `AppContainer`
 * for a capability no other module calls. `FireIntentAction`'s `IntentResolver`
 * is the same shape for the same reason.
 *
 * The port exists for one reason. The blink pattern is arithmetic and a
 * sequence of waits, and both must be testable without a device. The emulators
 * this project tests on report no flash unit, so no test on them can ever watch
 * the light come on.
 *
 * **Nothing here suspends, and that is deliberate.**
 * `CameraManager.setTorchMode` is one binder call. Keeping the port
 * non-suspending is what lets [BlinkFlashlightAction] switch the torch off in a
 * `finally` after its coroutine is cancelled. A suspending call on that path
 * would need `withContext(NonCancellable)` around it, which is one more thing
 * to forget on the one path that must never be missed.
 */
interface Torch {

    /**
     * Switches the torch on.
     *
     * [strengthPercent] is a percentage of this flash unit's own maximum, not a
     * physical unit: see [torchStrengthLevel]. A device that has one brightness
     * only, or that runs an Android older than 13, comes on at full and reports
     * [TorchResult.Ok], because it did switch the torch on. The setting says how
     * bright, never whether.
     */
    fun turnOn(strengthPercent: Int = FULL_STRENGTH_PERCENT): TorchResult

    /** Switches the torch off. Safe to call when it is already off. */
    fun turnOff(): TorchResult
}

// ---------------------------------------------------------------------------
// Which camera, and how bright
// ---------------------------------------------------------------------------

/** Which way a camera on this device points. */
enum class CameraFacing {
    BACK,
    FRONT,
    EXTERNAL,

    /** A camera whose facing this platform did not report. */
    UNKNOWN,
}

/** One camera on the device, reduced to the facts that decide which one is the torch. */
data class TorchCamera(
    val id: String,
    val hasFlash: Boolean,
    val facing: CameraFacing,
)

/**
 * The camera whose flash a person means by "the flashlight", or null when this
 * device has none.
 *
 * A phone has several cameras and they do not all have a flash unit. Two things
 * make this a decision rather than a lookup.
 *
 * The first is that `CameraManager.getCameraIdList` promises no order. "0" is
 * the rear camera on most phones by convention, and a convention is not a
 * contract. Reading the flash out of a fixed id is how an app works on the
 * phones it was tested on and lights nothing on the next one.
 *
 * The second is that more than one camera can report a flash. A phone with
 * several rear lenses exposes each as its own camera id, and a plugged in USB
 * camera is a camera id too. So the pick is by preference and not by "the first
 * that has one": the back camera, then any built in camera, then an external
 * one last. An external camera can be unplugged, and its flash is not what
 * anybody means by their phone's torch.
 *
 * Pure, so the preference is tested rather than trusted. The Android half only
 * has to read three fields per camera.
 */
fun pickTorchCamera(cameras: List<TorchCamera>): String? {
    val withFlash = cameras.filter { it.hasFlash }
    val chosen = withFlash.firstOrNull { it.facing == CameraFacing.BACK }
        ?: withFlash.firstOrNull { it.facing != CameraFacing.EXTERNAL }
        ?: withFlash.firstOrNull()
    return chosen?.id
}

/** A flash unit that can only be on or off reports this many levels. */
const val SINGLE_STRENGTH_LEVEL: Int = 1

/**
 * The strength level to ask `turnOnTorchWithStrengthLevel` for, or null when
 * plain on and off is the honest call.
 *
 * Null in two cases, and both of them matter.
 *
 * [maxLevel] of [SINGLE_STRENGTH_LEVEL] is a flash unit with one brightness.
 * Most phones are this. `CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL`
 * is what reports it, and it arrived in API 33 with the call itself, so an older
 * phone reads as one level here as well. Asking for a level on such a device
 * would do exactly what plain on does, through a newer API, for nothing.
 *
 * [FULL_STRENGTH_PERCENT] is the setting nobody moved. It must not drag the
 * newer API into the common case, because the action declares a minimum Android
 * version only when the brightness is actually turned down; see
 * [flashlightRequirements].
 *
 * The scale is a percentage of the unit's own maximum, for the reason
 * [volumeIndexFor] gives about audio streams: the maximum differs by device, so
 * a percentage is the only portable thing a rule can store. Zero is not a
 * brightness, it is off, and the mode field already says that, so the result
 * never drops below one level.
 */
fun torchStrengthLevel(percent: Int, maxLevel: Int): Int? {
    if (maxLevel <= SINGLE_STRENGTH_LEVEL) return null
    if (percent >= FULL_STRENGTH_PERCENT) return null
    val level = Math.round(maxLevel * percent.coerceIn(0, FULL_STRENGTH_PERCENT) / 100f)
    return level.coerceIn(SINGLE_STRENGTH_LEVEL, maxLevel)
}

/**
 * Brightness from config, defaulted and clamped.
 *
 * Forgiving where [delayDurationMillis] refuses, because the two fields are not
 * the same kind of thing. A wait has no natural length and a blank one hides the
 * choice the action exists for. A brightness always has an obvious reading, full
 * brightness, and the editor draws it as a slider that always holds a position.
 * A value that is not a number can therefore only reach this from a hand edited
 * export, and lighting the torch fully is the honest answer to "this file does
 * not say".
 */
fun torchStrengthPercent(raw: String?): Int {
    val percent = raw?.trim()?.toIntOrNull() ?: FULL_STRENGTH_PERCENT
    return percent.coerceIn(1, FULL_STRENGTH_PERCENT)
}

// ---------------------------------------------------------------------------
// The Android half
// ---------------------------------------------------------------------------

/**
 * The real [Torch], over `CameraManager`.
 *
 * **It holds nothing open.** There is no camera session here and no callback
 * registered, so building one of these costs nothing and two of them are free.
 * That is what lets both factories take their own instance and keeps
 * `actionFactories` to one line per action.
 *
 * **The camera id is resolved once.** Enumerating the cameras is one binder call
 * per camera plus the characteristics of each, and a blink would otherwise pay
 * that on every edge. A phone does not grow a flash unit while the process
 * lives. The one case this gets wrong is a USB camera plugged in after the first
 * use, and [pickTorchCamera] puts an external camera last anyway.
 */
class Camera2Torch(private val context: Context) : Torch {

    private val manager: CameraManager? by lazy {
        context.getSystemService(CameraManager::class.java)
    }

    private val cameraId: String? by lazy {
        manager?.let { pickTorchCamera(it.torchCameras()) }
    }

    /**
     * How many brightness levels this flash unit has. One means on and off.
     *
     * The version check is not a formality even though the characteristic is
     * absent on an older platform: reading a key that arrived in API 33 is the
     * kind of call lint has to be able to see is guarded.
     */
    private val maxStrengthLevel: Int by lazy {
        val id = cameraId
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || id == null) {
            SINGLE_STRENGTH_LEVEL
        } else {
            try {
                manager?.getCameraCharacteristics(id)
                    ?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL)
                    ?: SINGLE_STRENGTH_LEVEL
            } catch (unreadable: CameraAccessException) {
                SINGLE_STRENGTH_LEVEL
            }
        }
    }

    override fun turnOn(strengthPercent: Int): TorchResult = switch(on = true, strengthPercent)

    override fun turnOff(): TorchResult = switch(on = false, FULL_STRENGTH_PERCENT)

    private fun switch(on: Boolean, strengthPercent: Int): TorchResult {
        val manager = manager ?: return TorchResult.Failed("This device has no camera service.")
        val id = cameraId ?: return TorchResult.Failed("This device has no flashlight.")
        val level = if (on) torchStrengthLevel(strengthPercent, maxStrengthLevel) else null

        return try {
            if (level != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.turnOnTorchWithStrengthLevel(id, level)
            } else {
                manager.setTorchMode(id, on)
            }
            TorchResult.Ok
        } catch (refused: CameraAccessException) {
            TorchResult.Failed(torchRefusalReason(refused))
        } catch (gone: IllegalArgumentException) {
            // setTorchMode reports an id it no longer knows this way rather than
            // as a CameraAccessException. An unplugged USB camera is the case.
            TorchResult.Failed("This device's flashlight is no longer there.")
        }
    }

    private fun CameraManager.torchCameras(): List<TorchCamera> = try {
        cameraIdList.map { id ->
            val characteristics = getCameraCharacteristics(id)
            TorchCamera(
                id = id,
                hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                facing = facingOf(characteristics.get(CameraCharacteristics.LENS_FACING)),
            )
        }
    } catch (unreadable: CameraAccessException) {
        // No camera can be asked about, so none can be lit. Reported as "no
        // flashlight" by the caller, which is what it means to a rule.
        emptyList()
    }

    private fun facingOf(lensFacing: Int?): CameraFacing = when (lensFacing) {
        CameraMetadata.LENS_FACING_BACK -> CameraFacing.BACK
        CameraMetadata.LENS_FACING_FRONT -> CameraFacing.FRONT
        CameraMetadata.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
        else -> CameraFacing.UNKNOWN
    }

    /**
     * Why the platform said no, in words a person can act on.
     *
     * The reason codes are the whole point of catching this. "Another app has
     * the camera" is a thing the user can fix in a second, and a bare
     * "CameraAccessException" is not.
     */
    private fun torchRefusalReason(refused: CameraAccessException): String = when (refused.reason) {
        CameraAccessException.CAMERA_IN_USE,
        CameraAccessException.MAX_CAMERAS_IN_USE,
        -> "Another app is using the camera, so the flashlight is not free."

        CameraAccessException.CAMERA_DISABLED ->
            "A policy on this device has turned the camera off."

        CameraAccessException.CAMERA_DISCONNECTED ->
            "This device's camera is not connected."

        else -> "This device refused to switch the flashlight."
    }
}

// ---------------------------------------------------------------------------
// Turning it on and off
// ---------------------------------------------------------------------------

/** What the flashlight action does to the torch. */
enum class FlashlightMode(val configValue: String, val on: Boolean) {
    ON("on", true),
    OFF("off", false),
    ;

    companion object {
        const val CONFIG_KEY = "mode"

        val DEFAULT = ON

        /** Null for a value this action does not know, including none at all. */
        fun parseOrNull(raw: String?): FlashlightMode? =
            entries.firstOrNull { it.configValue.equals(raw?.trim(), ignoreCase = true) }

        fun parse(raw: String?): FlashlightMode = parseOrNull(raw)
            ?: error(
                "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                    "was '$raw'"
            )
    }
}

/**
 * Turns the torch on or off.
 *
 * **It needs no permission, and that is the finding worth writing down.**
 * `CameraManager.setTorchMode` is not `openCamera`. The SDK's own permission
 * database, the one lint reads, is
 * `platforms/android-35/data/annotations.zip`, and its camera2 entry puts
 * `@RequiresPermission(android.permission.CAMERA)` on both `openCamera`
 * overloads and on nothing else in `CameraManager`. There is no entry at all
 * for `setTorchMode`, `turnOnTorchWithStrengthLevel` or `registerTorchCallback`.
 * So this action runs from a rule with no prompt and no settings screen, which
 * is rare enough among the actions in this module to be worth stating. The only
 * manifest change it needs is the optional feature declaration, which is a Play
 * Store filter and not a permission.
 *
 * **There is no toggle, and the reason is that the torch is shared.**
 * `set_rule_enabled` has one, and the difference is who else can write the
 * value. A rule's enabled flag is Trigly's own, and nothing else on the phone
 * moves it. The torch is moved by the quick settings tile, by the camera app,
 * and by any other app that asks. Android offers no synchronous read of it
 * either: `registerTorchCallback` reports the current mode, and it reports it
 * later, on a callback. A toggle would therefore be a read, a wait, and a write,
 * with the tile free to move between the read and the write. That produces the
 * one failure this app works hardest to avoid, a rule that looks like it worked
 * and did the opposite of what it said.
 *
 * What that costs is a single home screen button that alternates, which is a
 * real thing somebody will want with the shortcut trigger. It is the cost of
 * a state this app cannot read without racing, and the shape it needs, an
 * asynchronous read with an answer for "nobody replied", is a bigger change
 * than adding a third option to a list. `docs/actions.md` records it.
 */
class FlashlightAction(
    private val torch: Torch,
    private val mode: FlashlightMode,
    private val strengthPercent: Int,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val result = if (mode.on) torch.turnOn(strengthPercent) else torch.turnOff()
        return when (result) {
            TorchResult.Ok -> ActionResult.Success()
            is TorchResult.Failed -> ActionResult.Failure(result.reason)
        }
    }

    companion object {
        const val TYPE = "flashlight"
        const val CONFIG_STRENGTH_PERCENT = "strengthPercent"
    }
}

/**
 * What both flashlight actions need from the device, whatever their settings.
 *
 * `FEATURE_CAMERA_FLASH` is a [ComponentRequirement.SystemFeature], so
 * `RequirementChecker.isPossible` reports it as permanently unmet on a phone
 * with no flash unit and the pickers never offer either action there. That is
 * the house rule: a device that cannot run a component is never shown it, and a
 * runtime failure is a worse way to say the same thing.
 *
 * It is the only requirement either action ever needs at full brightness. See
 * [FlashlightAction] for the evidence that the torch needs no permission.
 */
internal val FLASHLIGHT_REQUIREMENTS: List<ComponentRequirement> = listOf(
    ComponentRequirement.SystemFeature(PackageManager.FEATURE_CAMERA_FLASH),
)

/**
 * What this configuration of the flashlight action needs, which is more than
 * [FLASHLIGHT_REQUIREMENTS] only when the brightness is turned down.
 *
 * `turnOnTorchWithStrengthLevel` arrived in API 33
 * (`platforms/android-35/data/api-versions.xml` says `since="33"`, against
 * `since="23"` for `setTorchMode`), and this module's minSdk is 26. Declaring
 * the floor unconditionally would hide the whole action from every phone
 * running Android 12 or older, for a setting almost nobody moves. Declaring it
 * never would leave a brightness that quietly does nothing on those phones,
 * which is the failure the requirement model exists to prevent.
 *
 * So it is declared exactly when it is used, which is the `requirementsFor`
 * contract and needs a proven claim rather than a plausible one. The claim here
 * is proven by [torchStrengthLevel]: at [FULL_STRENGTH_PERCENT], and whenever
 * the torch is being switched off, the action calls `setTorchMode` and never
 * reaches the newer method at all.
 *
 * A mode this build does not know reads as no extra requirement, the same way
 * `requirementsForSendAs` treats one. A half filled form must not accuse the
 * phone of being too old.
 */
internal fun flashlightRequirements(config: Map<String, String>): List<ComponentRequirement> {
    val mode = FlashlightMode.parseOrNull(config[FlashlightMode.CONFIG_KEY])
    val percent = torchStrengthPercent(config[FlashlightAction.CONFIG_STRENGTH_PERCENT])
    return if (mode?.on == true && percent < FULL_STRENGTH_PERCENT) {
        FLASHLIGHT_REQUIREMENTS + ComponentRequirement.MinApiLevel(Build.VERSION_CODES.TIRAMISU)
    } else {
        FLASHLIGHT_REQUIREMENTS
    }
}

class FlashlightActionFactory(private val torch: Torch) : ActionFactory {
    override val type = FlashlightAction.TYPE

    override val displayName = "Flashlight"

    /**
     * A switch on the phone, beside the volume and the ringer, and not
     * [ActionCategory.NOTIFY] where `vibrate` sits. A steady torch is light to
     * see by. `flashlight_blink` is the one that exists to be noticed, and it
     * is grouped with the other ways a rule gets attention.
     */
    override val category = ActionCategory.DEVICE

    override val configFields = listOf(
        ConfigField.Choice(
            key = FlashlightMode.CONFIG_KEY,
            label = "Switch the flashlight",
            options = listOf(
                ConfigField.Option(FlashlightMode.ON.configValue, "on"),
                ConfigField.Option(FlashlightMode.OFF.configValue, "off"),
            ),
            default = FlashlightMode.DEFAULT.configValue,
        ),
        ConfigField.Slider(
            key = FlashlightAction.CONFIG_STRENGTH_PERCENT,
            label = "Brightness",
            min = 1,
            max = FULL_STRENGTH_PERCENT.toLong(),
            default = FULL_STRENGTH_PERCENT.toLong(),
            unit = "%",
            shownWhen = FieldCondition(FlashlightMode.CONFIG_KEY, FlashlightMode.ON.configValue),
            help = "Full brightness works on every phone with a flashlight. A lower " +
                "brightness needs Android 13 or later, and a flashlight that has more " +
                "than one brightness. Many phones have only one. On those the light " +
                "comes on at full.",
        ),
    )

    override val requirements = FLASHLIGHT_REQUIREMENTS

    override fun requirementsFor(config: Map<String, String>): List<ComponentRequirement> =
        flashlightRequirements(config)

    override val warning: String =
        "Trigly does not switch the flashlight off again by itself. It stays on " +
            "after this rule ends, and it stays on if Trigly stops. The light uses " +
            "much battery and makes the phone warm. Build a second rule that " +
            "switches it off, or use \"Blink the flashlight\", which always ends dark."

    override fun create(config: Map<String, String>): Action = FlashlightAction(
        torch = torch,
        mode = FlashlightMode.parse(config[FlashlightMode.CONFIG_KEY]),
        strengthPercent = torchStrengthPercent(config[FlashlightAction.CONFIG_STRENGTH_PERCENT]),
    )
}

// ---------------------------------------------------------------------------
// Blinking it
// ---------------------------------------------------------------------------

/** How a person says when the blinking stops. */
enum class BlinkLimit(val configValue: String) {
    /** "Blink three times." */
    COUNT("count"),

    /** "Blink for five seconds." */
    DURATION("duration"),
    ;

    companion object {
        const val CONFIG_KEY = "stopAfter"

        val DEFAULT = COUNT

        /**
         * Absent reads as [DEFAULT], which is what the editor draws for a rule
         * nobody has touched, so both readers of the config agree. A value this
         * build does not know is refused loudly instead, the way `DndMode.parse`
         * refuses one: it can only come from an edited file, and guessing which
         * of the two limits was meant would silently change what the rule does.
         */
        fun parse(raw: String?): BlinkLimit {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return DEFAULT
            return entries.firstOrNull { it.configValue.equals(trimmed, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
        }
    }
}

/**
 * A blink pattern, after every bound has been applied.
 *
 * The count is what the action runs on, whichever way the person expressed it.
 * "Blink three times" and "blink for five seconds" are two ways of saying the
 * same thing to a flash unit, and the second is turned into the first by
 * [blinkTimesWithin] once, in one place, rather than by a second kind of loop.
 */
data class BlinkPattern(
    val times: Int,
    val onMillis: Long,
    val offMillis: Long,
) {
    init {
        require(times >= 1) { "a blink pattern needs at least one blink, was $times" }
        require(onMillis > 0) { "onMillis must be positive, was $onMillis" }
        require(offMillis > 0) { "offMillis must be positive, was $offMillis" }
    }

    /** One blink: the light on, then the dark gap after it. */
    val cycleMillis: Long get() = onMillis + offMillis

    /**
     * How long this action actually holds the rule.
     *
     * The dark gap after the last blink is not waited through. The light is
     * already out, so waiting there would only make the next action in the rule
     * late for no visible difference. It is also what the wake lock's backstop
     * is sized against, so it has to be the real wait and not the round number.
     */
    val totalMillis: Long get() = times * cycleMillis - offMillis
}

/**
 * Blinks the torch, then leaves it off.
 *
 * **The whole pattern runs inside one [WakeGuard] span.** A blink is a sequence
 * of short waits, and a bare `delay` stops counting the moment the device
 * suspends, which is exactly the state a phone is in when a blink is worth
 * doing at all. `DelayAction`'s KDoc has the full argument and this is the same
 * one: under a wake lock a plain `delay` is accurate to the millisecond, and
 * the alarm port is not an option here at any length, because
 * `AlarmManagerScheduler` floors its window at five seconds and a whole blink
 * cycle is shorter than that.
 *
 * **So the pattern is capped, at [MAX_TOTAL_MILLIS].** Holding the CPU is the
 * only way to keep the timing, and a hold has to be a length that can be
 * defended. Thirty seconds is the same boundary `DelayAction` defends, arrived
 * at from a different direction: it is where that action stops holding the CPU
 * and starts scheduling. The number is written here rather than read from there
 * because the two would not move together. A change to the scheduler's window
 * would move `DelayAction`'s boundary and would say nothing about how long a
 * blink may hold a phone awake. Over the cap the count is reduced to what fits,
 * capped and not refused, the way every other bound in this module works.
 *
 * **The torch is switched off in a `finally`.** This is the part that matters
 * more than any of the rest. `TriggerEngine` cancels a rule's job when the rule
 * is disabled while it runs, and a cancelled blink that left the light on would
 * be a fault the user can see and that drains the battery fast. The `finally`
 * covers the three ways out: the pattern ending, a throw, and the cancellation.
 * It works because [Torch] does not suspend, so the last call still runs after
 * the coroutine is cancelled.
 *
 * **When somebody else owns the torch.** The quick settings tile, the camera
 * app and another automation app all write the same flash unit, and
 * `registerTorchCallback` is how Android reports that. It is deliberately not
 * used here. The same news arrives from the next edge anyway, as a
 * `CameraAccessException` that [Camera2Torch] turns into a
 * [TorchResult.Failed], and at most one half cycle later. Taking it from the
 * callback instead would mean owning a callback, a handler, and a race between
 * the callback thread and this loop, to learn the same fact sooner than a
 * person can see. The callback also reports this action's own edges, so telling
 * "somebody else" from "us" would need more state again.
 *
 * What that leaves: a failed edge stops the pattern and reports why, and a
 * person who switches the torch on from the tile mid pattern has it switched
 * off by the `finally`. That second one is a real cost and it is stated in the
 * factory's warning, because "always ends dark" is the promise that makes this
 * action safe to put in a rule at all.
 */
class BlinkFlashlightAction(
    private val torch: Torch,
    private val wake: WakeGuard,
    private val pattern: BlinkPattern,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult =
        wake.keepingAwake(TYPE, wakeTimeoutMillis(pattern.totalMillis)) {
            try {
                repeat(pattern.times) { blink ->
                    val lit = torch.turnOn()
                    if (lit is TorchResult.Failed) {
                        return@keepingAwake ActionResult.Failure(lit.reason)
                    }
                    delay(pattern.onMillis)

                    val dark = torch.turnOff()
                    if (dark is TorchResult.Failed) {
                        return@keepingAwake ActionResult.Failure(dark.reason)
                    }
                    if (blink < pattern.times - 1) delay(pattern.offMillis)
                }
                ActionResult.Success()
            } finally {
                torch.turnOff()
            }
        }

    companion object {
        const val TYPE = "flashlight_blink"
        const val CONFIG_TIMES = "times"
        const val CONFIG_TOTAL_MILLIS = "totalMillis"
        const val CONFIG_ON_MILLIS = "onMillis"
        const val CONFIG_OFF_MILLIS = "offMillis"

        const val DEFAULT_TIMES = 3
        const val DEFAULT_ON_MILLIS = 200L
        const val DEFAULT_OFF_MILLIS = 200L
        const val DEFAULT_TOTAL_MILLIS = 5_000L

        /**
         * The longest a pattern may run. Thirty seconds, and the reason is this
         * class's own KDoc: the whole pattern is one hold on the CPU.
         */
        const val MAX_TOTAL_MILLIS = 30_000L

        /**
         * The shortest light or dark phase, twenty milliseconds.
         *
         * Below this there is nothing to see and plenty to pay: a five
         * millisecond phase is two hundred calls a second into the camera
         * service for a light no eye resolves. The cap above it is the usual
         * one, a mistyped value that would otherwise hold the rule for the
         * whole thirty seconds on one blink.
         */
        const val MIN_PHASE_MILLIS = 20L
        const val MAX_PHASE_MILLIS = 10_000L
    }
}

/**
 * One phase of a blink, defaulted and bounded.
 *
 * Both phases have a length that means something when nobody set one, a short
 * flash and a short gap, so absence defaults rather than refusing. That is the
 * split `vibrationDurationMillis` and `delayDurationMillis` already draw
 * between them.
 */
fun blinkPhaseMillis(raw: String?, defaultMillis: Long): Long {
    val phase = raw?.trim()?.toLongOrNull() ?: defaultMillis
    return phase.coerceIn(
        BlinkFlashlightAction.MIN_PHASE_MILLIS,
        BlinkFlashlightAction.MAX_PHASE_MILLIS,
    )
}

/** How many blinks the person asked for, defaulted and floored at one. */
fun blinkTimes(raw: String?): Int {
    val times = raw?.trim()?.toIntOrNull() ?: BlinkFlashlightAction.DEFAULT_TIMES
    return times.coerceAtLeast(1)
}

/** How long the person asked the blinking to last, defaulted and capped. */
fun blinkTotalMillis(raw: String?): Long {
    val total = raw?.trim()?.toLongOrNull() ?: BlinkFlashlightAction.DEFAULT_TOTAL_MILLIS
    return total.coerceIn(1, BlinkFlashlightAction.MAX_TOTAL_MILLIS)
}

/**
 * How many blinks fit in a length of time.
 *
 * Rounded down, then floored at one. Down, because a pattern that overran the
 * time somebody typed would be the setting failing to mean what it says. One,
 * because "blink for half a second" with a one second cycle is still a request
 * to blink, and answering it with darkness would read as the action being
 * broken.
 */
fun blinkTimesWithin(totalMillis: Long, cycleMillis: Long): Int =
    (totalMillis / cycleMillis).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

/**
 * The count after the cap on the whole pattern, per
 * [BlinkFlashlightAction.MAX_TOTAL_MILLIS].
 *
 * Measured against the full cycles, not against
 * [BlinkPattern.totalMillis], which is one dark gap shorter. The bound being
 * defended is how long the CPU is held, and the difference between the two is
 * at most one gap, so the stricter of the two is the one to check.
 */
fun cappedBlinkTimes(times: Int, cycleMillis: Long): Int {
    val fits = BlinkFlashlightAction.MAX_TOTAL_MILLIS / cycleMillis
    return times.toLong().coerceIn(1, fits.coerceAtLeast(1)).toInt()
}

/**
 * The pattern a stored configuration means.
 *
 * Takes the whole config rather than one value at a time, because the count and
 * the phase lengths are one answer: the length of a cycle decides how many
 * blinks a duration buys, and the cap is on the two of them together.
 */
fun blinkPattern(config: Map<String, String>): BlinkPattern {
    val onMillis = blinkPhaseMillis(
        config[BlinkFlashlightAction.CONFIG_ON_MILLIS],
        BlinkFlashlightAction.DEFAULT_ON_MILLIS,
    )
    val offMillis = blinkPhaseMillis(
        config[BlinkFlashlightAction.CONFIG_OFF_MILLIS],
        BlinkFlashlightAction.DEFAULT_OFF_MILLIS,
    )
    val cycleMillis = onMillis + offMillis

    val asked = when (BlinkLimit.parse(config[BlinkLimit.CONFIG_KEY])) {
        BlinkLimit.COUNT -> blinkTimes(config[BlinkFlashlightAction.CONFIG_TIMES])
        BlinkLimit.DURATION -> blinkTimesWithin(
            blinkTotalMillis(config[BlinkFlashlightAction.CONFIG_TOTAL_MILLIS]),
            cycleMillis,
        )
    }

    return BlinkPattern(
        times = cappedBlinkTimes(asked, cycleMillis),
        onMillis = onMillis,
        offMillis = offMillis,
    )
}

class BlinkFlashlightActionFactory(
    private val torch: Torch,
    private val wake: WakeGuard,
) : ActionFactory {
    override val type = BlinkFlashlightAction.TYPE

    override val displayName = "Blink the flashlight"

    /**
     * Grouped with `vibrate` and `play_alert` rather than with the plain
     * flashlight. This exists to be noticed from across a room, which is what
     * that group is, while a steady torch is a device switch.
     */
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        ConfigField.Choice(
            key = BlinkLimit.CONFIG_KEY,
            label = "Stop after",
            options = listOf(
                ConfigField.Option(BlinkLimit.COUNT.configValue, "a number of blinks"),
                ConfigField.Option(BlinkLimit.DURATION.configValue, "a length of time"),
            ),
            default = BlinkLimit.DEFAULT.configValue,
        ),
        ConfigField.Number(
            key = BlinkFlashlightAction.CONFIG_TIMES,
            label = "Blinks",
            min = 1,
            max = 100,
            default = BlinkFlashlightAction.DEFAULT_TIMES.toLong(),
            shownWhen = FieldCondition(BlinkLimit.CONFIG_KEY, BlinkLimit.COUNT.configValue),
        ),
        ConfigField.Duration(
            key = BlinkFlashlightAction.CONFIG_TOTAL_MILLIS,
            label = "Blink for",
            defaultMillis = BlinkFlashlightAction.DEFAULT_TOTAL_MILLIS,
            maxMillis = BlinkFlashlightAction.MAX_TOTAL_MILLIS,
            preferred = DurationUnit.SECONDS,
            shownWhen = FieldCondition(BlinkLimit.CONFIG_KEY, BlinkLimit.DURATION.configValue),
        ),
        ConfigField.Duration(
            key = BlinkFlashlightAction.CONFIG_ON_MILLIS,
            label = "Light on for",
            defaultMillis = BlinkFlashlightAction.DEFAULT_ON_MILLIS,
            maxMillis = BlinkFlashlightAction.MAX_PHASE_MILLIS,
            preferred = DurationUnit.MILLISECONDS,
            help = "The shortest is ${BlinkFlashlightAction.MIN_PHASE_MILLIS} ms. " +
                "A shorter flash cannot be seen.",
        ),
        ConfigField.Duration(
            key = BlinkFlashlightAction.CONFIG_OFF_MILLIS,
            label = "Light off for",
            defaultMillis = BlinkFlashlightAction.DEFAULT_OFF_MILLIS,
            maxMillis = BlinkFlashlightAction.MAX_PHASE_MILLIS,
            preferred = DurationUnit.MILLISECONDS,
        ),
    )

    override val requirements = FLASHLIGHT_REQUIREMENTS

    override val warning: String =
        "This action pauses this rule while it blinks. The actions after it wait. " +
            "If this rule fires again while it blinks, two runs never happen at the " +
            "same time. The whole pattern is capped at " +
            "${BlinkFlashlightAction.MAX_TOTAL_MILLIS / 1_000} seconds, and Trigly " +
            "holds the phone awake for it, so the blinks keep their timing with the " +
            "screen off. A longer pattern blinks fewer times instead of running " +
            "longer. Trigly always leaves the flashlight off at the end, also when " +
            "you turn this rule off in the middle. If you switch the flashlight on " +
            "yourself while this runs, Trigly switches it off. If another app takes " +
            "the camera, the blinking stops and the rule says why."

    override fun create(config: Map<String, String>): Action = BlinkFlashlightAction(
        torch = torch,
        wake = wake,
        pattern = blinkPattern(config),
    )
}
