package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.withFreshIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A rule plus everything currently standing between it and firing. */
data class RuleStatus(
    val rule: Rule,
    val unmet: List<ComponentRequirement>,
) {
    val canFire: Boolean get() = unmet.isEmpty()
}

class RulesViewModel(
    private val repository: RuleRepository,
    private val registry: Registry,
    private val checker: RequirementChecker,
) : ViewModel() {

    /**
     * Permissions change outside the app — in a settings screen we sent the user
     * to — and nothing notifies us when they come back. Bumping this on resume
     * is what makes the warnings disappear once the user has acted, instead of
     * leaving stale "permission missing" text on screen.
     */
    private val refreshTick = MutableStateFlow(0)

    val statuses: StateFlow<List<RuleStatus>> =
        combine(repository.rules(), refreshTick) { rules, _ ->
            rules.map { rule -> RuleStatus(rule, checker.unmet(rule, registry)) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun refresh() {
        refreshTick.update { it + 1 }
    }

    /**
     * One-off feedback for import and export, which happen outside the rule list
     * and otherwise leave no trace of having worked or failed.
     */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Rules as a portable document. This — not Android's Auto Backup — is how
     * rules reach a new phone: Auto Backup needs a Google account and does not
     * run on de-Googled devices, which is the audience this project targets.
     */
    fun exportAll(): String = RuleJson.encode(statuses.value.map { it.rule })

    fun exportOne(rule: Rule): String = RuleJson.encode(rule)

    /**
     * Imported rules arrive alongside existing ones rather than replacing them:
     * fresh ids mean an import can never silently overwrite something the user
     * built by hand.
     */
    fun import(text: String) {
        viewModelScope.launch {
            try {
                val imported = RuleJson.decode(text).withFreshIds()
                imported.forEach { repository.upsert(it) }
                _message.value = when (imported.size) {
                    0 -> "That file contained no rules."
                    1 -> "Imported 1 rule."
                    else -> "Imported ${imported.size} rules."
                }
            } catch (invalid: IllegalArgumentException) {
                // Deliberately shows the codec's own message: it names the rule
                // and the missing field, which is what makes a bad file fixable.
                _message.value = invalid.message ?: "That file could not be read."
            }
        }
    }

    fun delete(ruleId: String) {
        viewModelScope.launch { repository.delete(ruleId) }
    }

    fun setEnabled(rule: Rule, enabled: Boolean) {
        viewModelScope.launch {
            repository.upsert(rule.copy(enabled = enabled))
        }
    }

    companion object {
        fun factory(
            repository: RuleRepository,
            registry: Registry,
            checker: RequirementChecker,
        ) = viewModelFactory {
            initializer { RulesViewModel(repository, registry, checker) }
        }
    }
}
