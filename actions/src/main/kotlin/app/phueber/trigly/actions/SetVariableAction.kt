package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ExpressionOutcome
import app.phueber.trigly.core.FieldCondition
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.core.evaluateExpression
import app.phueber.trigly.core.normalizeVariableName
import app.phueber.trigly.core.variableNameProblem
import java.math.BigDecimal

/** What `set_variable` does to the named app variable. */
enum class VariableWriteMode(val configValue: String, val displayName: String) {
    SET("set", "set it"),
    CLEAR("clear", "clear it"),
    ADD("add", "add to it"),
    EVALUATE("evaluate", "compute it"),
    ;

    companion object {
        const val CONFIG_KEY = "mode"

        fun parse(raw: String?): VariableWriteMode =
            entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
                ?: error(
                    "$CONFIG_KEY must be one of ${entries.joinToString { it.configValue }}, " +
                        "was '$raw'"
                )
    }
}

/**
 * What `add` does to whatever a variable currently holds.
 *
 * A sum type rather than a nullable string, for the reason `AlertStop` in
 * `PlayAlertAction.kt` is one: "cannot add here" is a case worth naming and
 * reporting, not a value to guess past.
 */
sealed interface VariableAddOutcome {

    /** The value `add` should write back. */
    data class Added(val newValue: String) : VariableAddOutcome

    /** [reason] names what stopped the counter, so nothing is silently thrown away. */
    data class Failed(val reason: String) : VariableAddOutcome
}

/**
 * Adds [addend] to whatever [stored] holds, for `set_variable`'s `add` mode.
 *
 * A missing [stored] reads as zero rather than failing. A counter has to be
 * able to start from nothing: a rule that adds to a variable nobody has
 * written yet is the ordinary first run of a counting rule, not a mistake to
 * refuse.
 *
 * A [stored] value that does not parse as a number is refused instead, and the
 * reason names what was found. Reading it as zero would silently throw away
 * whatever a person or another rule actually put there, which is worse than a
 * run that visibly fails.
 *
 * Both operands parse as decimals, not whole numbers only. A variable is a bare
 * string with no declared type, so a running total built by this action is as
 * likely to be a distance or a price as a count, and whole numbers alone would
 * refuse both for no reason a person set up this action to hit. Parsed and
 * added as [BigDecimal] rather than as [Double], so a chain of additions such
 * as repeated `0.1`s does not drift the way binary floating point does.
 */
fun addToVariable(stored: String?, addend: String): VariableAddOutcome {
    val base = when (val trimmed = stored?.trim()) {
        null, "" -> BigDecimal.ZERO
        else -> trimmed.toBigDecimalOrNull() ?: return VariableAddOutcome.Failed(
            "The stored value is '$trimmed', which is not a number. Add needs a " +
                "number to add to."
        )
    }

    val amount = addend.trim().let { trimmed ->
        trimmed.toBigDecimalOrNull() ?: return VariableAddOutcome.Failed(
            "The value to add is '$trimmed', which is not a number."
        )
    }

    // stripTrailingZeros keeps a whole-number result reading as "5" rather than
    // "5.0", which is what every counter looks like. toPlainString avoids the
    // scientific notation stripTrailingZeros would otherwise invite for a round
    // number, which nobody reading a rule's variables would expect to see.
    return VariableAddOutcome.Added(base.add(amount).stripTrailingZeros().toPlainString())
}

/**
 * Writes, clears or increments one app-scoped variable.
 *
 * The second action whose subject is Trigly itself rather than the device,
 * after `set_rule_enabled`, and the reason app-scope variables exist at all:
 * see `docs/variables.md`. Without this, an app variable could only ever be
 * read, never written, and there would be nothing for `variable_check` to
 * check.
 *
 * `add` is what makes a counter possible, which is the main reason app scope
 * exists in the first place: "how many times has this fired today", "how many
 * bytes since the last reset". See [addToVariable] for the two decisions that
 * shape it.
 *
 * `evaluate` is what makes a *computed* value possible, rather than just a
 * copied or accumulated one: `{{app.count}} + 1`, `upper({{trigger.name}})`,
 * `{{battery.level}} < 20 ? "low" : "ok"`. The value field carries the
 * expression source, already substituted into literals by
 * [Substitution.EXPRESSION] before this action ever sees it, and
 * [evaluateExpression] in `:core` does the rest. See `Expression.kt` for the
 * language and why it stops well short of a general scripting model: a rule
 * is a file someone else can import, and this mode must not become a way to
 * carry arbitrary code onto their phone.
 */
