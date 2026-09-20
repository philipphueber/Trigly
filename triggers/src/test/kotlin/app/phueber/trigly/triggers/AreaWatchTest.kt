package app.phueber.trigly.triggers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band around the radius, which is what stops a phone standing near the
 * edge from starting the same rule over and over.
 */
class HysteresisMarginTest {

    @Test
    fun `the band is the size of the error it has to absorb`() {
        assertEquals(30.0, hysteresisMarginMeters(radiusMeters = 500.0, accuracyMeters = 30f), 0.001)
        assertEquals(5.0, hysteresisMarginMeters(radiusMeters = 500.0, accuracyMeters = 5f), 0.001)
    }

    @Test
    fun `an exact fix gets almost no band, so it reports a crossing as promptly as before`() {
        assertEquals(0.0, hysteresisMarginMeters(radiusMeters = 200.0, accuracyMeters = 0f), 0.001)
    }

    @Test
    fun `the band never grows past half the radius, or no arrival could be reported`() {
        // 400 m of error against a 200 m circle would demand a position 200 m
        // *past* the centre, which is not a place.
        assertEquals(100.0, hysteresisMarginMeters(radiusMeters = 200.0, accuracyMeters = 400f), 0.001)
        assertEquals(100.0, hysteresisMarginMeters(radiusMeters = 200.0, accuracyMeters = 199f), 0.001)
    }

    @Test
    fun `an unreported error takes the widest band, the same as every other unknown here`() {
        assertEquals(100.0, hysteresisMarginMeters(radiusMeters = 200.0, accuracyMeters = null), 0.001)
    }

    @Test
    fun `a nonsense error does not produce a nonsense band`() {
        assertEquals(0.0, hysteresisMarginMeters(radiusMeters = 200.0, accuracyMeters = -5f), 0.001)
        assertEquals(0.0, hysteresisMarginMeters(radiusMeters = 0.0, accuracyMeters = 10f), 0.001)
        assertEquals(0.0, hysteresisMarginMeters(radiusMeters = -1.0, accuracyMeters = 10f), 0.001)
    }
}

/**
 * Turning positions into crossings, which is the decision this whole component
 * exists to make.
 */
class AreaWatchTest {

    private val radius = 200.0
    private val floor = 60_000L

    private fun watch() = AreaWatch(radiusMeters = radius, floorMillis = floor)

    @Test
    fun `the first reading is adopted and never reported`() {
        val inside = watch().accept(distanceMeters = 10.0, accuracyMeters = 5f)
        assertNull("enabling a rule while standing in the area is not an arrival", inside?.crossing)

        val outside = watch().accept(distanceMeters = 5_000.0, accuracyMeters = 5f)
        assertNull(outside?.crossing)
    }

    @Test
    fun `a walk in reports one arrival`() {
        val watch = watch()
        watch.accept(1_000.0, 5f)
        assertNull("still outside", watch.accept(400.0, 5f)?.crossing)
        assertEquals(AreaCrossing.ENTERED, watch.accept(50.0, 5f)?.crossing)
    }

    @Test
    fun `a walk out reports one departure`() {
        val watch = watch()
        watch.accept(10.0, 5f)
        assertEquals(AreaCrossing.EXITED, watch.accept(1_000.0, 5f)?.crossing)
    }

    @Test
    fun `staying inside reports nothing more`() {
        val watch = watch()
        watch.accept(1_000.0, 5f)
        assertEquals(AreaCrossing.ENTERED, watch.accept(50.0, 5f)?.crossing)
        assertNull(watch.accept(40.0, 5f)?.crossing)
        assertNull(watch.accept(60.0, 5f)?.crossing)
        assertNull(watch.accept(150.0, 5f)?.crossing)
    }

    /**
     * The regression this change exists for.
     *
     * A phone parked just outside its home area with a 50 m fix reports
     * positions that scatter across the boundary. Compared against the radius
     * alone, every reading that lands the other side is a crossing, and the
     * rule runs again each time. The assertion on [insideArea] below is what
     * the old code saw, kept here so the contrast stays visible.
     */
    @Test
    fun `a position scattering across the edge reports nothing`() {
        val watch = watch()
        watch.accept(300.0, 50f)

        val scatter = listOf(230.0, 190.0, 205.0, 180.0, 215.0, 195.0, 240.0)

        scatter.forEach { distance ->
            assertNull(
                "a reading at $distance m crossed the band and should not have",
                watch.accept(distance, 50f)?.crossing,
            )
        }

        // What a bare comparison against the radius would have made of the same
        // readings: four flips, so four runs of the rule.
        val bare = scatter.map { insideArea(it, radius, 50f) }
        assertEquals(
            listOf(false, true, false, true, false, true, false),
            bare,
        )
    }

