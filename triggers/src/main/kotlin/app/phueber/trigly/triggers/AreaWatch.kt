package app.phueber.trigly.triggers

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The arithmetic behind the two area components, with no Android type in it.
 *
 * `LocationTrigger` used to hold all of this inline, mixed with the platform
 * calls. It could then only be tested on a device, and a device cannot stand
 * on a boundary on command. Everything that decides something now lives here
 * as a pure function or a small state machine, and the trigger is left with
 * the parts that must touch `LocationManager`.
 *
 * Both components call into this file. `LocationTrigger` watches an area and
 * `LocationCheckTriggerFactory` builds the same class to answer a question, so
 * the rule about what a fix proves must be one rule. A copy in each is two
 * rules that agree until somebody edits one of them.
 */

/**
 * How fast this code assumes the phone can travel, in metres per second.
 *
 * 30 m/s is 108 km/h. It is not an average speed and it is not meant to be.
 * Every use of this constant asks "could the phone have reached the boundary
 * since the last fix", and the answer has to be safe for a person on a
 * motorway, not typical for a person walking. A number near the true average
 * would save more battery and would also miss the arrival of anybody driving,
 * which is the case an area rule is most often built for.
 *
 * A train or an aircraft beats it. Both are named in the components' own
 * caveat text, because no poll interval this code picks can be right for a
 * speed it refuses to plan for.
 */
private const val ASSUMED_SPEED_METERS_PER_SECOND = 30.0

/**
 * The widest the hysteresis band may grow, as a share of the radius.
 *
 * The band is normally the fix's own error, which is the honest width: a
 * reading is believed to have crossed the edge when it is clear of the edge by
 * more than the reading could be wrong by. That rule alone breaks down as the
 * error approaches the radius, because the band then swallows the area and no
 * reading can ever report an arrival.
 *
 * Half the radius is where it is cut off. At that width a phone must be well
 * inside the circle to count as arrived and well outside it to count as left,
 * which is late but is still a report. Beyond it the component would go quiet
 * and look healthy, which is the failure this project treats as the worst one.
 */
private const val MAX_MARGIN_FRACTION = 0.5

/**
 * The longest gap this code will ask a provider for, whatever the distance.
 *
 * A ceiling is needed because the distance arithmetic has no upper bound: a
 * phone on another continent would otherwise be asked for a position once a
 * day, and the first thing the rule would learn about the trip home is that it
 * already ended.
 *
 * 15 minutes is chosen against Doze rather than against the arithmetic. A
 * dozing device already batches work into maintenance windows spaced roughly
 * that far apart, so a request slower than this buys nothing the platform is
 * not doing anyway.
 */
const val DEFAULT_POLL_CEILING_MILLIS: Long = 15 * 60 * 1000L

/**
 * The oldest a shared fix may be before it is refused outright.
 *
 * [Fix.stillAnswers] normally decides by distance, which is the better test and
 * needs no constant. This is the guard for what distance cannot see. A phone
 * switched off, flown, or carried through a tunnel has moved in ways
 * [ASSUMED_SPEED_METERS_PER_SECOND] does not describe, and the stored fix says
 * nothing about any of it. Ten minutes bounds how wrong that can get.
 */
private const val DEFAULT_FIX_MAX_AGE_MILLIS: Long = 10 * 60 * 1000L

/** Which edge of the area a reading reports. */
enum class AreaCrossing {
    ENTERED,
    EXITED,
}

