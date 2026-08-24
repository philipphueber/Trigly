package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.chooseButton
import app.phueber.trigly.core.chooseNotification
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.UiController

private val NOTIFICATION_ACCESS = listOf(
    ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_LISTENER),
)

/**
 * Dismisses another app's notification.
 *
 * **The target is not always the notification that fired the rule**, and until
 * now it had to be. The raw-key text box this action used to carry was rightly
 * removed — a key is minted by the posting app and cannot be typed in advance —
 * but nothing replaced it, which left the action able to dismiss only its own
 * trigger's notification. "When I leave the house, clear the shopping-list
 * reminder" was not expressible at all, and there was no field to suggest
 * otherwise.
 *
 * It now selects the same way [TriggerNotificationButtonAction] does, through
 * [chooseNotification]: an app chosen in the editor means the newest live
 * notification from that app, and no app chosen means the one that fired the
 * rule. That fallback is the common case and stays the default — "when my bank
 * notifies me, dismiss it" needs no configuration.
 */
class DismissNotificationAction(
    private val controller: NotificationController,
    private val targetPackage: String?,
    private val legacyKey: String? = null,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        // A key stored by an old rule still wins: it names one exact
        // notification, which is more specific than anything the selector can
        // express. It will usually be stale — that is why the field is gone — but
        // honouring it keeps such a rule behaving as it did rather than silently
        // retargeting it at something else.
        legacyKey?.takeIf { it.isNotBlank() }?.let { return controller.dismiss(it) }

        // No app chosen: the payload already names the exact notification, so
        // dismiss it without looking anything up. Going through the active list
        // here — as the button action must, because it needs the buttons — would
        // add a way to fail that dismissing by key does not have: a notification
        // that has already gone, or a list read that came back empty, would turn
        // into "nothing to dismiss" instead of a harmless no-op.
        if (targetPackage == null) {
            val triggering = event.payload[SharedPayloadKeys.NOTIFICATION_KEY]
                ?: return ActionResult.Failure(
                    "nothing to dismiss: choose an app, or use this action on a rule " +
                        "triggered by a notification"
                )
            return controller.dismiss(triggering)
        }

        // An app was chosen, so which notification is a question about what is
        // currently on screen.
        val active = controller.activeNotifications()
        if (active.isEmpty() && !controller.isConnected) {
            return ActionResult.Failure(
                "notification access is not granted, or the listener is not bound yet"
            )
        }

        val target = chooseNotification(
            active = active,
            wantedPackage = targetPackage,
            // Not a fallback here: an app was named, and quietly dismissing the
            // trigger's notification instead when that app has nothing showing
            // would be the wrong notification, reported as success.
            triggeringKey = null,
        ) ?: return ActionResult.Failure("no notification from '$targetPackage' is showing")

        return controller.dismiss(target.key)
    }

    companion object {
        const val TYPE = "dismiss_notification"

        /** Which app's newest notification to dismiss. Blank means the trigger's. */
        const val CONFIG_PACKAGE = "package"

        /**
         * The old raw-key field. Nothing writes it any more; it is still read so
         * a rule saved when the text box existed keeps doing what it did.
         */
        const val CONFIG_KEY = "key"
    }
}

class DismissNotificationActionFactory(
    private val controller: NotificationController,
) : ActionFactory {
    override val type = DismissNotificationAction.TYPE

    override val displayName = "Dismiss a notification"
    override val category = ActionCategory.NOTIFICATIONS

    /**
     * One field: whose notification. An app, not a key — a key cannot be known
     * in advance, which is why the old text box existed only to be left empty.
     *
     * An app picker rather than a capture off a live notification, unlike the
     * button action next door: that one has to read the *buttons*, which only
     * exist while the notification is on screen, whereas this needs nothing but
     * the app. Requiring the notification to be showing while the rule is being
     * written would be a restriction with no reason behind it.
     */
    override val configFields = listOf(
        ConfigField.AppPackage(
            key = DismissNotificationAction.CONFIG_PACKAGE,
            label = "App",
            blankMeaning = "The notification that fired the rule",
            help = "Dismisses that app's newest notification. Leave it unset to " +
                "dismiss the one the trigger reported.",
        ),
    )

    override val warning: String =
        "With no app chosen this needs a notification trigger above it, since " +
            "there is otherwise nothing to dismiss. With one chosen it dismisses " +
            "that app's newest notification, whatever fired the rule."

    override val requirements = NOTIFICATION_ACCESS

    override fun create(config: Map<String, String>): Action = DismissNotificationAction(
        controller = controller,
        targetPackage = config[DismissNotificationAction.CONFIG_PACKAGE]
            ?.takeIf { it.isNotBlank() },
        legacyKey = config[DismissNotificationAction.CONFIG_KEY],
    )
}

