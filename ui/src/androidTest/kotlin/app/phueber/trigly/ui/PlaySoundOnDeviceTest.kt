package app.phueber.trigly.ui

import android.media.MediaPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.PlaySoundAction
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.TriggerEvent
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `play_sound` against a real `MediaPlayer`, which is the half `:actions`' unit
 * test cannot reach: there is no JVM implementation of the player, so the
 * arithmetic and the URI check are tested there and the playing is tested here.
 *
 * **The sound is generated, not borrowed from the device.** Using
 * `RingtoneManager.getDefaultUri` would make the test depend on which tones an
 * emulator image happens to ship and on whether a default is set, which is a
 * test that passes or fails for reasons that have nothing to do with this
 * action. A short silent WAV written to the app's own cache is deterministic,
 * needs no permission, and still exercises the whole path: `setDataSource`,
 * `prepare` off the caller's thread, `start`, the wait, and the release.
 *
 * Silent on purpose. A test that made noise on a shared device would be a
 * nuisance, and audibility is not what this asserts. What it asserts is that the
 * action reports success for a sound it could really play, and reports a failure
 * rather than throwing for one it could not.
 */
@RunWith(AndroidJUnit4::class)
class PlaySoundOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)
    private val written = mutableListOf<File>()

    @After
    fun tearDown() {
        written.forEach { it.delete() }
    }

    @Test
    fun a_real_sound_plays_and_reports_success() = runTest {
        val sound = silentWav(millis = 200)

        val result = PlaySoundAction(context, soundUri = "file://${sound.absolutePath}")
            .execute(event)

        assertEquals(ActionResult.Success(), result)
    }

    /**
     * The length the action waits for comes from the player, so a sound the
     * player can measure must not fall back to the unknown-length default. This
     * is the one assertion that proves the two halves are wired to each other:
     * the unit test covers the arithmetic, and this covers that the real player
     * feeds it a real number.
     */
    @Test
    fun a_generated_sound_reports_a_measurable_length() {
        val sound = silentWav(millis = 500)
        val player = MediaPlayer()

        try {
            player.setDataSource(context, android.net.Uri.parse("file://${sound.absolutePath}"))
            player.prepare()

            assertTrue(
                "the player should measure a generated wav, was ${player.duration}",
                player.duration > 0,
            )
        } finally {
            player.release()
        }
    }

    /**
     * A file that is not audio at all. The action must report it rather than let
     * the exception escape, because one broken action must not take down the rest
     * of the rule.
     */
    @Test
    fun an_unplayable_file_is_reported_rather_than_thrown() = runTest {
        val notAudio = File(context.cacheDir, "not-a-sound.wav").apply {
            writeText("this is not audio")
        }
        written += notAudio

        val result = PlaySoundAction(context, soundUri = "file://${notAudio.absolutePath}")
            .execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue(
            "was: ${(result as ActionResult.Failure).reason}",
            result.reason.contains("could not play the sound"),
        )
    }

    /**
     * A minimal 8 kHz mono 16-bit PCM WAV of silence, [millis] long.
     *
     * Written by hand because the alternative is shipping a binary fixture, and
     * 44 bytes of header plus zeroed samples is easier to read and to trust than
     * an opaque asset. The header is the standard RIFF/WAVE layout; every
     * multi-byte field is little-endian, which is what the format requires and
     * what a hand-rolled header most often gets wrong.
     */
    private fun silentWav(millis: Int): File {
        val sampleRate = 8_000
        val samples = sampleRate * millis / 1_000
        val dataBytes = samples * 2
        val file = File(context.cacheDir, "silence-$millis.wav")

        file.outputStream().use { out ->
            fun int(value: Int) = out.write(
                byteArrayOf(
                    (value and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(),
                    ((value shr 16) and 0xFF).toByte(),
                    ((value shr 24) and 0xFF).toByte(),
                )
            )

            fun short(value: Int) = out.write(
                byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
            )

            out.write("RIFF".toByteArray())
            int(36 + dataBytes)
            out.write("WAVE".toByteArray())

            out.write("fmt ".toByteArray())
            int(16)
            short(1)
            short(1)
            int(sampleRate)
            int(sampleRate * 2)
            short(2)
            short(16)

            out.write("data".toByteArray())
            int(dataBytes)
            out.write(ByteArray(dataBytes))
        }

        written += file
        return file
    }
}
