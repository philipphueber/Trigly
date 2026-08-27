package app.phueber.trigly.actions

import android.content.Context
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.InMemoryRuleVariableStore
import app.phueber.trigly.core.RuleRunner
import app.phueber.trigly.core.RuleVariableStore
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
     * The wake-up `delay` waits through instead of a plain coroutine `delay`;
     * see [DelayAction]. No default, unlike every parameter below, and for
     * the same reason `triggerFactories` in `:triggers` does not default its
     * own copy: there is no meaningful stand-in the way
     * [NotificationController.Unavailable] and [UiController.Unavailable] are
     * real, reportable states. A production assembly point that forgot to
     * wire the real implementation here would still compile, and would build
     * a `delay` action that silently does not wait, the same silent failure
     * `TriggerEngine`'s required `store` parameter already guards against.
     */
    scheduler: AlarmScheduler,
    /**
     * Reaches the engine's own action-running path for `run_rule`; see
     * [RuleRunner]. No default, for the same reason [scheduler] has none: a
     * production assembly point that forgot to wire the real
     * `RuleRunnerHandle` here would still compile, and would build a
     * `run_rule` action that silently never runs anything, because the
     * default it fell back to would be a handle `EngineService` never
     * learns about. See `app.phueber.trigly.core.RuleRunnerHandle`.
     */
    runner: RuleRunner,
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
    /**
     * The rule-scope half of `set_variable`'s three scopes. Defaulted the same
     * way [variables] is and for the same reason: a working in-memory store is
     * a real state, unlike a scheduler or an engine handle that has no
     * stand-in, so a caller that only wants to read the schema does not have to
     * build storage first.
     */
    ruleVariables: RuleVariableStore = InMemoryRuleVariableStore(),
): List<ActionFactory> = listOf(
    // Tell the user something
    PostNotificationActionFactory(context),
    CancelNotificationActionFactory(context),
    ToastActionFactory(context),
    SpeakActionFactory(context),
    VibrateActionFactory(context),
    PlayAlertActionFactory(context, notifications),
    PlaySoundActionFactory(context),

    // Open something
    OpenUrlActionFactory(context),
    OpenAppActionFactory(context),

    // Hand off to another app, user confirms
    ComposeEmailActionFactory(context),
    ComposeSmsActionFactory(context),
    SetAlarmActionFactory(context),
    AddCalendarEventActionFactory(context),

    // Timing
    DelayActionFactory(scheduler),

    // Trigly's own rules
    SetRuleEnabledActionFactory(rules),
    SetVariableActionFactory(variables, ruleVariables),
    RunRuleActionFactory(rules, runner),

    // Device state
    SetVolumeActionFactory(context),
    SetRingerModeActionFactory(context),
    ClipboardWriteActionFactory(context),

    // Reach the outside world
    HttpRequestActionFactory(),

    // Other apps' notifications, via the listener service
    DismissNotificationActionFactory(notifications),
    TriggerNotificationButtonActionFactory(notifications, ui),
    CaptureNotificationButtonActionFactory(notifications),
    PressCapturedButtonActionFactory(notifications),
    SetDndActionFactory(context),
)
