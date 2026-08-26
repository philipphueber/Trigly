package app.phueber.trigly.triggers

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec

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

    // Reads the same `resources.configuration` the broadcast's Intent is built
    // from, so there is no second source to disagree with the edge. Gated the
    // same as the factory's requirement: below API 29 the mask bit exists but
    // is not user-controlled, so it cannot answer what this trigger is asking.
    override suspend fun currentlyHolds(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return isNightMode(appContext.resources.configuration.uiMode) == onDark
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

    override val displayName = "Dark theme"
    override val category = Category.DEVICE

    override val configFields = listOf(
        stateChoice("Fires when the theme switches to", "dark", "dark", "light", "light"),
    )

    // Night mode exists earlier as a battery-saver side effect, but a
    // user-controlled system dark theme is API 29 and up.
    override val requirements = listOf(ComponentRequirement.MinApiLevel(29))

    override val supportsCondition = true

    override val variables = listOf(
        VariableSpec(
            key = DarkThemeTrigger.PAYLOAD_STATE,
            label = "State",
            kind = VariableKind.STATE,
            sample = DarkThemeTrigger.DARK,
            help = "One of '${DarkThemeTrigger.DARK}' or '${DarkThemeTrigger.LIGHT}'.",
        ),
    )

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

    // Same `resources.configuration` the broadcast reads, so edge and level
    // cannot disagree. Square-ish devices can report neither landscape nor
    // portrait — the same case [read] returns null for above — and there is
    // no orientation to hold or not hold, so this does too.
    override suspend fun currentlyHolds(): Boolean? =
        when (appContext.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> onLandscape
            Configuration.ORIENTATION_PORTRAIT -> !onLandscape
            else -> null
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

    override val displayName = "Screen orientation"
    override val category = Category.DEVICE

    override val configFields = listOf(
        stateChoice("Fires when the screen rotates to", "landscape", "landscape", "portrait", "portrait"),
    )

    override val supportsCondition = true

    override val variables = listOf(
        VariableSpec(
            key = OrientationTrigger.PAYLOAD_STATE,
            label = "State",
            kind = VariableKind.STATE,
            sample = OrientationTrigger.LANDSCAPE,
            help = "One of '${OrientationTrigger.LANDSCAPE}' or '${OrientationTrigger.PORTRAIT}'.",
        ),
    )

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
