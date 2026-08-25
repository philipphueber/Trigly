package app.phueber.trigly.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.map
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
 *   Called once per action per event, on the collecting coroutine. Carries the
 *   action's type, because a rule with three actions produces three outcomes and
 *   a reader that cannot tell them apart cannot say which one failed.
 * @param onStartFailure reports a rule that could not be built at all — an
 *   unknown type, or config its factory refuses. Separate from [onOutcome]
 *   because nothing ran: there is no event and no [ActionResult] to report.
 * @param onSuppressed reports a rule whose trigger fired and whose actions were
 *   then not run, because a component in the tree could not answer whether it
 *   held. Carries the components that could not answer, never the ones that
 *   answered no. A rule held back by a condition that plainly said "no" is the
 *   rule working; a rule held back by one that could not look is a fault, and
 *   until this existed the two were the same silence. See [triggerHolds].
 */
class TriggerEngine(
    private val registry: Registry,
    private val scope: CoroutineScope,
    private val onOutcome: (
        rule: Rule,
        event: TriggerEvent,
        actionType: String,
        result: ActionResult,
    ) -> Unit = { _, _, _, _ -> },
    private val onStartFailure: (rule: Rule, cause: Throwable) -> Unit = { _, _ -> },
    private val onSuppressed: (
        rule: Rule,
        event: TriggerEvent,
        unreadable: List<ComponentSpec>,
    ) -> Unit = { _, _, _ -> },
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
     * (Re)starts one rule. Resolving the trigger tree and actions happens here,
     * so a rule naming an unknown type fails at start with
     * [UnknownComponentException] rather than silently never firing. [sync] is
     * the caller that catches that; this one throws, so a deliberate
     * single-rule start still reports it.
     *
     * **One job per rule, however many leaves the trigger tree has.** Every
     * leaf of [Rule.trigger] — see [TriggerNode] — is a candidate to start the
     * rule, `ALL`/`ANY` groups included, and a rule with three leaves is
     * *semantically* three copies of itself. But three independent collectors
     * would run the actions concurrently when two leaves fire together, and a
     * rule has always done one thing at a time. So every leaf's [Trigger.events]
     * is merged into a single flow and collected once: same semantics, and
     * cancellation stays the single stop button that `stopRule` and the
     * editor's Test button rely on. A leaf that never produces events —
     * `time_window`'s `events()` is `emptyFlow()` — contributes nothing to the
     * merge and needs no special case; that emptiness is the whole point of
     * [TriggerFactory.producesEvents] existing.
     *
     * Triggers are built here, once per distinct leaf [ComponentSpec], rather
     * than per event. Every leaf may be asked for its state on any other
     * leaf's fire — see [TriggerNode.holds] — and constructing a fresh one
     * each time would pay the factory's cost repeatedly, and for anything
     * holding a resource, do so needlessly.
     */
    fun startRule(rule: Rule) = synchronized(lock) {
        stopRule(rule.id)

        val leafPaths = rule.trigger.leafPaths()
        val triggersBySpec: Map<ComponentSpec, Trigger> = leafPaths
            .map { (_, spec) -> spec }
            .distinct()
            .associateWith(registry::createTrigger)
        // Paired with the spec they were built from, so an outcome can say which
        // action it belongs to. The instance alone does not know its own type.
        val actions = rule.actions.map { spec -> spec.type to registry.createAction(spec) }

        val job = scope.launch {
            leafPaths
                .map { (path, spec) -> triggersBySpec.getValue(spec).events().map { path to it } }
                .merge()
                .collect { (firedPath, event) ->
                    val reader = StateReader(triggersBySpec)
                    if (!triggerHolds(rule.trigger, firedPath, reader)) {
                        // Only when something could not be read. A tree that
                        // held back because a condition answered a clean "no" is
                        // the rule doing its job, and reporting that would cry
                        // wolf on every rule with a condition in it.
                        if (reader.unreadable.isNotEmpty()) {
                            onSuppressed(rule, event, reader.unreadable.toList())
                        }
                        return@collect
                    }
                    actions.forEach { (type, action) -> run(rule, type, action, event) }
                }
        }
        jobs[rule.id] = Running(rule, job)
        Unit
    }

    /**
     * Whether [trigger] holds, given the leaf at [firedPath] that just started
     * this evaluation — see [TriggerNode.holds].
     *
     * [reader] both answers the state questions and records which of them could
     * not be answered, so the caller can tell a rule held back by a "no" from
     * one held back by a component that could not look. See [StateReader].
     */
    private suspend fun triggerHolds(
        trigger: TriggerNode,
        firedPath: NodePath,
        reader: StateReader,
    ): Boolean = trigger.holds(firedPath, reader::read)

    /**
     * Reads leaf states for one evaluation, and remembers which ones could not
     * answer.
     *
     * A class rather than a lambda because the "could not answer" set has to
     * outlive the read and be asked about afterwards. One instance per event,
     * never shared: two events evaluating at once would otherwise blame each
     * other's components. That also means [unreadable] needs no synchronisation.
     *
     * Only the components actually consulted appear. [TriggerNode.holds]
     * short-circuits, which is a promise it makes rather than an optimisation,
     * so a component the evaluation never reached is not something that failed
     * to answer.
     */
    private class StateReader(private val triggersBySpec: Map<ComponentSpec, Trigger>) {

        val unreadable = mutableListOf<ComponentSpec>()

        /**
         * A state read that throws is treated as unknown rather than as a
         * definite no. That is `null`, which matches [TriggerNode.holds]'s "null
         * does not satisfy": a state nobody could read is unknown, and firing
         * unattended actions on an unknown state is the worse of the two
         * failures. Cancellation is rethrown, because a cancelled rule is not a
         * rule whose trigger failed to hold.
         *
         * A spec with no trigger built for it also reads as unknown. That is
         * unreachable through [startRule], which builds one per distinct leaf,
         * and it is recorded rather than ignored so it could never become a
         * silent third meaning of null.
         */
        suspend fun read(spec: ComponentSpec): Boolean? {
            val answer = try {
                triggersBySpec[spec]?.currentlyHolds()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                null
            }
            if (answer == null) unreadable += spec
            return answer
        }
    }

    /**
     * One misbehaving action must not take down the rule that hosts it, or the
     * actions queued behind it — a rule that silently stops firing is the worst
     * failure mode this app has. Cancellation is not a failure and is rethrown
     * so the coroutine machinery still sees it.
     */
    private suspend fun run(
        rule: Rule,
        actionType: String,
        action: Action,
        event: TriggerEvent,
    ) {
        val result = try {
            action.execute(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ActionResult.Failure("This action threw ${t::class.simpleName}. ${t.message}", t)
        }
        onOutcome(rule, event, actionType, result)
    }

    fun stopRule(ruleId: String) = synchronized(lock) {
        jobs.remove(ruleId)?.job?.cancel()
        Unit
    }

    fun stop() = synchronized(lock) {
        jobs.keys.toList().forEach(::stopRule)
    }
}
