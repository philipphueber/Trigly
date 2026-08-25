package app.phueber.trigly.actions

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Speaks text aloud.
 *
 * An engine is created per run rather than held open: a long-lived
 * [TextToSpeech] keeps an audio service bound for the life of the process,
 * which is a poor trade for an action that fires occasionally.
 *
 * Two pieces of lifecycle care that are easy to get wrong:
 *
 *  - The engine initialises asynchronously, so this suspends until it is ready
 *    and reports an init failure rather than silently saying nothing.
 *  - Speaking is asynchronous too, and `shutdown()` *stops* playback. Shutting
 *    down straight after `speak()` would cut the utterance off mid-word, so the
 *    engine is released from the progress listener instead. The action itself
 *    returns as soon as the text is queued — the rule's job is done then, and
 *    waiting would block the engine's dispatch for the length of the sentence.
 */
class SpeakAction(
    private val context: Context,
    private val text: String,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        if (text.isBlank()) return ActionResult.Failure("There is no text to speak.")

        return suspendCancellableCoroutine { continuation ->
            var engine: TextToSpeech? = null

            fun finish(result: ActionResult) {
                if (continuation.isActive) continuation.resume(result)
            }

            engine = TextToSpeech(context) { status ->
                val tts = engine
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    tts?.shutdown()
                    finish(ActionResult.Failure("No text-to-speech engine works."))
                    return@TextToSpeech
                }

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        tts.shutdown()
                    }

                    @Deprecated("Required override; the newer overload is not abstract")
                    override fun onError(utteranceId: String?) {
                        tts.shutdown()
                    }
                })

                val queued = tts.speak(text, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
                if (queued == TextToSpeech.SUCCESS) {
                    finish(ActionResult.Success)
                } else {
                    tts.shutdown()
                    finish(ActionResult.Failure("The speech engine refused the text."))
                }
            }

            continuation.invokeOnCancellation { engine?.shutdown() }
        }
    }

    companion object {
        const val TYPE = "speak"
        const val CONFIG_TEXT = "text"
        private const val UTTERANCE_ID = "trigly"
    }
}

class SpeakActionFactory(private val context: Context) : ActionFactory {
    override val type = SpeakAction.TYPE

    override val displayName = "Speak out loud"
    override val category = ActionCategory.NOTIFY

    override val configFields = listOf(
        messageText(SpeakAction.CONFIG_TEXT, "What to say"),
    )

    override fun create(config: Map<String, String>): Action = SpeakAction(
        context = context,
        text = config[SpeakAction.CONFIG_TEXT] ?: error("$type needs '${SpeakAction.CONFIG_TEXT}'"),
    )
}
