package app.phueber.trigly.triggers.notification

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentTool
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.triggers.Category
import app.phueber.trigly.triggers.packageFilter
import app.phueber.trigly.triggers.stateChoice
import app.phueber.trigly.core.notificationHaystack
import app.phueber.trigly.triggers.textFilter
import app.phueber.trigly.core.SharedPayloadKeys
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
import kotlinx.coroutines.flow.map

private val NOTIFICATION_ACCESS = listOf(
    ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_LISTENER),
)

/**
 * Pure so the matching rules are unit-tested rather than inferred from a device.
 *
 * A null package and an empty [text] filter mean "don't care". Matching spans
 * title and body joined, because users think of a notification as one piece of
 * text — which also means a regex can straddle the two, and `^` anchors to the
 * start of the title rather than the start of the body.
 */
fun matchesNotification(
    notification: PostedNotification,
    packageName: String?,
    text: TextFilter,
    includeOngoing: Boolean,
): Boolean {
    if (!includeOngoing && notification.ongoing) return false
    if (packageName != null && notification.packageName != packageName) return false

    if (!text.isEmpty) {
        val haystack = notificationHaystack(notification.title, notification.text)
        if (!text.matches(haystack)) return false
    }
    return true
}

/**
 * Turns a live [StatusBarNotification] into the shape [matchesNotification]
 * already knows how to test, so the posted-notification condition below asks
 * exactly the question the edge asks through one matcher rather than two
 * definitions of "matches" that could drift apart.
 */
private fun StatusBarNotification.toPostedNotification(): PostedNotification {
    val extras = notification?.extras
    return PostedNotification(
        key = key,
        packageName = packageName,
        title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        postedAtMillis = postTime,
        ongoing = isOngoing,
    )
}

/** Fires when a notification is posted, optionally narrowed by app and text. */
class NotificationPostedTrigger(
    private val packageName: String?,
    private val text: TextFilter,
    private val includeOngoing: Boolean,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = NotificationEvents.posted.events
        .filter { matchesNotification(it, packageName, text, includeOngoing) }
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

    /**
     * As an edge this is "a notification arrived"; as a condition it is "one from
     * that app is currently showing" — a different question, see
     * `docs/conditions.md`. Reads the same live list the notification actions act
     * on and runs it through [matchesNotification], so this and [events] cannot
     * disagree about what counts as a match.
     *
     * An empty active-notification list is ambiguous: it means either nothing is
     * posted, or the listener has no access to tell. [NotificationEvents.service]
     * being unbound is what distinguishes the two, and only the second answers
     * null — reporting false there would make the condition fail closed on a
     * revoked permission with nothing on screen to say why.
     */
    override suspend fun currentlyHolds(): Boolean? {
        val service = NotificationEvents.service ?: return null
        val active = runCatching { service.activeNotifications }.getOrNull() ?: return null

        return active.any { sbn ->
            matchesNotification(sbn.toPostedNotification(), packageName, text, includeOngoing)
        }
    }

    companion object {
        const val TYPE = "notification_posted"
        const val CONFIG_PACKAGE = "package"
        const val CONFIG_TEXT_CONTAINS = "textContains"
        const val CONFIG_TEXT_MODE = "textContainsMode"
        const val CONFIG_INCLUDE_ONGOING = "includeOngoing"
        const val PAYLOAD_PACKAGE = "package"
        const val PAYLOAD_TITLE = "title"
        const val PAYLOAD_TEXT = "text"
    }
}

class NotificationPostedTriggerFactory : TriggerFactory {
    override val type = NotificationPostedTrigger.TYPE

    override val displayName = "Notification appears"
    override val category = Category.NOTIFICATIONS
    override val supportsCondition = true

    override val configFields = listOf(
        packageFilter(help = "Which app's notifications should fire this rule."),
        textFilter(
            key = NotificationPostedTrigger.CONFIG_TEXT_CONTAINS,
            label = "Title or text contains",
            blankMeaning = "Leave blank to match every notification",
        ),
        ConfigField.Flag(
            key = NotificationPostedTrigger.CONFIG_INCLUDE_ONGOING,
            label = "Include ongoing notifications",
            help = "Progress bars and media controls. Off by default, since these " +
                "update constantly.",
        ),
    )
    override val requirements = NOTIFICATION_ACCESS

    // Its filters are written against what a notification actually contains — a
    // package, a title, a piece of text — which is exactly what nobody can fill
    // in by guessing, and where a wrong guess yields a rule that silently never
    // fires. The inspector is the answer, so it is offered here rather than only
    // from the rule list, where you would have to know it exists.
    override fun toolsFor(config: Map<String, String>): List<ComponentTool> =
        listOf(ComponentTool.InspectNotifications)

    override fun create(config: Map<String, String>): Trigger = NotificationPostedTrigger(
        packageName = config[NotificationPostedTrigger.CONFIG_PACKAGE],
        text = TextFilter.fromConfig(
            config[NotificationPostedTrigger.CONFIG_TEXT_CONTAINS],
            config[NotificationPostedTrigger.CONFIG_TEXT_MODE],
        ),
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

    /**
     * DND is a real state, not just an edge — [NotificationEvents.interruptionFilter]
     * already tracks the current value for exactly this reason. [FILTER_UNKNOWN]
     * is the value before the listener has ever connected (see its doc), and that
     * is not "DND off": it is "no idea yet", so it answers null rather than a
     * guess.
     */
    override suspend fun currentlyHolds(): Boolean? {
        val filter = NotificationEvents.interruptionFilter.value
        if (filter == NotificationEvents.FILTER_UNKNOWN) return null
        return isDndOn(filter) == onDnd
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

    override val displayName = "Do Not Disturb"
    override val category = Category.NOTIFICATIONS
    override val supportsCondition = true

    override val configFields = listOf(
        stateChoice("Fires when Do Not Disturb is", "on", "switched on", "off", "switched off"),
    )
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
