package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ExpressionOutcome
import app.phueber.trigly.core.MAX_RUN_RULE_CHAIN_DEPTH
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.RuleRunner
import app.phueber.trigly.core.RunRuleOutcome
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
import app.phueber.trigly.core.evaluateExpression
import kotlinx.coroutines.flow.first

/**
 * Runs another rule's actions right now, optionally only when an expression
 * holds. The third action whose subject is Trigly itself, after
 * `set_rule_enabled` and `set_variable`.
 *
 * **What "run" means here.** [runner] reaches [app.phueber.trigly.core.TriggerEngine.runNow],
 * which builds and runs the target rule's own actions the same way the
 * engine always has, against the target rule's own id. That target rule's
 * own trigger and its `enabled` flag are not touched and not consulted: a
 * rule reached only this way, kept off so its own trigger never fires it, is
 * a legitimate use, closer to a callable routine than a watched rule.
 *
 * **The condition is a success or a failure, not automatically the former
 * simply for being asked.** [ExpressionOutcome.Failed] means the expression
 * itself is broken: a typo, an unmatched bracket, a name that does not
 * exist. That is this action failing, because the rule cannot say what it
 * meant. A condition that evaluates cleanly to something other than the
 * literal text `true` is different: the action did exactly what it was
 * asked, and the target simply does not run this time. [OUTPUT_RAN] is what
 * lets a person building the rule tell the two apart, and what stops a
 * rule's fault log from filling with red for a condition working as written.
 *
 * A blank condition is not evaluated at all. It means "always", the way
 * [ConfigField.Text.blankMeaning] already lets several fields say a real
 * setting with an empty box, rather than a shorter way to write `true`.
 *
 * **Self-calls and long chains are refused by [runner], not by this class.**
 * See [app.phueber.trigly.core.TriggerEngine.runNow] for the guard and the
 * reasoning, and `docs/variables.md` section 11 for the loop it exists to
 * prevent. This action only turns [RunRuleOutcome.Refused] into its own
 * [ActionResult.Failure], the same as it does for a rule id that no longer
 * exists.
 */
class RunRuleAction(
    private val repository: RuleRepository,
    private val runner: RuleRunner,
    private val ruleId: String?,
    private val condition: String?,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val target = ruleId?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("This action has no rule chosen to run.")

        val rule = repository.rules().first().firstOrNull { it.id == target }
            ?: return ActionResult.Failure(
                "No rule has the id '$target'. It was probably deleted after " +
                    "this action was set up."
            )

        val expression = condition.orEmpty()
        if (expression.isNotBlank()) {
            when (val outcome = evaluateExpression(expression)) {
                is ExpressionOutcome.Failed -> return ActionResult.Failure(
                    "The 'only if' condition could not be worked out. ${outcome.reason}"
                )

                is ExpressionOutcome.Ok -> if (outcome.value != CONDITION_TRUE) {
                    return ActionResult.Success(outputs = mapOf(OUTPUT_RAN to RAN_NO))
                }
            }
        }

        return when (val outcome = runner.runNow(rule, event)) {
            RunRuleOutcome.Ran -> ActionResult.Success(outputs = mapOf(OUTPUT_RAN to RAN_YES))
            is RunRuleOutcome.Refused -> ActionResult.Failure(outcome.reason)
        }
    }

    companion object {
        const val TYPE = "run_rule"
        const val CONFIG_RULE = "ruleId"
        const val CONFIG_CONDITION = "condition"

        /** The output key the factory declares below for whether the target ran. */
        const val OUTPUT_RAN = "ran"
        const val RAN_YES = "yes"
        const val RAN_NO = "no"

        /** What [evaluateExpression] formats a true boolean result as. */
        private const val CONDITION_TRUE = "true"
    }
}

class RunRuleActionFactory(
    /** The same store the engine reads, so the rule this action names is the
     * real one, not a copy. */
    private val repository: RuleRepository,
    /** Reaches the engine's own action-running path. See [RuleRunner]. */
    private val runner: RuleRunner,
) : ActionFactory {
    override val type = RunRuleAction.TYPE

    override val displayName = "Run another rule"
    override val category = ActionCategory.RULES

    override val configFields = listOf(
        ConfigField.RuleRef(
            key = RunRuleAction.CONFIG_RULE,
            label = "Rule",
            required = true,
            help = "Runs this rule's own actions right now. That rule's own " +
                "trigger and its on/off switch are not involved.",
        ),
        ConfigField.Text(
            key = RunRuleAction.CONFIG_CONDITION,
            label = "Only if",
            required = false,
            substitution = Substitution.EXPRESSION,
            blankMeaning = "Always run the target rule's actions.",
            help = "An expression such as {{app.mode}} == \"home\". The target " +
                "rule's actions run only while this reads exactly 'true'. A " +
                "condition that reads as false is not a failure: the 'Ran' " +
                "output below says which way it went.",
        ),
    )

    /**
     * Whether the target rule's actions ran this time. The reason this
     * exists: a false condition is this action working as written, not
     * failing, so nothing else says which way it went unless this does. A
     * later action reads it as `{{action.ran}}` or `{{run_rule.ran}}` to
     * announce the result.
     */
    override val variables = listOf(
        VariableSpec(
            key = RunRuleAction.OUTPUT_RAN,
            label = "Ran",
            kind = VariableKind.STATE,
            sample = RunRuleAction.RAN_YES,
            help = "'${RunRuleAction.RAN_YES}' when the target rule's actions " +
                "ran, '${RunRuleAction.RAN_NO}' when the condition read as " +
                "false. A rule id that no longer exists, a condition that " +
                "does not evaluate, or a chain of run-rule calls that went " +
                "too deep, fails this action instead of producing this.",
        ),
    )

    override val warning: String =
        "A rule that runs itself, directly or through a chain of this " +
            "action, is refused. A chain of run-rule calls is refused past " +
            "$MAX_RUN_RULE_CHAIN_DEPTH rules, so one rule cannot run another " +
            "forever."

    override fun create(config: Map<String, String>): Action = RunRuleAction(
        repository = repository,
        runner = runner,
        ruleId = config[RunRuleAction.CONFIG_RULE],
        condition = config[RunRuleAction.CONFIG_CONDITION],
    )
}
