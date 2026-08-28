package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.LivenessProbe
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.imported
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
    /**
     * Requirements this rule has granted, but whose service is confirmed not
     * bound right now. See [RequirementChecker.grantedButNotLive].
     *
     * Distinct from [unmet]: that list is what a settings screen has not yet
     * granted. This is what a settings screen already shows as granted, and
     * a "Grant" button would have nothing to offer.
     */
    val notLive: List<ComponentRequirement> = emptyList(),
    /**
     * What this rule's last failing action said, if one failed since the engine
     * started. See [RuleFaultLog] for why it does not outlive the engine.
     *
     * Distinct from [unmet]: that is a reason the rule *cannot* run, known before
     * it ever tries. This is a report from a run that happened.
     */
    val lastFault: RuleFault? = null,
) {
    val canFire: Boolean get() = unmet.isEmpty() && notLive.isEmpty()
}

class RulesViewModel(
    private val repository: RuleRepository,
    private val registry: Registry,
    private val checker: RequirementChecker,
    /**
     * Read only, and defaulted so a test that does not care about failures needs
     * to say nothing. The engine writes it; see [RuleFaultLog].
     */
    private val ruleFaults: RuleFaultLog = RuleFaultLog(),
    /**
     * How to ask whether the notification listener and the accessibility
     * service are actually bound right now, for [RuleStatus.notLive].
     *
     * Defaults to [LivenessProbe.Unknown], the same way [ruleFaults] defaults
     * to an empty log: a caller that does not have a real one, such as a test
     * building this ViewModel around a rule with no special-access
     * requirement, should not have to say so. [MainActivity] passes the real
     * one, built from the same [app.phueber.trigly.core.NotificationController]
     * and [app.phueber.trigly.core.UiController] the app already wires for its
     * actions.
     */
    private val livenessProbe: LivenessProbe = LivenessProbe.Unknown,
) : ViewModel() {

    /**
     * Permissions change outside the app — in a settings screen we sent the user
     * to — and nothing notifies us when they come back. Bumping this on resume
     * is what makes the warnings disappear once the user has acted, instead of
     * leaving stale "permission missing" text on screen.
     */
    private val refreshTick = MutableStateFlow(0)

    val statuses: StateFlow<List<RuleStatus>> =
        combine(
            repository.rules(),
            refreshTick,
            ruleFaults.faults,
        ) { rules, _, failures ->
            rules.map { rule ->
                RuleStatus(
                    rule = rule,
                    unmet = checker.unmet(rule, registry),
                    notLive = checker.grantedButNotLive(rule, registry, livenessProbe),
                    // Only for an enabled rule. A failure recorded before someone
                    // switched a rule off describes a run they have since stopped
                    // asking for, and reporting it would read as a live fault.
                    lastFault = if (rule.enabled) failures[rule.id] else null,
                )
            }
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
     * Imported rules arrive alongside existing ones, switched off, and with
     * every generated id minted again. See [Rule.imported] for why: a rule
     * file is a program written by whoever exported it, who may not be this
     * device's owner, and this is the one place that turns it into something
     * safe to hand to the repository unattended.
     *
     * The message says the rules arrived off. Someone who imports a rule and
     * finds it inert, with no reason given, will conclude the app is broken
     * rather than that it is being careful.
     */
    fun import(text: String) {
        viewModelScope.launch {
            try {
                val imported = RuleJson.decode(text).map { it.imported(registry) }
                imported.forEach { repository.upsert(it) }
                _message.value = when (imported.size) {
                    0 -> "That file contained no rules."
                    1 -> "Imported 1 rule. It is switched off."
                    else -> "Imported ${imported.size} rules. They are switched off."
                }
            } catch (invalid: IllegalArgumentException) {
                // Deliberately shows the codec's own message: it names the rule
                // and the missing field, which is what makes a bad file fixable.
                _message.value = invalid.message ?: "That file could not be read."
            }
        }
    }

    fun delete(ruleId: String) {
        // Forgotten as well as deleted: the log is keyed by rule id, and an id is
        // free to be reused by an import.
        ruleFaults.forget(ruleId)
        viewModelScope.launch { repository.delete(ruleId) }
    }

    /**
     * Saves a copy of [rule], switched off and named as a copy. See
     * [duplicated] for what a copy does not carry over.
     *
     * The copy lands at the end of the list, like every other new rule: the
     * repository gives a rule it has not seen the next free position. Placing it
     * next to the original would mean shifting the position of every rule below
     * it, and a list that reorders itself around a copy is a bigger surprise
     * than a copy at the bottom.
     */
    fun duplicate(rule: Rule) {
        viewModelScope.launch { repository.upsert(rule.duplicated(registry)) }
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
            ruleFaults: RuleFaultLog = RuleFaultLog(),
            livenessProbe: LivenessProbe = LivenessProbe.Unknown,
        ) = viewModelFactory {
            initializer {
                RulesViewModel(repository, registry, checker, ruleFaults, livenessProbe)
            }
        }
    }
}
