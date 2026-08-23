package app.phueber.trigly.triggers.accessibility

import android.view.accessibility.AccessibilityEvent
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.triggers.Category
import app.phueber.trigly.triggers.packageFilter
import app.phueber.trigly.triggers.stateChoice
import app.phueber.trigly.triggers.textFilter
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.triggers.parseTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

private val ACCESSIBILITY_ACCESS = listOf(
    ComponentRequirement.SpecialAccess(SpecialAccessKind.ACCESSIBILITY_SERVICE),
)

/** Pure, so the matching rules are unit-tested rather than inferred from a device. */
fun matchesUiEvent(
    event: UiEvent,
    eventType: Int,
    packageName: String?,
    text: TextFilter,
): Boolean {
    if (event.eventType != eventType) return false
    if (packageName != null && event.packageName != packageName) return false
    if (!text.matches(event.text)) return false
    return true
}

/**
 * Shared body of the two content triggers: they differ only in which
 * accessibility event type they care about.
 */
private class UiEventTrigger(
    private val type: String,
    private val eventType: Int,
    private val packageName: String?,
    private val text: TextFilter,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = AccessibilityEvents.ui.events
        .filter { matchesUiEvent(it, eventType, packageName, text) }
        .map { event ->
            TriggerEvent(
                triggerType = type,
                firedAtMillis = event.atMillis,
                payload = buildMap {
                    event.packageName?.let { put(PAYLOAD_PACKAGE, it) }
                    event.text?.let { put(PAYLOAD_TEXT, it) }
                    event.className?.let { put(PAYLOAD_CLASS, it) }
                },
            )
        }

    companion object {
        const val PAYLOAD_PACKAGE = "package"
        const val PAYLOAD_TEXT = "text"
        const val PAYLOAD_CLASS = "class"
    }
}

/** Fires when the user taps something, anywhere on the device. */
object UiClickTrigger {
    const val TYPE = "ui_click"
    const val CONFIG_PACKAGE = "package"
    const val CONFIG_TEXT_CONTAINS = "textContains"
    const val CONFIG_TEXT_MODE = "textContainsMode"
}

class UiClickTriggerFactory : TriggerFactory {
    override val type = UiClickTrigger.TYPE

    override val displayName = "Something is tapped"
    override val category = Category.SCREEN

    override val configFields = listOf(
        packageFilter(help = "Which app's taps to watch."),
        textFilter(
            key = UiClickTrigger.CONFIG_TEXT_CONTAINS,
            label = "Tapped item's text contains",
            blankMeaning = "Leave blank for any tap",
        ),
    )
    override val requirements = ACCESSIBILITY_ACCESS

    override fun create(config: Map<String, String>): Trigger = UiEventTrigger(
        type = UiClickTrigger.TYPE,
        eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
        packageName = config[UiClickTrigger.CONFIG_PACKAGE],
        text = TextFilter.fromConfig(
            config[UiClickTrigger.CONFIG_TEXT_CONTAINS],
            config[UiClickTrigger.CONFIG_TEXT_MODE],
        ),
    )
}

/**
 * Fires when on-screen content changes and matches.
 *
 * This is the noisiest event Android emits — an animating progress bar produces
 * a continuous stream. A `textContains` filter is effectively mandatory; without
 * one the rule fires constantly and drains the battery. The UI should require it.
 */
object ScreenContentTrigger {
    const val TYPE = "screen_content"
    const val CONFIG_PACKAGE = "package"
    const val CONFIG_TEXT_CONTAINS = "textContains"
    const val CONFIG_TEXT_MODE = "textContainsMode"
}

class ScreenContentTriggerFactory : TriggerFactory {
    override val type = ScreenContentTrigger.TYPE

    override val displayName = "Text appears on screen"
    override val category = Category.SCREEN

    override val configFields = listOf(
        packageFilter(help = "Which app's screen to watch."),
        textFilter(
            key = ScreenContentTrigger.CONFIG_TEXT_CONTAINS,
            label = "Screen contains",
            required = true,
            help = "Strongly recommended. Without it this fires on every visual " +
                "change, including animations.",
        ),
    )

    override val warning: String =
        "The noisiest trigger available. An animating progress bar produces a " +
            "continuous stream of events, so always set a text filter."
    override val requirements = ACCESSIBILITY_ACCESS

    override fun create(config: Map<String, String>): Trigger = UiEventTrigger(
        type = ScreenContentTrigger.TYPE,
        eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        packageName = config[ScreenContentTrigger.CONFIG_PACKAGE],
        text = TextFilter.fromConfig(
            config[ScreenContentTrigger.CONFIG_TEXT_CONTAINS],
            config[ScreenContentTrigger.CONFIG_TEXT_MODE],
        ),
    )
}

/**
 * Fires when the soft keyboard opens or closes.
 *
 * Best effort — Android has no API for this, so it is inferred from the presence
 * of an input-method window. Unknown readings are dropped rather than guessed,
 * and the state as it already is when the rule starts is not an event.
 */
class KeyboardVisibilityTrigger(private val onOpened: Boolean) : Trigger {

    override fun events(): Flow<TriggerEvent> = AccessibilityEvents.keyboardVisible
        .filterNotNull()
        .distinctUntilChanged()
        .drop(1)
        .filter { it == onOpened }
        .map { visible ->
            TriggerEvent(
                triggerType = TYPE,
                firedAtMillis = System.currentTimeMillis(),
                payload = mapOf(PAYLOAD_STATE to if (visible) OPENED else CLOSED),
            )
        }

    companion object {
        const val TYPE = "keyboard_visibility"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val OPENED = "opened"
        const val CLOSED = "closed"
    }
}

class KeyboardVisibilityTriggerFactory : TriggerFactory {
    override val type = KeyboardVisibilityTrigger.TYPE

    override val displayName = "Keyboard opens or closes"
    override val category = Category.SCREEN

    override val configFields = listOf(
        stateChoice("Fires when the keyboard is", "opened", "opened", "closed", "closed"),
    )

    override val warning: String =
        "Android has no keyboard-visibility API, so this is inferred and is not " +
            "reliable across every keyboard and phone."
    override val requirements = ACCESSIBILITY_ACCESS

    override fun create(config: Map<String, String>): Trigger = KeyboardVisibilityTrigger(
        onOpened = parseTarget(
            config = config,
            key = KeyboardVisibilityTrigger.CONFIG_STATE,
            onWord = KeyboardVisibilityTrigger.OPENED,
            offWord = KeyboardVisibilityTrigger.CLOSED,
        ),
    )
}
