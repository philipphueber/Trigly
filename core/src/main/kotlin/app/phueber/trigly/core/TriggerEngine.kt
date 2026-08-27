package app.phueber.trigly.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs enabled [Rule]s: collects each rule's trigger and executes its actions.
 *
 * Deliberately free of Android and UI types so it can be driven from a unit
 * test with fake triggers — see `TriggerEngineTest`. The owning service
 * supplies the [scope]; cancelling that scope stops everything.
 *
 * @param store the app-scope variable store. Required, not defaulted: a
 *   default here would mean a production assembly point that forgot to pass
 *   the real store fails silently, with every `{{app.*}}` reference reading as
 *   empty rather than the build failing to compile. See `ActionSlot` for where
 *   this is read.
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
    private val store: VariableStore,
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
) : RuleRunner {
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
        // Which namespace each leaf answers to, keyed by the path the merge
        // below already carries. Two leaves of one type are told apart here and
        // nowhere else: the event itself cannot say which of them produced it.
        val instanceByPath: Map<NodePath, String> = leafPaths
            .map { (path, _) -> path }
            .zip(componentInstanceNames(leafPaths.map { (_, spec) -> spec.type }))
            .toMap()
        // Paired with the spec they were built from, so an outcome can say which
        // action it belongs to. The instance alone does not know its own type.
        val actionInstances = componentInstanceNames(rule.actions.map { it.type })
        val actions = rule.actions.mapIndexed { index, spec ->
            ActionSlot(spec, registry, store, actionInstances[index])
        }

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
                    // The base of a run_rule chain. See runNow. Set here, on
                    // the normal firing path, so a run_rule action inside
                    // this very rule sees its own id as already "in
                    // progress" from the first hop, not only from the
                    // second.
                    withContext(RunChain(listOf(rule.id))) {
                        runActions(rule, event, actions, instanceByPath[firedPath])
                    }
                }
        }
        jobs[rule.id] = Running(rule, job)
        Unit
    }

    /**
     * Runs [actions] once for [event], in order, threading [ActionOutputs]
     * from one to the next. This is the body [startRule] always ran, and it
     * is also the body [runNow] runs for a rule invoked on demand. One
     * implementation, factored out once, is what keeps those two paths from
     * drifting into two subtly different ways to run a rule's actions.
     *
     * Deliberately takes [actions] rather than building it. [startRule]
     * builds its list once, outside this call, and reuses it for every
     * event: that is the compatibility promise [ActionSlot] documents.
     * [runNow] keeps no such promise. A rule run on demand has no "next
     * event" of its own to reuse the list for, so it builds a fresh one
     * every time.
     */
    private suspend fun runActions(
        rule: Rule,
        event: TriggerEvent,
        actions: List<ActionSlot>,
        firedTriggerInstance: String?,
    ) {
        // Fresh for every call, and never carried to the next one: see
        // ActionOutputs. Grows as each action below returns, so the action
        // after it can read what was just produced, the same way the
        // app-scope store is read fresh immediately before every action.
        var actionOutputs = ActionOutputs.EMPTY
        actions.forEach { slot ->
            when (val filled = slot.fill(rule, event, actionOutputs, firedTriggerInstance)) {
                is ActionSlot.Filled.Ready -> {
                    val result = run(rule, slot.type, filled.action, event)
                    if (result is ActionResult.Success) {
                        // Keyed by the slot's namespace, not its type, so two
                        // actions of one type keep their outputs apart.
                        actionOutputs = actionOutputs.plus(slot.namespace, result.outputs)
                    }
                }
                // A field that could not be filled in is reported through the
                // same hook a failed run uses, so it reaches the rule's fault
                // log as what it is: this action did not do its job, and
                // here is why.
                is ActionSlot.Filled.Refused ->
                    onOutcome(rule, event, slot.type, ActionResult.Failure(filled.reason))
            }
        }
    }

    /**
     * Runs [rule]'s actions once, right now, for `run_rule`. See [RuleRunner].
     * Bypasses [rule]'s own trigger and its `enabled` flag entirely. This can
     * run a disabled rule's actions, which turns a rule into something close
     * to a callable routine: kept off so its own trigger never fires it, and
     * reached only this way.
     *
     * **[causingEvent] is reused, not replaced.** [rule] never fired its own
     * trigger, so there is no fresh [TriggerEvent] of its own to build one
     * from. Reusing the event that caused the call means `{{event.*}}` and
     * `{{trigger.*}}` inside [rule]'s actions still read the payload that
     * started this whole chain. `{{rule.*}}` still reads as [rule] itself,
     * because [runActions] is given [rule], not the caller's rule: the
     * actions belong to [rule], and were written expecting their own rule's
     * name and id.
     *
     * **The guard against a loop.** `docs/variables.md` section 11 refused a
     * `variable_changed` trigger for exactly this shape: rule A changes
     * something that starts rule B, which changes something that starts rule
     * A. It wrote down that a guard has to exist before such a feature ships,
     * not after. `run_rule` is that same shape with an explicit call in place
     * of an implicit one, so the same guard applies here, in two parts:
     *
     * - **A rule cannot run itself**, directly or by appearing again further
     *   down its own chain of `run_rule` calls. This is refused outright
     *   rather than merely counted against the depth cap below, because
     *   nothing makes a cycle safe at any depth: it repeats forever on its
     *   own once it is allowed once. `TriggerEngineTest` covers the direct
     *   case. The depth cap below is what catches an indirect cycle this
     *   check cannot see.
     * - **A chain deeper than [MAX_RUN_RULE_CHAIN_DEPTH] is refused.** This
     *   catches a cycle through rules that are all distinct from each other,
     *   rule A running rule B running rule C and on, which the same-rule
     *   check above cannot see because no single rule ever repeats. See
     *   [MAX_RUN_RULE_CHAIN_DEPTH]'s own KDoc for the number and the
     *   reasoning behind it.
     *
     * **Why a coroutine context element, and not a parameter.**
     * [Action.execute] takes only a [TriggerEvent]. `run_rule` cannot hand
     * this method the chain so far, because nothing gives `run_rule` a way to
     * know it. The chain is instead ambient on the coroutine that is running
     * one firing of one rule from the top. [startRule] sets it to `[rule.id]`
     * before the first action of a normal firing runs, and every nested
     * [runNow] extends it by one before running the actions it was asked
     * for. A coroutine context element is what makes that ambient value
     * visible to a suspend call several layers down, without threading it
     * through every signature on the way. That is exactly `run_rule`'s
     * situation: it is an ordinary [Action], built and called the same way
     * every other action is.
     *
     * **A rule that cannot be built fails cleanly, not by throwing.**
     * Resolving [rule]'s actions can throw [UnknownComponentException] the
     * same way [startRule] can, most likely from an import from a newer
     * build. [startRule] lets that propagate, because [sync] is there to
     * catch it for every rule at once. Nothing here plays that role for an
     * on-demand run reached from inside another rule's own action, so this
     * catches it itself and answers [RunRuleOutcome.Refused] with what went
     * wrong. That becomes `run_rule`'s own failure reason.
     */
    override suspend fun runNow(rule: Rule, causingEvent: TriggerEvent): RunRuleOutcome {
        val chain = coroutineContext[RunChain]?.ruleIds.orEmpty()

        if (rule.id in chain) {
            return RunRuleOutcome.Refused(
                "'${rule.name}' is already running, earlier in this same chain of " +
                    "run-rule calls. Running it again would never stop, so Trigly refuses."
            )
        }
        if (chain.size >= MAX_RUN_RULE_CHAIN_DEPTH) {
            return RunRuleOutcome.Refused(
                "This chain of run-rule calls is already $MAX_RUN_RULE_CHAIN_DEPTH " +
                    "rules deep. Trigly stops here so one rule cannot run another forever."
            )
        }

        return try {
            val instances = componentInstanceNames(rule.actions.map { it.type })
            val actions = rule.actions.mapIndexed { index, spec ->
                ActionSlot(spec, registry, store, instances[index])
            }
            // No fired instance: this rule did not fire its own trigger, so no
            // leaf of *this* rule produced the event. `{{trigger.*}}` still
            // reads the causing event, per runNow's own KDoc, but a numbered
            // leaf namespace of this rule has nothing to resolve against.
            withContext(RunChain(chain + rule.id)) {
                runActions(rule, causingEvent, actions, firedTriggerInstance = null)
            }
            RunRuleOutcome.Ran
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            RunRuleOutcome.Refused(
                "'${rule.name}' could not be built. ${t.message ?: t::class.simpleName}"
            )
        }
    }

    /**
     * One action of a running rule, and the seam where a variable becomes a
     * value. See `docs/variables.md`.
     *
     * **Why the seam is here and not in `create()`.** An action is built once
     * per rule start and reused for every event, so a field's value is captured
     * in a constructor before any event exists: `HttpRequestAction` holds its
     * URL as a string. And **not inside each action** either, because that puts
     * the same code in twenty actions, and an action that forgets it is a rule
     * that quietly ignores its own variables, which is the coupling `CLAUDE.md`
     * calls the abstraction being wrong.
     *
     * **A rule with no variables behaves exactly as it did before this existed.**
     * The instance is built once, here, and every event reuses it. That is not an
     * optimisation, it is the compatibility promise: rebuilding a component
     * needlessly is how this project has caused phantom firings before, and no
     * existing rule should start paying for a feature it does not use.
     *
     * An action that *does* use one is rebuilt only when its resolved config
     * differs from what the live instance was built from. So a rule whose
     * variable resolves to the same value twice running also reuses the
     * instance, and only a value that really changed costs a construction.
     *
     * The instance built at start time from the *raw* config is built even for an
     * action that will be rebuilt on its first event. That is what keeps an
     * unknown type, or config a factory refuses, failing inside [startRule]
     * where [onStartFailure] reports it, rather than at the first event where it
     * would read as a failed run. One wasted construction per templated action,
     * once, in exchange for that.
     *
     * **The app-scope store is read once per action, immediately before that
     * action runs, never once for the whole event.** A rule's actions run in
     * sequence, and one of them can be `set_variable`, writing a value a later
     * one reads. A snapshot taken once before the first action would show a
     * later action the value from before the write, and "actions run in
     * order" is how anybody reads a list of actions. So [fill] fetches only
     * the store, and only the names this action's own templates reference,
     * on every call. An action whose templates name no app variable never
     * touches the store at all: [appVariableNames] is computed once, here,
     * and an empty set is the fast path that keeps the cost on the rules that
     * actually use app scope.
     *
     * **An action's own outputs follow the same rule, without a store to
     * read.** [startRule] keeps an [ActionOutputs] that grows as each action
     * returns, and passes it into [fill] for the next one, so `{{action.*}}`
     * sees what an earlier action in this run produced for the same reason
     * `{{app.*}}` does. It costs nothing to pass: unlike the store, it is
     * already in memory, built for this one event.
     */
    private class ActionSlot(
        private val spec: ComponentSpec,
        private val registry: Registry,
        private val store: VariableStore,
        /**
         * This action's namespace, from [componentInstanceNames]. The bare
         * type for the first action of its type in the rule, `<type>_2` for
         * the second.
         *
         * Held per slot rather than computed where it is used, because the
         * number depends on the whole rule's action list and a slot is the
         * only thing that knows its own position in it. An outcome still
         * reports [type], because a fault log names the action a person
         * recognises rather than a namespace.
         */
        val namespace: String,
    ) {

        val type: String get() = spec.type

        /**
         * The config keys that hold a reference, with the escaping each needs.
         *
         * Parsed once at start time. A field with no reference in it is absent
         * from here, which is what makes the fast path in [fill] free rather
         * than merely cheap.
         */
        private val templates: Map<String, Pair<Template, Substitution>> =
            registry.substitutionsFor(spec).mapNotNull { (key, encoding) ->
                val stored = spec.config[key] ?: return@mapNotNull null
                val template = parseTemplate(stored)
                if (template.hasReferences) key to (template to encoding) else null
            }.toMap()

        /**
         * The app-scope names this action's templates reference, so [fill]
         * knows without re-parsing on every event whether it needs the store
         * at all.
         */
        private val appVariableNames: Set<String> = templates.values
            .flatMap { (template, _) -> template.references }
            .filter { it.scope == VariableScope.APP }
            .mapTo(mutableSetOf()) { it.name }

        private var builtFrom: Map<String, String> = spec.config

        private var instance: Action = registry.createAction(spec)

        /** The action to run for this event, or why there is none. */
        sealed interface Filled {
            data class Ready(val action: Action) : Filled
            data class Refused(val reason: String) : Filled
        }

        suspend fun fill(
            rule: Rule,
            event: TriggerEvent,
            actionOutputs: ActionOutputs,
            firedTriggerInstance: String?,
        ): Filled {
            if (templates.isEmpty()) return Filled.Ready(instance)

            // Only the names this action actually needs, read right now: see
            // the class KDoc for why "right now" and not "once for the event".
            val appVariables: Map<String, String> = if (appVariableNames.isEmpty()) {
                emptyMap()
            } else {
                buildMap {
                    for (name in appVariableNames) {
                        store.get(name)?.let { put(name, it) }
                    }
                }
            }

            // No pre-filtering like appVariableNames above: an earlier
            // action's outputs are already in memory, built by the caller for
            // this one event, so there is no per-action fetch cost to spare.
            val lookup = EventLookup(
                rule,
                event,
                appVariables = appVariables,
                actionOutputs = actionOutputs,
                firedTriggerInstance = firedTriggerInstance,
            )
            val resolved = builtFrom.toMutableMap()
            for ((key, form) in templates) {
                val (template, encoding) = form
                when (val filled = template.substitute(lookup, encoding)) {
                    is Substituted.Ok -> resolved[key] = filled.value
                    is Substituted.Failed -> return Filled.Refused(
                        "Trigly could not fill in the '$key' setting. ${filled.reason}"
                    )
                }
            }

            if (resolved == builtFrom) return Filled.Ready(instance)

            // A factory can refuse a resolved value where it accepted the raw
            // one: a variable carries whatever the platform put in it. That is
            // this action failing rather than the rule failing to start, so it
            // is reported the way a failed run is.
            val rebuilt = try {
                registry.createAction(spec.copy(config = resolved))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                return Filled.Refused(
                    "Trigly filled in the settings for this action and then could not " +
                        "build it. ${t.message}"
                )
            }

            builtFrom = resolved
            instance = rebuilt
            return Filled.Ready(rebuilt)
        }
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
     * apart, inside [UNREADABLE_TOTAL_BUDGET_MILLIS] altogether.** A leaf that
     * answers on any of them is treated exactly like one that answered on the
     * first: the rule fires and nothing is reported, because a component that
     * missed once and then answered is the rule working, not a fault.
     *
     * **The budget counts the reads, not only the gaps between them.** An
     * earlier version bounded the waiting and left the reading unbounded, which
     * is not a bound at all: a leaf whose own read is slow can spend far longer
     * than the schedule suggests, and the position read in the `location`
     * component is allowed fifteen seconds by itself. Four of those plus the
     * gaps is about a minute, with the rule's collector held for all of it,
     * while this KDoc claimed six seconds. So the whole resolve now runs inside
     * one budget and a read cancelled by it is reported as a leaf that did not
     * answer, which is the outcome that already has a name here.
     *
     * [UNREADABLE_TOTAL_BUDGET_MILLIS] is set above the longest legitimate
     * single read in the project rather than below it. A budget under fifteen
     * seconds would cut off a position read that was going to succeed, and
     * turning a slow answer into no answer is not what this is for.
     *
     * **Why seconds and not longer.** A rule's actions are unattended, and
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
        val reader = StateReader(triggersBySpec)

        val held = withTimeoutOrNull(UNREADABLE_TOTAL_BUDGET_MILLIS) {
            var holds = triggerHolds(trigger, firedPath, reader)

            var retries = 0
            while (!holds && reader.unreadable.isNotEmpty() && retries < UNREADABLE_RETRIES) {
                delay(UNREADABLE_RETRY_DELAY_MILLIS)
                holds = triggerHolds(trigger, firedPath, reader)
                retries++
            }
            holds
        } ?: false

        return ResolvedHolds(held, reader.unreadable)
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

        /**
         * The latest answer from each leaf this evaluation has asked, where a
         * null value means "asked, and did not answer".
         *
         * A map rather than a list of failures, and one reader for the whole
         * resolve rather than one per try, because of two cases a list of
         * failures cannot express. A leaf that could not answer on one try and
         * answered on the next must stop counting as unreadable, which an
         * append-only list cannot undo. And a read still in flight when the
         * budget expires never returns at all: [read] marks the leaf before it
         * asks, so a cancelled read leaves the mark behind and is reported as
         * what it is, a leaf that did not answer.
         */
        private val answers = mutableMapOf<ComponentSpec, Boolean?>()

        val unreadable: List<ComponentSpec>
            get() = answers.filterValues { it == null }.keys.toList()

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
            // Marked before the read, not after it. A read cancelled by the
            // budget throws from inside `currentlyHolds` and never comes back
            // here, so a mark written afterwards would be lost and the leaf
            // that ran the clock out would go unnamed.
            answers[spec] = null
            val answer = try {
                triggersBySpec[spec]?.currentlyHolds()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                null
            }
            answers[spec] = answer
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
    ): ActionResult {
        val result = try {
            action.execute(event)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            ActionResult.Failure("This action threw ${t::class.simpleName}. ${t.message}", t)
        }
        onOutcome(rule, event, actionType, result)
        return result
    }

    fun stopRule(ruleId: String) = synchronized(lock) {
        jobs.remove(ruleId)?.job?.cancel()
        Unit
    }

    fun stop() = synchronized(lock) {
        jobs.keys.toList().forEach(::stopRule)
    }

    /**
     * The rule ids currently active in one chain of `run_rule` calls, base
     * rule first. See [runNow] for how this is built and read.
     *
     * A coroutine context element rather than a field on the engine, because
     * two different rules can each be mid-chain on their own coroutine at
     * the same time, and a shared field would mix their chains together. A
     * context element is scoped to the one coroutine that carries it, which
     * is exactly the lifetime of one firing's worth of nested calls.
     */
    private class RunChain(val ruleIds: List<String>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RunChain>
    }
}

