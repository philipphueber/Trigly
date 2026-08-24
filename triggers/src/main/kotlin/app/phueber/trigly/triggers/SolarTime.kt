package app.phueber.trigly.triggers

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Which end of the day. */
enum class SolarEvent(val configValue: String, val displayName: String) {
    SUNRISE("sunrise", "sunrise"),
    SUNSET("sunset", "sunset"),
    ;

    companion object {
        const val CONFIG_KEY = "event"

        fun parse(raw: String?): SolarEvent =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
    }
}

/**
 * Why a day has no sunrise or sunset.
 *
 * Not an error, and not a rounding problem: above the Arctic and below the
 * Antarctic circle there are genuinely days where the sun does not rise or does
 * not set. A calculator that returned *something* for those would be inventing a
 * time, and a rule scheduled from it would fire at a moment nothing happens.
 */
enum class NoSolarEvent { POLAR_DAY, POLAR_NIGHT }

/** Either the instant, or the reason there isn't one. */
sealed interface SolarResult {
    data class At(val time: ZonedDateTime) : SolarResult
    data class None(val why: NoSolarEvent) : SolarResult
}

/**
 * Sunrise and sunset from latitude, longitude and date — NOAA's solar equations.
 *
 * Pure arithmetic, no permission, no network, no clock: that is the whole reason
 * this is worth having as a trigger. A user who types where they are gets
 * sunrise rules without granting location access at all, and `docs/triggers.md`
 * asks for that path to be offered first.
 *
 * Accuracy is about a minute, which is far below anything a phone automation
 * cares about. The standard simplifications are deliberate: the equation of time
 * is the truncated series, refraction is folded into the fixed -0.833° zenith for
 * the visible disc's upper limb, and no correction is made for observer altitude.
 *
 * The signature takes the zone explicitly rather than reading a default, so the
 * result is reproducible in a test and correct for a user whose rule is about a
 * place they are not currently standing in.
 */
fun solarTime(
    date: LocalDate,
    latitude: Double,
    longitude: Double,
    event: SolarEvent,
    zone: ZoneId,
): SolarResult {
    // Fractional year, in radians, from the day of the year. NOAA's form carries
    // an hour term here; it is evaluated at midday, where it contributes nothing,
    // so it is left out rather than written as a zero.
    val gamma = 2.0 * Math.PI / daysInYear(date) * (date.dayOfYear - 1)

    // Equation of time, in minutes: the difference between apparent and mean
    // solar time, from Earth's tilt and its eccentric orbit.
    val eqTime = 229.18 * (
        0.000075 +
            0.001868 * cos(gamma) -
            0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) -
            0.040849 * sin(2 * gamma)
        )

    // Solar declination, in radians: how far the sun is from the equator today.
    val declination = 0.006918 -
        0.399912 * cos(gamma) +
        0.070257 * sin(gamma) -
        0.006758 * cos(2 * gamma) +
        0.000907 * sin(2 * gamma) -
        0.002697 * cos(3 * gamma) +
        0.00148 * sin(3 * gamma)

    val latRad = Math.toRadians(latitude)

    // The hour angle at which the sun's upper limb touches the horizon. -0.833°
    // rather than 0° accounts for the disc's radius and atmospheric refraction.
    val zenith = Math.toRadians(90.833)
    val cosHourAngle =
        (cos(zenith) - sin(latRad) * sin(declination)) / (cos(latRad) * cos(declination))

    // Out of range means the sun never reaches that height today, or never drops
    // to it. Which one depends on the hemisphere and the season, and the sign of
    // the out-of-range value tells us directly.
    if (cosHourAngle > 1) return SolarResult.None(NoSolarEvent.POLAR_NIGHT)
    if (cosHourAngle < -1) return SolarResult.None(NoSolarEvent.POLAR_DAY)

    val hourAngle = Math.toDegrees(acos(cosHourAngle)).let {
        if (event == SolarEvent.SUNRISE) it else -it
    }

    // Minutes from UTC midnight. 720 is solar noon at longitude 0.
    val minutesUtc = 720.0 - 4.0 * (longitude + hourAngle) - eqTime

    // Kept as a whole number of seconds: the equations are good to about a
    // minute, so sub-second precision would be false confidence, and a schedule
    // is happier with a round instant.
    val startOfDayUtc = date.atStartOfDay(ZoneId.of("UTC"))
    return SolarResult.At(
        startOfDayUtc
            .plusSeconds(Math.round(minutesUtc * 60.0))
            .withZoneSameInstant(zone)
    )
}

private fun daysInYear(date: LocalDate): Double = if (date.isLeapYear) 366.0 else 365.0

/** Whether a latitude/longitude pair is on the globe at all. */
fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
    abs(latitude) <= 90.0 && abs(longitude) <= 180.0
