package app.phueber.trigly.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.merge
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

    /**
     * Guards [jobs], because the engine is genuinely reached from two threads.
     *
     * The hosting service calls [sync] from the coroutine collecting the rule
     * store, while Android delivers `onStartCommand` on the main thread — and
     * that reads [runningRuleIds] to label the service's notification. Both
     * happen on every rule change, so an unguarded `HashMap` here means
     * iterating it on one thread while the other restructures it: a
     * `ConcurrentModificationException` on the main thread, or a count that is
     * quietly wrong. Intermittent, which is the worst way for this to show up.
     *
     * A monitor rather than a concurrent map, because [sync] is not one
     * operation: "stop what is gone, then start what is new" has to be atomic as
     * a whole or a reader can observe a moment where a rule is neither. Monitors
     * are reentrant, which is what lets [sync] call [stopRule] while holding it.
     */
    private val lock = Any()

    private val jobs = mutableMapOf<String, Running>()

    val runningRuleIds: Set<String> get() = synchronized(lock) { jobs.keys.toSet() }

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

        synchronized(lock) {
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
    }

    /**
     * (Re)starts one rule. Resolving the gate and actions happens here, so a
     * rule naming an unknown type fails at start with [UnknownComponentException]
     * rather than silently never firing. [sync] is the caller that catches that;
     * this one throws, so a deliberate single-rule start still reports it.
     *
     * **One job per rule, even with several trigger edges.** A gate's first level
     * is an OR of edges, and a rule with three of them is *semantically* three
     * copies of itself — but three independent collectors would run the actions
     * concurrently when two edges fire together, and a rule has always done one
     * thing at a time. So the edges are merged into a single flow and collected
     * once: same semantics, and cancellation stays the single stop button that
     * `stopRule` and the editor's Test button rely on.
     *
     * Condition triggers are built here too, once, rather than per event. They are
     * asked for their state on every fire, and constructing a fresh one each time
     * would pay the factory's cost — and, for anything holding a resource, do so
     * repeatedly.
     */
    fun startRule(rule: Rule) = synchronized(lock) {
        stopRule(rule.id)

        val edges = rule.gate.triggers.map(registry::createTrigger)
        val actions = rule.actions.map(registry::createAction)

        // Built up front so an unknown *condition* type fails at start like an
        // unknown trigger, rather than at the first fire — which would be a rule
        // that looks healthy until the moment it matters.
        val checks: Map<ComponentSpec, Trigger> = rule.gate.conditions
            ?.checks()
            .orEmpty()
            .distinct()
            .associateWith(registry::createTrigger)

        val job = scope.launch {
            edges.map { it.events() }.merge().collect { event ->
                if (!gateHolds(rule, checks)) return@collect
                actions.forEach { action -> run(rule, action, event) }
            }
        }
        jobs[rule.id] = Running(rule, job)
        Unit
    }

    /**
     * Whether the rule's conditions hold right now.
     *
     * A rule with no conditions always passes, which is every rule written before
     * gates existed.
     *
     * A condition that throws is treated as not holding, and that direction is
     * deliberate: a state nobody could read is unknown, and firing unattended
     * actions on an unknown state is the worse of the two failures — see
     * [ConditionNode.holds]. Cancellation is rethrown, because a cancelled rule is
     * not a rule whose conditions failed.
     */
    private suspend fun gateHolds(rule: Rule, checks: Map<ComponentSpec, Trigger>): Boolean {
        val conditions = rule.gate.conditions ?: return true

        return conditions.holds { spec ->
            try {
                checks[spec]?.currentlyHolds()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                null
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

    fun stopRule(ruleId: String) = synchronized(lock) {
        jobs.remove(ruleId)?.job?.cancel()
        Unit
    }

    fun stop() = synchronized(lock) {
        jobs.keys.toList().forEach(::stopRule)
    }
}
