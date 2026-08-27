package app.phueber.trigly.core

/**
 * What `run_rule` (in `:actions`) asks of the engine: run one rule's actions
 * right now, bypassing that rule's own trigger and its on/off switch. See
 * [TriggerEngine.runNow] for the guard against a rule that runs itself, and
 * `docs/variables.md` section 11 for the loop this guards against, first
 * written down for a `variable_changed` trigger that was never built because
 * this exact guard did not exist yet.
 *
 * **Why this is a separate interface, rather than `run_rule` holding a
 * [TriggerEngine] directly.** `:actions` already depends on `:core`, and
 * [TriggerEngine] lives there, so the module boundary is not the problem the
 * way it is for [NotificationController]. The problem is *when* an engine
 * exists. `run_rule`'s factory is built by `actionFactories()`, which
 * `AppContainer` calls from its own constructor. No [TriggerEngine] exists at
 * that point. `EngineService.onCreate` is what builds one, against the very
 * registry `actionFactories()` is still being assembled into, and it does so
 * only once a rule is enabled. `run_rule` would have nothing to hold if it
 * depended on the class directly.
 *
 * This interface is the seam that lets `run_rule` be built anyway. It asks a
 * [RuleRunner], not a [TriggerEngine]. [RuleRunnerHandle] is the object
 * `AppContainer` hands it: one that starts with nothing to delegate to, and
 * is told about the real engine once one exists. See [RuleFaultLog] in `:ui`
 * for the same shape solved in the other direction. That sink is written by
 * the engine once it exists and read by a screen built before it. This one
 * is called by an action built before the engine exists and answered by the
 * engine once it exists. [RuleFaultLog] can afford to live in `:ui`, next to
 * both its reader and its writer. This cannot, because `:actions` needs to
 * name the interface it calls, so the interface has to live somewhere
 * `:actions` can already see. `:core` is that place.
 */
interface RuleRunner {
    suspend fun runNow(rule: Rule, causingEvent: TriggerEvent): RunRuleOutcome
}

/** What [RuleRunner.runNow] found. */
sealed interface RunRuleOutcome {

    /**
     * The target rule's actions ran. What each one did is reported the same
     * way the calling rule's own actions are, through the engine's usual
     * `onOutcome` callback, against the *target* rule's id, not the caller's.
     * So this carries nothing further. `run_rule` reports only that the run
     * happened at all.
     */
    data object Ran : RunRuleOutcome

    /** The target rule's actions did not run. [reason] is written to become
     * `run_rule`'s own failure reason, so it names what happened rather than
     * merely that something did. */
    data class Refused(val reason: String) : RunRuleOutcome
}

/**
 * A [RuleRunner] that starts with nothing to run against, for `run_rule` to
 * hold before any [TriggerEngine] exists.
 *
 * A call that arrives before [attach] or after [detach] is refused with a
 * true, reportable reason, rather than silently doing nothing. That is the
 * same choice [NotificationController.Unavailable] makes for "notification
 * access is off": "no engine is currently running" is exactly as real a
 * device state.
 *
 * [delegate] is `@Volatile` because [attach] happens on `EngineService`'s
 * creation, and [runNow] is called from whatever coroutine is running a
 * rule's actions: a different one whenever the engine is live at all. An
 * unguarded field could let one thread read a half-published reference. A
 * plain reference assignment behind `@Volatile` cannot tear, so every reader
 * either sees the old value or the fully constructed new one.
 */
class RuleRunnerHandle : RuleRunner {

    @Volatile
    private var delegate: RuleRunner? = null

    /** Called once, when [TriggerEngine] is built, so calls already held by
     * `run_rule` start reaching a real engine. */
    fun attach(runner: RuleRunner) {
        delegate = runner
    }

    /** Called when the engine stops, so a call arriving after that reports
     * why rather than reaching an engine that is no longer collecting
     * anything. */
    fun detach() {
        delegate = null
    }

    override suspend fun runNow(rule: Rule, causingEvent: TriggerEvent): RunRuleOutcome =
        delegate?.runNow(rule, causingEvent)
            ?: RunRuleOutcome.Refused("Trigly's engine is not running right now.")
}
