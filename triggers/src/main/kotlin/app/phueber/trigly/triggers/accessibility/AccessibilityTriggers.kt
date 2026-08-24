package app.phueber.trigly.triggers.accessibility

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    /**
     * The passive form only exists for [ScreenContentTrigger] — [eventType]
     * distinguishes the two, since this class' whole reason to exist is that
     * click and content-change differ in nothing else. A click is an instant:
     * there is no "is it currently clicked" to ask, so [UiClickTrigger] leaves
     * this at the interface default of null unconditionally, the same as
     * `sms_received` and `interval` in `docs/conditions.md`'s "no passive form"
     * list.
     *
     * For content, the question is "is the configured text on screen right
     * now" — a different question from the edge's "did text just appear",
     * because an event only carries whichever node just changed, while this
     * asks about everything currently visible. That needs the live node tree,
     * which [events] never touches; [AccessibilityService.rootInActiveWindow]
     * is what [ServiceUiController] already reads for the same reason, from
     * the same service reference on [AccessibilityEvents].
     */
    override suspend fun currentlyHolds(): Boolean? {
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return null

        val service = AccessibilityEvents.service ?: return null
        val root = service.rootInActiveWindow ?: return null

        // Not the configured app's window at all, so none of its content can be
        // on screen — a real "no", not "cannot tell", because we did get an
        // answer about what window is actually in front.
        if (packageName != null && root.packageName?.toString() != packageName) return false

        return text.matches(visibleScreenText(root))
    }

    companion object {
        const val PAYLOAD_PACKAGE = "package"
        const val PAYLOAD_TEXT = "text"
        const val PAYLOAD_CLASS = "class"
    }
}

/**
 * Every visible node's text and content description in [root]'s subtree,
 * flattened into one haystack for [TextFilter] to search.
 *
 * A single joined string, not a per-node search, because [ScreenContentTrigger]
 * asks whether the configured text is *anywhere* on screen — reusing
 * [TextFilter.matches] as the one place that decides "matches" keeps this
 * condition and the edge it is the passive form of agreeing by construction,
 * rather than by two hand-written checks staying in sync.
 *
 * [AccessibilityNodeInfo.isVisibleToUser] is what keeps "on screen now" honest:
 * a scrolled-off row or a collapsed section is still in the tree but is not
 * what the user is currently looking at, and folding its text in would answer
 * a question about what could be scrolled into view rather than what is
 * showing right now.
 */
private fun visibleScreenText(root: AccessibilityNodeInfo): String = buildString {
    fun walk(node: AccessibilityNodeInfo) {
        if (node.isVisibleToUser) {
            node.text?.let { append(it).append(' ') }
            node.contentDescription?.let { append(it).append(' ') }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let(::walk)
        }
    }
    // A tree read racing an app tearing down its UI can throw partway through;
    // whatever text was already collected is still honest, just possibly
    // incomplete, which is the same trade `expandRows` in ServiceUiController
    // makes for the same reason.
    runCatching { walk(root) }
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

    override val supportsCondition = true
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
