package app.phueber.trigly.triggers.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.UiController
import app.phueber.trigly.core.UiNode
import app.phueber.trigly.core.canPressThroughShade
import app.phueber.trigly.core.findPressTarget
import kotlinx.coroutines.delay

/**
 * Implements [UiController] over the live accessibility service.
 *
 * Everything here exists because of one case: a notification whose visible
 * buttons are not in `Notification.actions`, because the app drew them itself
 * with `RemoteViews`. The system exposes no `PendingIntent` for those, so the
 * notification listener has nothing to send and the only remaining route is the
 * rendered shade.
 *
 * Four things make that scan fail, and each is handled deliberately rather than
 * by retrying:
 *
 *  1. **Wrong window.** Notification buttons live in a SystemUI window, not the
 *     foreground app's, so `rootInActiveWindow` never contains them. This
 *     iterates [AccessibilityService.getWindows] instead, which needs
 *     `flagRetrieveInteractiveWindows` — already set in the service config.
 *  2. **Collapsed notifications have no buttons yet.** The action nodes are not
 *     instantiated until the notification is expanded, so a matching row is sent
 *     `ACTION_EXPAND` before its subtree is searched.
 *  3. **The label is not the click target.** The node carrying the words is
 *     usually a non-clickable `TextView` inside a clickable container; clicking
 *     it silently does nothing. [findPressTarget] walks up to the nearest
 *     clickable ancestor.
 *  4. **Package filtering.** These nodes belong to `com.android.systemui`, not to
 *     the app that posted the notification, so filtering the tree by the target
 *     package finds nothing. The package is used to narrow *which row* to search
 *     — by the app's own label appearing in it — never to filter nodes.
 *
 * Holds no reference of its own; it reads the current service from
 * [AccessibilityEvents] on every call, so it stays correct across the unbind and
 * rebind cycles the framework puts the service through.
 */
class ServiceUiController : UiController {

    override val isConnected: Boolean get() = AccessibilityEvents.service != null

    override suspend fun pressNotificationButton(
        packageName: String?,
        label: String,
    ): ActionResult {
        val service = AccessibilityEvents.service
            ?: return ActionResult.Failure(
                "Accessibility access is not granted, or the service is not bound yet."
            )

        if (label.isBlank()) {
            return ActionResult.Failure("There is no button name to look for.")
        }

        // Checked before the shade is touched, because on a locked phone the
        // alternative is to open it, fail, and leave an unlock prompt on screen
        // that nobody asked for. See `canPressThroughShade` for why a phone with
        // no secure lock is still worth trying.
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        if (keyguard != null &&
            !canPressThroughShade(keyguard.isKeyguardLocked, keyguard.isDeviceSecure)
        ) {
            return ActionResult.Failure(
                "The screen is locked. Pressing a button through the shade needs " +
                    "an unlocked phone, and a rule cannot answer the unlock " +
                    "prompt. Buttons the app exposes properly do not have this limit."
            )
        }

        // Opening the shade is a visible side effect, and the reason this whole
        // path is opt-in per rule rather than a silent fallback.
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) {
            return ActionResult.Failure("Trigly could not open the notification shade.")
        }

