package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.NotificationButton
import app.phueber.trigly.core.chooseButton
import app.phueber.trigly.core.chooseNotification
import app.phueber.trigly.core.ComponentTool
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.SharedPayloadKeys
import app.phueber.trigly.core.SpecialAccessKind
import app.phueber.trigly.core.TextFilter
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.UiController
import app.phueber.trigly.core.notificationHaystack

private val NOTIFICATION_ACCESS = listOf(
    ComponentRequirement.SpecialAccess(SpecialAccessKind.NOTIFICATION_LISTENER),
)

/**
 * Dismisses another app's notification.
 *
 * **The target is not always the notification that fired the rule**, and until
 * now it had to be. The raw-key text box this action used to carry was rightly
 * removed (a key is minted by the posting app and cannot be typed in advance),
 * but nothing replaced it, which left the action able to dismiss only its own
 * trigger's notification. "When I leave the house, clear the shopping-list
 * reminder" was not expressible at all, and there was no field to suggest
 * otherwise.
 *
 * The target is now chosen by an app, a piece of text, both, or neither:
 *
 *  · **App only.** The newest live notification from that app, as before.
 *  · **Text only.** The newest live notification, from any app, whose
 *    [notificationHaystack] (its title and body, joined) matches [text].
 *    That is the same haystack `notification_posted` matches against, and
 *    what the notification inspector shows.
 *  · **Both.** The two narrow the choice together, not apart: a notification
 *    has to match the app *and* the text to be picked. "An app, or a text, or
 *    both" describes what a person may fill in, not an `or` between the two
 *    conditions. Filling in a second field only to have it widen the match
 *    would be the opposite of what anyone filling in a second field wants.
 *  · **Neither.** Unchanged: the notification that fired the rule. That
 *    fallback is the common case and stays the default: "when my bank
 *    notifies me, dismiss it" needs no configuration.
 *
 * A pattern [TextFilter] refuses to run to completion (`TextMatchMode.REGEX`,
 * a search [RegexGuard] abandons or already remembers as one that runs away)
 * reads as "does not match" for that one notification, the same as every other
 * [TextFilter] caller in this app. The selection simply has one fewer
 * candidate, which can end in the ordinary "nothing showing" failure below:
 * never a crash, and never a silent dismissal of the wrong notification.
 */
class DismissNotificationAction(
    private val controller: NotificationController,
    private val targetPackage: String?,
    private val text: TextFilter = TextFilter.Any,
    private val legacyKey: String? = null,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        // A key stored by an old rule still wins: it names one exact
        // notification, which is more specific than anything the selector can
        // express. It will usually be stale (that is why the field is gone), but
        // honouring it keeps such a rule behaving as it did rather than silently
        // retargeting it at something else.
        legacyKey?.takeIf { it.isNotBlank() }?.let { return controller.dismiss(it) }

        // Neither an app nor a text was asked for: the payload already names the
        // exact notification, so dismiss it without looking anything up. Going
        // through the active list here (as the button action must, because it
        // needs the buttons) would add a way to fail that dismissing by key does
        // not have: a notification that has already gone, or a list read that
        // came back empty, would turn into "nothing to dismiss" instead of a
        // harmless no-op. This is the "neither" case from the class doc, and it
        // is exactly what this action did before it could match on text at all.
        if (targetPackage == null && text.isEmpty) {
            val triggering = event.payload[SharedPayloadKeys.NOTIFICATION_KEY]
                ?: return ActionResult.Failure(
                    "There is nothing to dismiss. Choose an app or a text to " +
                        "match, or use this action on a rule triggered by a " +
                        "notification."
                )
            return controller.dismiss(triggering)
        }

        // An app, a text, or both were asked for, so which notification is a
        // question about what is currently on screen.
        val active = controller.activeNotifications()
        if (active.isEmpty() && !controller.isConnected) {
            return ActionResult.Failure(
                "Notification access is not granted, or the listener is not bound yet."
            )
        }

        // Not chooseNotification: that helper also falls back to the triggering
        // notification, which is wrong here. An app or a text was named, and
        // quietly dismissing the trigger's notification instead when nothing
        // matches would be the wrong notification, reported as success. The two
        // filters are applied together, narrowing the same list, so "both" comes
        // for free rather than needing its own case.
        val target = active
            .filter { targetPackage == null || it.packageName == targetPackage }
            .filter { text.isEmpty || text.matches(notificationHaystack(it.title, it.text)) }
            .maxByOrNull { it.postedAtMillis }
            ?: return ActionResult.Failure(noneShowingReason(targetPackage, text))

        return controller.dismiss(target.key)
    }

    companion object {
        const val TYPE = "dismiss_notification"

        /** Which app's notification to dismiss. Blank means any app. */
        const val CONFIG_PACKAGE = "package"

        /**
         * Text the notification's title or body must contain, or match, as a
         * pattern. Blank means any text. See [DismissNotificationAction]'s doc
         * for what "app and text both set" means and why.
         */
        const val CONFIG_TEXT = "textContains"

        /** [TextMatchMode], stored alongside [CONFIG_TEXT]. Absent reads as CONTAINS. */
        const val CONFIG_TEXT_MODE = "textContainsMode"

        /**
         * The old raw-key field. Nothing writes it any more; it is still read so
         * a rule saved when the text box existed keeps doing what it did.
         */
        const val CONFIG_KEY = "key"
    }
}