/**
 * The width of the band around the radius where a reading changes nothing.
 *
 * This is the piece the platform components did not have, and its absence is a
 * real fault rather than a rough edge. A phone standing near the edge of an
 * area reports positions that scatter around its true place by the width of
 * the fix's own error. Compared against the radius alone, that scatter reads as
 * a run of arrivals and departures, and each one starts the rule again. A
 * person who parks near the boundary of their home area can get the same
 * notification every minute all evening.
 *
 * A system geofence solves this with a margin, a dwell time, or both. The
 * margin is taken here and the dwell time is not, because a margin needs no
 * timer, no stored deadline and no new setting, and it survives the process
 * being killed between two fixes. A dwell time needs all four.
 *
 * The width is the fix's own accuracy, capped by [MAX_MARGIN_FRACTION]. An
 * accurate fix therefore gets almost no band and reports a crossing as
 * promptly as it did before; a vague fix gets a wide one and must commit
 * before it is believed. That is the right way round: the band exists to
 * absorb error, so it should be the size of the error.
 *
 * An unknown accuracy takes the full cap. This file reads a missing accuracy
 * as the strict case everywhere, the same way `locationRequirements` reads a
 * missing radius, because a provider that reports no error has not promised a
 * small one.
 */
fun hysteresisMarginMeters(radiusMeters: Double, accuracyMeters: Float?): Double {
    val cap = max(radiusMeters, 0.0) * MAX_MARGIN_FRACTION
    val fromAccuracy = accuracyMeters?.toDouble() ?: cap
    return min(max(fromAccuracy, 0.0), cap)
}

/**
 * How long to wait before asking for the next position.
 *
 * A fixed interval is wrong in both directions, and one number cannot fix it.
 * Set it short and the phone pays for a position every minute while its owner
 * sits at a desk 20 km away, where nothing can possibly happen. Set it long
 * and the rule finds the boundary minutes after the person crossed it.
 *
 * So the interval follows the distance to the boundary. The gap is the time it
 * would take to cover that distance at [ASSUMED_SPEED_METERS_PER_SECOND], which
 * means a phone far away is asked rarely and a phone near the edge is asked at
 * the rate the rule was configured with. Nothing can cross the boundary
 * unseen unless it travels faster than a car.
 *
 * The fix's own error is subtracted first. A reading 200 m from the edge with
 * 150 m of error may really be 50 m from it, and planning the next wake from
 * the optimistic number is how a crossing gets missed.
 *
 * **[floorMillis] is the configured interval, and it is a floor, never a
 * target.** This function can only make the gap longer. That is what keeps a
 * rule somebody already saved working exactly as before in the case that
 * matters, which is standing near the area: the fastest this ever polls is the
 * rate that rule already asked for, so the change cannot make an existing rule
 * slower to notice an arrival.
 *
 * **The result doubles rather than sliding.** Each step is the floor times a
 * power of two. A smooth curve would return a slightly different number from
 * every fix, and the caller re-registers its request whenever the number
 * changes, so a smooth curve would mean a re-registration per fix for no gain.
 * A ladder changes rarely and only when the distance has really changed scale.
 */
fun areaPollIntervalMillis(
    distanceMeters: Double,
    radiusMeters: Double,
    accuracyMeters: Float?,
    floorMillis: Long,
    ceilingMillis: Long = DEFAULT_POLL_CEILING_MILLIS,
): Long {
    // A floor of zero means "as fast as the provider will give it", which has no
    // ladder to climb: doubling it stays at zero. A floor at or above the
    // ceiling is a rule that already asked for less than this would give it, and
    // the rule wins.
    if (floorMillis <= 0L || floorMillis >= ceilingMillis) return floorMillis

    val toBoundary = max(
        abs(distanceMeters - radiusMeters) - (accuracyMeters?.toDouble() ?: 0.0),
        0.0,
    )
    val idealMillis = toBoundary / ASSUMED_SPEED_METERS_PER_SECOND * 1000.0

    // The clamp is outside the loop rather than a condition inside it, because
    // the rungs of a doubling ladder almost never land on the ceiling. From the
    // default 60 s floor they are 60 s, 2, 4, 8 and 16 minutes, and a loop that
    // refused to step past 15 minutes would stop at 8 and never use the last
    // third of the range the ceiling describes.
    var interval = floorMillis
    while (interval < ceilingMillis && interval * 2 <= idealMillis) {
        interval *= 2
    }
    return min(interval, ceilingMillis)
}

