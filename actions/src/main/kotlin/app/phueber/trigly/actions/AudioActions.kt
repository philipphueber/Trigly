package app.phueber.trigly.actions

import android.content.Context
import android.media.AudioManager
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.TriggerEvent

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

/** Sets the volume of one audio stream. */
class SetVolumeAction(
    private val context: Context,
    private val stream: VolumeStream,
    private val percent: Int,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
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
        ),
    )

    override fun create(config: Map<String, String>): Action {
        val raw = config[SetVolumeAction.CONFIG_PERCENT]
            ?: error("$type needs '${SetVolumeAction.CONFIG_PERCENT}'")
        val percent = raw.toIntOrNull()
            ?: error("${SetVolumeAction.CONFIG_PERCENT} must be a number 0-100, was '$raw'")

        return SetVolumeAction(
            context = context,
            stream = VolumeStream.parse(config[VolumeStream.CONFIG_KEY]),
            percent = percent,
        )
    }
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
