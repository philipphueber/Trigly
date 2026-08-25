package app.phueber.trigly.actions

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.TriggerEvent

/** Vibrates for a fixed duration. */
class VibrateAction(
    private val context: Context,
    private val durationMillis: Long,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val vibrator = context.vibrator()
            ?: return ActionResult.Failure("This device has no vibrator.")

        if (!vibrator.hasVibrator()) {
            return ActionResult.Failure("This device has no vibrator.")
        }

        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE)
        )
        return ActionResult.Success
    }

    companion object {
        const val TYPE = "vibrate"
        const val CONFIG_DURATION_MILLIS = "durationMillis"
        const val DEFAULT_DURATION_MILLIS = 300L

        /** Long enough to be unpleasant and to drain battery if a rule misfires. */
        const val MAX_DURATION_MILLIS = 10_000L
    }
}

/**
 * `getSystemService(Vibrator::class)` still works on API 31+, but is deprecated
 * in favour of going through [VibratorManager]; both paths are kept so neither
 * a deprecation warning nor a missing service breaks the action.
 */
private fun Context.vibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }

/**
 * Duration for a vibrate action, defaulted and capped.
 *
 * The cap is the point: a config typo of 30000 for 300 would buzz for half a
 * minute with no way to stop it short of killing the app. Pure so the bound is
 * tested rather than trusted.
 */
fun vibrationDurationMillis(raw: String?): Long {
    val duration = raw?.toLongOrNull() ?: VibrateAction.DEFAULT_DURATION_MILLIS
    require(duration > 0) { "duration must be positive, was $duration" }
    return duration.coerceAtMost(VibrateAction.MAX_DURATION_MILLIS)
}

class VibrateActionFactory(private val context: Context) : ActionFactory {
    override val type = VibrateAction.TYPE

    override val displayName = "Vibrate"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        ConfigField.Duration(
            key = VibrateAction.CONFIG_DURATION_MILLIS,
            label = "Duration",
            defaultMillis = VibrateAction.DEFAULT_DURATION_MILLIS,
            maxMillis = VibrateAction.MAX_DURATION_MILLIS,
            preferred = DurationUnit.MILLISECONDS,
            help = "This value is capped at ${VibrateAction.MAX_DURATION_MILLIS} ms.",
        ),
    )

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission("android.permission.VIBRATE"),
    )

    override fun create(config: Map<String, String>): Action = VibrateAction(
        context = context,
        durationMillis = vibrationDurationMillis(config[VibrateAction.CONFIG_DURATION_MILLIS]),
    )
}
