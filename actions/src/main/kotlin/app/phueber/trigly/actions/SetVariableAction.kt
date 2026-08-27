package app.phueber.trigly.actions

import app.phueber.trigly.core.Action
import app.phueber.trigly.core.ActionFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.ExpressionOutcome
import app.phueber.trigly.core.FieldCondition
import app.phueber.trigly.core.InMemoryRuleVariableStore
import app.phueber.trigly.core.RuleVariableStore
import app.phueber.trigly.core.RunScope
import app.phueber.trigly.core.VariableScope
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.VariableKind
import app.phueber.trigly.core.VariableSpec
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.core.evaluateExpression
import app.phueber.trigly.core.normalizeVariableName
import app.phueber.trigly.core.variableNameProblem
import java.math.BigDecimal
import kotlin.coroutines.coroutineContext

/** What `set_variable` does to the named app variable. */
/**
 * Which of the three writable scopes a value goes to. See `docs/variables.md`
 * section 3, and [VariableScope] for the namespaces these read back as.
 *
 * **[APP] is the default, and has to be**, because it is what every
 * `set_variable` action saved before this field existed did. A rule with no
 * `scope` key in its config is one of those, and reading a missing key as
 * anything else would silently move where its value goes.
 *
 * The display names say the lifetime rather than the namespace, because that is
 * the choice being made. A person picking here is deciding how long the value
 * lives and who else can see it; `{{local.x}}` against `{{mine.x}}` is the
 * consequence, and the field's help text is where that belongs.
 */
