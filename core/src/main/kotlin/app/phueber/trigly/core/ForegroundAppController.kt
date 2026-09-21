package app.phueber.trigly.core

/**
 * Whether a global screen action can do anything right now.
 *
 * A locked phone is the case this exists for. The answer is not simply "no".
 *
 * With a **secure** lock, which is a PIN, a pattern, a password or a
 * biometric, the keyguard is a window in front of everything. It takes the
 * Home key itself. Android still reports the global action as accepted, so a
 * caller that trusts that return value reports a success that did not happen.
 * A caller asks this first and refuses instead.
 *
 * With **no** secure lock, the keyguard is a swipe with nothing behind it. A
 * global action reaches the window behind it, so that case is tried. A refusal
 * there would be wrong for a person who set no lock, and wrong in the
 * direction of a feature that looks broken.
 *
 * Pure, so the rule is unit-tested instead of inferred from a phone.
 * [canPressThroughShade] is the same rule for a different caller, and it
 * delegates here so the rule lives in one place.
 */
fun canDriveTheScreen(keyguardLocked: Boolean, deviceSecure: Boolean): Boolean =
    !(keyguardLocked && deviceSecure)

/**
 * Which app is in front, and one way to put it behind.
 *
 * **Why this is a port.** The accessibility service lives in `:triggers`. The
 * action that wants it lives in `:actions`, and `:actions` must never depend on
 * `:triggers`. So `:core` declares this, `:triggers` implements it over the
 * live service, and `:ui` wires the two together. That is the path
 * [NotificationController] and [UiController] already took, and
 * `docs/actions.md` records it as the path for the next action that needs a
 * service.
 *
 * **Why it is not two more methods on [UiController].** That port has one job:
 * press a notification button that the notification API cannot reach. It takes
 * one intent-shaped request and exposes no nodes, on purpose. This port answers
 * a different question about a different thing. It says what is on screen now,
 * and it sends that away. Two narrow ports each say what they are for. One wide
 * "screen port" would say only that something touches the screen, which is the
 * fact this project most wants to keep narrow.
 *
 * Both ports read the same bound service, and both report [isConnected] from
 * it. `ControllerLivenessProbe` keeps reading [UiController] for the
 * accessibility service. Two probes of one service could disagree, and one
 * answer is enough.
 *
 * Every method returns a value rather than throwing. A service the user has not
 * turned on is ordinary traffic here, not an exception.
 */
interface ForegroundAppController {

    /** Whether the accessibility service is bound right now. */
    val isConnected: Boolean

    /**
     * The package of the app whose window is in front, or null when Trigly
     * cannot tell.
     *
     * Null is "cannot tell", never "no app". It has three causes. The service
     * is not bound. The screen is off, so there is no window to read. Or the
     * front window names no package. A caller asks [isConnected] first, which
     * separates the first cause from the other two.
     *
     * **A locked phone answers with the keyguard, not with the app behind it.**
     * The keyguard is a real window in front of everything. So a caller that
     * compares this value against a chosen app correctly finds that the app is
     * not in front, and does nothing. That is the right outcome: an app behind
     * the keyguard is already out of sight.
     *
     * **Split screen has no single answer.** Two apps are in front at once, and
     * this reports one of them. A rule that names the other one sees "not in
     * front" and does nothing, which is honest but is not what the person
     * wanted. Written down here because no caller can work around it.
     */
    fun foregroundPackage(): String?

    /**
     * Sends whatever is in front to the background, with the Home key.
     *
     * `performGlobalAction(GLOBAL_ACTION_HOME)` is the whole mechanism. It does
     * what the Home gesture does.
     *
     * **It does not stop the app.** The app keeps running and keeps its place.
     * Android gives a third-party app no way to stop another app.
     * `killBackgroundProcesses` needs a restricted permission, and the system
     * starts again what it kills. A force stop is a settings screen that only
     * the user can drive. So "soft" is the whole of what is on offer, and the
     * help text of anything built on this must say so.
     *
     * **It cannot name an app.** It acts on whatever is in front. A caller that
     * wants one named app sent back must read [foregroundPackage] first, and
     * stop when the answer is a different app.
     *
     * Refused while the phone is locked with a secure lock. See
     * [canDriveTheScreen] for why that refusal is better than the success
     * Android would report.
     */
    fun goHome(): ActionResult

    /**
     * No-op implementation, for assembling the app before or without the
     * service. Reports a clear failure rather than pretending to act.
     */
    companion object Unavailable : ForegroundAppController {
        override val isConnected: Boolean = false

        override fun foregroundPackage(): String? = null

        override fun goHome(): ActionResult = ActionResult.Failure(
            "Accessibility access is not available. Trigly cannot send an app " +
                "to the background."
        )
    }
}