/**
 * How many rules deep one chain of `run_rule` calls may go before
 * [TriggerEngine.runNow] refuses to extend it further. See that method for
 * the cycle this catches and the separate, unconditional refusal of a rule
 * that runs itself.
 *
 * Eight, chosen the way [UNREADABLE_RETRIES] is: comfortably past any chain
 * a person would deliberately build (a handful of rules handing off to the
 * next, such as a sequence of modes), and short enough that a mistake is
 * refused quickly rather than after visibly heavy work. `run_rule` has no
 * way to know in advance whether a chain is a deliberate design or a
 * mistake, so the cap is picked to be generous to the first case and cheap
 * for the second.
 *
 * Public, not `internal`: `run_rule`'s own warning text in `:actions` states
 * this number, so a person reads the same figure the engine actually
 * enforces rather than a copy that could drift from it.
 */
const val MAX_RUN_RULE_CHAIN_DEPTH: Int = 8

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

/**
 * The ceiling on one whole evaluation in [TriggerEngine.resolveHolds], the
 * reads included and not only the waits between them.
 *
 * Twenty seconds, which is above the fifteen the `location` component allows
 * one position read and below the minute that four such reads plus the gaps
 * would otherwise take. The rule's collector is held for this long in the worst
 * case, so it bounds how late one event can make a rule, and it is deliberately
 * not tight enough to interrupt a slow read that was going to answer.
 */
internal const val UNREADABLE_TOTAL_BUDGET_MILLIS = 20_000L
