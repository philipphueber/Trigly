package app.phueber.trigly.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_METERS = 6_371_000.0

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
 */
class LocationTrigger(
    private val context: Context,
    private val latitude: Double,
    private val longitude: Double,
    private val radiusMeters: Double,
    private val onEnter: Boolean,
    private val minIntervalMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) : Trigger {

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION is declared as a requirement.
    override fun events(): Flow<TriggerEvent> = callbackFlow {
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
        )
    }
}
