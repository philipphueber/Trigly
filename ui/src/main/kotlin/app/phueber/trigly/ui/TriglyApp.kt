package app.phueber.trigly.ui

import android.app.Application
import android.content.Context
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.AlarmScheduler
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.RuleRunnerHandle
import app.phueber.trigly.core.RuleVariableStore
import app.phueber.trigly.core.UiController
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.core.storage.ruleRepository
import app.phueber.trigly.core.storage.ruleVariableStore
import app.phueber.trigly.core.storage.variableStore
import app.phueber.trigly.triggers.AlarmManagerScheduler
import app.phueber.trigly.triggers.accessibility.ServiceUiController
import app.phueber.trigly.triggers.notification.ListenerNotificationController
import app.phueber.trigly.triggers.triggerFactories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TriglyApp : Application() {
    lateinit var container: AppContainer
        private set

    /**
     * Lives as long as the process, which is the right lifetime for exactly one
     * job: noticing that the engine ought to be running. The engine itself is
     * deliberately not here — see [EngineService].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        keepEngineRunning()
    }

    /**
     * Starts the engine whenever there is something for it to do.
     *
     * The pairing with [EngineService]'s self-stop is the whole design: the app
     * decides when the service should exist, the service decides when it should
     * not, and neither has to know what the other is doing. Every rule change
     * re-asks the question, so a service that went missing for any reason is
     * restarted by the next edit rather than staying dead until a reboot —
     * starting a service that is already running costs one `onStartCommand`.
     *
     * This runs in every process, not just the one behind the UI, because
     * `Application.onCreate` is the one thing that happens on every path back
     * to life: the launcher, the system rebinding the notification listener, or
     * `START_STICKY` bringing the service back.
     */
    private fun keepEngineRunning() {
        scope.launch {
            container.ruleRepository.rules().collect { rules ->
                if (rules.any { it.enabled }) EngineService.start(this@TriglyApp)
            }
        }
    }
}

/**
 * Where the app is assembled — the one place that knows every module.
 *
 * Handing the factory lists to [Registry] here is what keeps `:core` unaware of
 * `:triggers` and `:actions`. Plain constructor wiring rather than a DI
 * framework: at four modules it is not worth the annotation processor, and it
 * keeps the dependency graph readable in one screen.
 *
 * Note what is *not* here: the `TriggerEngine`. It is built by [EngineService]
 * against this container's registry, because an engine outliving the service
 * that hosts it would be a second, invisible answer to "is Trigly running?".
 */
class AppContainer(context: Context) {

    /**
     * The adapter from `:triggers` that lets actions in `:actions` reach the
     * notification listener service. Wired here because this is the only place
     * that can see both modules.
     *
     * Not private any more: the editor's button picker reads the live
     * notifications through the same port the acting action does, so the two
     * cannot disagree about what is on screen or what its buttons are.
     */
    val notifications: NotificationController = ListenerNotificationController()

    /**
     * The adapter to the accessibility service, wired here for the same reason.
     *
     * Used by exactly one action, and only when that rule has opted in: pressing
     * a notification button that the app drew itself, which Android exposes no
     * `PendingIntent` for. Constructing it is free and grants nothing — the
     * service does nothing until the user enables it in system settings.
     */
    val ui: UiController = ServiceUiController()

    /**
     * The wake-up every wall-clock or poll-based trigger waits through
     * instead of a plain coroutine `delay`, so the wait survives Doze. See
     * `app.phueber.trigly.core.AlarmScheduler` and `docs/todo.md`'s T1.
     *
     * Wired here for the same reason [notifications] and [ui] are: this is
     * the one place that can see both the port in `:core` and its Android
     * implementation in `:triggers`.
     */
    val scheduler: AlarmScheduler = AlarmManagerScheduler(context)

    /**
     * Where `run_rule` reaches the engine, before any engine exists.
     *
     * Declared here, before [registry], for the same reason [scheduler] is:
     * `run_rule`'s factory needs something to hold at construction time, and
     * no `TriggerEngine` exists yet when this container is built. `EngineService`
     * calls [RuleRunnerHandle.attach] once it builds one, against this same
     * instance, and [RuleRunnerHandle.detach] when it stops. See
     * `app.phueber.trigly.core.RuleRunner` for the full reasoning, and
     * [ruleFaults] below for the same "two ends in different places" shape
     * solved in the other direction.
     */
    val ruleRunner: RuleRunnerHandle = RuleRunnerHandle()

    /**
     * What an action said when it last failed, per rule.
     *
     * Lives here because it has two ends in different places: `EngineService`
     * writes it as rules run, and the rule list reads it to explain a rule that
     * fired and then did nothing. The service will not hand out the engine, so a
     * sink both sides can see is the way they meet. See [RuleFaultLog].
     */
    val ruleFaults: RuleFaultLog = RuleFaultLog()

    /**
     * Durable storage. Rules are hand-built by the user, so losing them to a
     * process death — which the in-memory stand-in did — was never shippable.
     *
     * Declared **before** [registry] on purpose: property initialisers run in
     * declaration order, and the registry now hands this to the action that
     * switches rules. Below the registry it would be read while still null, and
     * the symptom would be an action that quietly switches nothing.
     */
    val ruleRepository: RuleRepository = ruleRepository(context)

    /**
     * App-scope variable storage. Declared **before** [registry] for the same
     * reason [ruleRepository] is: property initialisers run in declaration
     * order, and the registry hands this to both factory lists below. Below
     * the registry it would still be null when they read it, and a default
     * store nobody writes to is exactly the silent-empty-variable failure
     * `TriggerEngine`'s required `store` parameter exists to catch.
     */
    val variableStore: VariableStore = variableStore(context)

    /**
     * The `{{mine.*}}` store: each rule's own private values. Beside
     * [variableStore] rather than replacing it, because the two scopes are two
     * keyspaces and only `set_variable` needs to know both.
     */
    val ruleVariableStore: RuleVariableStore = ruleVariableStore(context)

    val registry: Registry = Registry(
        triggerFactories = triggerFactories(context, scheduler, variableStore),
        actionFactories = actionFactories(
            context, scheduler, ruleRunner, notifications, ui, ruleRepository, variableStore,
            ruleVariableStore,
        ),
    )

    val requirementChecker: RequirementChecker = RequirementChecker(context)
}
