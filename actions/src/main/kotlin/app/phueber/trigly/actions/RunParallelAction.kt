package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.MAX_RUN_RULE_CHAIN_DEPTH
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.RuleRunner
import app.phueber.trigly.core.RunRuleOutcome
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * Runs the actions of two other rules at the same time, and continues with
 * the next action of this rule only when both of them are finished.
 *
 * **A branch is a rule.** An action holds config, and a list of actions is
 * not config, so there is no list of actions inside an action. `run_rule`
 * already gives a rule a way to call a list of actions by name, and this
 * action uses the same mechanism two times at once. A rule's own action list
 * can also hold a parallel block, with branches edited in place: see
 * `ActionNode.Parallel` in `:core`. That block makes the same decisions as
 * this action, which are listed below. Each branch goes through
 * [RuleRunner.runNow], so a branch gets the same loop guard, the same chain
 * depth cap, the same shared run scope and the same fault reports as
 * `run_rule`. There is no second way to run actions.
 *
 * **The join.** Both branches run as child coroutines of this action. The
 * action returns only after both children are complete, so the action after
 * this one starts after the slower branch.
 *
 * **A failed branch does not stop the other branch.** A branch that the
 * runner refuses (a deleted rule, a loop, a chain that is too deep, a rule
 * that cannot be built) or that throws is recorded, and this action still
 * waits for the other branch. Then this action fails and names each branch
 * that did not run. A failed action *inside* a branch is not a failed branch.
 * The engine reports it against the branch rule, and the branch continues
 * with its next action, the same as for any rule.
 *
 * **Cancellation.** When the engine stops this rule (the rule is turned off,
 * edited or deleted, or the engine stops), it cancels the coroutine that runs
 * this action. Both branches are children of that coroutine, so both are
 * cancelled too, and no action after this one runs. A branch that turns off
 * the rule that called it stops both branches for the same reason.
 *
 * **Variables.** Both branches share the run scope of the firing, the same as
 * a `run_rule` chain does, so a branch reads a `{{local.*}}` value written
 * before this action, and the actions after this action read the values that
 * both branches wrote. When both branches write the same name, the last write
 * is kept, and the order of two parallel writes is not defined. `{{mine.*}}`
 * is per rule, and the two branches are always two different rules, so they
 * cannot write the same rule-scope value. `{{action.*}}` outputs stay inside
 * the branch that made them, the same as for `run_rule`.
 */
class RunParallelAction(
    private val repository: RuleRepository,
    private val runner: RuleRunner,
    private val firstRuleId: String?,
    private val secondRuleId: String?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val firstId = firstRuleId?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("This action has no rule chosen for the first branch.")
        val secondId = secondRuleId?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("This action has no rule chosen for the second branch.")
        if (firstId == secondId) {
            return ActionResult.Failure(
                "Both branches name the same rule. Choose two different rules, or use " +
                    "'Run another rule' to run one rule."
            )
        }

        // Both rules are found before either branch starts. A parallel step
        // that runs only half of what the person built, because one rule was
        // deleted, is a result nobody asked for.
        val rules = repository.rules().first()
        val first = rules.firstOrNull { it.id == firstId }
            ?: return missing(FIRST, firstId)
        val second = rules.firstOrNull { it.id == secondId }
            ?: return missing(SECOND, secondId)

        val outcomes = coroutineScope {
            val firstRun = async { runBranch(first, event) }
            val secondRun = async { runBranch(second, event) }
            listOf(
                Triple(FIRST, first, firstRun.await()),
                Triple(SECOND, second, secondRun.await()),
            )
        }

        val refused = outcomes.mapNotNull { (branch, rule, outcome) ->
            if (outcome !is RunRuleOutcome.Refused) return@mapNotNull null
            "The $branch branch, '${rule.name}', did not run. ${outcome.reason}"
        }
        return if (refused.isEmpty()) {
            ActionResult.Success()
        } else {
            ActionResult.Failure(refused.joinToString(" "))
        }
    }

    /**
     * One branch, made safe for its sibling. Anything other than a real
     * cancellation becomes a refusal, so one branch can never cancel the
     * other through the scope they share.
     *
     * A [CancellationException] that arrives while this coroutine is still
     * active did not come from the engine. An action inside the branch threw
     * it, for example a timeout that leaked. That stops this branch only.
     * [ensureActive] tells the two cases apart: it throws again when the rule
     * itself is being stopped.
     */
    private suspend fun runBranch(rule: Rule, event: TriggerEvent): RunRuleOutcome = try {
        runner.runNow(rule, event)
    } catch (cancellation: CancellationException) {
        currentCoroutineContext().ensureActive()
        RunRuleOutcome.Refused("It stopped before its last action.")
    } catch (t: Throwable) {
        RunRuleOutcome.Refused("It threw ${t::class.simpleName}. ${t.message}")
    }

    private fun missing(branch: String, id: String) = ActionResult.Failure(
        "No rule has the id '$id' for the $branch branch. It was probably deleted after " +
            "this action was set up. Neither branch ran."
    )

    companion object {
        const val TYPE = "run_parallel"
        const val CONFIG_FIRST_RULE = "firstRuleId"
        const val CONFIG_SECOND_RULE = "secondRuleId"

        private const val FIRST = "first"
        private const val SECOND = "second"
    }
}

class RunParallelActionFactory(
    /** The same store the engine reads, so the rules this action names are
     * the real ones. The same as for `run_rule`. */
    private val repository: RuleRepository,
    /** Reaches the engine's own action-running path. See [RuleRunner]. */
    private val runner: RuleRunner,
) : ActionFactory {
    override val type = RunParallelAction.TYPE

    override val displayName = "Run two rules at the same time"
    override val category = ActionCategory.RULES

    override val configFields = listOf(
        ConfigField.RuleRef(
            key = RunParallelAction.CONFIG_FIRST_RULE,
            label = "First branch",
            required = true,
            help = "The actions of this rule run at the same time as the second " +
                "branch. The trigger and the on/off switch of this rule are not used.",
        ),
        ConfigField.RuleRef(
            key = RunParallelAction.CONFIG_SECOND_RULE,
            label = "Second branch",
            required = true,
            help = "Choose a rule that is not the first branch. The next action " +
                "of this rule starts when both branches are finished.",
        ),
    )

    override val warning: String =
        "Each branch is a rule. Make a rule for each branch, keep it off if " +
            "its own trigger must not run it, and choose it here. If one branch " +
            "fails, the other branch continues, and this action fails when both " +
            "are finished. If both branches write the same {{local.*}} value, " +
            "the result is not certain. When this rule stops, both branches " +
            "stop. A branch can run more branches, up to " +
            "$MAX_RUN_RULE_CHAIN_DEPTH rules deep."

    override fun create(config: Map<String, String>): Action = RunParallelAction(
        repository = repository,
        runner = runner,
        firstRuleId = config[RunParallelAction.CONFIG_FIRST_RULE],
        secondRuleId = config[RunParallelAction.CONFIG_SECOND_RULE],
    )
}
