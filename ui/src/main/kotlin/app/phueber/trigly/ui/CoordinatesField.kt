package app.phueber.trigly.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.phueber.trigly.core.ConfigField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Latitude and longitude, with the device's own position one tap away.
 *
 * The two boxes stay, because an area you are not currently standing in has to be
 * enterable somehow. What is new is that the common case — "home", "work", the
 * place you are at while building the rule — no longer means leaving the app,
 * finding coordinates elsewhere, and copying two numbers back in, where a dropped
 * digit in the sixth decimal place is a silently wrong rule rather than an error.
 */
@Composable
fun CoordinatesField(
    field: ConfigField.Coordinates,
    latitude: String?,
    longitude: String?,
    onChange: (latitude: String?, longitude: String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            CoordinateBox(
                label = fieldLabel(field.label, field.required),
                value = latitude,
                modifier = Modifier.weight(1f),
                onValueChange = { onChange(it, longitude) },
            )
            CoordinateBox(
                // Routed through fieldLabel like the latitude box: latitude and
                // longitude are one answer, so the asterisk (or its absence) has
                // to agree on both halves, not just the one that carries field.label.
                label = fieldLabel("Longitude", field.required),
                value = longitude,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                onValueChange = { onChange(latitude, it) },
            )
        }

        BlockOutlineButton(
            text = "Use where I am now",
            onClick = {
                scope.launch {
                    when (val outcome = readLastKnownLocation(context)) {
                        is CoordinateRead.Found -> {
                            status = null
                            onChange(
                                format(outcome.location.latitude),
                                format(outcome.location.longitude),
                            )
                        }
                        // Said rather than silently ignored: a button that
                        // sometimes does nothing is worse than one that explains.
                        is CoordinateRead.Failed -> status = outcome.reason
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        status?.let { Hint(it) }
    }
}

@Composable
private fun CoordinateBox(
    label: String,
    value: String?,
    modifier: Modifier,
    onValueChange: (String?) -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = { onValueChange(it.ifEmpty { null }) },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        singleLine = true,
        // Decimal so a southern or western coordinate can carry its sign.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private sealed interface CoordinateRead {
    data class Found(val location: Location) : CoordinateRead
    data class Failed(val reason: String) : CoordinateRead
}

/**
 * The last position the system already has, rather than a fresh fix.
 *
 * A live request would need a callback, a timeout and a spinner, and would still
 * fail indoors. The cached fix is instant, costs no battery, and is accurate
 * enough for an area with a radius measured in tens of metres. When there is no
 * cached fix the honest answer is to say so and let the user type.
 */
private suspend fun readLastKnownLocation(context: Context): CoordinateRead =
    withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            // Not "below": a component can render this field with no requirements
            // section at all (SolarTrigger needs no location permission, and even
            // one that does declare it hides the row once granted), so the fix has
            // to be findable from a phrase alone, not a pointer to a control that
            // may not be on screen.
            return@withContext CoordinateRead.Failed(
                "Grant Trigly's location permission in system settings, then try again."
            )
        }

        val manager = context.getSystemService(LocationManager::class.java)
            ?: return@withContext CoordinateRead.Failed("This device has no location service.")

        // Whichever provider has the freshest fix. GPS is the most accurate and
        // the most often stale; network is usually the one that has anything
        // indoors.
        val newest = runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                .maxByOrNull { it.time }
        }.getOrNull()

        newest?.let { CoordinateRead.Found(it) }
            ?: CoordinateRead.Failed(
                "No recent position stored. Open a maps app to get a fix, or type the coordinates."
            )
    }

/** Six decimals is roughly a tenth of a metre — past what any geofence resolves. */
private fun format(degrees: Double): String = String.format(Locale.US, "%.6f", degrees)
