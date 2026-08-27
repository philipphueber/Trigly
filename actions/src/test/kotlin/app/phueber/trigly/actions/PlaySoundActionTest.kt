package app.phueber.trigly.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `play_sound`'s two pure parts: how long it holds the rule open, and what it
 * refuses before it touches a player.
 *
 * The playing half needs a real `MediaPlayer`, which has no JVM implementation,
 * so it lives in `:ui`'s instrumented `PlaySoundOnDeviceTest`. Splitting it this
 * way is the reason both halves are testable at all: the arithmetic and the
 * validation are top-level functions, matching the four sums in
 * `PlayAlertAction.kt`.
 */
class PlaySoundActionTest {

    // --- soundSoundingMillis ----------------------------------------------------------

    @Test
    fun `a measured sound is held for its own length`() {
        assertEquals(1_800L, soundSoundingMillis(1_800))
    }

    /**
     * `MediaPlayer` reports -1 for a file it could not measure, and 0 is no more
     * usable. Neither should make the action return before the sound is audible.
     */
    @Test
    fun `an unmeasurable sound falls back rather than returning at once`() {
        assertEquals(PlaySoundAction.UNKNOWN_LENGTH_MILLIS, soundSoundingMillis(-1))
        assertEquals(PlaySoundAction.UNKNOWN_LENGTH_MILLIS, soundSoundingMillis(0))
    }

    /**
     * The picker can reach a podcast as easily as a chime, and this action holds
     * the rule while the sound plays, so an hour-long file would hold every
     * action after it for an hour.
     */
    @Test
    fun `a very long sound is capped`() {
        assertEquals(PlaySoundAction.MAX_SOUNDING_MILLIS, soundSoundingMillis(60 * 60 * 1_000))
    }

    @Test
    fun `a sound exactly at the cap is not shortened`() {
        assertEquals(
            PlaySoundAction.MAX_SOUNDING_MILLIS,
            soundSoundingMillis(PlaySoundAction.MAX_SOUNDING_MILLIS.toInt()),
        )
    }

    // --- soundUriProblem --------------------------------------------------------------

    @Test
    fun `no sound chosen is a problem, and says so plainly`() {
        val problem = soundUriProblem(null)

        assertTrue("was: $problem", problem!!.contains("no sound chosen"))
    }

    @Test
    fun `a blank sound is the same as none`() {
        assertTrue(soundUriProblem("   ")!!.contains("no sound chosen"))
        assertTrue(soundUriProblem("")!!.contains("no sound chosen"))
    }

    /**
     * The reason this guard exists, and it is not about codecs. A rule config can
     * arrive from an import or a shared recipe, and a web sound URI would make
     * that rule report to a stranger's server every time it fired. The same check
     * `play_alert` makes, through the same [isPlayableSoundUri].
     */
    @Test
    fun `a web sound is refused and the value is named`() {
        val problem = soundUriProblem("https://example.com/chime.mp3")

        assertTrue("was: $problem", problem!!.contains("content:"))
        assertTrue("the value is named so it can be found", problem.contains("example.com"))
    }

    @Test
    fun `a content sound is accepted`() {
        assertNull(soundUriProblem("content://media/internal/audio/media/42"))
    }

    @Test
    fun `a file sound is accepted`() {
        assertNull(soundUriProblem("file:///storage/emulated/0/chime.ogg"))
    }

    @Test
    fun `surrounding space does not decide the answer`() {
        assertNull(soundUriProblem("  content://media/internal/audio/media/42  "))
    }
}
