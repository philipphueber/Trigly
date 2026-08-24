package app.phueber.trigly.ui

import android.app.Application
import android.content.Context
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.UiController
import app.phueber.trigly.core.storage.ruleRepository
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

    val registry: Registry = Registry(
        triggerFactories = triggerFactories(context),
        actionFactories = actionFactories(context, notifications, ui),
    )

    /**
     * Durable storage. Rules are hand-built by the user, so losing them to a
     * process death — which the in-memory stand-in did — was never shippable.
     */
    val ruleRepository: RuleRepository = ruleRepository(context)

    val requirementChecker: RequirementChecker = RequirementChecker(context)
}
