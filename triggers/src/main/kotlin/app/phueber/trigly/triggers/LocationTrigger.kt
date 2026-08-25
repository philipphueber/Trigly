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
import kotlin.coroutines.resume
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Tried in order; the fix is used from whichever of these is switched on. */
private val CANDIDATE_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

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

        manager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            minIntervalMillis,
            // Distance filter left to the trigger's own maths; the provider's
            // filter would suppress the very update that crosses the boundary.
            0f,
            listener,
            Looper.getMainLooper(),
        )

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
        val provider = CANDIDATE_PROVIDERS.firstOrNull { manager.isProviderEnabled(it) } ?: return null

        val location = runCatching { readOnce(manager, provider) }.getOrNull() ?: return null

        val inside = distanceMeters(latitude, longitude, location.latitude, location.longitude) <= radiusMeters
        return inside == onEnter
    }

    // Suppressed on the helpers too, not only on the caller: the permission is
    // checked once at the top of `currentlyHolds` and null returned without it,
    // but lint cannot follow that across a function boundary.
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

    // The reboot sentence is not a nicety. Since Android 12 a foreground service
    // started while the app was in the background — which BOOT_COMPLETED is, even
    // though it is an *allowed* start — permanently loses while-in-use location
    // access for that service instance. The platform says so in logcat and
    // nowhere the user can see:
    //
    //   Foreground service started from background can not have location/camera/
    //   microphone access: service app.phueber.trigly/.ui.EngineService
    //
    // Everything then looks healthy: the engine is running, every broadcast
    // trigger fires, and the requirement check passes because ACCESS_FINE_LOCATION
    // genuinely *is* granted — it is the service instance that is restricted, which
    // nothing in the app models. The rule simply never fires (as a trigger) or
    // never holds (as a condition), and either reads as "you have not reached
    // the area" / "you are not there" rather than as the failure it is.
    //
    // Saying it is the honest stopgap, not the fix. The fix is for the engine to
    // notice it was boot-started and re-`startForeground` from a foreground
    // context, or to run location work in a UI-started service.
    //
    // Deliberately no remedy in the sentence. Opening the app is *not* one:
    // `MainActivity` does call `EngineService.start`, but on an already-running
    // service that only re-delivers `onStartCommand` — the instance, and its
    // restriction, are the same one. Naming a remedy that does not work would be
    // worse than naming none, so the warning states the condition only.
    //
    // Two different costs for the two roles this component plays, both stated
    // rather than left implicit: as a trigger it holds GPS open, which is a
    // battery cost; as a condition it takes one fix and lets go, which trades
    // that battery cost for staleness instead — a cached fix can be minutes
    // old, fine for "am I at home" and wrong for "am I in the driveway". The
    // reboot limitation is the one thing both roles share unchanged.
    override val warning: String =
        "As a trigger, this component holds an active GPS request while the rule is " +
            "on. This costs more battery. Choose a large radius and a long check " +
            "interval to lower the cost. As a condition, this component takes a " +
            "single location fix. This costs less battery, but the fix can be " +
            "minutes old. An old fix works for \"am I at home\" and fails for " +
            "\"am I in the driveway\". Both roles share one limit. After a reboot, " +
            "Android blocks Trigly's access to location in the background. This " +
            "component cannot fire or hold until you restart Trigly. No other part " +
            "of the app reports this limit."

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION),
    )

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
     */
    override val warning: String =
        "This takes a single location fix when another trigger starts the rule. " +
            "It watches nothing, so it costs little battery. The fix can be " +
            "minutes old. An old fix works for \"am I at home\" and fails for " +
            "\"am I in the driveway\". After a reboot, Android blocks Trigly's " +
            "access to location in the background. This component cannot answer " +
            "until you restart Trigly. No other part of the app reports this limit."

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION),
    )

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
