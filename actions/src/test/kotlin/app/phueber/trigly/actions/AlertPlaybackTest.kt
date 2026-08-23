package app.phueber.trigly.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Once versus repeat, and how long each holds the rule open for.
 *
 * The interesting half is the absent case. `play_alert` only ever looped, so no
 * rule saved before this has a `playback` key — and reading that as "once" would
 * silently convert every existing alarm into a single chime, which is a change
 * nobody asked for and nothing would report.
 */
class AlertPlaybackTest {

    @Test
    fun `an absent playback mode still means repeat`() {
        assertEquals(AlertPlayback.REPEAT, AlertPlayback.parse(null))
    }

    @Test
    fun `both modes parse, in any case`() {
        assertEquals(AlertPlayback.ONCE, AlertPlayback.parse("once"))
        assertEquals(AlertPlayback.REPEAT, AlertPlayback.parse("repeat"))
        assertEquals(AlertPlayback.ONCE, AlertPlayback.parse("ONCE"))
    }

    @Test
    fun `an unknown mode is refused with the offending value`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            AlertPlayback.parse("twice")
        }
        assertTrue(thrown.message.orEmpty().contains("twice"))
    }

    // --- how long it sounds for ---------------------------------------------

    @Test
    fun `repeating sounds for exactly the duration set`() {
        assertEquals(30_000L, alertSoundingMillis(AlertPlayback.REPEAT, durationMillis = 30_000L, trackMillis = 1_200))
    }

    @Test
    fun `playing once sounds for the tone's own length, not the duration`() {
        // The whole point of "once": the length is the tone's, which is the one
        // thing the duration field cannot express.
        assertEquals(1_200L, alertSoundingMillis(AlertPlayback.ONCE, durationMillis = 30_000L, trackMillis = 1_200))
    }

    @Test
    fun `a track that reports no length falls back rather than returning instantly`() {
        // MediaPlayer reports -1 for a malformed file, and 0 would make the action
        // return before the sound was audible.
        assertEquals(
            PlayAlertAction.DEFAULT_DURATION_MILLIS,
            alertSoundingMillis(AlertPlayback.ONCE, durationMillis = 30_000L, trackMillis = -1),
        )
        assertEquals(
            PlayAlertAction.DEFAULT_DURATION_MILLIS,
            alertSoundingMillis(AlertPlayback.ONCE, durationMillis = 30_000L, trackMillis = 0),
        )
    }

    @Test
    fun `an absurdly long track is capped, so it cannot pin the rule open`() {
        assertEquals(
            PlayAlertAction.MAX_DURATION_MILLIS,
            alertSoundingMillis(AlertPlayback.ONCE, durationMillis = 1_000L, trackMillis = Int.MAX_VALUE),
        )
    }
}
