package app.phueber.trigly.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What an action said when it last failed, per rule.
 *
 * The gap this closes. A rule whose trigger fires and whose action then fails
 * said nothing at all: `EngineService` wrote a line to logcat and stopped there.
 * So "my rule does nothing" had two indistinguishable causes, the trigger never
 * firing and the action never working, and the only way to tell them apart was a
 * cable and `adb logcat`. For an app whose whole argument is that a rule which
 * does nothing must say so, that was the hole in the middle of it.
 *
 * **Why the engine does not own this.** `EngineService` owns the engine's
 * lifetime and deliberately hands out no reference to it, so the list screen
 * cannot ask the engine anything. This is a plain sink in the application
 * container instead: the service writes, the list reads, and neither knows about
 * the other.
 *
 * **Why it is not persisted.** The record is worth exactly as long as the engine
 * that produced it. If the process died and came back, a failure from the
 * previous process describes a run whose conditions are gone, and showing it
 * would be a claim this class cannot support. A rule that still fails will fail
 * again and say so again. Persisting it would also mean a schema migration for
 * something no rule depends on.
 */
class ActionFailureLog {

    private val _failures = MutableStateFlow<Map<String, ActionFailure>>(emptyMap())

    /** Keyed by rule id. */
    val failures: StateFlow<Map<String, ActionFailure>> = _failures.asStateFlow()

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
        _failures.update { it + (ruleId to ActionFailure(actionType, reason)) }
    }

    /**
     * Records that [ruleId] fired and then ran nothing, because a component in
     * its trigger tree could not say whether it held.
     *
     * The other half of the same question, and the half that was missing. A rule
     * whose action fails leaves a record; a rule dropped before any action ran
     * left none at all, so "my rule does nothing" still had a cause the app
     * could not name. The area check reading no position in the background is
     * exactly that case, and it is indistinguishable, on screen, from being
     * outside the area.
     *
     * Carries no action type because no action was reached. That absence is the
     * distinction the reader renders, and it is why [ActionFailure.actionType]
     * is nullable rather than carrying a sentinel string.
     */
    fun couldNotDecide(ruleId: String, reason: String) {
        _failures.update { it + (ruleId to ActionFailure(actionType = null, reason = reason)) }
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
     * A [couldNotDecide] record is the exception, and it is cleared by any
     * action succeeding. It says the rule did not run at all, so a single action
     * running is proof that it is stale, whichever action that was.
     */
    fun succeeded(ruleId: String, actionType: String) {
        _failures.update { current ->
            val held = current[ruleId] ?: return@update current
            if (held.actionType == null || held.actionType == actionType) {
                current - ruleId
            } else {
                current
            }
        }
    }

    /**
     * Forgets [ruleId] entirely, for when the rule is deleted or switched off.
     *
     * A stale failure against a rule that no longer exists, or that the person
     * has since turned off, is noise at best and a wrong accusation at worst.
     */
    fun forget(ruleId: String) {
        _failures.update { it - ruleId }
    }
}

/**
 * One failure: which action, and what it said.
 *
 * A null [actionType] means no action was reached, because the rule was dropped
 * before any could run. See [ActionFailureLog.couldNotDecide].
 */
data class ActionFailure(
    val actionType: String?,
    val reason: String,
)
