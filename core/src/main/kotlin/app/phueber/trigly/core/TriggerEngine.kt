package app.phueber.trigly.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 *   then not run, because a component in the tree never answered whether it
 *   held, even after [resolveHolds] retried it. Carries the components that
 *   could not answer, never the ones that answered no. A rule held back by a
 *   condition that plainly said "no" is the rule working; a rule held back by
 *   one that could not look, and still could not look after retrying, is a
 *   fault, and until this existed the two were the same silence. Called once
 *   per event, only after the retry budget is spent, never on the first miss:
 *   a component that answers on the second or third try is the rule working
 *   late, not a fault. See [resolveHolds].
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
                    val resolved = resolveHolds(rule.trigger, firedPath, triggersBySpec)
                    if (!resolved.held) {
                        // Only when something never answered, even after
                        // retrying. A tree that held back because a condition
                        // answered a clean "no" is the rule doing its job, and
                        // reporting that would cry wolf on every rule with a
                        // condition in it.
                        if (resolved.unreadable.isNotEmpty()) {
                            onSuppressed(rule, event, resolved.unreadable)
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
     * What one call to [resolveHolds] found: whether the tree held, and, if it
     * did not, which leaves still could not answer on the last try.
     */
    private class ResolvedHolds(val held: Boolean, val unreadable: List<ComponentSpec>)

    /**
     * Evaluates [trigger] against [firedPath], and asks again if a leaf could
     * not answer, before finally treating the event as one nobody could decide.
     *
     * **This is the retry T3 asks for.** Before it existed, one failed read
     * dropped the event for good: a door opens, a position read misses for a
     * second or two, and the rule never gets another chance at that event. That
     * is the gap this closes. It does not change the conservative half beside
     * it: an unknown state still does not hold, on any single try; see
     * [TriggerNode.holds] and [StateReader.read].
     *
     * **The schedule: up to [UNREADABLE_RETRIES] extra tries, [UNREADABLE_RETRY_DELAY_MILLIS]
     * apart.** Four reads in total, spread over six seconds at most. A leaf that
     * answers on any of them is treated exactly like one that answered on the
     * first: the rule fires and nothing is reported, because a component that
     * missed once and then answered is the rule working, not a fault.
     *
     * **Why six seconds and not longer.** A rule's actions are unattended, and
     * firing them late can be worse than not firing them at all: a door that
     * has since been closed again does not want an "unlock" action arriving a
     * minute after the event that asked for it. The retry exists to ride out a
     * read that misses for a couple of seconds, which is the failure this item
     * was written against, not to hold an event open indefinitely on the chance
     * that a much longer outage clears. So the trade here favours giving up
     * over holding on: past a few seconds with no answer, a late fire is judged
     * more likely to be wrong than a dropped one.
     *
     * **Why a coroutine `delay` here is not the scheduler `docs/todo.md` T1
     * asks for.** T1's waits stop because the process is asleep and nothing
     * wakes it up again. This wait runs inside a job that is already alive,
     * already reacting to an event that already happened; the process stays
     * awake for the six seconds, the same as it stays awake while an action
     * runs. A short delay inside a live reaction is not the failure a delay
     * meant to sleep through a Doze window for minutes is.
     *
     * **What this does not do.** It holds one event, for one rule, for a few
     * seconds; it does not queue events without bound and it does not persist
     * anything. A process killed mid-wait loses the retry along with
     * everything else the process was doing, which is no different from today.
     * Whether the eventual give-up is itself worth persisting is `docs/todo.md`
     * T8's question, not this one's.
     *
     * **The trap this is guarding against.** A permanent unknown reads exactly
     * like a temporary one at the call site: both are a `null` from
     * [StateReader.read], on every single try, indistinguishable until the
     * budget runs out. That is why the give-up has to be unconditional once the
     * tries are spent. Naming it as its own outcome, rather than letting it read
     * as the rule quietly doing nothing, is then the caller's job; see
     * [onSuppressed].
     */
    private suspend fun resolveHolds(
        trigger: TriggerNode,
        firedPath: NodePath,
        triggersBySpec: Map<ComponentSpec, Trigger>,
    ): ResolvedHolds {
        var reader = StateReader(triggersBySpec)
        var held = triggerHolds(trigger, firedPath, reader)

        var retries = 0
        while (!held && reader.unreadable.isNotEmpty() && retries < UNREADABLE_RETRIES) {
            delay(UNREADABLE_RETRY_DELAY_MILLIS)
            reader = StateReader(triggersBySpec)
            held = triggerHolds(trigger, firedPath, reader)
            retries++
        }
        return ResolvedHolds(held, reader.unreadable.toList())
    }

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

/**
 * How many extra tries [TriggerEngine.resolveHolds] gets after a state read
 * that could not answer, beyond the one it already made.
 *
 * Chosen together with [UNREADABLE_RETRY_DELAY_MILLIS]: see that constant, and
 * [TriggerEngine.resolveHolds], for the trade this bound makes.
 *
 * `internal`, not `private`, so `TriggerEngineTest` can advance virtual time by
 * exactly this much rather than by a magic number that quietly drifts from the
 * real schedule.
 */
internal const val UNREADABLE_RETRIES = 3

/**
 * How long [TriggerEngine.resolveHolds] waits between one try and the next.
 *
 * Two seconds, three times, six seconds total: long enough to ride out the
 * failure this exists for, a state read that misses for a second or two, and
 * short enough that a rule whose actions are unattended is not left firing on
 * a stale event. See [TriggerEngine.resolveHolds] for the full reasoning.
 */
internal const val UNREADABLE_RETRY_DELAY_MILLIS = 2_000L