class SetVariableAction(
    private val store: VariableStore,
    private val name: String,
    private val mode: VariableWriteMode,
    private val value: String,
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult = when (mode) {
        VariableWriteMode.SET -> {
            store.set(name, value)
            ActionResult.Success(outputs = mapOf(OUTPUT_VALUE to value))
        }

        VariableWriteMode.CLEAR -> {
            // Removing a name that was never set is not an error: see
            // VariableStore.remove. "Make sure this is cleared" must not fail
            // just because nothing was there to clear. No output either: there
            // is no stored value left to report.
            store.remove(name)
            ActionResult.Success()
        }

        VariableWriteMode.ADD -> when (val outcome = addToVariable(store.get(name), value)) {
            is VariableAddOutcome.Added -> {
                store.set(name, outcome.newValue)
                ActionResult.Success(outputs = mapOf(OUTPUT_VALUE to outcome.newValue))
            }

            is VariableAddOutcome.Failed -> ActionResult.Failure(outcome.reason)
        }

        VariableWriteMode.EVALUATE -> when (val outcome = evaluateExpression(value)) {
            is ExpressionOutcome.Ok -> {
                store.set(name, outcome.value)
                ActionResult.Success(outputs = mapOf(OUTPUT_VALUE to outcome.value))
            }

            is ExpressionOutcome.Failed -> ActionResult.Failure(outcome.reason)
        }
    }

    companion object {
        const val TYPE = "set_variable"
        const val CONFIG_NAME = "name"
        const val CONFIG_VALUE = "value"

        /** The output key the factory declares below for what was just stored. */
        const val OUTPUT_VALUE = "value"
    }
}

class SetVariableActionFactory(
    /**
     * The same store `variable_check` reads and the picker lists. Defaults to a
     * working in-memory store, not a refusing stub: see [VariableStore] for why
     * "this device has no variables" is not a real state.
     */
    private val store: VariableStore,
) : ActionFactory {
    override val type = SetVariableAction.TYPE

    override val displayName = "Set an app variable"
    override val category = ActionCategory.RULES

    override val configFields = listOf(
        ConfigField.Text(
            key = SetVariableAction.CONFIG_NAME,
            label = "Variable name",
            required = true,
            help = "Any rule can read this back as {{app.name}}. A name has no " +
                "spaces and no '|', '{' or '}'.",
        ),
        ConfigField.Choice(
            key = VariableWriteMode.CONFIG_KEY,
            label = "Then",
            options = VariableWriteMode.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            default = VariableWriteMode.SET.configValue,
        ),
        ConfigField.Text(
            key = SetVariableAction.CONFIG_VALUE,
            label = "Value",
            required = true,
            substitution = Substitution.TEXT,
            help = "This can include another variable, such as {{trigger.name}}. " +
                "Adding needs a value that is a plain number. Evaluating runs this " +
                "as an expression, such as {{app.count}} + 1 or " +
                "upper({{trigger.name}}).",
            // Gone entirely when the mode is clear, rather than shown with a
            // sentence explaining that it does nothing: clearing needs no value.
            shownWhen = FieldCondition(
                key = VariableWriteMode.CONFIG_KEY,
                isAnyOf = setOf(
                    VariableWriteMode.SET.configValue,
                    VariableWriteMode.ADD.configValue,
                    VariableWriteMode.EVALUATE.configValue,
                ),
            ),
        ),
    )

    /**
     * The value field's escaping depends on the mode, which is a sibling
     * field: see [ConfigField.substitution] and `docs/variables.md` section 8.
     * Every mode but evaluate treats the value as prose that may embed a
     * variable, [Substitution.TEXT]. Evaluate treats it as expression source,
     * [Substitution.EXPRESSION], so a substituted value arrives as a literal
     * the evaluator can parse rather than as raw device text spliced into
     * code. [HttpRequestActionFactory.substitutionsFor] is the pattern this
     * copies, for the same reason: the editor rendering a picker and the
     * engine escaping a value must agree on what mode is chosen, so both read
     * the mode with the same fallback [create] uses for it.
     */
    override fun substitutionsFor(config: Map<String, String>): Map<String, Substitution> {
        val rawMode = config[VariableWriteMode.CONFIG_KEY] ?: VariableWriteMode.SET.configValue
        val isEvaluate = rawMode.equals(VariableWriteMode.EVALUATE.configValue, ignoreCase = true)
        val valueSubstitution = if (isEvaluate) Substitution.EXPRESSION else Substitution.TEXT
        return super.substitutionsFor(config) +
            (SetVariableAction.CONFIG_VALUE to valueSubstitution)
    }

    /**
     * What this action just stored, so a later action can announce it without
     * a second read: "Trip {{action.value}} recorded" right after the count
     * that reads is the one this same run just wrote. Not declared
     * [VariableSpec.alwaysPresent], because the clear mode leaves nothing
     * stored to report.
     */
    override val variables = listOf(
        VariableSpec(
            key = SetVariableAction.OUTPUT_VALUE,
            label = "Value stored",
            kind = VariableKind.TEXT,
            sample = "4",
            help = "What this action just set the variable to. Not produced " +
                "when the mode clears the variable.",
            alwaysPresent = false,
        ),
    )

    override fun create(config: Map<String, String>): Action {
        val rawName = config[SetVariableAction.CONFIG_NAME].orEmpty()
        val problem = variableNameProblem(rawName)
        require(problem == null) { problem.orEmpty() }

        return SetVariableAction(
            store = store,
            name = normalizeVariableName(rawName),
            mode = VariableWriteMode.parse(
                config[VariableWriteMode.CONFIG_KEY] ?: VariableWriteMode.SET.configValue
            ),
            value = config[SetVariableAction.CONFIG_VALUE].orEmpty(),
        )
    }
}