/**
 * "No notification is showing" worded for whichever of an app and a text were
 * asked for, so the failure names exactly what did not match rather than a
 * generic refusal.
 *
 * Only reached when at least one of [targetPackage] and [text] is set (see
 * [DismissNotificationAction.execute]'s "neither" branch, which returns before
 * this is ever called and has its own message).
 */
private fun noneShowingReason(targetPackage: String?, text: TextFilter): String {
    val whoseApp = targetPackage?.let { "from '$it'" }
    val whoseText = text.pattern?.let { "matching '$it'" }
    val description = listOfNotNull(whoseApp, whoseText).joinToString(" ")
    return "No notification $description is showing."
}

class DismissNotificationActionFactory(
    private val controller: NotificationController,
) : ActionFactory {
    override val type = DismissNotificationAction.TYPE

    override val displayName = "Dismiss a notification"
    override val category = ActionCategory.NOTIFICATIONS

    /**
     * Two fields, both optional: which app, and what text. Neither is a key:
     * a key cannot be known in advance, which is why the old text box existed
     * only to be left empty.
     *
     * An app picker and a text field rather than a capture off a live
     * notification, unlike the button action next door: that one has to read
     * the *buttons*, which only exist while the notification is on screen,
     * whereas this needs nothing but the app and the text. Requiring the
     * notification to be showing while the rule is being written would be a
     * restriction with no reason behind it.
     */
    override val configFields = listOf(
        ConfigField.AppPackage(
            key = DismissNotificationAction.CONFIG_PACKAGE,
            label = "App",
            blankMeaning = "Any app",
            help = "Dismisses that app's newest notification. Combined with " +
                "the text below, the two narrow the choice together: a " +
                "notification set here has to match both, not just one.",
        ),
        ConfigField.TextPattern(
            key = DismissNotificationAction.CONFIG_TEXT,
            label = "Title or text contains",
            blankMeaning = "Any text",
            modeKey = DismissNotificationAction.CONFIG_TEXT_MODE,
            help = "Matches the notification's title and body together. " +
                "Combined with the app above, the two narrow the choice " +
                "together: filling in both does not widen the match, it " +
                "narrows it. Leave both blank to dismiss the notification " +
                "that fired the rule.",
        ),
    )

    override val warning: String =
        "Leave both fields blank and this action needs a notification trigger " +
            "before it in the rule: there is then nothing to dismiss but the " +
            "one that fired it. Fill in an app, a text to match, or both. Both " +
            "together narrow the choice, so only a notification matching " +
            "everything filled in is dismissed, never one that only matches " +
            "part of it. Any of these dismisses its target no matter what fired " +
            "the rule."

    override val requirements = NOTIFICATION_ACCESS

    // Its filters are written against what a notification actually contains (a
    // package, a title, a piece of text), which is exactly what nobody can fill
    // in by guessing, and where a wrong guess yields a rule that silently never
    // fires. The inspector is the answer, so it is offered here rather than only
    // from the rule list, where you would have to know it exists.
    override fun toolsFor(config: Map<String, String>): List<ComponentTool> =
        listOf(ComponentTool.Test, ComponentTool.InspectNotifications)

    override fun create(config: Map<String, String>): Action = DismissNotificationAction(
        controller = controller,
        targetPackage = config[DismissNotificationAction.CONFIG_PACKAGE]
            ?.takeIf { it.isNotBlank() },
        text = TextFilter.fromConfig(
            config[DismissNotificationAction.CONFIG_TEXT],
            config[DismissNotificationAction.CONFIG_TEXT_MODE],
        ),
        legacyKey = config[DismissNotificationAction.CONFIG_KEY],
    )
}

