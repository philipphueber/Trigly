package app.phueber.trigly.triggers.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityWindowInfo
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ForegroundAppController
import app.phueber.trigly.core.canDriveTheScreen

/**
 * Implements [ForegroundAppController] over the live accessibility service.
 *
 * Holds no reference of its own. It reads the current service from
 * [AccessibilityEvents] on every call, the same as [ServiceUiController], so it
 * stays correct across the unbind and rebind cycles the framework puts the
 * service through.
 *
 * **The front app is read from the window list, not from an event.** The event
 * bus carries edges: a `TYPE_WINDOW_STATE_CHANGED` says an app came forward at
 * one moment. An action runs at a different moment, so an edge cannot answer
 * "what is in front now". The live window list can, and it is the source
 * `UiEventTrigger.currentlyHolds` already reads for the same reason. Adding a
 * remembered "last app seen" would be a second source of one fact, and the two
 * would disagree after every process restart.
 */
class ServiceForegroundAppController : ForegroundAppController {

    override val isConnected: Boolean get() = AccessibilityEvents.service != null

    override fun foregroundPackage(): String? {
        val service = AccessibilityEvents.service ?: return null
        return activeApplicationPackage(service) ?: rootPackage(service)
    }

    override fun goHome(): ActionResult {
        val service = AccessibilityEvents.service
            ?: return ActionResult.Failure(
                "Accessibility access is not granted, or the service is not bound yet."
            )

        // Asked before the key is sent. On a locked phone the keyguard takes
        // the Home key and performGlobalAction still reports success, so a
        // caller that trusts the return value reports something that did not
        // happen. See `canDriveTheScreen` for why a phone with no secure lock
        // is still worth trying.
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        if (keyguard != null &&
            !canDriveTheScreen(keyguard.isKeyguardLocked, keyguard.isDeviceSecure)
        ) {
            return ActionResult.Failure(
                "The phone is locked. The lock screen takes the Home key, and " +
                    "Android reports the key as sent anyway. An app behind the " +
                    "lock screen is already out of sight."
            )
        }

        return if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
            ActionResult.Success()
        } else {
            ActionResult.Failure("Android refused the Home action.")
        }
    }

    /**
     * The package of the application window that has input focus.
     *
     * Filtered to `TYPE_APPLICATION` first, and that filter is the point. The
     * keyboard is its own window and so is the notification shade, and either
     * one can hold focus while an app is plainly the app in front. A read that
     * did not filter would name the keyboard's package while somebody typed.
     *
     * `flagRetrieveInteractiveWindows` is what makes the window list readable,
     * and the service config already sets it.
     *
     * Three steps down, because focus is not always where it looks. `isActive`
     * is the window the system treats as current. `isFocused` is the next best
     * answer when another window type holds input focus. The first application
     * window is the last resort, and it is also the split-screen case: two apps
     * are in front and there is no single right answer.
     */
    private fun activeApplicationPackage(service: AccessibilityService): String? =
        runCatching {
            val applications = service.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

            val chosen = applications.firstOrNull { it.isActive }
                ?: applications.firstOrNull { it.isFocused }
                ?: applications.firstOrNull()

            chosen?.root?.packageName?.toString()
        }.getOrNull()

    /**
     * The fallback, for a device or a moment that lists no application window.
     *
     * `rootInActiveWindow` can name the keyboard or the shade, which is why it
     * is second and not first. It is still better than nothing: it answers on a
     * build where the window list comes back empty.
     */
    private fun rootPackage(service: AccessibilityService): String? =
        runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()
}
