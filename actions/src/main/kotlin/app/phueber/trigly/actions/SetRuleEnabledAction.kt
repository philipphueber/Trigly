package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
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
 *
 * **This is the case the output feature exists for.** `TOGGLE` is the only
 * mode whose result nothing else can know: `ENABLE` and `DISABLE` say the
 * outcome in their own config, but "flip it" leaves the rule that flipped it
 * as the only place that ever learns which way it went. [OUTPUT_ENABLED]
 * reports that, so a later action can announce it. It is reported for every
 * mode, not only `TOGGLE`, because the value is free once computed and a
 * rule should not have to know which mode it used to read the result.
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
        if (wanted != null) {
            repository.upsert(rule.copy(enabled = wanted))
        }

        // `wanted` is null when the mode asked for what the rule already was.
        // The output still reports the true resulting state, which is the
        // rule's own state either way.
        val resultingState = wanted ?: rule.enabled
        return ActionResult.Success(
            outputs = mapOf(OUTPUT_ENABLED to if (resultingState) ENABLED else DISABLED)
        )
    }

    companion object {
        const val TYPE = "set_rule_enabled"
        const val CONFIG_RULE = "ruleId"

        /** The output key the factory declares below for the resulting state. */
        const val OUTPUT_ENABLED = "enabled"
        const val ENABLED = "on"
        const val DISABLED = "off"
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
     * Whether the target rule is on or off once this action has run. The
     * reason this exists: [RuleSwitch.TOGGLE] is "flip it", and nothing but
     * this action ever learns which way that went. A later action reads it as
     * `{{action.enabled}}` or `{{set_rule_enabled.enabled}}` to announce the
     * result, such as "Driving mode is now {{action.enabled}}".
     */
    override val variables = listOf(
        VariableSpec(
            key = SetRuleEnabledAction.OUTPUT_ENABLED,
            label = "Rule is now",
            kind = VariableKind.STATE,
            sample = SetRuleEnabledAction.ENABLED,
            help = "'${SetRuleEnabledAction.ENABLED}' or " +
                "'${SetRuleEnabledAction.DISABLED}', whichever the target rule " +
                "ended up as.",
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