enum class VariableWriteScope(
    val configValue: String,
    val displayName: String,
    /** The namespace this scope reads back as, for the help text to name it. */
    val namespace: String,
) {
    RUN("run", "this run only", VariableScope.LOCAL),
    RULE("rule", "this rule", VariableScope.MINE),
    APP("app", "every rule", VariableScope.APP),
    ;

    companion object {
        const val CONFIG_KEY = "scope"

        /**
         * [APP] for anything unrecognised, which is the opposite call from
         * [VariableWriteMode.parse]'s refusal, and deliberately so. An
         * unrecognised *mode* means the rule asked for an operation this build
         * cannot perform, and guessing which one would do the wrong thing to a
         * stored value. An unrecognised scope on a rule from a newer build is
         * more likely to be a scope this build has never heard of, and the
         * honest fallback for "where does this go" is where it has always gone.
         */
        fun parse(raw: String?): VariableWriteScope =
            entries.firstOrNull { it.configValue.equals(raw?.trim(), ignoreCase = true) } ?: APP
    }
}

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
 * `{{battery_level.level}} < 20 ? "low" : "ok"`. The value field carries the
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
    /**
     * Which of the three scopes this action writes to. Defaulted to
     * [VariableWriteScope.APP] because that is what this action did before the
     * scope existed, and it keeps app scope the thing you get when nobody says
     * otherwise, in the class exactly as in the config.
     */
    private val scope: VariableWriteScope = VariableWriteScope.APP,
    /**
     * Where a [VariableWriteScope.RULE] value goes. Defaulted so a caller
     * writing app scope does not have to name a store it will never touch;
     * [SetVariableActionFactory] always passes the real one.
     */
    private val ruleStore: RuleVariableStore = InMemoryRuleVariableStore(),
) : Action {

    override suspend fun execute(event: TriggerEvent): ActionResult {
        // The run and the rule scopes both need to know which firing this is.
        // The engine puts that on the coroutine; nothing else can supply it.
        // See RunScope, and `TriggerEngine.runActions` for where it is set.
        val run = coroutineContext[RunScope]
        if (scope != VariableWriteScope.APP && run == null) {
            return ActionResult.Failure(
                "'${scope.displayName}' only exists while a rule is running, and " +
                    "this action was not run by a rule."
            )
        }

        return when (mode) {
            VariableWriteMode.SET -> {
                write(run, value)
                ActionResult.Success(outputs = mapOf(OUTPUT_VALUE to value))
            }

            VariableWriteMode.CLEAR -> {
                // Removing a name that was never set is not an error: see
                // VariableStore.remove. "Make sure this is cleared" must not
                // fail just because nothing was there to clear. No output
                // either: there is no stored value left to report.
                clear(run)
                ActionResult.Success()
            }

            VariableWriteMode.ADD -> when (val outcome = addToVariable(read(run), value)) {
                is VariableAddOutcome.Added -> {
                    write(run, outcome.newValue)
                    ActionResult.Success(outputs = mapOf(OUTPUT_VALUE to outcome.newValue))
                }

                is VariableAddOutcome.Failed -> ActionResult.Failure(outcome.reason)
            }

            VariableWriteMode.EVALUATE -> when (val outcome = evaluateExpression(value)) {
                is ExpressionOutcome.Ok -> {
                    write(run, outcome.value)
                    ActionResult.Success(outputs = mapOf(OUTPUT_VALUE to outcome.value))
                }

                is ExpressionOutcome.Failed -> ActionResult.Failure(outcome.reason)
            }
        }
    }

    /**
     * The three scopes, each read and written in its own place, behind one set
     * of names so the four modes above do not each grow a `when` over scopes.
     *
     * [run] is non-null for every scope that needs it, which the guard in
     * [execute] has already established, so these do not repeat that check.
     */
    private suspend fun read(run: RunScope?): String? = when (scope) {
        VariableWriteScope.RUN -> run?.snapshot()?.get(name)
        VariableWriteScope.RULE -> run?.let { ruleStore.get(it.ruleId, name) }
        VariableWriteScope.APP -> store.get(name)
    }

    private suspend fun write(run: RunScope?, newValue: String) {
        when (scope) {
            VariableWriteScope.RUN -> run?.set(name, newValue)
            VariableWriteScope.RULE -> run?.let { ruleStore.set(it.ruleId, name, newValue) }
            VariableWriteScope.APP -> store.set(name, newValue)
        }
    }

    private suspend fun clear(run: RunScope?) {
        when (scope) {
            VariableWriteScope.RUN -> run?.remove(name)
            VariableWriteScope.RULE -> run?.let { ruleStore.remove(it.ruleId, name) }
            VariableWriteScope.APP -> store.remove(name)
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
    /**
     * Where a rule-scope value goes. Beside [store] rather than replacing it,
     * because the two scopes are two stores and this action is the one place
     * that has to know both.
     */
    private val ruleStore: RuleVariableStore = InMemoryRuleVariableStore(),
) : ActionFactory {
    override val type = SetVariableAction.TYPE

    override val displayName = "Set an app variable"
    override val category = ActionCategory.RULES

    override val configFields = listOf(
        /**
         * First, above the name, because it decides what the name *is*. The
         * same word means three different values depending on this field, and a
         * person choosing a name should have already chosen who can see it.
         */
        ConfigField.Choice(
            key = VariableWriteScope.CONFIG_KEY,
            label = "Where it lives",
            options = VariableWriteScope.entries.map {
                ConfigField.Option(it.configValue, it.displayName)
            },
            default = VariableWriteScope.APP.configValue,
            help = "'this run only' is gone when the rule finishes, and only " +
                "this run can read it. 'this rule' survives, and no other rule " +
                "can see it. 'every rule' is shared, and it is what appears in " +
                "Saved values.",
        ),
        ConfigField.Text(
            key = SetVariableAction.CONFIG_NAME,
            label = "Variable name",
            required = true,
            help = "Read it back as {{local.name}}, {{mine.name}} or {{app.name}}, " +
                "matching the scope above. A name has no spaces and no '|', " +
                "'{' or '}'.",
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
        val scope = VariableWriteScope.parse(config[VariableWriteScope.CONFIG_KEY])
        val rawName = config[SetVariableAction.CONFIG_NAME].orEmpty()
        val problem = variableNameProblem(rawName)
        require(problem == null) { problem.orEmpty() }

        return SetVariableAction(
            store = store,
            ruleStore = ruleStore,
            scope = scope,
            name = normalizeVariableName(rawName),
            mode = VariableWriteMode.parse(
                config[VariableWriteMode.CONFIG_KEY] ?: VariableWriteMode.SET.configValue
            ),
            value = config[SetVariableAction.CONFIG_VALUE].orEmpty(),
        )
    }
}
