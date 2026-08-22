package app.phueber.trigly.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rule config may arrive from an import or a shared recipe, so ACTION_VIEW
 * must not be handed arbitrary schemes: `file:` can expose local content and a
 * custom app scheme can invoke someone else's deep link with chosen parameters.
 */
class LaunchableUrlTest {

    @Test
    fun `http and https are allowed`() {
        assertTrue(isLaunchableWebUrl("https://example.com"))
        assertTrue(isLaunchableWebUrl("http://example.com/path?q=1"))
    }

    @Test
    fun `scheme matching ignores case`() {
        assertTrue(isLaunchableWebUrl("HTTPS://example.com"))
    }

    @Test
    fun `other schemes are rejected`() {
        assertFalse(isLaunchableWebUrl("file:///etc/passwd"))
        assertFalse(isLaunchableWebUrl("content://media/external/images/1"))
        assertFalse(isLaunchableWebUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isLaunchableWebUrl("javascript:alert(1)"))
        assertFalse(isLaunchableWebUrl("someapp://do-something"))
    }

    @Test
    fun `a scheme that merely starts with http is rejected`() {
        assertFalse(isLaunchableWebUrl("httpevil://example.com"))
    }

    @Test
    fun `blank and schemeless input is rejected`() {
        assertFalse(isLaunchableWebUrl(null))
        assertFalse(isLaunchableWebUrl(""))
        assertFalse(isLaunchableWebUrl("   "))
        assertFalse(isLaunchableWebUrl("example.com"))
    }
}

class VolumeIndexTest {

    @Test
    fun `percentages map onto the stream maximum`() {
        assertEquals(0, volumeIndexFor(percent = 0, maxIndex = 15))
        assertEquals(15, volumeIndexFor(percent = 100, maxIndex = 15))
        assertEquals(8, volumeIndexFor(percent = 50, maxIndex = 15))
    }

    @Test
    fun `a different stream maximum rescales`() {
        // Ring streams are commonly 7 steps where media is 15.
        assertEquals(7, volumeIndexFor(percent = 100, maxIndex = 7))
        assertEquals(4, volumeIndexFor(percent = 50, maxIndex = 7))
    }

    @Test
    fun `out of range percentages are clamped, not wrapped`() {
        assertEquals(0, volumeIndexFor(percent = -20, maxIndex = 15))
        assertEquals(15, volumeIndexFor(percent = 500, maxIndex = 15))
    }
}

class VibrationDurationTest {

    @Test
    fun `absent duration falls back to the default`() {
        assertEquals(VibrateAction.DEFAULT_DURATION_MILLIS, vibrationDurationMillis(null))
        assertEquals(VibrateAction.DEFAULT_DURATION_MILLIS, vibrationDurationMillis("not a number"))
    }

    @Test
    fun `a sane duration passes through`() {
        assertEquals(500L, vibrationDurationMillis("500"))
    }

    @Test
    fun `an implausibly long duration is capped`() {
        // The 30000-for-300 typo.
        assertEquals(VibrateAction.MAX_DURATION_MILLIS, vibrationDurationMillis("30000"))
    }

    @Test
    fun `zero and negative durations are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { vibrationDurationMillis("0") }
        assertThrows(IllegalArgumentException::class.java) { vibrationDurationMillis("-5") }
    }
}

class HttpStatusTest {

    @Test
    fun `2xx is success`() {
        assertTrue(isSuccessfulStatus(200))
        assertTrue(isSuccessfulStatus(201))
        assertTrue(isSuccessfulStatus(204))
        assertTrue(isSuccessfulStatus(299))
    }

    @Test
    fun `redirects and errors are not success`() {
        assertFalse(isSuccessfulStatus(301))
        assertFalse(isSuccessfulStatus(404))
        assertFalse(isSuccessfulStatus(500))
        assertFalse(isSuccessfulStatus(199))
    }
}

class EnumParsingTest {

    @Test
    fun `ringer modes parse case insensitively`() {
        assertEquals(RingerMode.SILENT, RingerMode.parse("silent"))
        assertEquals(RingerMode.VIBRATE, RingerMode.parse("VIBRATE"))
    }

    @Test
    fun `volume streams parse case insensitively`() {
        assertEquals(VolumeStream.MEDIA, VolumeStream.parse("media"))
        assertEquals(VolumeStream.RING, VolumeStream.parse("Ring"))
    }

    @Test
    fun `an unknown value lists the valid ones instead of defaulting`() {
        val ringer = assertThrows(IllegalStateException::class.java) { RingerMode.parse("loud") }
        assertTrue(ringer.message!!.contains("silent"))

        val stream = assertThrows(IllegalStateException::class.java) { VolumeStream.parse("bass") }
        assertTrue(stream.message!!.contains("media"))
    }
}
