package app.phueber.trigly.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs enabled [Rule]s: collects each rule's trigger and executes its actions.
 *
 * Deliberately free of Android and UI types so it can be driven from a unit
 * test with fake triggers — see `TriggerEngineTest`. The owning service
 * supplies the [scope]; cancelling that scope stops everything.
 *
 * @param onOutcome observation hook for logging and for the UI's run history.
 *   Called once per action per event, on the collecting coroutine.
 */
class TriggerEngine(
    private val registry: Registry,
    private val scope: CoroutineScope,
    private val onOutcome: (rule: Rule, event: TriggerEvent, result: ActionResult) -> Unit =
        { _, _, _ -> },
) {
    private val jobs = mutableMapOf<String, Job>()

    val runningRuleIds: Set<String> get() = jobs.keys.toSet()

    /** Starts every enabled rule. Disabled rules are left alone, not started and stopped. */
    fun start(rules: List<Rule>) {
        rules.filter { it.enabled }.forEach(::startRule)
    }

    /**
     * (Re)starts one rule. Resolving the trigger and actions happens here, so a
     * rule naming an unknown type fails at start with [UnknownComponentException]
     * rather than silently never firing.
     */
    fun startRule(rule: Rule) {
        stopRule(rule.id)

        val trigger = registry.createTrigger(rule.trigger)
        val actions = rule.actions.map(registry::createAction)

        jobs[rule.id] = scope.launch {
            trigger.events().collect { event ->
                actions.forEach { action -> run(rule, action, event) }
            }
        }
    }

    /**
     * One misbehaving action must not take down the rule that hosts it, or the
     * actions queued behind it — a rule that silently stops firing is the worst
     * failure mode this app has. Cancellation is not a failure and is rethrown
     * so the coroutine machinery still sees it.
     */
    private suspend fun run(rule: Rule, action: Action, event: TriggerEvent) {
        val result = try {
            action.execute(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ActionResult.Failure("threw ${t::class.simpleName}: ${t.message}", t)
        }
        onOutcome(rule, event, result)
    }

    fun stopRule(ruleId: String) {
        jobs.remove(ruleId)?.cancel()
    }

    fun stop() {
        jobs.keys.toList().forEach(::stopRule)
    }
}
