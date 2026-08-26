package app.phueber.trigly.triggers

import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId

/**
 * Fires at sunrise or sunset for a place the user names.
 *
 * The place is typed, not sensed, and that is the feature: sunrise is a
 * calculation from latitude, longitude and the date, so this trigger needs **no
 * permission at all** — no location access, no network. `docs/triggers.md` asks
 * for that path to be offered first, and this is it. The maths lives in
 * [solarTime], pure and unit-tested, because "why did my rule fire at the wrong
 * time" is not a question anyone should have to debug on a device.
 *
 * **Scheduling used to be the honest weakness here, and now is not.** [events]
 * waits with [AlarmScheduler.waitUntil], not a plain coroutine `delay`, so a
 * sunset hours away survives Doze instead of only firing on whatever next
 * wakes the CPU. `docs/todo.md`'s T1 is the record of the old gap and the fix;
 * this is the one place in this class that changed. Drift of up to a few
 * minutes is still expected, which the warning below says plainly, because a
 * few kilometres of typed location already changes true sunrise by only
 * seconds and this trigger was never promising more than that.
 *
 * Days with no sunrise or sunset — real, above the Arctic circle — are skipped
 * rather than approximated: the loop moves to the next day instead of inventing
 * an instant. A polar-summer rule quietly not firing is correct; the sun did not
 * set.
 */
class SolarTrigger(
    private val latitude: Double,
    private val longitude: Double,
    private val event: SolarEvent,
    private val scheduler: AlarmScheduler,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    init {
        require(isValidCoordinate(latitude, longitude)) {
            "latitude/longitude out of range: $latitude, $longitude"
        }
    }

    override fun events(): Flow<TriggerEvent> = flow {
        while (true) {
            val fireAt = nextOccurrenceMillis(now()) ?: break
            // Computed from the scheduled instant, never from "now plus a day" —
            // the drift bug `docs/triggers.md` warns about for recurring time
            // triggers is exactly that mistake.
            scheduler.waitUntil(fireAt)
            emit(
                TriggerEvent(
                    triggerType = TYPE,
                    firedAtMillis = now(),
                    payload = mapOf(PAYLOAD_EVENT to event.configValue),
                )
            )
            // Past the emitted instant, so the same day cannot be scheduled twice
            // if the clock has not visibly advanced.
            scheduler.waitFor(1_000)
        }
    }

    /**
     * The next time this event happens strictly after [fromMillis], or null if it
     * does not happen within [SEARCH_DAYS].
     *
     * Searching forward a bounded number of days rather than one is what handles
     * the poles: a rule for sunset inside the Arctic summer has no occurrence for
     * weeks, and the bound is what stops the search being unbounded work. Null
     * ends the flow, which is the honest outcome — this rule cannot fire from
     * here, and pretending to wait forever would hide that.
     */
    internal fun nextOccurrenceMillis(fromMillis: Long): Long? {
        val from = Instant.ofEpochMilli(fromMillis).atZone(zone)

        for (offset in 0..SEARCH_DAYS) {
            val result = solarTime(
                date = from.toLocalDate().plusDays(offset.toLong()),
                latitude = latitude,
                longitude = longitude,
                event = event,
                zone = zone,
            )
            if (result is SolarResult.At) {
                val millis = result.time.toInstant().toEpochMilli()
                if (millis > fromMillis) return millis
            }
        }
        return null
    }

    /**
     * The passive form: is it currently daytime (configured for sunrise) or
     * currently dark (configured for sunset) at the configured place?
     *
     * The cheapest condition in the project, and the one `docs/conditions.md`
     * singles out for it: no permission, no manager, no clock drift to worry
     * about — just [solarTime], the same pure calculation [nextOccurrenceMillis]
     * already trusts, asked about today instead of searched over
     * [SEARCH_DAYS]. Both ends of the day are computed regardless of which one
     * this instance fires on, because "is it daytime" is a question about the
     * whole day, not about one instant in it.
     *
     * A polar day or polar night is not a failure to answer — see
     * [NoSolarEvent] — it is the answer: above the Arctic circle, "is it
     * daytime" has a real yes-or-no even on the day the sun never sets or
     * never rises. [solarTime] decides which one applies with the same
     * cosine-range test for sunrise and sunset on a given day (the sign of the
     * hour angle only comes in afterwards, to tell them apart), so the two
     * calls below always agree on which [NoSolarEvent] a day is, or agree that
     * the day has both. The one `null` branch is not a third legitimate answer
     * — it means that invariant broke, and returning a guessed true or false
     * would bury exactly the bug that needs to surface instead.
     */
    override suspend fun currentlyHolds(): Boolean? {
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        val sunrise = solarTime(today, latitude, longitude, SolarEvent.SUNRISE, zone)
        val sunset = solarTime(today, latitude, longitude, SolarEvent.SUNSET, zone)

        val isDaytime = when {
            sunrise is SolarResult.At && sunset is SolarResult.At -> {
                val nowMillis = now()
                val sunriseMillis = sunrise.time.toInstant().toEpochMilli()
                val sunsetMillis = sunset.time.toInstant().toEpochMilli()
                // Half-open: the instant of sunrise itself already counts as
                // day, the instant of sunset itself already counts as night.
                nowMillis >= sunriseMillis && nowMillis < sunsetMillis
            }

            sunrise is SolarResult.None && sunrise.why == NoSolarEvent.POLAR_DAY -> true
            sunrise is SolarResult.None && sunrise.why == NoSolarEvent.POLAR_NIGHT -> false
            else -> null
        } ?: return null

        return if (event == SolarEvent.SUNRISE) isDaytime else !isDaytime
    }

    companion object {
        const val TYPE = "solar"
        const val CONFIG_LATITUDE = "latitude"
        const val CONFIG_LONGITUDE = "longitude"
        const val PAYLOAD_EVENT = "event"

        /**
         * Long enough to cross a polar summer or winter, which is the only case
         * that needs more than one day.
         */
        const val SEARCH_DAYS = 200
    }
}

