package app.phueber.trigly.actions

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.FieldCondition
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Which of the device's own tones an alert uses when no custom sound is given. */
enum class AlertSound(
    val configValue: String,
    val ringtoneType: Int,
    val displayName: String,
) {
    NOTIFICATION("notification", RingtoneManager.TYPE_NOTIFICATION, "the notification tone"),
    ALARM("alarm", RingtoneManager.TYPE_ALARM, "the alarm tone"),
    RINGTONE("ringtone", RingtoneManager.TYPE_RINGTONE, "the ringtone"),
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

/** The cap, in the words the duration field now shows it in rather than raw ms. */
private fun describeAlertCap(): String =
    "${PlayAlertAction.MAX_DURATION_MILLIS / 1000} seconds"

/**
 * Whether an alert plays through once or keeps going.
 *
 * Two genuinely different jobs behind one action. A single pass is a *chime* —
 * "the washing is done" — and its length is the tone's own, which is the one
 * thing a duration field cannot express. Repeating is an *alarm*, and there the
 * length is the whole point: keep going until I look at the phone.
 */
enum class AlertPlayback(val configValue: String, val displayName: String) {
    ONCE("once", "play it once"),
    REPEAT("repeat", "repeat for a set time"),
    ;

    companion object {
        const val CONFIG_KEY = "playback"

        /**
         * Absent reads as [REPEAT], because that is what this action did before
         * it could do anything else. Every rule saved then meant "loop for the
         * duration", and an update must not quietly turn those into single
         * chimes. An unknown value is an error, as everywhere else.
         */
        fun parse(raw: String?): AlertPlayback = when (val value = raw?.lowercase()) {
            null -> REPEAT
            else -> entries.firstOrNull { it.configValue == value }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
        }
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
 * How long an alert holds the rule open for.
 *
 * Repeating is the duration the user set. Playing once is the tone's own length,
 * which is only knowable after `prepare()` — and is capped and floored anyway,
 * because `MediaPlayer` reports -1 for a malformed file and a stream can report
 * something absurd. Neither should pin an action open, and neither should make it
 * return before the sound is audible.
 *
 * Pure and top-level, like the other three sums in this file, so the arithmetic
 * is tested rather than trusted — and without needing a `Context` to do it.
 */
fun alertSoundingMillis(
    playback: AlertPlayback,
    durationMillis: Long,
    trackMillis: Int,
): Long = when (playback) {
    AlertPlayback.REPEAT -> durationMillis
    AlertPlayback.ONCE -> trackMillis
        .takeIf { it > 0 }
        ?.toLong()
        ?.coerceAtMost(PlayAlertAction.MAX_DURATION_MILLIS)
        ?: PlayAlertAction.DEFAULT_DURATION_MILLIS
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
 * What, if anything, cuts an alert short before its time is up.
 *
 * A sum type rather than a nullable key, because "the option is on but cannot
 * work here" is a third case and the one worth reporting. Silently falling back
 * to the full duration would leave someone believing their alarm stops when they
 * swipe the notification away, and find out otherwise in a meeting.
 */
sealed interface AlertStop {

    /** Nothing to watch: the alert sounds for its whole length. */
    data object Duration : AlertStop

    /** Stop as soon as the notification with this key is no longer posted. */
    data class WhenGone(val key: String) : AlertStop

    /** Asked for, but impossible here. [reason] is reported after the alert. */
    data class Unwatchable(val reason: String) : AlertStop
}

/**
 * Decides what an alert watches, from the option and what is actually available.
 *
 * Pure, because the two ways this can be impossible are exactly the cases nobody
 * tests by hand: a rule whose trigger is not a notification at all, and
 * notification access not being granted. Both have to be *reported* rather than
 * quietly ignored, and both are a decision rather than an effect.
 *
 * The key comes from the event, never from configuration. A notification key is
 * minted by the posting app on every post, so a stored one would be stale by the
 * time the rule ran — see `docs/actions.md`. Within one firing, though, it is
 * exactly the right handle: an app updating its notification keeps the same key,
 * so a progress notification that keeps changing still counts as present.
 */
fun alertStop(
    stopWhenGone: Boolean,
    event: TriggerEvent,
    notificationAccess: Boolean,
): AlertStop {
    if (!stopWhenGone) return AlertStop.Duration

    val key = event.payload[SharedPayloadKeys.NOTIFICATION_KEY]
    return when {
        key.isNullOrBlank() -> AlertStop.Unwatchable(
            "The \"stop when the notification goes away\" option needs a " +
                "notification to watch. This rule was fired by " +
                "${event.triggerType}, which carries no notification. The alert " +
                "played for its full length."
        )
        !notificationAccess -> AlertStop.Unwatchable(
            "The \"stop when the notification goes away\" option needs " +
                "notification access. Notification access is not granted, or is " +
                "not bound yet. The alert played for its full length."
        )
        else -> AlertStop.WhenGone(key)
    }
}

/**
 * Suspends until the notification with [key] is no longer on screen.
 *
 * Polled, not subscribed, and for the same reason the watchdog trigger polls:
 * presence is a state the listener only reports the *edges* of, and an alert
 * that started while the notification was already gone would never hear an edge
 * at all. Checking before the first delay is what covers that case.
 *
 * A revoked access reads as "gone", because [NotificationController] reports an
 * empty list either way. That is the safe direction to be wrong in: the mistake
 * is a silence, not an alarm nobody can stop.
 */
suspend fun awaitNotificationGone(
    notifications: NotificationController,
    key: String,
    pollMillis: Long = PlayAlertAction.PRESENCE_POLL_MILLIS,
) {
    while (notifications.activeNotifications().any { it.key == key }) {
        delay(pollMillis)
    }
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
 * the rule mid-alert stops the sound. With [stopWhenGone] on there is a second,
 * far more natural stop: swiping away the notification that caused the alert.
 */
/**
 * Which audio route a sound takes, which decides far more than how loud it is.
 *
 * [ALERT] is the alarm stream, and it is what this action was built on. An
 * average silenced phone still lets it through, and the platform's own policy
 * makes an alarm audible over whatever else is playing. It is also, on most
 * devices, the stream Android keeps on the phone speaker rather than sending to
 * a connected Bluetooth device, because an alarm you cannot hear because your
 * headphones are in a bag is not an alarm.
 *
 * [MUSIC] is the media route, and that last sentence is exactly why it exists.
 * A rule that plays a sound because the car connected wants the car to play it.
 * Media follows the connected output, and it follows the media volume, and Do
 * Not Disturb does not silence it. A silent phone stays silent, which is the
 * trade in the other direction: this route is for a sound that should arrive
 * where the music is, not for one that must be heard whatever the phone is set
 * to.
 *
 * [takesFocus] is the part that is not obvious and is not optional. The alarm
 * stream needs no audio focus, because the platform ducks other audio for it.
 * Media does not: a sound played on the media route without asking for focus
 * simply mixes underneath whatever is already playing, at whatever the two
 * volumes happen to be, and can be inaudible. So the media route asks for
 * transient focus and gives it back, and the sound is heard rather than
 * technically played.
 */
enum class AlertRoute(
    val configValue: String,
    val label: String,
    val usage: Int,
    val contentType: Int,
    val takesFocus: Boolean,
) {
    ALERT(
        configValue = "alert",
        label = "An alert",
        usage = AudioAttributes.USAGE_ALARM,
        contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
        takesFocus = false,
    ),

    MUSIC(
        configValue = "music",
        label = "Music",
        usage = AudioAttributes.USAGE_MEDIA,
        contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
        takesFocus = true,
    ),
    ;

    companion object {
        const val CONFIG_KEY = "route"

        /**
         * Absent means [ALERT], because every rule saved before this key existed
         * meant the alarm stream: it was the only thing this action could do.
         * An unknown value is an error rather than a guess, for the reason
         * [AlertPlayback.parse] gives.
         */
        fun parse(raw: String?): AlertRoute {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return ALERT
            return entries.firstOrNull { it.configValue == value }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$value'"
                )
        }
    }
}

class PlayAlertAction(
    private val context: Context,
    private val sound: AlertSound,
    private val customUri: String?,
    private val volumeGain: Float,
    private val durationMillis: Long,
    private val playback: AlertPlayback = AlertPlayback.REPEAT,
    private val notifications: NotificationController = NotificationController.Unavailable,
    private val stopWhenGone: Boolean = false,
    private val route: AlertRoute = AlertRoute.ALERT,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        // Checked here rather than in the factory, like `open_url`: the schema is
        // what the editor can produce, and a factory that rejected a plausible
        // string would make the field unsaveable. A bad URI is a run that fails,
        // not a rule that cannot exist.
        val custom = customUri?.trim().orEmpty()
        if (custom.isNotEmpty() && !isPlayableSoundUri(custom)) {
            return ActionResult.Failure(
                "A custom sound must use a content: or file: URI. This value is '$custom'."
            )
        }

        val uri = resolveUri() ?: return ActionResult.Failure(
            "This device has no ${sound.configValue} sound set."
        )

        // Decided before a note is played, so that an option that cannot work
        // here is reported even if the sound itself goes fine.
        val stop = alertStop(stopWhenGone, event, notifications.isConnected)

        val player = MediaPlayer()
        val audio = context.getSystemService(AudioManager::class.java)
        // Requested before the first note and given back in the `finally`, so a
        // cancelled rule does not leave other audio ducked. Null for the alarm
        // route, which needs no focus, and null if the service is missing, which
        // is not a reason to refuse to play.
        val focus = if (route.takesFocus) transientFocusRequest(route) else null
        if (focus != null && audio != null) audio.requestAudioFocus(focus)

        return try {
            // prepare() reads the file, so it is I/O and must not run on the
            // caller's thread.
            withContext(Dispatchers.IO) {
                player.setAudioAttributes(audioAttributes(route))
                player.setDataSource(context, uri)
                player.isLooping = playback == AlertPlayback.REPEAT
                player.setVolume(volumeGain, volumeGain)
                player.prepare()
            }

            player.start()
            val soundingMillis = alertSoundingMillis(playback, durationMillis, player.duration)

            if (stop is AlertStop.WhenGone) {
                // Whichever comes first. The duration stays the safety net: an
                // ongoing notification that never goes away must not turn the
                // cap into "until the battery dies".
                withTimeoutOrNull(soundingMillis) {
                    awaitNotificationGone(notifications, stop.key)
                }
            } else {
                delay(soundingMillis)
            }

            when (stop) {
                is AlertStop.Unwatchable -> ActionResult.Failure(stop.reason)
                else -> ActionResult.Success()
            }
        } catch (failure: Exception) {
            // A bad custom URI, an unreadable file, a codec the device lacks.
            // Reported rather than thrown: one broken action must not take down
            // the rest of the rule.
            ActionResult.Failure("Trigly could not play the alert. ${failure.message}", failure)
        } finally {
            // Reached on cancellation too, which is what makes disabling the rule
            // an actual stop button. stop() throws if the player never started.
            runCatching { player.stop() }
            player.release()
            if (focus != null && audio != null) audio.abandonAudioFocusRequest(focus)
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
        const val CONFIG_STOP_WHEN_GONE = "stopWhenGone"

        const val DEFAULT_DURATION_MILLIS = 3_000L
        const val DEFAULT_VOLUME_PERCENT = 100

        /**
         * A minute of looping alarm is already far past "I noticed". Beyond this
         * the only way to stop it is to disable the rule or kill the app.
         */
        const val MAX_DURATION_MILLIS = 60_000L

        /**
         * How often presence is re-checked while an alert is sounding.
         *
         * Half a second is under the threshold at which a stop feels like a
         * consequence of the swipe rather than a coincidence, and it is 120
         * checks across the longest alert this action can play — cheap enough
         * not to need a cleverer mechanism.
         */
        const val PRESENCE_POLL_MILLIS = 500L
    }
}

class PlayAlertActionFactory(
    private val context: Context,
    /**
     * Only used by the "stop when the notification goes away" option, so it
     * defaults to unavailable rather than being required: an alert with the
     * option off works with no notification access at all, and demanding the
     * access at the factory would hide the whole action from anyone who has not
     * granted it.
     */
    private val notifications: NotificationController = NotificationController.Unavailable,
) : ActionFactory {
    override val type = PlayAlertAction.TYPE

    override val displayName = "Play an alert sound"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        ConfigField.Choice(
            key = AlertSound.CONFIG_KEY,
            label = "Tone",
            options = AlertSound.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            default = AlertSound.ALARM.configValue,
            help = "This is the phone's own tone of that kind. The alarm tone is " +
                "the loudest. A silenced ringer does not affect it.",
        ),
        // A picker, not a text box: the stored value is a media URI, which is not
        // something anyone can produce from memory or would want to read back.
        ConfigField.SoundUri(
            key = PlayAlertAction.CONFIG_SOUND_URI,
            label = "Custom sound",
            blankMeaning = "Use the tone above",
            help = "This can be any alarm, notification or ringtone on this " +
                "phone. Trigly refuses a network sound. Otherwise an imported " +
                "rule could call home every time it fires.",
        ),
        // Above the "Play it" choice on purpose: where a sound comes out is a
        // bigger decision than whether it repeats, and it changes what the
        // volume slider below means.
        ConfigField.Choice(
            key = AlertRoute.CONFIG_KEY,
            label = "Play it as",
            options = AlertRoute.entries.map {
                ConfigField.Option(it.configValue, it.label)
            },
            default = AlertRoute.ALERT.configValue,
            help = "An alert uses the alarm volume. A silenced phone still plays " +
                "it, and it usually comes out of the phone speaker even when a " +
                "Bluetooth device is connected. Music uses the media volume and " +
                "goes where the music goes, so a connected car or headphones " +
                "plays it. A silenced phone plays no music. Choose music for a " +
                "sound that belongs in the car, and an alert for a sound you " +
                "must not miss.",
        ),
        ConfigField.Choice(
            key = AlertPlayback.CONFIG_KEY,
            label = "Play it",
            options = AlertPlayback.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            default = AlertPlayback.REPEAT.configValue,
            help = "Once is a chime and lasts as long as the tone does. Repeating " +
                "is an alarm and lasts as long as you set below.",
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
            help = "This is a percentage of the alarm volume. It cannot exceed " +
                "the alarm volume.",
        ),
        ConfigField.Duration(
            key = PlayAlertAction.CONFIG_DURATION_MILLIS,
            label = "Keep sounding for",
            defaultMillis = PlayAlertAction.DEFAULT_DURATION_MILLIS,
            maxMillis = PlayAlertAction.MAX_DURATION_MILLIS,
            preferred = DurationUnit.SECONDS,
            // Gone entirely when the tone plays once, rather than shown with a
            // sentence explaining that it does nothing. A single pass lasts as
            // long as the tone does, which is the one length a duration field
            // cannot express — so there is nothing here to set.
            shownWhen = FieldCondition(
                key = AlertPlayback.CONFIG_KEY,
                value = AlertPlayback.REPEAT.configValue,
            ),
            help = "This value is capped at ${describeAlertCap()}.",
        ),
        // Last, because it reads as a qualifier on the duration above rather
        // than a setting of its own: whichever comes first.
        ConfigField.Flag(
            key = PlayAlertAction.CONFIG_STOP_WHEN_GONE,
            label = "Stop when the notification goes away",
            help = "This setting applies when a notification fires the rule. " +
                "The sound stops the moment you swipe away that notification, " +
                "or its app clears it. Without this setting, the sound runs " +
                "for the full time. This setting needs notification access. " +
                "The time above still applies as the upper limit.",
        ),
    )

    override val warning: String =
        "This action plays on the alarm stream. You can hear it through a " +
            "silent ringer. The sound cannot be louder than the alarm volume. " +
            "Do Not Disturb can still silence it, unless the phone allows " +
            "alarms through. If the option below is on, you can cut a long " +
            "alert short. Dismiss the notification that caused it. Otherwise, " +
            "you must turn off the rule to stop it."

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
            playback = AlertPlayback.parse(config[AlertPlayback.CONFIG_KEY]),
            route = AlertRoute.parse(config[AlertRoute.CONFIG_KEY]),
            notifications = notifications,
            stopWhenGone = config[PlayAlertAction.CONFIG_STOP_WHEN_GONE]?.toBoolean() ?: false,
        )
    }
}

/**
 * The attributes for [route]. Separate from the player so a test can read what
 * a route asks for without a `MediaPlayer`.
 *
 * Public rather than internal, and the reason is where the test has to live.
 * `AudioAttributes.Builder` has no JVM implementation, so the only place this
 * can be exercised is an instrumented test, and the instrumented tests that need
 * a whole assembled app live in `:ui`. That is the same reason
 * `ConfigSchemaContractTest` sits there rather than beside the schema.
 */
fun audioAttributes(route: AlertRoute): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(route.usage)
        .setContentType(route.contentType)
        .build()

/**
 * The focus request for a route that needs one.
 *
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` rather than a full transient gain: a
 * short sound over the top of music wants the music quieter for a moment, not
 * paused and restarted. Pausing is what a phone call does, and a rule playing a
 * two second chime is not a phone call.
 *
 * Public for the reason [audioAttributes] is.
 */
fun transientFocusRequest(route: AlertRoute): AudioFocusRequest =
    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes(route))
        .build()
