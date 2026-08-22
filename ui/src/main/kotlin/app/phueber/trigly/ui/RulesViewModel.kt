package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Holds the rule list for the UI. Depends on [RuleRepository], never on a
 * concrete store, so it can be driven from a test with the in-memory one.
 */
class RulesViewModel(
    private val repository: RuleRepository,
) : ViewModel() {

    val rules: StateFlow<List<Rule>> = repository.rules().stateIn(
        scope = viewModelScope,
        // Survives a configuration change without re-reading storage, but lets
        // the upstream go when the screen is really gone.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun setEnabled(rule: Rule, enabled: Boolean) {
        viewModelScope.launch {
            repository.upsert(rule.copy(enabled = enabled))
        }
    }

    companion object {
        fun factory(repository: RuleRepository) = viewModelFactory {
            initializer { RulesViewModel(repository) }
        }
    }
}
