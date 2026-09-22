package app.phueber.trigly.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the two level fields a stored `set_volume` config means.
 *
 * The first test is the one that matters most: every rule saved before the
 * source field existed holds a `percent` and no `source`, and it has to keep
 * setting the level it always set. A rule that quietly stops working after an
 * update is the worst outcome this project has.
 *
 * [SetVolumeActionFactory] needs a `Context` to build an action, and a JVM
 * test has none, so the config is read through [volumeLevelFrom] instead. That
 * is the same function the factory calls.
 */
class VolumeLevelConfigTest {

    @Test
    fun `a rule saved with only a percent still means that percent`() {
        val config = mapOf("stream" to "media", "percent" to "70")

        assertEquals(VolumeLevel.Fixed(70), volumeLevelFrom(config))
        // And it still reaches the same step it always did.
        assertEquals(11, volumeIndexFor(percent = 70, maxIndex = 15))
    }

    @Test
    fun `an empty source reads as the slider too`() {
        // A rule edited by hand, or an export that wrote the key out blank.
        assertEquals(
            VolumeLevel.Fixed(70),
            volumeLevelFrom(mapOf("source" to "", "percent" to "70")),
        )
        assertEquals(
            VolumeLevel.Fixed(70),
            volumeLevelFrom(mapOf("source" to "  ", "percent" to "70")),
        )
    }

    @Test
    fun `the slider source keeps its old refusals`() {
        assertThrows(IllegalStateException::class.java) {
            volumeLevelFrom(mapOf("stream" to "media"))
        }
        assertThrows(IllegalStateException::class.java) {
            volumeLevelFrom(mapOf("percent" to "loud"))
        }
    }

    @Test
    fun `a value source keeps the text for the engine to fill in`() {
        val config = mapOf(
            "stream" to "media",
            "source" to "value",
            "level" to "{{mine.volume}} - 20",
        )

        assertEquals(VolumeLevel.FromValue("{{mine.volume}} - 20"), volumeLevelFrom(config))
    }

    @Test
    fun `a value source needs no percent at all`() {
        val config = mapOf("source" to "value", "level" to "{{mine.volume}}")

        assertEquals(VolumeLevel.FromValue("{{mine.volume}}"), volumeLevelFrom(config))
    }

    /**
     * A source this build has never heard of comes from a newer build. Reading
     * it as the slider would set a level nobody asked for, so it is refused
     * where the refusal can be reported: the rule does not start.
     */
    @Test
    fun `an unknown source is refused`() {
        assertThrows(IllegalStateException::class.java) {
            volumeLevelFrom(mapOf("source" to "somewhere", "percent" to "70"))
        }
    }

    @Test
    fun `only the value source asks the engine to fill the level in`() {
        assertTrue(volumeLevelAcceptsVariables(mapOf("source" to "value")))
        assertFalse(volumeLevelAcceptsVariables(mapOf("source" to "fixed")))
        // The old rule again: nothing to fill in, so a stale level left in the
        // config cannot refuse the action.
        assertFalse(volumeLevelAcceptsVariables(mapOf("percent" to "70")))
        // Never throws, where volumeLevelFrom does.
        assertFalse(volumeLevelAcceptsVariables(mapOf("source" to "somewhere")))
    }
}

/**
 * What a level worked out while the rule runs comes to.
 *
 * The strings here are what the engine hands over, after every `{{...}}` is
 * already a literal: see `Substitution.EXPRESSION`. So a variable holding 40
 * arrives as `40`, and one holding `high` arrives as `"high"`, quotes and all.
 */
class VolumePercentForTest {

    @Test
    fun `a variable holding a number is that number`() {
        assertEquals(VolumeLevelOutcome.Percent(40), volumePercentFor("40"))
        assertEquals(VolumeLevelOutcome.Percent(0), volumePercentFor("0"))
        assertEquals(VolumeLevelOutcome.Percent(100), volumePercentFor("100"))
    }

    @Test
    fun `surrounding space does not matter`() {
        assertEquals(VolumeLevelOutcome.Percent(40), volumePercentFor("  40  "))
    }

    @Test
    fun `an expression is worked out`() {
        // "save the level, drop it, put it back" is the pair this exists for.
        assertEquals(VolumeLevelOutcome.Percent(20), volumePercentFor("40 - 20"))
        assertEquals(VolumeLevelOutcome.Percent(80), volumePercentFor("40 * 2"))
        assertEquals(VolumeLevelOutcome.Percent(30), volumePercentFor("60 / 2"))
        assertEquals(VolumeLevelOutcome.Percent(60), volumePercentFor("40 < 50 ? 60 : 30"))
    }

    @Test
    fun `a result with decimals rounds to the nearest percent, half up`() {
        assertEquals(VolumeLevelOutcome.Percent(41), volumePercentFor("40.5"))
        assertEquals(VolumeLevelOutcome.Percent(40), volumePercentFor("40.4"))
        // 41 / 2 is 20.5.
        assertEquals(VolumeLevelOutcome.Percent(21), volumePercentFor("41 / 2"))
    }

    @Test
    fun `a level above 100 goes to the top, not past it`() {
        assertEquals(VolumeLevelOutcome.Percent(100), volumePercentFor("110"))
        // The case this really guards: arithmetic that ran off the end.
        assertEquals(VolumeLevelOutcome.Percent(100), volumePercentFor("90 + 20"))
    }

    @Test
    fun `a level below 0 goes to the bottom`() {
        assertEquals(VolumeLevelOutcome.Percent(0), volumePercentFor("-10"))
        assertEquals(VolumeLevelOutcome.Percent(0), volumePercentFor("10 - 20"))
    }

    /** A number far outside the range must not reach Int arithmetic at all. */
    @Test
    fun `a number too large for an Int is still clamped`() {
        assertEquals(VolumeLevelOutcome.Percent(100), volumePercentFor("99999999999999"))
        assertEquals(VolumeLevelOutcome.Percent(0), volumePercentFor("-99999999999999"))
    }

    @Test
    fun `an empty level is refused, and does not read as silent`() {
        val blank = volumePercentFor("") as VolumeLevelOutcome.Failed
        assertTrue(blank.reason, blank.reason.contains("empty"))

        val spaces = volumePercentFor("   ") as VolumeLevelOutcome.Failed
        assertEquals(blank.reason, spaces.reason)

        // A variable that holds nothing arrives as an empty piece of text, and
        // it is the same problem to the person reading the fault log.
        val emptyVariable = volumePercentFor("\"\"") as VolumeLevelOutcome.Failed
        assertEquals(blank.reason, emptyVariable.reason)
    }

    @Test
    fun `a value that is not a number is refused, and names what it found`() {
        // A variable holding "high", as the engine escapes it.
        val outcome = volumePercentFor("\"high\"") as VolumeLevelOutcome.Failed
        assertTrue(outcome.reason, outcome.reason.contains("high"))
        assertTrue(outcome.reason, outcome.reason.contains("not a number"))
    }

    @Test
    fun `an expression that does not parse is refused with its own reason`() {
        val outcome = volumePercentFor("40 +") as VolumeLevelOutcome.Failed
        assertTrue(outcome.reason, outcome.reason.contains("could not be worked out"))
    }

    @Test
    fun `a percent sign is not part of the language, and is refused`() {
        // Somebody typing "50%" gets a sentence, not a phone set to silent.
        assertTrue(volumePercentFor("50%") is VolumeLevelOutcome.Failed)
    }
}
