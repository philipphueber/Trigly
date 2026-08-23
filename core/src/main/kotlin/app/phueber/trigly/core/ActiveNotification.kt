package app.phueber.trigly.core

/**
 * One button on a posted notification, flattened free of Android types.
 *
 * Three ways to say which button, in descending order of durability, which is
 * exactly why all three are carried:
 *
 *  · [semanticAction] is what the button *means* — reply, archive, mark as read.
 *    It survives translation and reordering, and is the only identifier that
 *    does. Available from API 28, and null below that or when the app declared
 *    nothing.
 *  · [label] is what the button says. Survives reordering; breaks if the app
 *    relabels it or the phone changes language.
 *  · [index] survives nothing meaningful, and is kept only so a rule saved when
 *    it was the sole option still works.
 *
 * [takesText] is a warning rather than a detail. A reply button carries a
 * `RemoteInput`, and firing its intent with no text attached does not send a
 * reply — it does nothing, or opens the app. An automation that reports success
 * while doing nothing is the failure this project is most hostile to, so the
 * editor marks these and the action refuses them.
 */
data class NotificationButton(
    val index: Int,
    val label: String?,
    val semanticAction: Int? = null,
    val takesText: Boolean = false,
)

/**
 * A notification currently on screen, as the editor's picker and the acting
 * action both see it.
 *
 * Deliberately a flattened snapshot rather than a `StatusBarNotification`: this
 * crosses from `:triggers` (which owns the listener service) through `:core` to
 * `:ui` and `:actions`, none of which may see the others. Same reasoning as
 * [ComponentDescriptor] being a snapshot rather than a live factory.
 */
data class ActiveNotification(
    val key: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAtMillis: Long,
    val buttons: List<NotificationButton>,
)

/**
 * Which notification an action should act on.
 *
 * **The target is not always the notification that fired the rule**, which is
 * the whole reason this takes a package at all. "When I connect to the car,
 * press play on the music notification" has a Bluetooth trigger and a media
 * target; an action that could only ever act on its own trigger's notification
 * could not express it.
 *
 * A package is matched to the *most recently posted* notification from that app,
 * because an app with several live notifications almost always means the newest
 * one — and because picking arbitrarily would make the rule behave differently
 * on different days.
 *
 * With no package configured it falls back to [triggeringKey], which is the
 * common case and stays the default: "when my bank app notifies me, dismiss it".
 */
fun chooseNotification(
    active: List<ActiveNotification>,
    wantedPackage: String?,
    triggeringKey: String?,
): ActiveNotification? {
    if (!wantedPackage.isNullOrBlank()) {
        return active
            .filter { it.packageName == wantedPackage }
            .maxByOrNull { it.postedAtMillis }
    }
    return triggeringKey?.let { key -> active.firstOrNull { it.key == key } }
}

/**
 * Which button to press, preferring the identifier that survives the most.
 *
 * Semantic action first, then label, then the stored index. The order is the
 * point: an app that translates its buttons breaks label matching, an app that
 * reorders them breaks index matching, and a rule should keep working through as
 * many of those as it can.
 *
 * Returns null rather than guessing when nothing matches. A rule that presses
 * *some* button because the one it wanted is gone is worse than a rule that
 * reports it could not find it.
 */
fun chooseButton(
    buttons: List<NotificationButton>,
    wantedSemantic: Int?,
    wantedLabel: String?,
    storedIndex: Int?,
): NotificationButton? {
    if (wantedSemantic != null && wantedSemantic != SEMANTIC_ACTION_NONE) {
        buttons.firstOrNull { it.semanticAction == wantedSemantic }?.let { return it }
    }
    if (!wantedLabel.isNullOrBlank()) {
        buttons.firstOrNull { it.label.equals(wantedLabel, ignoreCase = true) }?.let { return it }
    }
    // Only when neither of the durable identifiers was stored *or* matched. A
    // rule written before this action knew about labels has nothing else.
    return storedIndex?.let { buttons.getOrNull(it) }
}

/**
 * `Notification.Action.SEMANTIC_ACTION_NONE`, named here so `:core` and
 * `:actions` can reason about it without importing an Android class — and so
 * "the app declared no meaning" is never mistaken for a meaning to match on.
 */
const val SEMANTIC_ACTION_NONE = 0