/**
 * Presses one of a notification's own buttons — "Reply", "Snooze", "Archive".
 *
 * **The target is not always the notification that fired the rule.** With a
 * package configured it acts on the newest live notification from that app, which
 * is what makes "when I connect to the car, press play on the music notification"
 * expressible — a Bluetooth trigger and a media target. With no package it falls
 * back to the triggering notification, the commoner case.
 *
 * The button is identified by *meaning* first, then label, then the stored index;
 * see [chooseButton] for why that order. A rule keeps working through an app
 * reordering its buttons or translating them, and reports honestly when the button
 * it wanted is simply gone rather than pressing whatever is in that position now.
 */
class TriggerNotificationButtonAction(
    private val controller: NotificationController,
    private val buttonLabel: String?,
    private val semanticAction: Int?,
    private val targetPackage: String?,
    private val legacyIndex: Int?,
    /**
     * Used only when the notification API cannot reach the button — see
     * [useScreenFallback]. Defaults to unavailable so the action assembles, and
     * behaves exactly as before, without accessibility access.
     */
    private val ui: UiController = UiController.Unavailable,
    /**
     * Opt-in, because the fallback **opens the notification shade on screen**.
     * A rule that did that without being asked would be indistinguishable from
     * the phone acting on its own.
     */
    private val useScreenFallback: Boolean = false,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val active = controller.activeNotifications()
        if (active.isEmpty() && !controller.isConnected) {
            return ActionResult.Failure(
                "notification access is not granted, or the listener is not bound yet"
            )
        }

        val target = chooseNotification(
            active = active,
            wantedPackage = targetPackage,
            triggeringKey = event.payload[SharedPayloadKeys.NOTIFICATION_KEY],
        ) ?: return ActionResult.Failure(
            if (targetPackage != null) {
                "no notification from '$targetPackage' is showing"
            } else {
                "no notification to act on: choose an app, or use this action on a " +
                    "rule triggered by a notification"
            }
        )

        // A notification with no exposed actions at all is the custom-RemoteViews
        // case: the buttons on screen are real, and the system offers no
        // PendingIntent for any of them. There is nothing here to choose from, so
        // go straight to the screen if the rule allows it.
        if (target.buttons.isEmpty()) {
            return pressOnScreen(
                reason = "that notification exposes no buttons to the system — its " +
                    "app draws them itself"
            )
        }

        val button = chooseButton(
            buttons = target.buttons,
            wantedSemantic = semanticAction,
            wantedLabel = buttonLabel,
            storedIndex = legacyIndex,
        ) ?: return pressOnScreen(
            reason = "that notification has no button matching " +
                "'${buttonLabel ?: legacyIndex ?: "anything configured"}'. " +
                "It has ${target.buttons.size}: " +
                target.buttons.joinToString { it.label ?: "?" }
        )

        // Refused rather than attempted. Firing a reply button's intent with no
        // text attached does not send a reply — it does nothing, or opens the app
        // — and reporting success for that is the failure mode this whole action
        // is trying not to have.
        if (button.takesText) {
            return ActionResult.Failure(
                "'${button.label}' is a reply box and needs text typed into it, " +
                    "which a rule cannot supply"
            )
        }

        return controller.triggerActionButton(target.key, button.index)
    }

    /**
     * The screen route, or an honest refusal.
     *
     * [reason] is what the notification API had to say, and it is carried into
     * the failure rather than replaced: "no button matching 'BEENDEN'" and
     * "accessibility is not granted" are different problems, and a rule that
     * reported only the second would send someone to the wrong setting.
     *
     * Needs a label. Semantic actions and stored indexes describe entries in an
     * `actions` array that, in this case, does not have them — only the words on
     * the button exist on screen.
     */
    private suspend fun pressOnScreen(reason: String): ActionResult {
        if (!useScreenFallback) {
            return ActionResult.Failure(
                "$reason. Turn on \"use the screen\" for this action to press it " +
                    "through the notification shade instead."
            )
        }
        val label = buttonLabel?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure(
                "$reason. Pressing through the screen needs the button's name, " +
                    "which this rule does not have."
            )

        return when (val pressed = ui.pressNotificationButton(targetPackage, label)) {
            is ActionResult.Success -> pressed
            is ActionResult.Failure -> ActionResult.Failure("$reason. ${pressed.reason}")
        }
    }

    companion object {
        const val TYPE = "notification_button"
        const val CONFIG_BUTTON = "button"
        const val CONFIG_BUTTON_SEMANTIC = "buttonSemantic"
        const val CONFIG_USE_SCREEN = "useScreen"
        const val CONFIG_PACKAGE = "package"

        /** Kept only to resolve rules saved when a position was the sole option. */
        const val CONFIG_BUTTON_INDEX = "buttonIndex"
    }
}

