package app.phueber.trigly.triggers

import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.FieldCondition
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.Trigger
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerFactory
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.core.normalizeVariableName
import app.phueber.trigly.core.variableNameProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** How [variableCheckHolds] decides whether an app-scope value satisfies a check. */
enum class VariableComparison(val configValue: String) {
    IS_SET("is_set"),
    IS_EMPTY("is_empty"),
    EQUALS("equals"),
    NOT_EQUALS("not_equals"),
    CONTAINS("contains"),
    ABOVE("above"),
    BELOW("below"),
    ;

    /**
     * Whether this comparison reads the value field at all. "Is set" and "is
     * empty" are answered by presence and content alone, so the value field is
     * meaningless for them and the factory hides it; see
     * `VariableCheckFactory.configFields`.
     */
    val requiresValue: Boolean get() = this != IS_SET && this != IS_EMPTY

    companion object {
        /**
         * The comparison [raw] names: [EQUALS] when nothing is stored, and
         * **null when something is stored that this build does not know**.
         *
         * The two cases are deliberately not the same answer, and this is the
         * one place in the file where being lenient would be wrong.
         *
         * Nothing stored is the ordinary case. A `Choice` field declares a
         * default, the editor draws it, and `ComponentFactory.normalise` writes
         * it down, so an absent key means "the default nobody has changed" and
         * reading it as [EQUALS] is reading it correctly.
         *
         * A value this build does not know can only come from a hand-edited file
         * or an export from a newer build, and it means the rule asks a question
         * this build cannot answer. Degrading it to some other comparison would
         * make the rule quietly do something its author did not write, and this
         * component decides whether unattended actions run. So the factory
         * refuses it and the rule reports that it could not start, which is what
         * `HttpRequestAction` already does for a method it does not know. Loud
         * beats a plausible guess here, and `TextMatchMode.parse` is lenient for
         * a reason that does not apply: absence there had a meaning from before
         * the mode existed, and this key never did.
         */
        fun parse(raw: String?): VariableComparison? =
            if (raw.isNullOrBlank()) {
                EQUALS
            } else {
                entries.firstOrNull { it.configValue.equals(raw, ignoreCase = true) }
            }
    }
}

/**
 * Whether a stored app-scope value satisfies [comparison] against [value].
 * Pure, so every comparison and every edge case is testable without a store.
 *
 * **A name that is not in the store answers a definite, knowable question, not
 * an unknown one.** [stored] is null exactly when [VariableStore.get] found
 * nothing under the name, which is a fact about the store, not a failure to
 * read it. So an absent name is [VariableComparison.IS_EMPTY] and nothing
 * else: not "is set", not "equals" whatever [value] happens to be, not "does
 * not equal" it either. One rule for the whole family keeps that from being
 * seven separate judgement calls that could quietly disagree with each other.
 *
 * **Case.** "equals", "does not equal" and "contains" compare
 * case-insensitively, the same default [app.phueber.trigly.core.TextMatchMode]
 * documents for a text filter and that most rules want: `{{app.state}}` stored
 * as `"On"` should still match a comparison value of `"on"`. This is a
 * different question from a variable's *name*, which
 * [app.phueber.trigly.core.normalizeVariableName] treats as exact, because a
 * name is typed once by the person building the rule and a store that
 * silently merged `Trips` and `trips` would be guessing; a stored *value*, by
 * contrast, is exactly the kind of free text a text filter already treats this
 * way.
 *
 * **A non-numeric side of "is above" or "is below" answers false, not
 * unknown.** Both sides parse as a number at the point of comparison, because
 * a variable has no value type of its own. When either side does not parse,
 * this returns false rather than throwing, guessing zero, or answering null.
 * False and not null, because the value is entirely knowable, it is simply not
 * a number: this is a definite answer, the same way an absent name is. False
 * and not true, because this gates whether unattended actions run, and a
 * comparison that cannot be made must not accidentally read as satisfied.
 *
 * A blank or absent [value] on "contains" matches every stored value, the same
 * convention [app.phueber.trigly.core.TextFilter] uses for a blank pattern:
 * every string contains the empty one.
 */
fun variableCheckHolds(
    stored: String?,
    comparison: VariableComparison,
    value: String?,
): Boolean {
    if (stored == null) return comparison == VariableComparison.IS_EMPTY

    val compareValue = value.orEmpty()
    return when (comparison) {
        VariableComparison.IS_SET -> true
        VariableComparison.IS_EMPTY -> stored.isEmpty()
        VariableComparison.EQUALS -> stored.equals(compareValue, ignoreCase = true)
        VariableComparison.NOT_EQUALS -> !stored.equals(compareValue, ignoreCase = true)
        VariableComparison.CONTAINS -> stored.contains(compareValue, ignoreCase = true)
        VariableComparison.ABOVE -> numericCompare(stored, compareValue) { a, b -> a > b }
        VariableComparison.BELOW -> numericCompare(stored, compareValue) { a, b -> a < b }
    }
}

private fun numericCompare(
    stored: String,
    value: String,
    holds: (storedNumber: Double, valueNumber: Double) -> Boolean,
): Boolean {
    val storedNumber = stored.toDoubleOrNull() ?: return false
    val valueNumber = value.toDoubleOrNull() ?: return false
    return holds(storedNumber, valueNumber)
}