/**
 * One reading, turned into what the caller has to do about it.
 *
 * @param crossing the edge this reading reports, or null when the reading
 *   leaves the answer where it was. Most readings report nothing, which is the
 *   normal case and not a failure.
 * @param nextPollMillis what to ask the provider for from here. See
 *   [areaPollIntervalMillis].
 */
data class AreaReading(
    val crossing: AreaCrossing?,
    val nextPollMillis: Long,
)

/**
 * Turns a stream of positions into a stream of crossings.
 *
 * The same job [StateTracker] does for broadcasts, and it cannot be that class,
 * for a reason worth writing down. [StateTracker] compares a reading against
 * the last one and reports every difference. That is right for a broadcast,
 * where "connected" and "disconnected" are facts the system asserts. It is
 * wrong for a position, where inside and outside are *derived* from a
 * measurement with a known error, and two readings can differ only because the
 * error moved. A tracker over such readings reports crossings that did not
 * happen.
 *
 * So this holds the same one-bit state and applies two extra rules to it:
 *
 *  - A reading too coarse to resolve the area at all answers nothing. See
 *    [insideArea]. It is not a state that was missed, it is a reading that has
 *    no bearing on the question, and feeding it to a tracker would invent a
 *    crossing from noise.
 *  - A reading that lands inside the band around the radius answers nothing
 *    either. See [hysteresisMarginMeters].
 *
 * **The first reading is adopted and not reported**, which keeps the behaviour
 * [StateTracker]`(suppressInitialState = true)` gave. Enabling a rule while
 * standing inside an area is not an arrival, and a rule that fires because it
 * was switched on reads to its owner as a fault.
 *
 * The first reading is also the one case that is compared against the plain
 * radius with no band. There is no previous side for a band to protect, so a
 * band there would only pick a side more slowly and less accurately.
 *
 * Not thread-safe, and it does not need to be: one instance per flow
 * collection, touched only from the one looper the location listener is
 * registered on.
 */
class AreaWatch(
    private val radiusMeters: Double,
    private val floorMillis: Long,
    private val ceilingMillis: Long = DEFAULT_POLL_CEILING_MILLIS,
) {

    /** True for inside, false for outside, null until a reading has answered. */
    private var inside: Boolean? = null

    /**
     * @return what this reading means, or null when the reading could not
     *   answer the question at all. Null leaves the caller's poll interval
     *   alone, deliberately: a fix too coarse to say which side the phone is on
     *   is also too coarse to say how far from the edge it is, so it must not
     *   be allowed to lengthen the gap to the next one.
     */
    fun accept(distanceMeters: Double, accuracyMeters: Float?): AreaReading? {
        val plainly = insideArea(distanceMeters, radiusMeters, accuracyMeters) ?: return null
        val nextPoll = areaPollIntervalMillis(
            distanceMeters = distanceMeters,
            radiusMeters = radiusMeters,
            accuracyMeters = accuracyMeters,
            floorMillis = floorMillis,
            ceilingMillis = ceilingMillis,
        )

        val previous = inside
        if (previous == null) {
            inside = plainly
            return AreaReading(crossing = null, nextPollMillis = nextPoll)
        }

        val margin = hysteresisMarginMeters(radiusMeters, accuracyMeters)
        val crossing = when {
            !previous && distanceMeters <= radiusMeters - margin -> AreaCrossing.ENTERED
            previous && distanceMeters >= radiusMeters + margin -> AreaCrossing.EXITED
            else -> null
        }
        if (crossing != null) inside = crossing == AreaCrossing.ENTERED

        return AreaReading(crossing = crossing, nextPollMillis = nextPoll)
    }
}

/**
 * A position, with no Android type in it.
 *
 * `android.location.Location` cannot be built in a JVM test, so the shared
 * store below would be untestable if it held one. This carries the four values
 * anything in this file reads and nothing else.
 *
 * [atMillis] is stamped by the trigger's own clock when the fix arrives, not
 * taken from the fix. `Location.getElapsedRealtimeAgeMillis` would be the more
 * exact source and arrived too late to use here without a second path for
 * older devices, and `getTime` is wall-clock and moves when the clock is set.
 * The arrival time is off by however long the platform held the fix, which is
 * small next to the ages this is compared against, and it comes from the same
 * injected clock the tests drive.
 */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val atMillis: Long,
)

