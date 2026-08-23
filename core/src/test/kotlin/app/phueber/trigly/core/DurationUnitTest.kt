package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a stored millisecond value is expressed back to the person who typed it.
 *
 * The whole point of the `Duration` field kind is that the storage is unchanged
 * and only the reading differs, so this is the piece that has to be right: pick
 * the wrong unit and 30 minutes comes back as 1800 seconds, which is correct,
 * useless, and exactly what the field was added to stop.
 */
class DurationUnitTest {

    @Test
    fun `a round number of minutes reads as minutes`() {
        assertEquals(DurationUnit.MINUTES, DurationUnit.bestFor(1_800_000))
        assertEquals(30, 1_800_000 / DurationUnit.MINUTES.millis)
    }

    @Test
    fun `a round number of hours reads as hours, not minutes`() {
        // Both divide exactly, so this pins the tie-break: the largest unit wins,
        // because "2 hours" is what someone meant and "120 min" is not.
        assertEquals(DurationUnit.HOURS, DurationUnit.bestFor(7_200_000))
    }

    @Test
    fun `seconds that are not whole minutes read as seconds`() {
        assertEquals(DurationUnit.SECONDS, DurationUnit.bestFor(90_000))
    }

    @Test
    fun `a value that divides nothing falls back to milliseconds`() {
        assertEquals(DurationUnit.MILLISECONDS, DurationUnit.bestFor(1_500))
        assertEquals(DurationUnit.MILLISECONDS, DurationUnit.bestFor(1))
    }

    /**
     * Zero divides every unit, so the tie-break picks the largest. Harmless —
     * zero reads the same in any unit — but worth pinning so it cannot start
     * throwing instead.
     */
    @Test
    fun `zero is expressible`() {
        assertEquals(DurationUnit.HOURS, DurationUnit.bestFor(0))
    }

    @Test
    fun `every unit round-trips its own whole values`() {
        DurationUnit.entries.forEach { unit ->
            val millis = 3 * unit.millis
            val chosen = DurationUnit.bestFor(millis)
            // Not necessarily the same unit — 3 hours is also 180 minutes — but
            // whichever is chosen must divide exactly, or the box shows a decimal
            // for a value the user entered as a whole number.
            assertEquals(
                "$unit: $millis is not whole in ${chosen.label}",
                0L,
                millis % chosen.millis,
            )
        }
    }
}