/**
 * Which button of which notification an action should act on, or why it cannot.
 *
 * Extracted because two actions need exactly this and then diverge on one
 * point. `notification_button` presses, and can fall back to the notification
 * shade when the system exposes no `PendingIntent`. `capture_notification_button`
 * keeps the token for later, so for it a button with no `PendingIntent` is not a
 * fallback but a dead end: there is nothing to keep. Both still have to tell a
 * missing notification apart from a notification whose app draws its own
 * buttons apart from a button that simply does not match apart from a reply box,
 * and each of those is a different sentence a person can act on.
 *
 * The cases are separate types rather than one failure string so the caller can
 * make that decision. Collapsing them would force the screen fallback and the
 * capture to share a policy they do not share.
 */
internal sealed interface ButtonTarget {

    /** A real button with a real intent behind it. */
    data class Ready(val key: String, val button: NotificationButton) : ButtonTarget

    /**
     * The custom-RemoteViews case: the buttons on screen are real and the system
     * offers a `PendingIntent` for none of them.
     */
    data class NoExposedButtons(val reason: String) : ButtonTarget

    /** Buttons exist, and none of them is the one the rule asked for. */
    data class NoMatch(val reason: String) : ButtonTarget

    /**
     * No notification to act on, no access, or a button a rule must not press.
     * Nothing reaches these by another route, so there is nothing to fall back
     * to and [reason] is the answer.
     */
    data class Refused(val reason: String) : ButtonTarget
}

/**
 * Resolves [ButtonTarget] against the notifications showing right now.
 *
 * Reads the live list once, so the target and its buttons come from the same
 * snapshot: asking twice could choose a notification and then find its buttons
 * belonged to a newer one.
 */
internal fun resolveButtonTarget(
    controller: NotificationController,
    event: TriggerEvent,
    targetPackage: String?,
    buttonLabel: String?,
    semanticAction: Int?,
    legacyIndex: Int?,
): ButtonTarget {
    val active = controller.activeNotifications()
    if (active.isEmpty() && !controller.isConnected) {
        return ButtonTarget.Refused(
            "Notification access is not granted, or the listener is not bound yet."
        )
    }

    val target = chooseNotification(
        active = active,
        wantedPackage = targetPackage,
        triggeringKey = event.payload[SharedPayloadKeys.NOTIFICATION_KEY],
    ) ?: return ButtonTarget.Refused(
        if (targetPackage != null) {
            "No notification from '$targetPackage' is showing."
        } else {
            "There is no notification to act on. Choose an app, or use " +
                "this action on a rule triggered by a notification."
        }
    )

    if (target.buttons.isEmpty()) {
        return ButtonTarget.NoExposedButtons(
            "That notification exposes no buttons to the system. " +
                "Its app draws them itself."
        )
    }

    val button = chooseButton(
        buttons = target.buttons,
        wantedSemantic = semanticAction,
        wantedLabel = buttonLabel,
        storedIndex = legacyIndex,
    ) ?: return ButtonTarget.NoMatch(
        "That notification has no button matching " +
            "'${buttonLabel ?: legacyIndex ?: "anything configured"}'. " +
            "It has ${target.buttons.size} buttons: " +
            "${target.buttons.joinToString { it.label ?: "?" }}."
    )

    // Refused rather than attempted. Firing a reply button's intent with no text
    // attached does not send a reply: it does nothing, or opens the app, and
    // reporting success for that is the failure mode this whole area is trying
    // not to have. Capturing one is no better, so this is refused for both.
    if (button.takesText) {
        return ButtonTarget.Refused(
            "'${button.label}' is a reply box. It needs text typed into it, " +
                "which a rule cannot supply."
        )
    }

    return ButtonTarget.Ready(target.key, button)
}