    @Test
    fun `clearing the band by more than the error does report the crossing`() {
        val watch = watch()
        watch.accept(300.0, 50f)
        // The band with a 50 m fix is 150 m to 250 m.
        assertNull(watch.accept(160.0, 50f)?.crossing)
        assertEquals(AreaCrossing.ENTERED, watch.accept(149.0, 50f)?.crossing)
    }

    @Test
    fun `a genuine round trip reports both edges`() {
        val watch = watch()
        watch.accept(1_000.0, 5f)
        assertEquals(AreaCrossing.ENTERED, watch.accept(50.0, 5f)?.crossing)
        assertEquals(AreaCrossing.EXITED, watch.accept(1_000.0, 5f)?.crossing)
        assertEquals(AreaCrossing.ENTERED, watch.accept(50.0, 5f)?.crossing)
    }

    @Test
    fun `a fix too coarse to resolve the area answers nothing at all`() {
        val watch = watch()
        watch.accept(1_000.0, 5f)
        assertNull("a 500 m fix cannot place a phone in a 200 m circle", watch.accept(50.0, 500f))
    }

    @Test
    fun `a fix too coarse to answer does not move the remembered side either`() {
        val watch = watch()
        watch.accept(1_000.0, 5f)
        watch.accept(50.0, 500f)
        // Still remembered as outside, so walking in is still an arrival.
        assertEquals(AreaCrossing.ENTERED, watch.accept(50.0, 5f)?.crossing)
    }

    @Test
    fun `an unreported error still reports a crossing, once the phone is well in`() {
        val watch = watch()
        watch.accept(1_000.0, null)
        assertNull("halfway in is where the widest band ends", watch.accept(150.0, null)?.crossing)
        assertEquals(AreaCrossing.ENTERED, watch.accept(90.0, null)?.crossing)
    }

    @Test
    fun `every reading carries a poll interval, including the first`() {
        assertEquals(floor, watch().accept(210.0, 5f)?.nextPollMillis)
    }
}

/**
 * How long to wait for the next position.
 *
 * The property that matters most is the first one: the configured interval is a
 * floor. Nothing here may make an existing rule slower to notice an arrival
 * than it already was.
 */
class AreaPollIntervalTest {

    private val floor = 60_000L
    private val ceiling = DEFAULT_POLL_CEILING_MILLIS

    private fun interval(distance: Double, accuracy: Float?, radius: Double = 200.0) =
        areaPollIntervalMillis(
            distanceMeters = distance,
            radiusMeters = radius,
            accuracyMeters = accuracy,
            floorMillis = floor,
            ceilingMillis = ceiling,
        )

    @Test
    fun `at the boundary the rule gets exactly the rate it asked for`() {
        assertEquals(floor, interval(200.0, 5f))
    }

    @Test
    fun `inside a small area the rule gets the rate it asked for, because a step out is always near`() {
        assertEquals(floor, interval(0.0, 5f))
        assertEquals(floor, interval(150.0, 5f))
    }

    @Test
    fun `a phone ten kilometres away is asked four times less often`() {
        // Roughly 9.8 km to the boundary, which is five and a half minutes of
        // motorway, so four minutes is the last rung that cannot overshoot it.
        assertEquals(240_000L, interval(10_000.0, 10f))
    }

    @Test
    fun `a phone on another continent is held at the ceiling and not beyond it`() {
        assertEquals(ceiling, interval(5_000_000.0, 10f))
    }

    @Test
    fun `the ceiling is reachable from the default floor`() {
        // A doubling ladder from 60 s runs 1, 2, 4, 8, 16 minutes, so without a
        // clamp the ceiling of 15 could never be used.
        assertEquals(15 * 60 * 1000L, ceiling)
        assertEquals(ceiling, interval(1_000_000.0, 10f))
    }

    @Test
    fun `the error is taken off the distance before the wait is planned`() {
        // A 5 km area with the phone 12 km from the centre, so 7 km from the
        // boundary on paper. With 4 km of error it may really be 3 km from it,
        // and the optimistic number would plan a wait the phone can outrun.
        val optimistic = areaPollIntervalMillis(12_000.0, 5_000.0, 0f, floor, ceiling)
        val honest = areaPollIntervalMillis(12_000.0, 5_000.0, 4_000f, floor, ceiling)
        assertEquals(120_000L, optimistic)
        assertEquals(floor, honest)
    }

    @Test
    fun `an unreported error is not treated as no error`() {
        // Nothing is subtracted for an unknown error here, unlike the band: the
        // band decides whether to believe a reading, and this decides when to
        // ask for the next one. A rule that asked for a check a minute still
        // gets one when it is close, whatever the provider chose to report.
        assertEquals(interval(10_000.0, 0f), interval(10_000.0, null))
    }

