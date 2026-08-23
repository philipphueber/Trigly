package app.phueber.trigly.actions

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Which of the device's own tones an alert uses when no custom sound is given. */
enum class AlertSound(val configValue: String, val ringtoneType: Int) {
    NOTIFICATION("notification", RingtoneManager.TYPE_NOTIFICATION),
    ALARM("alarm", RingtoneManager.TYPE_ALARM),
    RINGTONE("ringtone", RingtoneManager.TYPE_RINGTONE),
    ;

    companion object {
        const val CONFIG_KEY = "sound"

        fun parse(raw: String?): AlertSound =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
    }
}

/**
 * Duration for an alert, defaulted and capped.
 *
 * The cap is the whole safety story for this action. The tone loops, so unlike
 * `vibrate` a mistake here is not merely unpleasant — a rule that fires on every
 * notification with a 30-minute duration is an alarm nobody can switch off from
 * inside the app. Pure, so the bound is tested rather than trusted.
 */
fun alertDurationMillis(raw: String?): Long {
    val duration = raw?.toLongOrNull() ?: PlayAlertAction.DEFAULT_DURATION_MILLIS
    require(duration > 0) { "duration must be positive, was $duration" }
    return duration.coerceAtMost(PlayAlertAction.MAX_DURATION_MILLIS)
}

/**
 * Converts a 0–100 percentage to the linear scalar [MediaPlayer.setVolume] wants.
 *
 * This is the alert's *own* gain, applied on top of whatever the alarm stream is
 * set to — it attenuates, it cannot exceed the stream. Linear rather than
 * perceptual: 50% is half the amplitude, which sounds louder than half.
 */
fun alertVolumeGain(raw: String?): Float {
    val percent = raw?.toIntOrNull() ?: PlayAlertAction.DEFAULT_VOLUME_PERCENT
    return percent.coerceIn(0, 100) / 100f
}

/**
 * Whether a custom sound URI is one this action will play.
 *
 * Local only — `content:` and `file:`. A rule config can arrive from an import
 * or a shared recipe, and an `http:` sound URI would turn "play an alert" into a
 * beacon that reports to a stranger's server every time the rule fires, which is
 * the same reasoning that keeps `http_request` to https and `open_url` to the
 * web schemes. A local URI cannot phone home.
 */
fun isPlayableSoundUri(raw: String): Boolean {
    val scheme = raw.trim().substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "content" || scheme == "file"
}

/**
 * Sounds an alert loud enough to be noticed, for a set length of time.
 *
 * The point of this over `post_notification` is the two things a notification
 * cannot promise. It plays on the **alarm** stream, so a silenced ringer does not
 * swallow it — which is the whole reason to reach for this rather than letting
 * the triggering app make its own sound. And it *keeps* sounding for a chosen
 * duration rather than playing one short tone, so "a beep" and "an alarm until I
 * look at the phone" are the same action with a different number.
 *
 * Honest limits, because this is the kind of feature people assume is absolute:
 *
 *  · **The alarm stream is loud, not unmuteable.** If the user has turned the
 *    alarm volume itself down, this is quiet — no app can override that without
 *    Do Not Disturb access, and taking that access for a sound effect would be a
 *    bad trade. What it does bypass is the far more common case: ringer on
 *    silent or vibrate, notification volume at zero.
 *  · **Do Not Disturb can still suppress it**, unless the user has allowed
 *    alarms through, which is the default on most devices.
 *
 * The action suspends for the duration so the engine can cancel it — disabling
 * the rule mid-alert stops the sound, which is the only in-app way to stop one.
 */
