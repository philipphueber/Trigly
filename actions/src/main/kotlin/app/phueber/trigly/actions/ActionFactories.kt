package app.phueber.trigly.actions

import android.content.Context
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.UiController
import app.phueber.trigly.core.VariableStore

/**
 * Every action type this module provides.
 *
 * **This is the only existing file a new action touches** — same rule as
 * `triggerFactories`: new files for the implementation and its factory, one
 * line here.
 *
 * `docs/actions.md` catalogues the actions still to be built, and the ones that
 * are no longer possible for a third-party app at all.
 */
fun actionFactories(
    context: Context,
    /**
     * Supplied by `:ui`, which is the only module that can see both the listener
     * service in `:triggers` and the actions here. Defaults to the unavailable
     * implementation so tests and previews can assemble without it.
     */
    notifications: NotificationController = NotificationController.Unavailable,
    /**
     * The accessibility service, for the one action that can fall back to
     * pressing through the rendered shade. Same source as [notifications] —
     * `:ui` is the only module that can see both services — and the same default,
     * so nothing here requires accessibility access to be granted.
     */
    ui: UiController = UiController.Unavailable,
    /**
     * The rule store, for the one action whose subject is Trigly itself. The
     * same instance the engine reads, or the switch it writes would take effect
     * on nothing.
     */
    rules: RuleRepository = InMemoryRuleRepository(),
    /**
     * Where this device's app-scoped variables live. Defaults to a working
     * in-memory store rather than a refusing stub, unlike [notifications] and
     * [ui]: see [VariableStore] for why "this device has no variables" is not a
     * real state.
     */
    variables: VariableStore = InMemoryVariableStore(),
): List<ActionFactory> = listOf(
    // Tell the user something
    PostNotificationActionFactory(context),
    CancelNotificationActionFactory(context),
    ToastActionFactory(context),
    SpeakActionFactory(context),
    VibrateActionFactory(context),
    PlayAlertActionFactory(context, notifications),

    // Open something
    OpenUrlActionFactory(context),
    OpenAppActionFactory(context),

    // Hand off to another app, user confirms
    ComposeEmailActionFactory(context),
    ComposeSmsActionFactory(context),
    SetAlarmActionFactory(context),
    AddCalendarEventActionFactory(context),

    // Trigly's own rules
    SetRuleEnabledActionFactory(rules),
    SetVariableActionFactory(variables),

    // Device state
    SetVolumeActionFactory(context),
    SetRingerModeActionFactory(context),
    ClipboardWriteActionFactory(context),

    // Reach the outside world
    HttpRequestActionFactory(),

    // Other apps' notifications, via the listener service
    DismissNotificationActionFactory(notifications),
    TriggerNotificationButtonActionFactory(notifications, ui),
    SetDndActionFactory(context),
)
