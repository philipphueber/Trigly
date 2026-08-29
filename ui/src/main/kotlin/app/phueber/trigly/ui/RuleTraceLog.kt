package app.phueber.trigly.ui

import app.phueber.trigly.core.TriggerTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The last trigger evaluation for each rule, one slot per rule id, overwritten.
 *
 * **Why this is a separate sink from [RuleFaultLog], not a fourth [RuleFault.Kind].**
 * A fault is a claim that something is wrong; `TriggerEngine.onEvaluated` fires
 * for every evaluation, including the ones that held and ran fine. Folding a
 * healthy run into a class named for faults would mean either inventing a
 * "kind" that is not a fault at all, or silently dropping the held case to keep
 * the class honest about its own name. Dropping it is exactly the choice
 * this class exists to avoid: see `TriggerEngine.onEvaluated`'s own KDoc for why
 * a run that held is worth keeping too.
 *
 * **Why it is not persisted, in the same words [RuleFaultLog] uses for the same
 * reason.** A trace describes a run under conditions that existed at the time
 * it happened. If the process died and came back, those conditions are gone,
 * and a trace from the previous process would be a claim this class cannot
 * support. A rule that runs again produces a fresh trace of its own.
 *
 * **One overwritten slot is also the answer to the volume question.**
 * `screen_content` can drive ten evaluations a second, and `onEvaluated` fires
 * on every one of them. Overwriting one `Map` entry ten times a second costs
 * nothing; a history or a ring buffer would, and `docs/todo.md`'s trace entry
 * states the constraint as "once per rule rather than once per event" for
 * exactly this reason.
 */
class RuleTraceLog {

    private val _traces = MutableStateFlow<Map<String, TriggerTrace>>(emptyMap())

    /** Keyed by rule id. */
    val traces: StateFlow<Map<String, TriggerTrace>> = _traces.asStateFlow()

    /** Records [ruleId]'s latest evaluation, replacing whatever it had before. */
    fun recorded(ruleId: String, trace: TriggerTrace) {
        _traces.update { it + (ruleId to trace) }
    }

    /**
     * Forgets [ruleId] entirely, for when the rule is deleted.
     *
     * A trace against a rule that no longer exists is noise nobody can act on,
     * and an id is free to be reused by an import. See [RuleFaultLog.forget]
     * for the same reasoning against the same risk.
     */
    fun forget(ruleId: String) {
        _traces.update { it - ruleId }
    }
}