class PlayAlertAction(
    private val context: Context,
    private val sound: AlertSound,
    private val customUri: String?,
    private val volumeGain: Float,
    private val durationMillis: Long,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        // Checked here rather than in the factory, like `open_url`: the schema is
        // what the editor can produce, and a factory that rejected a plausible
        // string would make the field unsaveable. A bad URI is a run that fails,
        // not a rule that cannot exist.
        val custom = customUri?.trim().orEmpty()
        if (custom.isNotEmpty() && !isPlayableSoundUri(custom)) {
            return ActionResult.Failure(
                "a custom sound must be a content: or file: URI, got '$custom'"
            )
        }

        val uri = resolveUri() ?: return ActionResult.Failure(
            "no ${sound.configValue} sound is set on this device"
        )

        val player = MediaPlayer()
        return try {
            // prepare() reads the file, so it is I/O and must not run on the
            // caller's thread.
            withContext(Dispatchers.IO) {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(context, uri)
                // Looping plus a duration, rather than playing the tone once: a
                // notification tone is under a second, and "alert me" means keep
                // going until I notice.
                player.isLooping = true
                player.setVolume(volumeGain, volumeGain)
                player.prepare()
            }

            player.start()
            delay(durationMillis)
            ActionResult.Success
        } catch (failure: Exception) {
            // A bad custom URI, an unreadable file, a codec the device lacks.
            // Reported rather than thrown: one broken action must not take down
            // the rest of the rule.
            ActionResult.Failure("could not play the alert: ${failure.message}", failure)
        } finally {
            // Reached on cancellation too, which is what makes disabling the rule
            // an actual stop button. stop() throws if the player never started.
            runCatching { player.stop() }
            player.release()
        }
    }

    private fun resolveUri(): Uri? {
        val custom = customUri?.trim().orEmpty()
        if (custom.isNotEmpty()) return Uri.parse(custom)
        return RingtoneManager.getDefaultUri(sound.ringtoneType)
    }

    companion object {
        const val TYPE = "play_alert"
        const val CONFIG_SOUND_URI = "soundUri"
        const val CONFIG_VOLUME_PERCENT = "volumePercent"
        const val CONFIG_DURATION_MILLIS = "durationMillis"

        const val DEFAULT_DURATION_MILLIS = 3_000L
        const val DEFAULT_VOLUME_PERCENT = 100

        /**
         * A minute of looping alarm is already far past "I noticed". Beyond this
         * the only way to stop it is to disable the rule or kill the app.
         */
        const val MAX_DURATION_MILLIS = 60_000L
    }
}

class PlayAlertActionFactory(private val context: Context) : ActionFactory {
    override val type = PlayAlertAction.TYPE

    override val displayName = "Play an alert sound"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        ConfigField.Choice(
            key = AlertSound.CONFIG_KEY,
            label = "Tone",
            options = AlertSound.entries.map {
                ConfigField.Option(it.configValue, it.configValue)
            },
            default = AlertSound.ALARM.configValue,
            help = "The device's own tone of that kind. Alarm is the loudest and " +
                "the one a silenced ringer does not affect.",
        ),
        // A picker, not a text box: the stored value is a media URI, which is not
        // something anyone can produce from memory or would want to read back.
        ConfigField.SoundUri(
            key = PlayAlertAction.CONFIG_SOUND_URI,
            label = "Custom sound",
            blankMeaning = "Use the tone above",
            help = "Any alarm, notification or ringtone this phone knows about. " +
                "Network sounds are refused — an imported rule could otherwise " +
                "call home every time it fires.",
        ),
        // A slider, not a number box: this is the one setting here whose value is
        // a position rather than a decision. Nobody knows they want 65% — they
        // know they want it quieter, and drag until it looks right.
        ConfigField.Slider(
            key = PlayAlertAction.CONFIG_VOLUME_PERCENT,
            label = "Volume",
            min = 0,
            max = 100,
            default = PlayAlertAction.DEFAULT_VOLUME_PERCENT.toLong(),
            unit = "%",
            help = "Of the alarm volume, which this cannot exceed.",
        ),
        ConfigField.Number(
            key = PlayAlertAction.CONFIG_DURATION_MILLIS,
            label = "Keep sounding for",
            default = PlayAlertAction.DEFAULT_DURATION_MILLIS,
            min = 1,
            max = PlayAlertAction.MAX_DURATION_MILLIS,
            unit = "ms",
            help = "The tone repeats until this elapses. Capped at " +
                "${PlayAlertAction.MAX_DURATION_MILLIS} ms.",
        ),
    )

    override val warning: String =
        "Plays on the alarm stream, so it is heard through a silent ringer. It " +
            "cannot be louder than the alarm volume, and Do Not Disturb can still " +
            "silence it unless alarms are allowed through. Disabling the rule is " +
            "the only way to cut a long alert short."

    override fun create(config: Map<String, String>): Action {
        val custom = config[PlayAlertAction.CONFIG_SOUND_URI]?.trim().orEmpty()

        return PlayAlertAction(
            context = context,
            sound = AlertSound.parse(
                config[AlertSound.CONFIG_KEY] ?: AlertSound.ALARM.configValue
            ),
            customUri = custom.ifEmpty { null },
            volumeGain = alertVolumeGain(config[PlayAlertAction.CONFIG_VOLUME_PERCENT]),
            durationMillis = alertDurationMillis(config[PlayAlertAction.CONFIG_DURATION_MILLIS]),
        )
    }
}
