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

    @Test
    fun `alert tones parse case insensitively`() {
        assertEquals(AlertSound.ALARM, AlertSound.parse("alarm"))
        assertEquals(AlertSound.NOTIFICATION, AlertSound.parse("Notification"))
    }
}

/**
 * The alert's duration is the one number in this app that can make a phone
 * unpleasant for a stranger to be near, because the tone loops. The cap matters
 * more here than it does for `vibrate`.
 */
class AlertDurationTest {

    @Test
    fun `absent duration falls back to the default`() {
        assertEquals(PlayAlertAction.DEFAULT_DURATION_MILLIS, alertDurationMillis(null))
        assertEquals(PlayAlertAction.DEFAULT_DURATION_MILLIS, alertDurationMillis("nonsense"))
    }

    @Test
    fun `a sane duration passes through`() {
        assertEquals(1_500L, alertDurationMillis("1500"))
    }

    @Test
    fun `an implausibly long alert is capped`() {
        // Half an hour of looping alarm, from a rule that fires on every
        // notification, with no in-app stop button.
        assertEquals(PlayAlertAction.MAX_DURATION_MILLIS, alertDurationMillis("1800000"))
    }

    @Test
    fun `zero and negative durations are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { alertDurationMillis("0") }
        assertThrows(IllegalArgumentException::class.java) { alertDurationMillis("-1") }
    }
}

class AlertVolumeTest {

    @Test
    fun `absent volume plays at full gain`() {
        assertEquals(1f, alertVolumeGain(null), 0.001f)
    }

    @Test
    fun `a percentage becomes a linear scalar`() {
        assertEquals(0.5f, alertVolumeGain("50"), 0.001f)
        assertEquals(0f, alertVolumeGain("0"), 0.001f)
    }

    @Test
    fun `out of range percentages are clamped rather than rejected`() {
        // Unlike the duration, an absurd volume has an obvious sane reading and
        // no way to hurt anyone — 150% is just "as loud as it goes".
        assertEquals(1f, alertVolumeGain("150"), 0.001f)
        assertEquals(0f, alertVolumeGain("-20"), 0.001f)
    }
}

/**
 * A custom sound URI arrives from the same untrusted places a rule does, so it
 * gets the same treatment as `open_url` and `http_request`: local schemes only.
 */
class PlayableSoundUriTest {

    @Test
    fun `local sounds are allowed`() {
        assertTrue(isPlayableSoundUri("content://media/internal/audio/media/12"))
        assertTrue(isPlayableSoundUri("file:///storage/emulated/0/Alarms/siren.ogg"))
    }

    @Test
    fun `scheme matching ignores case and surrounding space`() {
        assertTrue(isPlayableSoundUri("  CONTENT://media/internal/audio/media/12  "))
    }

    @Test
    fun `network sounds are refused`() {
        // An imported rule would otherwise beacon to a stranger's server on
        // every fire, and leak that the rule exists at all.
        assertFalse(isPlayableSoundUri("https://example.com/siren.mp3"))
        assertFalse(isPlayableSoundUri("http://example.com/siren.mp3"))
        assertFalse(isPlayableSoundUri("rtsp://example.com/stream"))
    }

    @Test
    fun `a bare path with no scheme is refused`() {
        assertFalse(isPlayableSoundUri("/storage/emulated/0/Alarms/siren.ogg"))
        assertFalse(isPlayableSoundUri(""))
    }
}
