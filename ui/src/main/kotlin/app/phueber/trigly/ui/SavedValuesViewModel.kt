package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.RuleVariableStore
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.core.normalizeVariableName
import app.phueber.trigly.core.rulesReading
import app.phueber.trigly.core.variableNameProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// SavedValueRow itself is declared in SavedValuesScreen.kt, owned by the
// screen: it is the screen's own rendering shape, sorted and filled in here.

/**
 * One value a rule keeps to itself: the `{{mine.*}}` scope.
 *
 * Carries the rule's name as well as its id, because a rule id is a UUID and
 * says nothing to a person. The id is what a delete needs; the name is what the
 * row shows.
 */
data class RuleValueRow(
    val ruleId: String,
    val ruleName: String,
    val name: String,
    val value: String,
    val lastChangedMillis: Long,
)

data class SavedValuesState(
    /** Sorted by name, so the order does not move under a finger while a rule writes a value. */
    val values: List<SavedValueRow> = emptyList(),
    /**
     * Every rule-scope value, sorted by rule name and then by value name.
     *
     * Listed here at all because this screen's promise is to show what is
     * saved, and a rule-scope value is saved. Leaving it out would make it the
     * one kind of stored state with no way to see it and no way to clear it,
     * which is the "working feature nobody can find" failure this screen exists
     * to fix.
     */
    val ruleValues: List<RuleValueRow> = emptyList(),
    /** Set when [SavedValuesViewModel.setValue] was refused. Cleared on the next attempt. */
    val error: String? = null,
)

/**
 * Backs the saved-values screen: every app-scope variable, who reads each one,
 * and hand-editing. See `docs/variables.md`'s "still missing" note, which this
 * closes.
 */
class SavedValuesViewModel(
    private val variableStore: VariableStore,
    /** The `{{mine.*}}` scope, listed beside the shared one. */
    private val ruleVariableStore: RuleVariableStore,
    private val ruleRepository: RuleRepository,
    /**
     * How to ask which config keys of a component are substitutable, so
     * [app.phueber.trigly.core.rulesReading] can tell which rules read a given
     * name. `container.registry::substitutionsFor` in the running app; a fake
     * in a test.
     */
    private val substitutionsFor: (ComponentSpec) -> Map<String, Substitution>,
) : ViewModel() {

    private val _state = MutableStateFlow(SavedValuesState())
    val state: StateFlow<SavedValuesState> = _state.asStateFlow()

    init {
        // Collected here, into `_state`, rather than exposed as its own
        // `stateIn`. Same reason `RuleEditorViewModel` collects
        // `variableStore.scoped()` into its own state instead of leaving it a
        // cold `Flow`. A plain getter and a screen both need a value that is
        // already there, not one that only starts once something subscribes.
        viewModelScope.launch {
            combine(variableStore.history(), ruleRepository.rules()) { history, rules ->
                history.entries.sortedBy { it.key }.map { (name, record) ->
                    SavedValueRow(
                        name = name,
                        value = record.value,
                        lastChangedMillis = record.updatedAtMillis,
                        readByRuleNames = rules.rulesReading(name, substitutionsFor)
                            .map { it.name },
                    )
                }
            }.collect { rows ->
                _state.update { it.copy(values = rows) }
            }
        }

        // Its own collection rather than a third flow in the combine above: a
        // rule-scope row needs the rule's name and nothing else from a rule, so
        // folding it into the same combine would recompute every app-scope
        // row's "read by" list whenever any rule wrote a private value.
        viewModelScope.launch {
            combine(
                ruleVariableStore.historyByRule(),
                ruleRepository.rules(),
            ) { byRule, rules ->
                val namesById = rules.associate { it.id to it.name }
                byRule.entries
                    .flatMap { (ruleId, values) ->
                        values.map { (name, record) ->
                            RuleValueRow(
                                ruleId = ruleId,
                                // A value whose rule is gone should not appear,
                                // and cannot: the foreign key deletes it with
                                // the rule. This name is the honest fallback if
                                // one ever does, rather than a blank row.
                                ruleName = namesById[ruleId] ?: "A deleted rule",
                                name = name,
                                value = record.value,
                                lastChangedMillis = record.updatedAtMillis,
                            )
                        }
                    }
                    .sortedWith(compareBy({ it.ruleName }, { it.name }))
            }.collect { rows ->
                _state.update { it.copy(ruleValues = rows) }
            }
        }
    }

    /**
     * Removes one rule's private value.
     *
     * No confirmation naming the rules that read it, unlike [deleteValue] for
     * the shared scope. Only one rule can read this, and its name is on the row
     * being deleted, so the dialog would tell the person what they are already
     * looking at.
     */
    fun deleteRuleValue(ruleId: String, name: String) {
        viewModelScope.launch { ruleVariableStore.remove(ruleId, name) }
    }

    /**
     * Adds a new value, or replaces the one already stored under [rawName]. One
     * path for both: editing a value that rules already read is the ordinary
     * case here, and needs no ceremony beyond what an ordinary write gets.
     *
     * Validated the same way a rule's own reference is validated, and refused
     * with [variableNameProblem]'s own words rather than a second wording of
     * the same rule: a name a person can store here has to be a name a rule can
     * read back, and that function is the one place that question is answered.
     */
    fun setValue(rawName: String, value: String) {
        val problem = variableNameProblem(rawName)
        if (problem != null) {
            _state.update { it.copy(error = problem) }
            return
        }
        _state.update { it.copy(error = null) }
        val name = normalizeVariableName(rawName)
        viewModelScope.launch { variableStore.set(name, value) }
    }

    /**
     * Forgets [name].
     *
     * No confirmation lives here: [SavedValueRow.readByRuleNames] is already in
     * state before this is ever called, so the screen can warn with the actual
     * rules at risk rather than this ViewModel guessing what warning is wanted.
     */
    fun delete(name: String) {
        viewModelScope.launch { variableStore.remove(name) }
    }

    /** Dismisses a refused write without attempting another one. */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        fun factory(
            variableStore: VariableStore,
            ruleVariableStore: RuleVariableStore,
            ruleRepository: RuleRepository,
            substitutionsFor: (ComponentSpec) -> Map<String, Substitution>,
        ) = viewModelFactory {
            initializer {
                SavedValuesViewModel(
                    variableStore, ruleVariableStore, ruleRepository, substitutionsFor,
                )
            }
        }
    }
}