class SolarTriggerFactory(private val scheduler: AlarmScheduler) : TriggerFactory {
    override val type = SolarTrigger.TYPE

    override val displayName = "Sunrise or sunset"
    override val category = Category.TIME

    override val configFields = listOf(
        ConfigField.Choice(
            key = SolarEvent.CONFIG_KEY,
            label = "Fires at",
            options = SolarEvent.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            required = false,
            default = SolarEvent.SUNRISE.configValue,
        ),
        ConfigField.Coordinates(
            key = SolarTrigger.CONFIG_LATITUDE,
            longitudeKey = SolarTrigger.CONFIG_LONGITUDE,
            label = "Where",
            required = true,
            help = "You type the location. Trigly does not sense it. This trigger " +
                "needs no location permission. An approximate location is enough. " +
                "A few kilometres change sunrise by only seconds.",
        ),
    )

    override val warning: String =
        "This trigger needs Trigly running to fire. The fire time can drift by " +
            "a few minutes, because this trigger does not use an exact alarm. " +
            "Use it for lights or volume. Do not use it as an alarm clock. If " +
            "you force stop Trigly, this trigger stops until you open the app " +
            "again."

    override fun create(config: Map<String, String>): Trigger {
        fun coordinate(key: String): Double {
            val raw = config[key] ?: error("${SolarTrigger.TYPE} needs '$key'")
            return raw.toDoubleOrNull() ?: error("$key must be a number, was '$raw'")
        }

        return SolarTrigger(
            latitude = coordinate(SolarTrigger.CONFIG_LATITUDE),
            longitude = coordinate(SolarTrigger.CONFIG_LONGITUDE),
            event = SolarEvent.parse(
                config[SolarEvent.CONFIG_KEY] ?: SolarEvent.SUNRISE.configValue
            ),
            scheduler = scheduler,
        )
    }

    override val supportsCondition = true
}
