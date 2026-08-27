package app.phueber.trigly.actions

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * How long this action holds the rule open for, given the length
 * `MediaPlayer` reported after `prepare()`.
 *
 * The sound plays once, so its own length is the answer, and the two guards
 * around that are the same ones [alertSoundingMillis] needs and for the same
 * reasons. `MediaPlayer` reports -1 for a file it could not measure, and a
 * stream can report something absurd, so a length that is not a positive
 * number falls back to [PlaySoundAction.UNKNOWN_LENGTH_MILLIS] and any length
 * is capped at [PlaySoundAction.MAX_SOUNDING_MILLIS].
 *
 * **The cap is not about the platform, it is about the rule.** A sound is
 * picked from this phone, so it is usually a chime of a second or two, but the
 * picker can reach a podcast episode as easily as a notification tone. This
 * action holds the rule while the sound plays, so an hour-long file would hold
 * every action after it for an hour. Two minutes is past any sound somebody
 * means as a sound, and short enough that the mistake is survivable.
 *
 * Pure and top-level, matching the four sums in `PlayAlertAction.kt`, so the
 * arithmetic is tested without needing a `Context`.
 */
fun soundSoundingMillis(trackMillis: Int): Long = trackMillis
    .takeIf { it > 0 }
    ?.toLong()
    ?.coerceAtMost(PlaySoundAction.MAX_SOUNDING_MILLIS)
    ?: PlaySoundAction.UNKNOWN_LENGTH_MILLIS

/**
 * What is wrong with a stored sound URI, as the sentence to report, or null when
 * there is nothing wrong with it.
 *
 * Pure and top-level for the reason [soundSoundingMillis] is: the two refusals
 * this action makes are the whole of its input validation, and they are worth
 * testing without a `Context` or a `MediaPlayer`, neither of which exists on the
 * JVM. It also keeps [PlaySoundAction.execute] readable as the three steps it
 * actually is: check, play, release.
 *
 * Checked at run time rather than in the factory, matching `play_alert` and
 * `open_url`: the schema is what the editor can produce, and a factory that
 * refused a plausible string would make the field unsaveable. A bad URI is a run
 * that fails, not a rule that cannot exist.
 */
fun soundUriProblem(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return "This action has no sound chosen."
    if (!isPlayableSoundUri(value)) {
        return "A sound must use a content: or file: URI. This value is '$value'."
    }
    return null
}

/**
 * Plays one sound, once, on whatever stream Android sends an unconfigured sound
 * to.
 *
 * **This action's whole identity is what it does not do.** `play_alert` exists
 * to be heard: it defaults to the alarm stream specifically to bypass the
 * common case of a silenced ringer with the notification volume at zero, it
 * repeats for a duration so that "an alarm until I look at the phone" is
 * reachable, and it carries a route, a playback mode, a duration, a volume and
 * a stop-when-gone switch to make all of that expressible. That is the right
 * shape for an alert and the wrong shape for "play this sound". A person who
 * wants a chime should not have to understand audio routing to get one.
 *
 * So: one field, the sound. It plays once and the rule moves on.
 *
 * **No audio attributes are set, deliberately, and that is the feature.** Every
 * other sound in this codebase declares a usage and a content type, because
 * each of those callers has an opinion about where the sound belongs.
 * [PlayAlertAction] has [AlertRoute] for exactly that. This action's opinion is
 * that it has none: it leaves the player unconfigured and lets the platform
 * decide, which is what "the default audio stream" means. In practice Android
 * treats an unconfigured player as media, so the sound follows the media volume
 * and the connected output, and a silenced phone plays nothing.
 *
 * Setting `USAGE_MEDIA` explicitly would land in the same place today and would
 * be a different promise: it would say this action has chosen the media stream,
 * and it would keep choosing it if the platform default ever moved. Leaving the
 * attributes unset says what was actually decided.
 *
 * **No audio focus, which is the honest cost of that.** `play_alert`'s music
 * route asks for transient ducking focus, because a sound played on the media
 * stream without it mixes underneath whatever is already playing and can be
 * inaudible; its KDoc calls that out as not optional. This action does not ask,
 * because asking is an opinion about importance and this action holds none. The
 * consequence is real and belongs in the field's help rather than in a comment:
 * over music, this may be quiet. Somebody who needs to be heard over music
 * wants `play_alert`.
 *
 * **A local sound only**, through the same [isPlayableSoundUri] guard
 * `play_alert` uses, for the same reason: a rule can arrive from an import, and
 * an `http:` sound URI would make it phone home on every firing.
 *
 * The action suspends while the sound plays, so cancelling the rule stops it,
 * and the `finally` releases the player on that path too.
 */
class PlaySoundAction(
    private val context: Context,
    private val soundUri: String?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        soundUriProblem(soundUri)?.let { return ActionResult.Failure(it) }
        val raw = soundUri.orEmpty().trim()

        val player = MediaPlayer()
        return try {
            // prepare() reads the file, so it is I/O and must not run on the
            // caller's thread. No setAudioAttributes call: see the class KDoc.
            withContext(Dispatchers.IO) {
                player.setDataSource(context, Uri.parse(raw))
                player.prepare()
            }

            player.start()
            delay(soundSoundingMillis(player.duration))
            ActionResult.Success()
        } catch (failure: Exception) {
            // An unreadable file, a codec the device lacks, a content URI whose
            // owner is gone. Reported rather than thrown, so one broken action
            // does not take down the rest of the rule.
            ActionResult.Failure("Trigly could not play the sound. ${failure.message}", failure)
        } finally {
            // Reached on cancellation too, which is what makes disabling the
            // rule a stop button. stop() throws if the player never started.
            runCatching { player.stop() }
            player.release()
        }
    }

    companion object {
        const val TYPE = "play_sound"
        const val CONFIG_SOUND_URI = "soundUri"

        /** Two minutes. See [soundSoundingMillis] for why there is a cap at all. */
        const val MAX_SOUNDING_MILLIS = 2 * 60_000L

        /**
         * How long to wait when `MediaPlayer` could not say how long the sound
         * is. Five seconds: long enough for a chime to finish being audible,
         * short enough that a file this action cannot measure does not hold the
         * rule. [alertSoundingMillis] makes the same call for the same reason.
         */
        const val UNKNOWN_LENGTH_MILLIS = 5_000L
    }
}

class PlaySoundActionFactory(private val context: Context) : ActionFactory {
    override val type = PlaySoundAction.TYPE

    override val displayName = "Play a sound"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        // Required, unlike `play_alert`'s custom sound, which may be blank
        // because a tone choice stands behind it. Nothing stands behind this
        // one: no sound means nothing to play.
        ConfigField.SoundUri(
            key = PlaySoundAction.CONFIG_SOUND_URI,
            label = "Sound",
            required = true,
            help = "Any alarm, notification or ringtone on this phone. Trigly " +
                "refuses a sound from the web, because an imported rule could " +
                "then call home every time it fires.",
        ),
    )

    override val warning: String =
        "This sound uses the phone's normal sound output, so the media volume " +
            "sets how loud it is and it goes where music goes. A silenced phone " +
            "plays nothing, and a sound played while music is playing can be " +
            "quiet. Use 'Play an alert' for a sound you must not miss."

    override fun create(config: Map<String, String>): Action = PlaySoundAction(
        context = context,
        soundUri = config[PlaySoundAction.CONFIG_SOUND_URI],
    )
}