/**
 * Presses one of a notification's own buttons: "Reply", "Snooze", "Archive".
 *
 * **The target is not always the notification that fired the rule.** With a
 * package configured it acts on the newest live notification from that app, which
 * is what makes "when I connect to the car, press play on the music notification"
 * expressible (a Bluetooth trigger and a media target). With no package it falls
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
     * Used only when the notification API cannot reach the button (see
     * [useScreenFallback]). Defaults to unavailable so the action assembles, and
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

    override suspend fun execute(event: TriggerEvent): ActionResult =
        when (
            val found = resolveButtonTarget(
                controller = controller,
                event = event,
                targetPackage = targetPackage,
                buttonLabel = buttonLabel,
                semanticAction = semanticAction,
                legacyIndex = legacyIndex,
            )
        ) {
            is ButtonTarget.Ready -> controller.triggerActionButton(found.key, found.button.index)

            // The two cases where the system offers no `PendingIntent` are the
            // two the screen can still reach, because the buttons are on screen
            // whatever the API says about them.
            is ButtonTarget.NoExposedButtons -> pressOnScreen(found.reason)
            is ButtonTarget.NoMatch -> pressOnScreen(found.reason)

            is ButtonTarget.Refused -> ActionResult.Failure(found.reason)
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
     * `actions` array that, in this case, does not have them. Only the words on
     * the button exist on screen.
     */
    private suspend fun pressOnScreen(reason: String): ActionResult {
        // `reason` is already a complete sentence, so what follows is joined
        // with a space rather than a second full stop.
        if (!useScreenFallback) {
            return ActionResult.Failure(
                "$reason Turn on \"use the screen\" for this action to press it " +
                    "through the notification shade instead."
            )
        }
        val label = buttonLabel?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure(
                "$reason Pressing through the screen needs the button's name, " +
                    "which this rule does not have."
            )

        return when (val pressed = ui.pressNotificationButton(targetPackage, label)) {
            is ActionResult.Success -> pressed
            is ActionResult.Failure -> ActionResult.Failure("$reason ${pressed.reason}")
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
            help = "Some apps draw their own notification buttons. Android then " +
                "offers no way to press them directly. This setting opens the " +
                "notification shade instead, and taps the button by name. It " +
                "needs accessibility access. It briefly shows the shade on " +
                "screen. It depends on your phone's layout. Turn it on only when " +
                "you need it. It also needs the phone unlocked. A rule cannot " +
                "get past your PIN.",
        ),
    )

    override val warning: String =
        "This action matches a button by its meaning first, then by its name. A " +
            "rule reports a failure instead of pressing the wrong button if the " +
            "one it wants is gone. An app that draws its own buttons blocks this " +
            "method completely. The setting below is the workaround. It uses " +
            "the screen instead."

    override val requirements = NOTIFICATION_ACCESS

    // Its filters are written against what a notification actually contains (a
    // package, a title, a piece of text), which is exactly what nobody can fill
    // in by guessing, and where a wrong guess yields a rule that silently never
    // fires. The inspector is the answer, so it is offered here rather than only
    // from the rule list, where you would have to know it exists.
    override fun toolsFor(config: Map<String, String>): List<ComponentTool> =
        listOf(ComponentTool.Test, ComponentTool.InspectNotifications)

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
