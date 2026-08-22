package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory

/**
 * `ACTION_CONFIGURATION_CHANGED` fires for *any* configuration change — locale,
 * font scale, density, keyboard. Both triggers here watch a single dimension and
 * rely on [StateTracker] to discard the rest; without that they would fire on
 * unrelated changes, which is the classic bug with this broadcast.
 */
private const val CONFIGURATION_ACTION = Intent.ACTION_CONFIGURATION_CHANGED

/** True when the current configuration is in night mode. */
fun isNightMode(uiMode: Int): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

class DarkThemeTrigger(
    context: Context,
    private val onDark: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(CONFIGURATION_ACTION)

    override fun read(intent: Intent): Reading {
        val dark = isNightMode(appContext.resources.configuration.uiMode)
        val key = if (dark) DARK else LIGHT
        return Reading(
            payload = mapOf(PAYLOAD_STATE to key),
            stateKey = key,
            emit = dark == onDark,
        )
    }

    companion object {
        const val TYPE = "dark_theme"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val DARK = "dark"
        const val LIGHT = "light"
    }
}

class DarkThemeTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = DarkThemeTrigger.TYPE

    // Night mode exists earlier as a battery-saver side effect, but a
    // user-controlled system dark theme is API 29 and up.
    override val requirements = listOf(ComponentRequirement.MinApiLevel(29))

    override fun create(config: Map<String, String>): Trigger = DarkThemeTrigger(
        context = context,
        onDark = parseTarget(
            config = config,
            key = DarkThemeTrigger.CONFIG_STATE,
            onWord = DarkThemeTrigger.DARK,
            offWord = DarkThemeTrigger.LIGHT,
        ),
    )
}

class OrientationTrigger(
    context: Context,
    private val onLandscape: Boolean,
    now: () -> Long = System::currentTimeMillis,
) : BroadcastTrigger(context, now) {

    override val eventType = TYPE
    override val actions = listOf(CONFIGURATION_ACTION)

    override fun read(intent: Intent): Reading? {
        val landscape = when (appContext.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> true
            Configuration.ORIENTATION_PORTRAIT -> false
            else -> return null
        }

        val key = if (landscape) LANDSCAPE else PORTRAIT
        return Reading(
            payload = mapOf(PAYLOAD_STATE to key),
            stateKey = key,
            emit = landscape == onLandscape,
        )
    }

    companion object {
        const val TYPE = "screen_orientation"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val LANDSCAPE = "landscape"
        const val PORTRAIT = "portrait"
    }
}

class OrientationTriggerFactory(private val context: Context) : TriggerFactory {
    override val type = OrientationTrigger.TYPE

    override fun create(config: Map<String, String>): Trigger = OrientationTrigger(
        context = context,
        onLandscape = parseTarget(
            config = config,
            key = OrientationTrigger.CONFIG_STATE,
            onWord = OrientationTrigger.LANDSCAPE,
            offWord = OrientationTrigger.PORTRAIT,
        ),
    )
}
