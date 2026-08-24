package app.phueber.trigly.core

/**
 * One node of a rendered accessibility tree, reduced to what pressing something
 * needs.
 *
 * An interface rather than the platform's `AccessibilityNodeInfo` so the part
 * that is easy to get wrong — deciding *which* node a press should land on — is
 * a pure function with tests, instead of something only observable by watching a
 * phone. `:triggers` adapts the real thing to this.
 */
interface UiNode {
    val text: String?
    val contentDescription: String?

    /** Whether the platform will accept a click on this node. */
    val isClickable: Boolean

    val childCount: Int
    fun child(index: Int): UiNode?
    val parent: UiNode?
}

/**
 * Finds the node a press should actually land on, given the label a person can
 * see.
 *
 * **This is the "the button is not clickable" problem, and it is not a bug in the
 * scan.** A notification's action button is usually a `TextView` inside a
 * clickable container: the node carrying the words "BEENDEN" reports
 * `isClickable == false`, and `performAction(ACTION_CLICK)` on it does nothing
 * and returns false — no error, no effect, which reads as "the button does not
 * work" rather than "you clicked the wrong node". The label and the click target
 * are simply different nodes.
 *
 * So: find the node that *says* it, then walk up to the nearest ancestor that
 * will *take* a click. Matching considers `contentDescription` as well as text,
 * because an icon-only button has no text at all and a custom layout may label
 * only one of the two.
 *
 * Returns null rather than guessing when the label is nowhere, or when nothing
 * above it is clickable. A press that lands on an arbitrary nearby node is worse
 * than a reported failure — on a notification, the node above an action button is
 * frequently the notification itself, and clicking that opens the app.
 */
fun findPressTarget(root: UiNode, label: String): UiNode? {
    val wanted = label.trim()
    if (wanted.isEmpty()) return null

    val labelled = findLabelled(root, wanted) ?: return null

    var node: UiNode? = labelled
    while (node != null) {
        if (node.isClickable) return node
        node = node.parent
    }
    return null
}

/**
 * Depth-first, and deliberately not breadth-first: a shade holds several
 * notifications, and the deepest match under the first subtree that contains the
 * label is the button itself rather than a container that happens to summarise
 * its contents. Exact match on the trimmed label, case-insensitively, because
 * OEMs upper-case button text in the layout rather than in the string.
 */
private fun findLabelled(node: UiNode, wanted: String): UiNode? {
    if (node.text?.trim().equals(wanted, ignoreCase = true) ||
        node.contentDescription?.trim().equals(wanted, ignoreCase = true)
    ) {
        return node
    }

    for (index in 0 until node.childCount) {
        val child = node.child(index) ?: continue
        findLabelled(child, wanted)?.let { return it }
    }
    return null
}

/**
 * Whether pressing through the rendered shade can work at all right now.
 *
 * A locked phone is the case this exists for, and the answer is not simply "no".
 *
 * With a **secure** lock — a PIN, pattern, password or biometric — it is no, and
 * refusing immediately is the honest outcome. Three separate things stop it, any
 * one of which is enough: lock-screen privacy can redact a notification, so the
 * label is not drawn at all; action buttons are frequently not rendered on the
 * keyguard even when the notification is; and where they are, firing one demands
 * authentication first. A rule cannot answer that prompt —
 * `KeyguardManager.requestDismissKeyguard` needs an `Activity`, and a rule fired
 * by the engine has none — so the best case is a shade opened in front of a locked
 * phone, an unlock prompt nobody asked for, and no press.
 *
 * With **no** secure lock, the keyguard is a swipe with nothing behind it, the
 * shade opens over it, and the press has a real chance. So that case is attempted
 * rather than pre-refused: reporting "locked" to someone whose phone has no lock
 * set would be wrong, and wrong in the direction of a feature that appears not to
 * work.
 *
 * Pure, because the interesting part is this rule and not the two framework
 * calls that feed it.
 */
fun canPressThroughShade(keyguardLocked: Boolean, deviceSecure: Boolean): Boolean =
    !(keyguardLocked && deviceSecure)

/**
 * Presses things the notification API cannot reach.
 *
 * The port exists for exactly one job: a notification whose visible buttons are
 * **not** in `Notification.actions`. That happens when an app builds its own
 * layout with `RemoteViews` instead of adding actions through
 * `Notification.Builder` — the buttons are real and tappable on screen, and the
 * system exposes no `PendingIntent` for them at all, so
 * `NotificationController.triggerActionButton` has nothing to send.
 *
 * Reaching those means going through the rendered shade, which is what the
 * accessibility service can do and nothing else can. It is a worse mechanism in
 * every respect — it opens the notification shade in front of the user, it
 * depends on how an OEM lays the shade out, and it can be defeated by a custom
 * layout that labels nothing — which is why it is opt-in per rule and never the
 * first thing tried.
 */
interface UiController {

    /** Whether the accessibility service is currently bound. */
    val isConnected: Boolean

    /**
     * Opens the notification shade, presses the button showing [label], and
     * closes the shade again.
     *
     * @param packageName the app whose notification to look in, or null to
     *   search the whole shade. Narrowing matters when two apps use the same
     *   button word.
     */
    suspend fun pressNotificationButton(packageName: String?, label: String): ActionResult

    /**
     * No-op implementation, for assembling the app without the service — and for
     * every rule that has not opted in. Reports a clear failure rather than
     * pretending to press something.
     */
    companion object Unavailable : UiController {
        override val isConnected: Boolean = false

        override suspend fun pressNotificationButton(
            packageName: String?,
            label: String,
        ): ActionResult = ActionResult.Failure(
            "accessibility access is not available, so the screen cannot be used " +
                "to press '$label'"
        )
    }
}