/**
 * Whether this fix can still be trusted to say which side of the area the
 * phone is on.
 *
 * Not a fixed staleness window, because staleness is not the question. A fix
 * ten minutes old is worthless to a rule about a driveway and perfectly good
 * to a rule about a city the phone is 40 km outside of. What matters is
 * whether the phone could have reached the boundary since, and that is the
 * distance to the boundary against [ASSUMED_SPEED_METERS_PER_SECOND].
 *
 * The fix's own error is subtracted before the comparison, for the same reason
 * [areaPollIntervalMillis] subtracts it: the reading may already be nearer the
 * edge than it claims.
 *
 * [maxAgeMillis] is the backstop for everything the speed model cannot see.
 * See [DEFAULT_FIX_MAX_AGE_MILLIS]. A fix from the future, which a clock
 * change can produce, is refused rather than treated as fresh.
 */
fun Fix.stillAnswers(
    nowMillis: Long,
    distanceMeters: Double,
    radiusMeters: Double,
    maxAgeMillis: Long = DEFAULT_FIX_MAX_AGE_MILLIS,
): Boolean {
    val ageMillis = nowMillis - atMillis
    if (ageMillis < 0L || ageMillis > maxAgeMillis) return false

    val toBoundary = abs(distanceMeters - radiusMeters) - (accuracyMeters?.toDouble() ?: 0.0)
    val couldHaveTravelled = ageMillis / 1000.0 * ASSUMED_SPEED_METERS_PER_SECOND
    return toBoundary > couldHaveTravelled
}

/**
 * The last position anything in this process saw, shared by every area rule.
 *
 * **What this is for, and what it is not for.** It is not an attempt to merge
 * the watching requests. The platform already does that: several requests from
 * one app to one provider become one provider request at the strictest
 * interval, so a second rule watching a second area adds a callback and not a
 * duty cycle. Sharing those would save nothing measurable.
 *
 * What does cost twice is the *asking*. Every `location_check` leaf in an
 * evaluation runs its own one-shot read, each with its own budget of seconds,
 * each while the rule's actions wait behind it. Two such leaves in one rule, or
 * two rules evaluated by the same event, paid that twice over for a position
 * that had not changed in between. Worse, a rule already watching an area had a
 * fresh fix in hand and no way to hand it to the leaf beside it.
 *
 * So this is a one-slot store that every source writes to: the watching
 * request, the passive provider, and each one-shot read. A check reads it
 * first and only pays for a read when the stored fix cannot answer. The
 * decision about whether it can answer is [Fix.stillAnswers], which is by
 * distance and not by a clock alone.
 *
 * One slot rather than a cache keyed by anything. A position is a property of
 * the phone and not of the rule that asked for it, so there is nothing to key
 * on, and the newest fix is always the best one for every area at once.
 *
 * Process-wide and deliberately not persisted, for the reason [BootEvents]
 * gives: a new process means the phone may be anywhere, and a stored fix that
 * outlived the process is exactly the stale answer this file refuses to give.
 */
object AreaFixes {

    @Volatile
    private var stored: Fix? = null

    /**
     * Records [fix] unless something newer is already stored.
     *
     * The guard matters because the writers are not ordered against each other.
     * A one-shot read that started before a passive fix arrived can finish after
     * it, and letting it win would move the store backwards in time.
     */
    fun record(fix: Fix) {
        val previous = stored
        if (previous == null || fix.atMillis >= previous.atMillis) stored = fix
    }

    /** The newest fix any source in this process has seen, or null. */
    fun latest(): Fix? = stored

    /** For tests. Nothing in the app clears this; a new process starts empty. */
    fun forget() {
        stored = null
    }
}
