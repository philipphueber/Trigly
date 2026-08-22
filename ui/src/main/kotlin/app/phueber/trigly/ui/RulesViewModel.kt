package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
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
