package app.phueber.trigly.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.DurationUnit
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * What both location components need, watching and checking alike.
 *
 * **Why the background grant is here and not left out.** The fine grant on its
 * own is "while in use". It answers a position read while an activity is on
 * screen and returns nothing when none is. The engine runs off screen almost
 * all of the time, so the checking half answered "I cannot look" for every rule
 * that ran with the app in the background, and an AND above it dropped the rule
 * with no record. Declaring the grant is what turns that from a silent failure
 * into a row the editor shows with a button on it.
 *
 * The grant arrived in Android 10 (API 29). Below that the fine grant already
 * covers the background, so asking for it there would show a row the user could
 * never satisfy. `minSdk` is 26, so the test is real and not decoration.
 */
private val LOCATION_REQUIREMENTS: List<ComponentRequirement> = buildList {
    add(ComponentRequirement.RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(ComponentRequirement.RuntimePermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }
}

/**
 * Which providers to ask, in the order to ask them, on a device at [apiLevel].
 *
 * Not an order of preference for accuracy. It is an order of preference for
 * *an answer at all*, and GPS is last for that reason. GPS is the most accurate
 * provider on the phone and the one that cannot see the sky from indoors, which
 * is where a phone spends most of its life and where the question this component
 * exists for, "am I at home", gets asked. The fused provider is the platform's
 * own best-available blend and answers from Wi-Fi and cell when GPS cannot;
 * network answers the same way on a device that has no fused provider. So the
 * cheap answers are asked for first and GPS is what is left to try, rather than
 * the wall the read used to stop at.
 *
 * `FUSED_PROVIDER` is public API from API 31. Below that the name is not part of
 * the platform, and `isProviderEnabled` rejects a provider it does not know, so
 * the version decides whether it is in the list rather than a filter later on.
 *
 * Pure and separate from the trigger, because the order is the decision here and
 * a `LocationManager` cannot be built in a JVM test.
 */
fun locationProviderOrder(apiLevel: Int): List<String> = buildList {
    if (apiLevel >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    add(LocationManager.NETWORK_PROVIDER)
    add(LocationManager.GPS_PROVIDER)
}

private val CANDIDATE_PROVIDERS: List<String> = locationProviderOrder(Build.VERSION.SDK_INT)

/**
 * The ceiling on one position read, counted across every provider it tries.
 *
 * [LocationTrigger.currentlyHolds] runs inside a rule's gate with the rule's
 * actions waiting behind it, so this cannot be open-ended. A fused or network
 * read answers in well under a second whenever there is anything recent to give,
 * and a cold GPS read can take most of a minute on its own; 15 seconds is enough
 * for the first kind and deliberately not enough for the worst of the second.
 * A rule delayed by a quarter of a minute is late. A rule delayed by a minute
 * per event is broken.
 */
private const val POSITION_READ_BUDGET_MILLIS = 15_000L

/**
 * Great-circle distance in metres.
 *
 * Hand-rolled rather than using `Location.distanceBetween` so it is a pure
 * function testable on the JVM — the framework version is a static native call
 * that throws in unit tests. Haversine is accurate to well under a metre at
 * geofence scale.
 */
fun distanceMeters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
}

/**
 * Fires on entering or leaving a circular area.
 *
 * Uses the platform [LocationManager] rather than Play Services' Geofencing API,
 * deliberately: adding `play-services-location` would make an open-source
 * automation app depend on Google Play Services and stop it working on
 * de-Googled devices. The trade is real — the Play version is batched,
 * system-managed and far cheaper on battery, while this holds an active location
 * request. See `docs/triggers.md`; this is a product decision, not a settled one.
 *
 * Entering and leaving are edges, so [StateTracker] does the same job it does for
 * broadcasts: the position at the moment the rule starts is recorded, not fired.
 *
 * Also answers as a condition, via [currentlyHolds]: "am I currently inside" is
 * the same geometry asked instead of watched, per `docs/conditions.md`'s
 * "Location — checked, not watched" and "Grouped under one component,
 * transparently" — one component, and the slot it is placed in decides which
 * question is being asked. [currentlyHolds] takes a single fix and lets go
 * rather than starting or reusing the [events] hold, which is the entire point:
 * asking is cheap where watching is not, and asking through the trigger's own
 * active request would spend the expensive thing on the cheap question.
 */
class LocationTrigger(
    private val context: Context,
    private val latitude: Double,
    private val longitude: Double,
    private val radiusMeters: Double,
    private val onEnter: Boolean,
    private val minIntervalMillis: Long,
    /**
     * When true this trigger watches nothing and only answers when asked. See
     * the field's own help text, and `LocationTriggerFactory.producesEvents`,
     * which has to agree with this or the editor and the engine disagree about
     * what this leaf can do.
     */
    private val checkOnly: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION is declared as a requirement.
    override fun events(): Flow<TriggerEvent> = if (checkOnly) {
        // Empty before the service is even looked up, so nothing here can open a
        // position request. This is the whole switch: `requestLocationUpdates`
        // below is what costs the battery, and the only way not to pay for it is
        // not to reach it.
        emptyFlow()
    } else {
        watchArea()
    }

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION is declared as a requirement.
    private fun watchArea(): Flow<TriggerEvent> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return@callbackFlow

        val tracker = StateTracker(suppressInitialState = true)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val inside = distanceMeters(
                    latitude, longitude, location.latitude, location.longitude
                ) <= radiusMeters

                val key = if (inside) INSIDE else OUTSIDE
                if (!tracker.accept(key)) return
                if (inside != onEnter) return

                trySend(
                    TriggerEvent(
                        triggerType = TYPE,
                        firedAtMillis = now(),
                        payload = mapOf(PAYLOAD_STATE to if (inside) ENTERED else EXITED),
                    )
                )
            }

            // Abstract before API 30; overridden so the class is complete on
            // every supported version rather than relying on default methods.
            @Deprecated("Required on API < 30", ReplaceWith(""))
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }

        // One provider, chosen by the same order and for the same reason as the
        // checking half. See [locationProviderOrder]. This was `GPS_PROVIDER`,
        // named directly: a watch on an area indoors then never saw an update,
        // and a watch on a phone with GPS switched off never saw one either,
        // because a request on a disabled provider is accepted and then silent.
        //
        // Falls back to the last candidate instead of giving up when nothing is
        // enabled, which keeps the old behaviour for that case: registered and
        // quiet, rather than a leaf that ends its flow and stays ended after the
        // user switches location back on. The call is wrapped because a provider
        // this device does not have is an argument the platform rejects.
        val provider = CANDIDATE_PROVIDERS.firstOrNull {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        } ?: CANDIDATE_PROVIDERS.last()

        runCatching {
            manager.requestLocationUpdates(
                provider,
                minIntervalMillis,
                // Distance filter left to the trigger's own maths; the provider's
                // filter would suppress the very update that crosses the boundary.
                0f,
                listener,
                Looper.getMainLooper(),
            )
        }

        awaitClose { manager.removeUpdates(listener) }
    }

    /**
     * Null, not false, whenever the question could not actually be asked:
     * permission withheld, every provider switched off, the read coming back
     * with no fix, or the platform call throwing. Each of those is "I could
     * not look", which the gate must not read as "you are not there" — see
     * [Trigger.currentlyHolds] and `docs/conditions.md`'s note that null must
     * not read as true. The same reboot restriction [events] carries applies
     * here unchanged: a boot-started engine has no location access at all, so
     * this returns null for the engine's whole lifetime rather than the false
     * a careless read would produce.
     *
     * Reads the direction from [onEnter]: configured for "entered", this holds
     * while inside the radius; configured for "exited", while outside. Same
     * config, same [distanceMeters] call [events] uses to find the boundary —
     * only the question changes from "did you just cross it" to "which side
     * are you on".
     */
    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION is declared as a requirement.
    override suspend fun currentlyHolds(): Boolean? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val location = readPosition(manager) ?: return null

        val inside = distanceMeters(latitude, longitude, location.latitude, location.longitude) <= radiusMeters
        return inside == onEnter
    }

    /**
     * The first position any provider will give, or null when none of them will.
     *
     * The fallback is the whole point. This used to take the first *enabled*
     * provider and read that one only, which on a phone with GPS switched on
     * meant GPS and nothing else: indoors that read comes back empty, the
     * component answered "I cannot look", and the rule was held back while the
     * position sat unread in the network provider. Enabled is not the same
     * property as able to answer, and only one of the two is worth branching on.
     *
     * A provider that answers nothing is passed over rather than treated as a
     * refusal, and so is one this device does not have: `isProviderEnabled`
     * rejects an unknown name, which is a fact about the phone and not about
     * where its owner is standing.
     *
     * Bounded as a whole rather than per provider, so that adding a provider to
     * [locationProviderOrder] can never lengthen the wait a rule pays. Running
     * out of [POSITION_READ_BUDGET_MILLIS] reads as null, the same as nobody
     * answering, because for the rule waiting behind it those are one outcome.
     */
    // Suppressed on the helpers too, not only on the caller: the permission is
    // checked once at the top of `currentlyHolds` and null returned without it,
    // but lint cannot follow that across a function boundary.
    @SuppressLint("MissingPermission")
    private suspend fun readPosition(manager: LocationManager): Location? =
        withTimeoutOrNull(POSITION_READ_BUDGET_MILLIS) {
            CANDIDATE_PROVIDERS.firstNotNullOfOrNull { provider ->
                if (runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) {
                    runCatching { readOnce(manager, provider) }.getOrNull()
                } else {
                    null
                }
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun readOnce(manager: LocationManager, provider: String): Location? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            currentLocationOnce(manager, provider)
        } else {
            manager.getLastKnownLocation(provider)
        }

    // getCurrentLocation is callback-based, not suspend, so it is bridged here.
    // Cancelling the coroutine cancels the platform request through the
    // CancellationSignal rather than leaving it to complete and be discarded —
    // there is no reason to keep a radio warm for an answer nobody will read.
    // This is a one-shot request of its own, separate from and never touching
    // the `requestLocationUpdates` hold [events] keeps open — the two never
    // run at once for the same instance, but they do not share state either
    // way, so there is nothing to reuse even when [events] happens to be
    // collected at the same time.
    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private suspend fun currentLocationOnce(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }

            manager.getCurrentLocation(
                provider,
                signal,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }

    companion object {
        const val TYPE = "location"
        const val CONFIG_LATITUDE = "latitude"
        const val CONFIG_LONGITUDE = "longitude"
        const val CONFIG_RADIUS_METERS = "radiusMeters"
        const val CONFIG_STATE = "state"
        const val CONFIG_MIN_INTERVAL_MILLIS = "minIntervalMillis"
        const val PAYLOAD_STATE = "state"
        const val ENTERED = "entered"
        const val EXITED = "exited"

        const val DEFAULT_MIN_INTERVAL_MILLIS = 60_000L

        private const val INSIDE = "inside"
        private const val OUTSIDE = "outside"
    }
}

class LocationTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = LocationTrigger.TYPE

    override val displayName = "Enter or leave an area"
    override val category = Category.LOCATION

    override val supportsCondition = true

    override val configFields = listOf(
        // One field over two keys: a latitude without a longitude is not half an
        // answer, it is no answer. The editor offers the device's own position so
        // that "here" does not mean copying two numbers in from elsewhere.
        ConfigField.Coordinates(
            key = LocationTrigger.CONFIG_LATITUDE,
            label = "Latitude",
            required = true,
            longitudeKey = LocationTrigger.CONFIG_LONGITUDE,
        ),
        ConfigField.Decimal(
            key = LocationTrigger.CONFIG_RADIUS_METERS,
            label = "Radius",
            required = true,
            min = 1.0,
            unit = "m",
        ),
        stateChoice("Fires when you", "entered", "arrive", "exited", "leave"),
        ConfigField.Duration(
            key = LocationTrigger.CONFIG_MIN_INTERVAL_MILLIS,
            label = "Minimum time between checks",
            defaultMillis = LocationTrigger.DEFAULT_MIN_INTERVAL_MILLIS,
            preferred = DurationUnit.SECONDS,
            help = "Shorter intervals find the boundary sooner. They also use more battery.",
        ),
    )

    // The last sentence is not a nicety, and it used to describe a limit this
    // app simply had.
    //
    // The fine grant on its own is "while in use". Android answers a position
    // read while an activity is on screen and gives nothing when none is. The
    // engine is off screen almost always, so this component could not fire or
    // hold in the background at all, and it looked healthy the whole time: the
    // engine was running, every broadcast trigger fired, and the requirement
    // check passed because ACCESS_FINE_LOCATION genuinely was granted. The rule
    // simply never ran, which reads as "you have not reached the area" rather
    // than as the failure it was.
    //
    // Two things now carry it. The engine claims the `location` foreground
    // service type, which makes it count as in use for a position read; and the
    // app holds ACCESS_BACKGROUND_LOCATION, which is what survives a reboot,
    // because since Android 12 a service started from the background loses
    // while-in-use access for its whole life whatever type it claims.
    //
    // So the sentence names the one thing left that the user controls and that
    // still stops this component dead. "Allow all the time" is a setting only
    // they can choose, the requirement row now says so with a button on it, and
    // the warning says it before a rule is built around the component.
    //
    // Two different costs for the two roles this component plays, both stated
    // rather than left implicit: as a trigger it holds GPS open, which is a
    // battery cost; as a condition it takes one fix and lets go, which trades
    // that battery cost for staleness instead. A cached fix can be minutes
    // old, fine for "am I at home" and wrong for "am I in the driveway". The
    // location setting is the one thing both roles share unchanged.
    override val warning: String =
        "As a trigger, this component holds an active GPS request while the rule is " +
            "on. This costs more battery. Choose a large radius and a long check " +
            "interval to lower the cost. As a condition, this component takes a " +
            "single location fix. This costs less battery, but the fix can be " +
            "minutes old. An old fix works for \"am I at home\" and fails for " +
            "\"am I in the driveway\". Both roles need one setting. Set location " +
            "to \"Allow all the time\". With any other setting, Android gives " +
            "Trigly no position while the app is in the background, and this " +
            "component cannot fire or hold."

    override val requirements = LOCATION_REQUIREMENTS

    override fun create(config: Map<String, String>): Trigger {
        fun requiredDouble(key: String): Double {
            val raw = config[key] ?: error("$type needs '$key'")
            return raw.toDoubleOrNull() ?: error("$key must be a number, was '$raw'")
        }

        return LocationTrigger(
            context = context,
            latitude = requiredDouble(LocationTrigger.CONFIG_LATITUDE),
            longitude = requiredDouble(LocationTrigger.CONFIG_LONGITUDE),
            radiusMeters = requiredDouble(LocationTrigger.CONFIG_RADIUS_METERS),
            onEnter = parseTarget(
                config = config,
                key = LocationTrigger.CONFIG_STATE,
                onWord = LocationTrigger.ENTERED,
                offWord = LocationTrigger.EXITED,
            ),
            minIntervalMillis = config[LocationTrigger.CONFIG_MIN_INTERVAL_MILLIS]
                ?.toLongOrNull() ?: LocationTrigger.DEFAULT_MIN_INTERVAL_MILLIS,
            // Watching, which is what this factory is. The other one is
            // `LocationCheckTriggerFactory`.
            checkOnly = false,
        )
    }
}

