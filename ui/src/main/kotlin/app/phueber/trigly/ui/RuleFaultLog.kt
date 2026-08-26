package app.phueber.trigly.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Why a rule last did nothing, per rule.
 *
 * The gap this closes. A rule whose trigger fires and whose action then fails
 * said nothing at all: `EngineService` wrote a line to logcat and stopped there.
 * So "my rule does nothing" had causes that looked identical from the outside,
 * and the only way to tell them apart was a cable and `adb logcat`. For an app
 * whose whole argument is that a rule which does nothing must say so, that was
 * the hole in the middle of it.
 *
 * Three ways a rule can do nothing, and they are three different sentences. An
 * action ran and failed. The rule fired and was dropped because part of its
 * trigger could not say whether it held. Or the rule was never built at all, so
 * nothing was ever watching. See [RuleFault.Kind]. This class was named for the
 * first of them alone until the third arrived.
 *
 * **Why the engine does not own this.** `EngineService` owns the engine's
 * lifetime and deliberately hands out no reference to it, so the list screen
 * cannot ask the engine anything. This is a plain sink in the application
 * container instead: the service writes, the list reads, and neither knows about
 * the other.
 *
 * **Why it is not persisted.** The record is worth exactly as long as the engine
 * that produced it. If the process died and came back, a fault from the previous
 * process describes a run whose conditions are gone, and showing it would be a
 * claim this class cannot support. A rule that still fails will fail again and
 * say so again. Persisting it would also mean a schema migration for something
 * no rule depends on.
 */
class RuleFaultLog {

    private val _faults = MutableStateFlow<Map<String, RuleFault>>(emptyMap())

    /** Keyed by rule id. */
    val faults: StateFlow<Map<String, RuleFault>> = _faults.asStateFlow()

    /**
     * Records that [actionType] in [ruleId] failed, replacing whatever that rule
     * had before.
     *
     * One entry per rule rather than one per action, because the question being
     * answered is "why did this rule do nothing", and the most recent answer is
     * the useful one. The action type is kept so the reader can say which action
     * it was, and so [succeeded] knows what it is allowed to clear.
     */
    fun failed(ruleId: String, actionType: String, reason: String) {
        _faults.update {
            it + (ruleId to RuleFault(RuleFault.Kind.ACTION_FAILED, reason, actionType))
        }
    }

    /**
     * Records that [ruleId] fired and then ran nothing, because a component in
     * its trigger tree never said whether it held, even after the engine asked
     * it again.
     *
     * A rule whose action fails leaves a record; a rule dropped before any
     * action ran left none at all, so "my rule does nothing" still had a cause
     * the app could not name. The area check reading no position in the
     * background is exactly that case, and it is indistinguishable, on screen,
     * from being outside the area.
     *
     * **Called only once the engine has given up, never on the first miss.**
     * `TriggerEngine.resolveHolds` retries a component that could not answer,
     * on a bounded schedule, before this is reached at all. A component that
     * answers on a later try never produces a call here: that is the rule
     * working, later than usual, and not a fault worth a record. This is the
     * outcome for the other case, the one `docs/conditions.md` warns reads
     * exactly the same at the call site: a component that never answers.
     */
    fun couldNotDecide(ruleId: String, reason: String) {
        _faults.update { it + (ruleId to RuleFault(RuleFault.Kind.UNDECIDED, reason)) }
    }

    /**
     * Records that [ruleId] could not be built, so nothing is watching for it.
     *
     * The last way a rule could do nothing and say nothing. The other two
     * records are about a run: something happened and then went wrong. This one
     * says no run was ever possible, because resolving the rule's components
     * threw before a single receiver was registered. An unknown type from a file
     * a newer build exported, or config a factory refuses.
     *
     * It looked exactly like a rule waiting patiently for its trigger. The rules
     * screen even showed it as on, because it is: stored, enabled, and never
     * started. The engine's `Log.w` line was the only trace, and reading that
     * needs the cable this whole class exists to make unnecessary.
     */
    fun couldNotStart(ruleId: String, reason: String) {
        _faults.update { it + (ruleId to RuleFault(RuleFault.Kind.COULD_NOT_START, reason)) }
    }

    /**
     * Clears the record for [ruleId], but only if the success speaks for it.
     *
     * The guard is what makes a rule with several actions honest. If the second
     * of three actions fails and the third succeeds, an unguarded clear would
     * erase the failure a moment after recording it, and the rule would look
     * fine while doing two thirds of its job. A success only speaks for the
     * action that succeeded.
     *
     * Any other kind of fault is cleared by any action succeeding. Both say the
     * rule did not run at all, so a single action running is proof that the
     * record is stale, whichever action that was.
     */
    fun succeeded(ruleId: String, actionType: String) {
        _faults.update { current ->
            val held = current[ruleId] ?: return@update current
            if (held.kind != RuleFault.Kind.ACTION_FAILED || held.actionType == actionType) {
                current - ruleId
            } else {
                current
            }
        }
    }

    /**
     * Clears a [couldNotStart] record for [ruleId], and nothing else.
     *
     * Called for every rule the engine has running, on every sync. A rule that
     * is running is not a rule that failed to start, and the record has to go
     * the moment an edit fixes the config, without waiting for the trigger to
     * fire. Narrow on purpose: a failed action from an earlier run is still
     * true, and a rule that starts says nothing about it.
     */
    fun started(ruleId: String) {
        _faults.update { current ->
            if (current[ruleId]?.kind == RuleFault.Kind.COULD_NOT_START) current - ruleId else current
        }
    }

    /**
     * Forgets [ruleId] entirely, for when the rule is deleted or switched off.
     *
     * A stale fault against a rule that no longer exists, or that the person has
     * since turned off, is noise at best and a wrong accusation at worst.
     */
    fun forget(ruleId: String) {
        _faults.update { it - ruleId }
    }
}

/**
 * Why one rule last did nothing.
 *
 * [actionType] is set only for [Kind.ACTION_FAILED], because it is the only kind
 * where an action was reached. That is a distinction the reader renders as a
 * different sentence rather than a missing word in the same one.
 */
data class RuleFault(
    val kind: Kind,
    val reason: String,
    val actionType: String? = null,
) {
    /** Three ways to do nothing, and three different things to tell the reader. */
    enum class Kind {
        /** An action ran and failed. */
        ACTION_FAILED,

        /** The rule fired, and a component could not say whether it held. */
        UNDECIDED,

        /** The rule was never built, so nothing was watching. */
        COULD_NOT_START,
    }
}