    @Test
    fun `a rule that asked for less than the ceiling keeps what it asked for`() {
        assertEquals(
            30 * 60 * 1000L,
            areaPollIntervalMillis(1_000_000.0, 200.0, 10f, 30 * 60 * 1000L, ceiling),
        )
    }

    @Test
    fun `a floor of zero is returned untouched and does not hang`() {
        assertEquals(0L, areaPollIntervalMillis(1_000_000.0, 200.0, 10f, 0L, ceiling))
        assertEquals(-1L, areaPollIntervalMillis(1_000_000.0, 200.0, 10f, -1L, ceiling))
    }

    @Test
    fun `the result is never below the floor, wherever the phone is`() {
        listOf(0.0, 1.0, 199.0, 200.0, 201.0, 1_000.0, 100_000.0).forEach { distance ->
            listOf(null, 0f, 5f, 150f).forEach { accuracy ->
                val result = interval(distance, accuracy)
                assertTrue("$distance m at $accuracy m gave $result", result >= floor)
                assertTrue("$distance m at $accuracy m gave $result", result <= ceiling)
            }
        }
    }
}

/**
 * Whether a position this process already has can answer without asking for
 * another one.
 */
class FixStillAnswersTest {

    private val radius = 200.0

    private fun fix(atMillis: Long, accuracy: Float? = 10f) =
        Fix(latitude = 52.52, longitude = 13.405, accuracyMeters = accuracy, atMillis = atMillis)

    @Test
    fun `a fresh fix answers`() {
        assertTrue(fix(0L).stillAnswers(1_000L, distanceMeters = 5_000.0, radiusMeters = radius))
    }

    @Test
    fun `a fix taken far from the boundary answers for a long time`() {
        // 40 km out. Two minutes of motorway is 3.6 km, nowhere near enough.
        assertTrue(fix(0L).stillAnswers(120_000L, distanceMeters = 40_000.0, radiusMeters = radius))
    }

    @Test
    fun `a fix taken near the boundary stops answering almost at once`() {
        // 500 m past the edge is under 17 seconds of motorway.
        assertTrue(fix(0L).stillAnswers(5_000L, distanceMeters = 700.0, radiusMeters = radius))
        assertFalse(fix(0L).stillAnswers(30_000L, distanceMeters = 700.0, radiusMeters = radius))
    }

    @Test
    fun `a fix inside the area stops answering once the edge is reachable`() {
        assertTrue(fix(0L).stillAnswers(1_000L, distanceMeters = 10.0, radiusMeters = radius))
        assertFalse(fix(0L).stillAnswers(60_000L, distanceMeters = 10.0, radiusMeters = radius))
    }

    @Test
    fun `nothing answers past the backstop, however far away it was taken`() {
        val anHour = 60 * 60 * 1000L
        assertFalse(
            "a phone can be switched off, flown, or driven onto a train",
            fix(0L).stillAnswers(anHour, distanceMeters = 5_000_000.0, radiusMeters = radius),
        )
    }

    @Test
    fun `a fix from the future is refused rather than treated as fresh`() {
        assertFalse(fix(10_000L).stillAnswers(0L, distanceMeters = 5_000_000.0, radiusMeters = radius))
    }

    @Test
    fun `the error is taken off the distance here too`() {
        assertTrue(fix(0L, accuracy = 10f).stillAnswers(10_000L, 1_000.0, radius))
        assertFalse(fix(0L, accuracy = 700f).stillAnswers(10_000L, 1_000.0, radius))
    }

    @Test
    fun `an unreported error is read as no error, and the backstop is what catches it`() {
        assertTrue(fix(0L, accuracy = null).stillAnswers(10_000L, 1_000.0, radius))
    }
}

/** The one-slot store every area component reads and writes. */
class AreaFixesTest {

    @After
    fun tearDown() = AreaFixes.forget()

    private fun fix(atMillis: Long) =
        Fix(latitude = 52.52, longitude = 13.405, accuracyMeters = 10f, atMillis = atMillis)

    @Test
    fun `a new process starts with nothing`() {
        AreaFixes.forget()
        assertNull(AreaFixes.latest())
    }

    @Test
    fun `the newest fix is what everything reads`() {
        AreaFixes.record(fix(1_000L))
        AreaFixes.record(fix(2_000L))
        assertEquals(2_000L, AreaFixes.latest()?.atMillis)
    }

    @Test
    fun `a slow read finishing late does not move the store backwards`() {
        AreaFixes.record(fix(2_000L))
        AreaFixes.record(fix(1_000L))
        assertEquals(2_000L, AreaFixes.latest()?.atMillis)
    }
}