        return try {
            // The shade animates in, and a tree read during that returns a
            // partial one. Polled rather than slept once: a fixed wait long
            // enough for a slow device is a wait everyone pays.
            val target = awaitPressTarget(service, packageName, label)
                ?: return ActionResult.Failure(
                    "There is no button called '$label' in the notification shade" +
                        (packageName?.let { " for $it" } ?: "") +
                        ". Custom notification layouts sometimes label nothing " +
                        "the screen reader can see. In that case, this cannot work."
                )

            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                ActionResult.Success()
            } else {
                // The platform refused a click on a node that said it was
                // clickable. Reported rather than retried: it means the tree
                // moved under us, and pressing again could hit something else.
                ActionResult.Failure("The shade refused the press on '$label'.")
            }
        } finally {
            // Always close it, including on failure. Leaving the shade open
            // because a rule did not find its button is a rule that vandalises
            // the screen.
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private suspend fun awaitPressTarget(
        service: AccessibilityService,
        packageName: String?,
        label: String,
    ): AccessibilityNodeInfo? {
        repeat(SHADE_POLLS) {
            delay(SHADE_POLL_MILLIS)

            val roots = runCatching {
                service.windows
                    // The shade is a system window. Filtering to system windows
                    // rather than naming SystemUI keeps this working on OEM
                    // builds that use a different package for it.
                    .filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
                    .mapNotNull { it.root }
            }.getOrDefault(emptyList())

            for (root in roots) {
                findIn(root, packageName, label)?.let { return it }
            }
        }
        return null
    }

    /**
     * Searches one window, expanding collapsed rows on the way.
     *
     * The expand pass is why this is not a single [findPressTarget] call: a
     * collapsed notification's buttons do not exist as nodes, so a search that
     * only looked would correctly report "not found" for a button the user can
     * see after one tap.
     */
    private fun findIn(
        root: AccessibilityNodeInfo,
        packageName: String?,
        label: String,
    ): AccessibilityNodeInfo? {
        findPressTarget(root.asUiNode(), label)?.let { return (it as AndroidUiNode).node }

        // Nothing yet. Expand what can be expanded and look again — the buttons
        // may not have existed a moment ago.
        val expanded = expandRows(root, packageName)
        if (!expanded) return null

        return findPressTarget(root.asUiNode(), label)?.let { (it as AndroidUiNode).node }
    }

    /**
     * Sends `ACTION_EXPAND` to every row that offers it, and reports whether any
     * accepted.
     *
     * [packageName] narrows it when given: a row is considered the app's if the
     * app's name appears anywhere in it. That is a heuristic and is only used to
     * avoid expanding the whole shade, never to decide what to press — the press
     * target is always chosen by label.
     */
    private fun expandRows(root: AccessibilityNodeInfo, packageName: String?): Boolean {
        var any = false

        fun walk(node: AccessibilityNodeInfo) {
            val canExpand = node.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_EXPAND
            }
            if (canExpand && (packageName == null || mentions(node, packageName))) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_EXPAND)) any = true
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::walk)
            }
        }
        runCatching { walk(root) }
        return any
    }

    /**
     * Whether this subtree looks like it belongs to [packageName].
     *
     * The nodes are SystemUI's, so the package is not on them — what is present
     * is the app's display name, which the shade draws as the notification's
     * header. Matching the last dotted segment of the package against the visible
     * text is crude and knowingly so: it is a filter for *which row to expand*,
     * and being wrong only costs an extra expansion.
     */
    private fun mentions(node: AccessibilityNodeInfo, packageName: String): Boolean {
        val hint = packageName.substringAfterLast('.').lowercase()
        if (hint.isBlank()) return true

        fun contains(candidate: AccessibilityNodeInfo): Boolean {
            val text = candidate.text?.toString()?.lowercase().orEmpty()
            val description = candidate.contentDescription?.toString()?.lowercase().orEmpty()
            if (text.contains(hint) || description.contains(hint)) return true

            for (index in 0 until candidate.childCount) {
                val child = candidate.getChild(index) ?: continue
                if (contains(child)) return true
            }
            return false
        }
        return runCatching { contains(node) }.getOrDefault(false)
    }

    private companion object {
        /**
         * The shade's entrance animation is a few hundred milliseconds, and a
         * tree read during it comes back partial. Six polls of 150 ms covers a
         * slow device without making a fast one wait for it.
         */
        const val SHADE_POLLS = 6
        const val SHADE_POLL_MILLIS = 150L
    }
}

/** Adapts the platform's node to the testable [UiNode] the pure search uses. */
private class AndroidUiNode(val node: AccessibilityNodeInfo) : UiNode {
    override val text: String? get() = node.text?.toString()
    override val contentDescription: String? get() = node.contentDescription?.toString()
    override val isClickable: Boolean get() = node.isClickable
    override val childCount: Int get() = node.childCount
    override fun child(index: Int): UiNode? = node.getChild(index)?.let(::AndroidUiNode)
    override val parent: UiNode? get() = node.parent?.let(::AndroidUiNode)
}

private fun AccessibilityNodeInfo.asUiNode(): UiNode = AndroidUiNode(this)
