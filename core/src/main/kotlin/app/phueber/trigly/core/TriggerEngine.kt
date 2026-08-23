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
 * @param onStartFailure reports a rule that could not be built at all — an
 *   unknown type, or config its factory refuses. Separate from [onOutcome]
 *   because nothing ran: there is no event and no [ActionResult] to report.
 */
class TriggerEngine(
    private val registry: Registry,
    private val scope: CoroutineScope,
    private val onOutcome: (rule: Rule, event: TriggerEvent, result: ActionResult) -> Unit =
        { _, _, _ -> },
    private val onStartFailure: (rule: Rule, cause: Throwable) -> Unit = { _, _ -> },
) {
    /** The rule as it was when started, so [sync] can tell an edit from a redelivery. */
    private class Running(val rule: Rule, val job: Job)

    private val jobs = mutableMapOf<String, Running>()

    val runningRuleIds: Set<String> get() = jobs.keys.toSet()

    /**
     * Makes what is running match [rules]: starts what is newly enabled, stops
     * what was disabled or deleted, and restarts what was edited.
     *
     * This is the engine's only entry point for a *set* of rules, because it is
     * called repeatedly — the hosting service collects the rule store and calls
     * this on every change. Which is why an unchanged rule is deliberately left
     * alone rather than restarted: tearing a trigger down and building it again
     * re-registers its receiver, and a sticky broadcast replays on registration.
     * A rule would then fire because an *unrelated* rule was edited, which is
     * the phantom firing `StateTracker` exists to prevent.
     *
     * A rule that cannot be built is reported through [onStartFailure] and
     * skipped rather than thrown: one bad rule — config left invalid by an
     * import from a newer build — must not stop the others from running.
     */
    fun sync(rules: List<Rule>) {
        val wanted = rules.filter { it.enabled }.associateBy { it.id }

        (jobs.keys - wanted.keys).forEach(::stopRule)

        wanted.values.forEach { rule ->
            if (jobs[rule.id]?.rule == rule) return@forEach
            try {
                startRule(rule)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                onStartFailure(rule, t)
            }
        }
    }

    /**
     * (Re)starts one rule. Resolving the trigger and actions happens here, so a
     * rule naming an unknown type fails at start with [UnknownComponentException]
     * rather than silently never firing. [sync] is the caller that catches that;
     * this one throws, so a deliberate single-rule start still reports it.
     */
    fun startRule(rule: Rule) {
        stopRule(rule.id)

        val trigger = registry.createTrigger(rule.trigger)
        val actions = rule.actions.map(registry::createAction)

        val job = scope.launch {
            trigger.events().collect { event ->
                actions.forEach { action -> run(rule, action, event) }
            }
        }
        jobs[rule.id] = Running(rule, job)
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
        jobs.remove(ruleId)?.job?.cancel()
    }

    fun stop() {
        jobs.keys.toList().forEach(::stopRule)
    }
}
