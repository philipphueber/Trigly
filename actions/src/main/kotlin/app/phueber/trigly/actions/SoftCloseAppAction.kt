package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ForegroundAppController
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec

/**
 * Sends one chosen app to the background, if that app is the one in front.
 *
 * **What "soft close" means here, and what it does not.** The app is pushed
 * behind the home screen with the Home key, through
 * `performGlobalAction(GLOBAL_ACTION_HOME)`. The app keeps running. It keeps
 * its place, its playback, its unsaved text. This is **not** a force stop and
 * it is **not** `killBackgroundProcesses`. Android gives a third-party app no
 * way to stop another app at all, so there is no stronger version of this to
 * build. A person who expects the app to be stopped reads this as broken, so
 * the field help, the warning and this text all say it in plain words.
 *
 * **Why it acts only on the front app.** The Home key acts on whatever is in
 * front. It cannot name an app. So the action reads which app is in front
 * first, and stops when that is a different app. Pressing Home anyway would
 * send away whatever the person happened to be using, which is the worst thing
 * this action could do.
 *
 * Four outcomes, each said as itself, because each is fixed in a different
 * place.
 *
 *  1. **The accessibility service is off or not bound.** A failure, and it
 *     names the grant. This is the failure mode this project cares most about:
 *     an action that quietly does nothing because a service is off. The factory
 *     also declares the requirement, so the editor says it before a rule ever
 *     runs.
 *  2. **Trigly cannot read what is in front.** A failure. The screen is
 *     usually off. Not folded into outcome 3: "I cannot see" and "I looked, and
 *     it was another app" are different facts and lead to different fixes.
 *  3. **Another app is in front.** A **success** carrying `sentBack = no` and
 *     the package that was in front. Not a failure: the action did the right
 *     thing, which was nothing. A failure here would make every ordinary run of
 *     a rule read as broken. The outputs are what makes the rule trace honest
 *     about which branch ran.
 *  4. **The chosen app is in front.** Home is pressed, and the result carries
 *     `sentBack = yes`.
 *
 * **The locked phone is outcome 3, not a separate case.** The lock screen is a
 * window in front of everything, so a locked phone reports the lock screen as
 * the front app and the chosen app is not in front. That is correct: an app
 * behind the lock screen is already out of sight, and there is nothing to send
 * away. The port refuses a Home press on a securely locked phone as well, for
 * the moment when the phone locks between the read and the press. See
 * `canDriveTheScreen`: Android reports that press as accepted while the lock
 * screen takes the key, so trusting the return value would report a success
 * that did not happen.
 *
 * **Package visibility does not limit this, unlike `open_app`.** Nothing here
 * asks `PackageManager` about the chosen app. The action compares one string
 * against the package of the window in front, and the accessibility service
 * supplies that. So an app with no launcher icon works here, even though the
 * picker cannot list it, and the picker's own manual entry is the way to name
 * it.
 */
class SoftCloseAppAction(
    private val controller: ForegroundAppController,
    private val packageName: String,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        if (!controller.isConnected) {
            return ActionResult.Failure(
                "Trigly cannot see the screen. The accessibility service is off, " +
                    "or Android has not started it yet. Turn on Accessibility " +
                    "access for Trigly. Until then this action does nothing."
            )
        }

        val front = controller.foregroundPackage()
            ?: return ActionResult.Failure(
                "Trigly could not read which app is in front. The screen is " +
                    "probably off."
            )

        if (front != packageName) {
            return ActionResult.Success(
                outputs = mapOf(
                    OUTPUT_SENT_BACK to NO,
                    OUTPUT_FOREGROUND to front,
                ),
            )
        }

        return when (val home = controller.goHome()) {
            is ActionResult.Success -> ActionResult.Success(
                outputs = mapOf(
                    OUTPUT_SENT_BACK to YES,
                    OUTPUT_FOREGROUND to front,
                ),
            )

            // Passed through rather than reworded. The port knows why it
            // refused, and a locked phone is a different fix from a refused
            // global action.
            is ActionResult.Failure -> home
        }
    }

    companion object {
        const val TYPE = "soft_close_app"
        const val CONFIG_PACKAGE = "package"

        /** Whether the app was actually sent back on this run. */
        const val OUTPUT_SENT_BACK = "sentBack"

        /** The package that was in front when this action looked. */
        const val OUTPUT_FOREGROUND = "foreground"

        const val YES = "yes"
        const val NO = "no"
    }
}

class SoftCloseAppActionFactory(
    private val controller: ForegroundAppController,
) : ActionFactory {
    override val type = SoftCloseAppAction.TYPE

    override val displayName = "Send an app to the background"
    override val category = ActionCategory.SCREEN

    override val configFields = listOf(
        ConfigField.AppPackage(
            key = SoftCloseAppAction.CONFIG_PACKAGE,
            label = "App",
            required = true,
            help = "This action works only while you are looking at this app. " +
                "It then goes to the home screen, which is what the Home " +
                "gesture does. The app is not stopped. It keeps running and " +
                "keeps its place. If another app is in front, this action does " +
                "nothing and reports that.",
        ),
    )

    override val requirements = listOf(
        ComponentRequirement.SpecialAccess(SpecialAccessKind.ACCESSIBILITY_SERVICE),
    )

    /**
     * States the limit that reads as a fault. Somebody who builds "close the
     * game at bedtime" expects the game to stop, and it does not. Saying it in
     * the warning puts it where the rule is built, not where the rule fails.
     */
    override val warning: String =
        "Android gives no app a way to stop another app. This action only goes " +
            "to the home screen. The app you choose keeps running. It also does " +
            "nothing unless that app is the one you are looking at."

    /**
     * Two outputs, because both answers are computed here and nothing else
     * could know either one in advance. See `ActionResult.Success.outputs`.
     *
     * [SoftCloseAppAction.OUTPUT_SENT_BACK] is the branch the run took, which
     * is the same shape as `set_rule_enabled` reporting which way a toggle
     * went: the action succeeds either way, and only it knows which way. A
     * later action reads it to announce the result, or a condition reads it to
     * do something else when the app was not in front.
     */
    override val variables = listOf(
        VariableSpec(
            key = SoftCloseAppAction.OUTPUT_SENT_BACK,
            label = "Sent to the background",
            kind = VariableKind.STATE,
            sample = SoftCloseAppAction.YES,
            help = "'${SoftCloseAppAction.YES}' when the app was in front and " +
                "went back. '${SoftCloseAppAction.NO}' when another app was in " +
                "front, so nothing happened.",
        ),
        VariableSpec(
            key = SoftCloseAppAction.OUTPUT_FOREGROUND,
            label = "App in front",
            kind = VariableKind.PACKAGE,
            sample = "com.example.app",
            help = "The app that was in front when this action looked.",
        ),
    )

    /**
     * Trimmed and refused when empty, rather than passed on as a blank string.
     * A blank package matches no window, so the action would report "another
     * app is in front" on every run and look like a working rule that never
     * does anything. The editor validates by calling this and showing what it
     * throws, so a refusal here is seen while the rule is built.
     */
    override fun create(config: Map<String, String>): Action {
        val packageName = config[SoftCloseAppAction.CONFIG_PACKAGE]?.trim().orEmpty()
        require(packageName.isNotEmpty()) {
            "$type needs an app in '${SoftCloseAppAction.CONFIG_PACKAGE}'."
        }
        return SoftCloseAppAction(controller = controller, packageName = packageName)
    }
}
