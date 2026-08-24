package app.phueber.trigly.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Tried in order; the fix is used from whichever of these is switched on. */
private val CANDIDATE_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

/**
 * Whether the device is currently within a radius of a point — read once, on
 * demand, never held open.
 *
 * [LocationTrigger] holds an active `requestLocationUpdates` for as long as
 * its rule is enabled, which is what makes it expensive; that cost exists
 * because a trigger has to notice the *moment* a boundary is crossed. A
 * condition never has to notice a moment — it is only ever asked "right now,
 * are you inside?" — so it can take a single fix and let go, per
 * `docs/conditions.md`'s "Location — checked, not watched".
 *
 * The single fix comes from `getCurrentLocation` on API 30+, which asks a
 * provider for a fresh reading; below that, from `getLastKnownLocation`, which
 * returns whatever the provider last had cached and may be minutes old. Both
 * are one-shot reads through [LocationManager] rather than the Geofencing API,
 * for the same de-Googled-device reason [LocationTrigger] gives.
 *
 * [events] is an empty flow for the same reason [TimeWindowCheck]'s is: "am I
 * currently in this area" is a level with no instant to fire on. The edge —
 * "you entered this area" — is what [LocationTrigger] already covers, in the
 * trigger slot the gate keeps this out of.
 */
class LocationCheck(
    private val context: Context,
    private val latitude: Double,
    private val longitude: Double,
    private val radiusMeters: Double,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = emptyFlow()

    /**
     * Null, not false, whenever the question could not actually be asked:
     * permission withheld, every provider switched off, the read coming back
     * with no fix, or the platform call throwing. Each of those is "I could
     * not look", which the gate must not read as "you are not there" — see
     * [Trigger.currentlyHolds] and `docs/conditions.md`'s note that null must
     * not read as true. The same reboot restriction [LocationTrigger] carries
     * applies here unchanged: a boot-started engine has no location access at
     * all, so this returns null for the engine's whole lifetime rather than
     * the false a careless read would produce.
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

        return distanceMeters(latitude, longitude, location.latitude, location.longitude) <= radiusMeters
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
        const val TYPE = "location_check"
        const val CONFIG_LATITUDE = "latitude"
        const val CONFIG_LONGITUDE = "longitude"
        const val CONFIG_RADIUS_METERS = "radiusMeters"
    }
}

class LocationCheckFactory(private val context: Context) : TriggerFactory {
    override val type = LocationCheck.TYPE

    override val displayName = "In an area"
    override val category = Category.LOCATION

    override val supportsCondition = true

    override val configFields = listOf(
        ConfigField.Coordinates(
            key = LocationCheck.CONFIG_LATITUDE,
            label = "Latitude",
            required = true,
            longitudeKey = LocationCheck.CONFIG_LONGITUDE,
        ),
        ConfigField.Decimal(
            key = LocationCheck.CONFIG_RADIUS_METERS,
            label = "Radius",
            required = true,
            min = 1.0,
            unit = "m",
        ),
    )

    override val requirements = listOf(
        ComponentRequirement.RuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION),
    )

    // Two costs, both honest and both different from LocationTrigger's:
    // staleness rather than battery (this never holds a request open), and the
    // same reboot restriction restated because it bites a *condition* in a way
    // that is easy to assume does not apply just because nothing here holds
    // GPS open.
    override val warning: String =
        "A cached fix can be minutes old, which is fine for \"am I at home\" and " +
            "wrong for \"am I in the driveway\". Known limitation: after a reboot " +
            "Android denies the background engine location access for its whole " +
            "life, so this check reads nothing until the engine is started fresh " +
            "— and nothing else in the app reports that."

    override fun create(config: Map<String, String>): Trigger {
        fun requiredDouble(key: String): Double {
            val raw = config[key] ?: error("$type needs '$key'")
            return raw.toDoubleOrNull() ?: error("$key must be a number, was '$raw'")
        }

        return LocationCheck(
            context = context,
            latitude = requiredDouble(LocationCheck.CONFIG_LATITUDE),
            longitude = requiredDouble(LocationCheck.CONFIG_LONGITUDE),
            radiusMeters = requiredDouble(LocationCheck.CONFIG_RADIUS_METERS),
        )
    }
}
