package app.phueber.trigly.actions

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which route a stored value means, and what each route asks the platform for.
 *
 * The parse half matters because this key is new: every rule saved before it
 * meant the alarm stream, since that was the only thing this action could do.
 * The attributes half matters because the two routes differ in exactly the ways
 * a person cannot see, and a wrong usage value plays the sound in the wrong
 * place while looking perfectly healthy.
 */
class AlertRouteTest {

    @Test
    fun `an absent route means an alert, which is what every older rule meant`() {
        assertEquals(AlertRoute.ALERT, AlertRoute.parse(null))
        assertEquals(AlertRoute.ALERT, AlertRoute.parse(""))
        assertEquals(AlertRoute.ALERT, AlertRoute.parse("  "))
    }

    @Test
    fun `a stored route is honoured`() {
        assertEquals(AlertRoute.ALERT, AlertRoute.parse("alert"))
        assertEquals(AlertRoute.MUSIC, AlertRoute.parse("music"))
    }

    /**
     * A typo is a wrong answer to the question, not an unanswered one, so it
     * fails the rule loudly rather than picking a route for the person.
     */
    @Test
    fun `an unknown route is refused`() {
        assertThrows(IllegalStateException::class.java) { AlertRoute.parse("speaker") }
    }

    /**
     * The values, not the built object. `AudioAttributes.Builder` is an Android
     * class with no JVM implementation, so building one belongs in an
     * instrumented test; these constants are plain ints and the choice of them
     * is the decision worth pinning here.
     */
    @Test
    fun `an alert asks for the alarm stream and no focus`() {
        assertEquals(AudioAttributes.USAGE_ALARM, AlertRoute.ALERT.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, AlertRoute.ALERT.contentType)
        assertFalse(AlertRoute.ALERT.takesFocus)
    }

    /**
     * Media, and focus with it. Without the focus request the sound mixes under
     * whatever is already playing and can be inaudible, which would make this
     * route look broken rather than quiet.
     */
    @Test
    fun `music asks for the media route and takes focus`() {
        assertEquals(AudioAttributes.USAGE_MEDIA, AlertRoute.MUSIC.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, AlertRoute.MUSIC.contentType)
        assertTrue(AlertRoute.MUSIC.takesFocus)
    }
}