class TriggerNotificationButtonActionFactory(
    private val controller: NotificationController,
    /**
     * Only reached by the opt-in screen fallback, so it defaults to unavailable:
     * the action's ordinary path needs notification access and nothing else, and
     * a rule that has not asked for the screen must not require accessibility.
     */
    private val ui: UiController = UiController.Unavailable,
) : ActionFactory {
    override val type = TriggerNotificationButtonAction.TYPE

    override val displayName = "Press a notification's button"
    override val category = ActionCategory.NOTIFICATIONS

    /**
     * One field owning three keys, because they are one choice: capturing a
     * button off a live notification records what it says, what it means, and
     * whose notification it was.
     */
    override val configFields = listOf(
        ConfigField.NotificationButton(
            key = TriggerNotificationButtonAction.CONFIG_BUTTON,
            label = "Button",
            semanticKey = TriggerNotificationButtonAction.CONFIG_BUTTON_SEMANTIC,
            packageKey = TriggerNotificationButtonAction.CONFIG_PACKAGE,
            help = "Make the notification appear, then capture the button. " +
                "Leave it empty to act on the notification that fired the rule.",
        ),
        // Last, because it only matters once the ordinary route has failed.
        ConfigField.Flag(
            key = TriggerNotificationButtonAction.CONFIG_USE_SCREEN,
            label = "Use the screen if the button is not exposed",
            help = "Some apps draw their own notification buttons, and Android then " +
                "offers no way to press them directly. This opens the notification " +
                "shade and taps the button by name instead. Needs accessibility " +
                "access, briefly shows the shade, and depends on your phone's " +
                "layout — so it is off unless you need it. It also needs the phone " +
                "unlocked: a rule cannot get past your PIN.",
        ),
    )

    override val warning: String =
        "Matched by what the button means, then by its name. A rule reports a " +
            "failure rather than pressing a different button if the one it wants " +
            "is gone. Buttons an app draws itself cannot be pressed this way at " +
            "all — the setting below is the workaround, and it uses the screen."

    override val requirements = NOTIFICATION_ACCESS

    override fun create(config: Map<String, String>): Action {
        val legacy = config[TriggerNotificationButtonAction.CONFIG_BUTTON_INDEX]
        val index = legacy?.let {
            it.toIntOrNull() ?: error(
                "${TriggerNotificationButtonAction.CONFIG_BUTTON_INDEX} must be a " +
                    "number, was '$it'"
            )
        }
        require(index == null || index >= 0) { "button index cannot be negative, was $index" }

        return TriggerNotificationButtonAction(
            controller = controller,
            ui = ui,
            useScreenFallback = config[TriggerNotificationButtonAction.CONFIG_USE_SCREEN]
                ?.toBoolean() ?: false,
            buttonLabel = config[TriggerNotificationButtonAction.CONFIG_BUTTON],
            semanticAction = config[TriggerNotificationButtonAction.CONFIG_BUTTON_SEMANTIC]
                ?.toIntOrNull(),
            targetPackage = config[TriggerNotificationButtonAction.CONFIG_PACKAGE]
                ?.takeIf { it.isNotBlank() },
            legacyIndex = index,
        )
    }
}