/**
 * "Is in an area", the cheap half of the location component, as its own row in
 * the trigger picker.
 *
 * Watching an area and checking one are two different costs, and until now they
 * were one picker entry with a switch inside it. That was the wrong shape for
 * the same reason a group is a picker row rather than a second region of the
 * editor: the choice belongs where the choosing happens. Someone building an AND
 * of "the screen came on" and "I am at home" is picking a thing, not adding a
 * thing and then correcting it.
 *
 * Making it a type rather than a config value pays twice over.
 *
 * The editor's own filtering does the rest of the work for free.
 * [TriggerNode.canStart] already refuses a tree that nothing can start, and
 * `RuleEditorViewModel.triggerOptionsFor` already derives what a slot may offer
 * from exactly that, so this row is simply absent from a slot where it would be
 * the only trigger. As a switch it could be turned on *after* the leaf existed,
 * which produced a rule that could never start and needed a save-time refusal to
 * catch. A picker row cannot be got into that state.
 *
 * And the swap between the two is the ordinary "change the type of this block",
 * which carries compatible config across: the keys here are the keys
 * [LocationTriggerFactory] uses, so changing your mind keeps the coordinates and
 * the radius you typed. The one field this drops is the interval between checks,
 * because nothing here is watching.
 */
class LocationCheckTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = TYPE

    override val displayName = "Is in an area"
    override val category = Category.LOCATION

    override val supportsCondition = true

    /**
     * The whole point of this factory, and the honest half of the pair with
     * [supportsCondition]: it answers a question and never starts anything. The
     * trigger it builds returns an empty flow before it looks up the location
     * service, so nothing on that path can open a position request.
     */
    override val producesEvents = false

    override val configFields = listOf(
        ConfigField.Coordinates(
            key = LocationTrigger.CONFIG_LATITUDE,
            label = "Latitude",
            required = true,
            longitudeKey = LocationTrigger.CONFIG_LONGITUDE,
        ),
        ConfigField.Decimal(
            key = LocationTrigger.CONFIG_RADIUS_METERS,
            label = "Radius",
            required = true,
            unit = "m",
            help = "How close counts as being there. A phone's position is not " +
                "exact, so a radius under 100 m answers wrongly more often.",
        ),
        // Same key and same stored words as the watching factory, so switching a
        // block between the two keeps the answer the person already gave.
        stateChoice("Holds when you are", "entered", "inside the area", "exited", "outside the area"),
    )

    /**
     * The condition half of [LocationTriggerFactory]'s warning, and the reboot
     * limit, which both roles share.
     *
     * Says what this costs instead of what it saves. One position read is cheap
     * next to holding GPS open, and that is why this exists, but the thing a
     * person needs to know is that the answer can be stale.
     *
     * The location-setting sentence replaced a reboot sentence that described a
     * limit the app no longer has. See [LocationTriggerFactory] for what carries
     * it now.
     */
    override val warning: String =
        "This takes a single location fix when another trigger starts the rule. " +
            "It watches nothing, so it costs little battery. Trigly asks the " +
            "cheapest source that can answer, which is usually Wi-Fi or the " +
            "mobile network and not GPS. The fix can be minutes old and it can " +
            "be some hundred metres out. An old or coarse fix works for \"am I " +
            "at home\" and fails for \"am I in the driveway\". Set location to \"Allow all the time\". " +
            "With any other setting, Android gives Trigly no position while the " +
            "app is in the background. This component then cannot answer, and a " +
            "rule that asks it does not run."

    override val requirements = LOCATION_REQUIREMENTS

    override fun create(config: Map<String, String>): Trigger {
        fun requiredDouble(key: String): Double {
            val raw = config[key] ?: error("$type needs '$key'")
            return raw.toDoubleOrNull() ?: error("$key must be a number, was '$raw'")
        }

        return LocationTrigger(
            context = context,
            latitude = requiredDouble(LocationTrigger.CONFIG_LATITUDE),
            longitude = requiredDouble(LocationTrigger.CONFIG_LONGITUDE),
            radiusMeters = requiredDouble(LocationTrigger.CONFIG_RADIUS_METERS),
            onEnter = parseTarget(
                config = config,
                key = LocationTrigger.CONFIG_STATE,
                onWord = LocationTrigger.ENTERED,
                offWord = LocationTrigger.EXITED,
            ),
            // Never read: with `checkOnly` set there is no update request to
            // space out. Passed as the default rather than made nullable, so the
            // trigger keeps one constructor for both factories.
            minIntervalMillis = LocationTrigger.DEFAULT_MIN_INTERVAL_MILLIS,
            checkOnly = true,
        )
    }

    companion object {
        const val TYPE = "location_check"
    }
}
