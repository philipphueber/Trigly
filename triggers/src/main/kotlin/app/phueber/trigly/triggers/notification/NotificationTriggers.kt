package app.phueber.trigly.triggers.notification

import android.app.NotificationManager
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.triggers.parseTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private val NOTIFICATION_ACCESS = listOf(
    ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_LISTENER),
)

/**
 * Pure so the matching rules are unit-tested rather than inferred from a device.
 *
 * A null filter means "don't care". Text matching is case-insensitive and spans
 * title and body, because users think of a notification as one piece of text.
 */
fun matchesNotification(
    notification: PostedNotification,
    packageName: String?,
    textContains: String?,
    includeOngoing: Boolean,
): Boolean {
    if (!includeOngoing && notification.ongoing) return false
    if (packageName != null && notification.packageName != packageName) return false

    if (textContains != null) {
        val haystack = "${notification.title.orEmpty()} ${notification.text.orEmpty()}"
        if (!haystack.contains(textContains, ignoreCase = true)) return false
    }
    return true
}

/** Fires when a notification is posted, optionally narrowed by app and text. */
class NotificationPostedTrigger(
    private val packageName: String?,
    private val textContains: String?,
    private val includeOngoing: Boolean,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = NotificationEvents.posted.events
        .filter { matchesNotification(it, packageName, textContains, includeOngoing) }
        .map { posted ->
            TriggerEvent(
                triggerType = TYPE,
                firedAtMillis = posted.postedAtMillis,
                payload = buildMap {
                    // Carried so the dismiss and button actions can target this
                    // exact notification; it cannot be known ahead of time.
                    put(SharedPayloadKeys.NOTIFICATION_KEY, posted.key)
                    put(PAYLOAD_PACKAGE, posted.packageName)
                    posted.title?.let { put(PAYLOAD_TITLE, it) }
                    posted.text?.let { put(PAYLOAD_TEXT, it) }
                },
            )
        }

    companion object {
        const val TYPE = "notification_posted"
        const val CONFIG_PACKAGE = "package"
        const val CONFIG_TEXT_CONTAINS = "textContains"
        const val CONFIG_INCLUDE_ONGOING = "includeOngoing"
        const val PAYLOAD_PACKAGE = "package"
        const val PAYLOAD_TITLE = "title"
        const val PAYLOAD_TEXT = "text"
    }
}

class NotificationPostedTriggerFactory : TriggerFactory {
    override val type = NotificationPostedTrigger.TYPE
    override val requirements = NOTIFICATION_ACCESS

    override fun create(config: Map<String, String>): Trigger = NotificationPostedTrigger(
        packageName = config[NotificationPostedTrigger.CONFIG_PACKAGE],
        textContains = config[NotificationPostedTrigger.CONFIG_TEXT_CONTAINS],
        includeOngoing =
            config[NotificationPostedTrigger.CONFIG_INCLUDE_ONGOING]?.toBoolean() ?: false,
    )
}

/**
 * True for every Do Not Disturb mode. "Alarms only" and "priority only" are
 * both DND as a user understands it; only [NotificationManager.INTERRUPTION_FILTER_ALL]
 * means DND is off.
 */
fun isDndOn(interruptionFilter: Int): Boolean = when (interruptionFilter) {
    NotificationManager.INTERRUPTION_FILTER_PRIORITY,
    NotificationManager.INTERRUPTION_FILTER_NONE,
    NotificationManager.INTERRUPTION_FILTER_ALARMS,
    -> true

    else -> false
}

/**
 * Fires when Do Not Disturb is switched on or off.
 *
 * Reads a state rather than an event stream, so the first value — the filter as
 * it already is when the rule starts — is dropped. Otherwise enabling a rule
 * while DND is on would fire it immediately.
 */
class DndModeTrigger(private val onDnd: Boolean) : Trigger {

    override fun events(): Flow<TriggerEvent> = NotificationEvents.interruptionFilter
        .filter { it != NotificationEvents.FILTER_UNKNOWN }
        .map(::isDndOn)
        .distinctUntilChanged()
        .drop(1)
        .filter { it == onDnd }
        .map { dnd ->
            TriggerEvent(
                triggerType = TYPE,
                firedAtMillis = System.currentTimeMillis(),
                payload = mapOf(PAYLOAD_STATE to if (dnd) ON else OFF),
            )
        }

    companion object {
        const val TYPE = "dnd_mode"
        const val CONFIG_STATE = "state"
        const val PAYLOAD_STATE = "state"
        const val ON = "on"
        const val OFF = "off"
    }
}

class DndModeTriggerFactory : TriggerFactory {
    override val type = DndModeTrigger.TYPE
    override val requirements = NOTIFICATION_ACCESS

    override fun create(config: Map<String, String>): Trigger = DndModeTrigger(
        onDnd = parseTarget(
            config = config,
            key = DndModeTrigger.CONFIG_STATE,
            onWord = DndModeTrigger.ON,
            offWord = DndModeTrigger.OFF,
        ),
    )
}
