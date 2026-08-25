package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.flow.first

/** What to do to the rule's own switch. */
enum class RuleSwitch(val configValue: String, val displayName: String) {
    ENABLE("enable", "turn it on"),
    DISABLE("disable", "turn it off"),
    TOGGLE("toggle", "flip it"),
    ;

    /** @return the new state, or null when it is already what was asked for. */
    fun applyTo(enabled: Boolean): Boolean? = when (this) {
        ENABLE -> if (enabled) null else true
        DISABLE -> if (enabled) false else null
        TOGGLE -> !enabled
    }

    companion object {
        const val CONFIG_KEY = "mode"

        fun parse(raw: String?): RuleSwitch =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
    }
}

/**
 * Turns one of the user's own rules on or off.
 *
 * The first action whose subject is Trigly itself, and it needs no new engine
 * machinery to work: `EngineService` collects the rule store and `TriggerEngine`
 * syncs what is running against the `enabled` flag, so writing the flag *is* the
 * mechanism. Starting and stopping is already the engine's job — this only
 * changes its mind.
 *
 * What it makes expressible is the whole class of rules that arm and disarm each
 * other. A one-shot: fire, then turn yourself off. A mode: "when I connect to the
 * car, enable the driving rules". A guard: "when the battery drops below 10%,
 * disable everything that polls".
 *
 * Idempotent on purpose. Enabling an enabled rule writes nothing and reports
 * success: a write would churn the engine into stopping and restarting a rule
 * that was already running, and reporting failure would make "make sure this is
 * on" an action that fails whenever it was already on.
 */
class SetRuleEnabledAction(
    private val repository: RuleRepository,
    private val ruleId: String?,
    private val mode: RuleSwitch,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        val target = ruleId?.takeIf { it.isNotBlank() }
            ?: return ActionResult.Failure("This action has no rule chosen to switch.")

        // A one-shot read, like the editor's: acting on a snapshot is right here,
        // because the decision is about the rule as it is at this instant.
        val rule: Rule = repository.rules().first().firstOrNull { it.id == target }
            ?: return ActionResult.Failure(
                "No rule has the id '$target'. It was probably deleted after " +
                    "this action was set up."
            )

        val wanted = mode.applyTo(rule.enabled)
            ?: return ActionResult.Success

        repository.upsert(rule.copy(enabled = wanted))
        return ActionResult.Success
    }

    companion object {
        const val TYPE = "set_rule_enabled"
        const val CONFIG_RULE = "ruleId"
    }
}

class SetRuleEnabledActionFactory(
    /**
     * The same store the engine reads, which is what makes writing the flag take
     * effect. Defaults to a throwaway so previews and tests assemble; an action
     * built against that one switches a rule nobody is running.
     */
    private val repository: RuleRepository,
) : ActionFactory {
    override val type = SetRuleEnabledAction.TYPE

    override val displayName = "Turn a rule on or off"
    override val category = ActionCategory.RULES

    override val configFields = listOf(
        ConfigField.RuleRef(
            key = SetRuleEnabledAction.CONFIG_RULE,
            label = "Rule",
            required = true,
            help = "Select the rule to switch. You can select this same rule. " +
                "That is how a rule runs once and then turns itself off.",
        ),
        ConfigField.Choice(
            key = RuleSwitch.CONFIG_KEY,
            label = "Then",
            options = RuleSwitch.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            default = RuleSwitch.DISABLE.configValue,
        ),
    )

    /**
     * Both halves of this are things someone will otherwise discover by being
     * confused. Turning off the rule you are standing in cancels it — the engine
     * stops the coroutine the actions are running in — so a "do X, then disable
     * myself" rule must put the disable *last*. And two rules that switch each
     * other on will keep restarting each other for as long as both exist, which
     * no amount of care inside this action can prevent.
     */
    override val warning: String =
        "If a rule uses this action to turn off itself, the rest of that " +
            "rule's actions stop right away. Put this action last in the rule. " +
            "Two rules that switch each other on will keep doing that forever."

    override fun create(config: Map<String, String>): Action = SetRuleEnabledAction(
        repository = repository,
        ruleId = config[SetRuleEnabledAction.CONFIG_RULE],
        mode = RuleSwitch.parse(
            config[RuleSwitch.CONFIG_KEY] ?: RuleSwitch.DISABLE.configValue
        ),
    )
}
