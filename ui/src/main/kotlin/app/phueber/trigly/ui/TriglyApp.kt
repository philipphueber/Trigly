package app.phueber.trigly.ui

import android.app.Application
import android.content.Context
import app.phueber.trigly.actions.PostNotificationAction
import app.phueber.trigly.actions.actionFactories
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.InMemoryRuleRepository
import app.phueber.trigly.core.NotificationController
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.TriggerEngine
import app.phueber.trigly.triggers.IntervalTrigger
import app.phueber.trigly.triggers.notification.ListenerNotificationController
import app.phueber.trigly.triggers.triggerFactories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TriglyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Where the app is assembled — the one place that knows every module.
 *
 * Handing the factory lists to [Registry] here is what keeps `:core` unaware of
 * `:triggers` and `:actions`. Plain constructor wiring rather than a DI
 * framework: at four modules it is not worth the annotation processor, and it
 * keeps the dependency graph readable in one screen.
 */
class AppContainer(context: Context) {

    /**
     * TODO(service): the engine currently lives as long as the process, so rules
     *  stop firing when the process is killed. It belongs in a foreground
     *  service with a persistent notification — that is the only way background
     *  execution survives OEM battery optimisation.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The adapter from `:triggers` that lets actions in `:actions` reach the
     * notification listener service. Wired here because this is the only place
     * that can see both modules.
     */
    private val notificationController: NotificationController = ListenerNotificationController()

    val registry: Registry = Registry(
        triggerFactories = triggerFactories(context),
        actionFactories = actionFactories(context, notificationController),
    )

    val ruleRepository: RuleRepository = InMemoryRuleRepository(sampleRules())

    val requirementChecker: RequirementChecker = RequirementChecker(context)

    val engine: TriggerEngine = TriggerEngine(registry, applicationScope)
}

/**
 * TODO(persistence): sample data so a fresh install has something to show.
 *  Delete once [InMemoryRuleRepository] is replaced by real storage.
 */
private fun sampleRules(): List<Rule> = listOf(
    Rule(
        id = "sample-interval",
        name = "Ping every minute",
        trigger = ComponentSpec(
            type = IntervalTrigger.TYPE,
            config = mapOf(IntervalTrigger.CONFIG_PERIOD_MILLIS to "60000"),
        ),
        actions = listOf(
            ComponentSpec(
                type = PostNotificationAction.TYPE,
                config = mapOf(
                    PostNotificationAction.CONFIG_TITLE to "Trigly",
                    PostNotificationAction.CONFIG_BODY to "A minute passed.",
                ),
            )
        ),
        enabled = false,
    ),
)
