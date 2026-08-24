package app.phueber.trigly.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The solar arithmetic, checked against known published times.
 *
 * This is the whole reason the calculation is a pure function: "my sunrise rule
 * fired at the wrong time" is not something anyone should have to debug by
 * waiting for dawn on a device. The tolerance is two minutes, which is honest
 * about the standard NOAA simplifications — truncated equation of time, a fixed
 * refraction allowance, no observer altitude — and still far tighter than
 * anything a phone automation notices.
 */
class SolarTimeTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val berlinLat = 52.520008
    private val berlinLon = 13.404954

    private fun assertCloseTo(expected: ZonedDateTime, actual: SolarResult, label: String) {
        assertTrue("$label: expected a time, got $actual", actual is SolarResult.At)
        val got = (actual as SolarResult.At).time
        val driftMinutes = Duration.between(expected, got).toMinutes()
        assertTrue(
            "$label: expected ~$expected, got $got (${driftMinutes}m off)",
            driftMinutes in -2..2,
        )
    }

    @Test
    fun `midsummer sunrise in Berlin`() {
        // Published: 2026-06-21 sunrise 04:43 local.
        assertCloseTo(
            ZonedDateTime.of(2026, 6, 21, 4, 43, 0, 0, berlin),
            solarTime(LocalDate.of(2026, 6, 21), berlinLat, berlinLon, SolarEvent.SUNRISE, berlin),
            "Berlin midsummer sunrise",
        )
    }

    @Test
    fun `midsummer sunset in Berlin`() {
        // Published: 2026-06-21 sunset 21:33 local.
        assertCloseTo(
            ZonedDateTime.of(2026, 6, 21, 21, 33, 0, 0, berlin),
            solarTime(LocalDate.of(2026, 6, 21), berlinLat, berlinLon, SolarEvent.SUNSET, berlin),
            "Berlin midsummer sunset",
        )
    }

    @Test
    fun `midwinter sunrise in Berlin, the other end of the year`() {
        // Published: 2026-12-21 sunrise 08:15 local. Winter matters as a separate
        // case because the declination and equation-of-time terms have swapped
        // sign, which is where a transcription error in the series would show.
        assertCloseTo(
            ZonedDateTime.of(2026, 12, 21, 8, 15, 0, 0, berlin),
            solarTime(LocalDate.of(2026, 12, 21), berlinLat, berlinLon, SolarEvent.SUNRISE, berlin),
            "Berlin midwinter sunrise",
        )
    }

    @Test
    fun `the southern hemisphere is not the northern one upside down`() {
        // Sydney, 2026-06-21 — midwinter there. Sunrise near 07:00 local.
        val sydney = ZoneId.of("Australia/Sydney")
        assertCloseTo(
            ZonedDateTime.of(2026, 6, 21, 7, 0, 0, 0, sydney),
            solarTime(LocalDate.of(2026, 6, 21), -33.868820, 151.209290, SolarEvent.SUNRISE, sydney),
            "Sydney midwinter sunrise",
        )
    }

    @Test
    fun `the polar summer has no sunset, and says so instead of inventing one`() {
        // Longyearbyen, 78°N, midsummer: the sun does not set for months. A
        // calculator that returned a time here would schedule a rule for a moment
        // when nothing happens.
        val result = solarTime(
            date = LocalDate.of(2026, 6, 21),
            latitude = 78.223,
            longitude = 15.626,
            event = SolarEvent.SUNSET,
            zone = ZoneId.of("Arctic/Longyearbyen"),
        )

        assertEquals(SolarResult.None(NoSolarEvent.POLAR_DAY), result)
    }

    @Test
    fun `the polar winter has no sunrise`() {
        val result = solarTime(
            date = LocalDate.of(2026, 12, 21),
            latitude = 78.223,
            longitude = 15.626,
            event = SolarEvent.SUNRISE,
            zone = ZoneId.of("Arctic/Longyearbyen"),
        )

        assertEquals(SolarResult.None(NoSolarEvent.POLAR_NIGHT), result)
    }

    @Test
    fun `the returned instant carries the zone that was asked for`() {
        val result = solarTime(
            LocalDate.of(2026, 6, 21), berlinLat, berlinLon, SolarEvent.SUNRISE, berlin,
        )

        assertEquals(berlin, (result as SolarResult.At).time.zone)
    }

    @Test
    fun `coordinates off the globe are refused`() {
        assertTrue(isValidCoordinate(52.5, 13.4))
        assertTrue("the poles and the date line are on the globe", isValidCoordinate(-90.0, 180.0))
        assertFalse(isValidCoordinate(91.0, 0.0))
        assertFalse(isValidCoordinate(0.0, -181.0))
    }

    // --- how the trigger schedules from that ---------------------------------

    @Test
    fun `the next occurrence is strictly in the future`() {
        val trigger = SolarTrigger(berlinLat, berlinLon, SolarEvent.SUNRISE, berlin)
        val sunrise = (
            solarTime(
                LocalDate.of(2026, 6, 21), berlinLat, berlinLon, SolarEvent.SUNRISE, berlin,
            ) as SolarResult.At
            ).time.toInstant().toEpochMilli()

        // Asked at the exact instant of sunrise, the answer must be tomorrow's,
        // not today's — otherwise the flow would fire twice for one dawn.
        val next = trigger.nextOccurrenceMillis(sunrise)

        assertTrue("expected a later occurrence, got $next", next != null && next > sunrise)
        assertTrue(
            "and it should be about a day later, was ${(next!! - sunrise) / 3_600_000}h",
            next - sunrise in Duration.ofHours(23).toMillis()..Duration.ofHours(25).toMillis(),
        )
    }

    @Test
    fun `a sunset rule inside the polar summer looks weeks ahead rather than giving up`() {
        val trigger = SolarTrigger(78.223, 15.626, SolarEvent.SUNSET, ZoneId.of("UTC"))
        val midsummer = ZonedDateTime.of(2026, 6, 21, 12, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()

        val next = trigger.nextOccurrenceMillis(midsummer)

        // There is a sunset again in late August; the search window reaches it.
        assertTrue("expected an eventual sunset, got null", next != null)
        assertTrue("and it must be in the future", next!! > midsummer)
    }
}
