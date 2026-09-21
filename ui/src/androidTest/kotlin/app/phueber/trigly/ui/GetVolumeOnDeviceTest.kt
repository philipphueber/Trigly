package app.phueber.trigly.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.phueber.trigly.actions.AudioManagerVolumeLevels
import app.phueber.trigly.actions.VolumeReading
import app.phueber.trigly.actions.VolumeStream
import app.phueber.trigly.actions.volumePercentOf
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `AudioManagerVolumeLevels` against a real `AudioManager`, which is the half
 * `:actions`' unit test cannot reach. There is no JVM implementation of the
 * audio service, so the percent, the failures and the write are tested there
 * with a fake, and what a real device answers is tested here.
 *
 * Two claims only, and both are about the numbers the port hands on:
 *
 * - every stream the picker offers really answers on this device, so no stream
 *   in the list is one the action can never read;
 * - the three numbers come back in the right places. A swapped maximum and
 *   minimum, or a current read from another stream, compiles and passes every
 *   JVM test, because a fake supplies whatever the test asked for.
 *
 * `getStreamMinVolume` is API 28 and this project's minimum is API 26, so the
 * port reads it only from API 28. This test does not branch on that: zero is a
 * valid minimum, and what matters here is that whatever comes back is usable.
 *
 * **Read only, on purpose.** Setting a volume would leave the device changed
 * for whatever runs next, and on a shared emulator that is somebody else's
 * failing test. Nothing here writes.
 */
@RunWith(AndroidJUnit4::class)
class GetVolumeOnDeviceTest {

    private val levels =
        AudioManagerVolumeLevels(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun every_offered_stream_answers_with_a_usable_range() {
        VolumeStream.entries.forEach { stream ->
            val reading = levels.read(stream)

            assertTrue(
                "${stream.configValue} did not report a level: $reading",
                reading is VolumeReading.Level,
            )
            val level = reading as VolumeReading.Level
            assertTrue(
                "${stream.configValue} reports no steps: $level",
                level.max > 0,
            )
            assertTrue(
                "${stream.configValue} reports a minimum above its maximum: $level",
                level.min in 0..level.max,
            )
            // The current index can sit below the minimum, because a muted
            // stream reports zero whatever its minimum is. It can never sit
            // above the maximum.
            assertTrue(
                "${stream.configValue} reports a level outside its own range: $level",
                level.current in 0..level.max,
            )
        }
    }

    @Test
    fun every_offered_stream_yields_a_percent_between_0_and_100() {
        VolumeStream.entries.forEach { stream ->
            val level = levels.read(stream) as VolumeReading.Level

            val percent = volumePercentOf(level.current, level.min, level.max)

            assertNotNull("${stream.configValue} gave no percent: $level", percent)
            assertTrue(
                "${stream.configValue} gave $percent from $level",
                percent!! in 0..100,
            )
        }
    }
}