/**
 * "Is an app-scope variable set, empty, or holding a particular value" — phase
 * 2 of `docs/variables.md`, section 10.
 *
 * Built like [TimeWindowCheck], which is the same shape for the same reason:
 * this is a level, not an edge. Nothing about an app-scope variable is an
 * instant to fire on; it is read, not watched. [events] is therefore an empty
 * flow rather than a stub, and this pairs with a trigger elsewhere in the
 * tree — "when the doorbell rings, if `{{app.mode}}` equals `away`" — never on
 * its own. See `docs/conditions.md`.
 *
 * **Null means only one thing here: the store could not be read.**
 * [VariableStore.get] declares no checked failure, but its real,
 * Room-backed implementation reads a database, and a database read can throw.
 * That is the only path to null below. Every other case, including a name
 * with nothing stored, is answered directly by [variableCheckHolds] as a
 * definite true or false. A component that returned null for the ordinary
 * case of an absent name would be a rule that can never fire with nothing on
 * screen to say why — the exact failure `docs/conditions.md` warns null must
 * not cause.
 */
class VariableCheck(
    private val name: String,
    private val comparison: VariableComparison,
    private val value: String?,
    private val store: VariableStore,
) : Trigger {

    override fun events(): Flow<TriggerEvent> = emptyFlow()

    override suspend fun currentlyHolds(): Boolean? {
        val stored = try {
            store.get(name)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return null
        }
        return variableCheckHolds(stored, comparison, value)
    }

    companion object {
        const val TYPE = "variable_check"
        const val CONFIG_NAME = "name"
        const val CONFIG_COMPARISON = "comparison"
        const val CONFIG_VALUE = "value"
    }
}

class VariableCheckFactory(private val store: VariableStore = InMemoryVariableStore()) : TriggerFactory {
    override val type = VariableCheck.TYPE

    override val displayName = "Variable"
    override val category = Category.VARIABLES

    override val supportsCondition = true

    // A read, not a watch, for the same reason `time_window` says so: nothing
    // about an app-scope variable is an instant, so there is no event to
    // offer and this can never start a rule on its own.
    override val producesEvents = false

    override val configFields = listOf(
        ConfigField.Text(
            key = VariableCheck.CONFIG_NAME,
            label = "Variable",
            required = true,
            help = "The name of an app-scope variable, written by \"Set a variable\" " +
                "somewhere else in this rule set.",
        ),
        ConfigField.Choice(
            key = VariableCheck.CONFIG_COMPARISON,
            label = "Comparison",
            options = COMPARISON_OPTIONS,
            default = VariableComparison.EQUALS.configValue,
        ),
        ConfigField.Text(
            key = VariableCheck.CONFIG_VALUE,
            label = "Value",
            // Hidden for "is set" and "is empty", which are answered by
            // presence and content alone and have no use for a value to
            // compare against — the same choice `play_alert` makes for "keep
            // sounding for" once the tone is set to play once. See
            // `ConfigField.shownWhen`.
            shownWhen = FieldCondition(
                key = VariableCheck.CONFIG_COMPARISON,
                isAnyOf = VALUE_COMPARISONS,
            ),
            // Deliberately left at the default, Substitution.NONE, and not
            // Substitution.TEXT. A condition is asked without an event:
            // `currentlyHolds()` runs about a leaf that did not fire, so there
            // is nothing for a `{{trigger.*}}` reference in this field to
            // resolve against. Offering the picker here would offer a field
            // that is empty exactly when it is read.
            substitution = Substitution.NONE,
        ),
    )

    override fun create(config: Map<String, String>): Trigger {
        val rawName = config[VariableCheck.CONFIG_NAME].orEmpty()
        variableNameProblem(rawName)?.let { error(it) }

        val rawComparison = config[VariableCheck.CONFIG_COMPARISON]
        val comparison = VariableComparison.parse(rawComparison)
            ?: error(
                "$type does not know the comparison '$rawComparison'. It knows " +
                    VariableComparison.entries.joinToString { it.configValue } + "."
            )

        return VariableCheck(
            name = normalizeVariableName(rawName),
            comparison = comparison,
            value = config[VariableCheck.CONFIG_VALUE],
            store = store,
        )
    }

    companion object {
        private val VALUE_COMPARISONS: Set<String> =
            VariableComparison.entries.filter { it.requiresValue }.map { it.configValue }.toSet()

        private val COMPARISON_OPTIONS: List<ConfigField.Option> = listOf(
            ConfigField.Option(VariableComparison.IS_SET.configValue, "is set"),
            ConfigField.Option(VariableComparison.IS_EMPTY.configValue, "is empty"),
            ConfigField.Option(VariableComparison.EQUALS.configValue, "equals"),
            ConfigField.Option(VariableComparison.NOT_EQUALS.configValue, "does not equal"),
            ConfigField.Option(VariableComparison.CONTAINS.configValue, "contains"),
            ConfigField.Option(VariableComparison.ABOVE.configValue, "is above"),
            ConfigField.Option(VariableComparison.BELOW.configValue, "is below"),
        )
    }
}
