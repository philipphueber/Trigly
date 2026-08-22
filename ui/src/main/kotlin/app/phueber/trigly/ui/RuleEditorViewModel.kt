package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorState(
    val draft: RuleDraft,
    /** Set when a save was refused. Cleared on the next edit. */
    val error: String? = null,
    val saved: Boolean = false,
)

class RuleEditorViewModel(
    private val repository: RuleRepository,
    private val registry: Registry,
    val checker: RequirementChecker,
    ruleId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState(RuleDraft(id = ruleId)))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /**
     * What the pickers offer: only what this device can actually run.
     *
     * A trigger whose API arrived after this phone's Android version, or whose
     * radio the phone does not have, is filtered out — building a rule around it
     * would produce something that silently never fires. Permission-gated
     * components stay: those are a prompt away, and the editor states the
     * requirement inline.
     *
     * Filtered here rather than in `Registry`, which is deliberately
     * device-agnostic — the engine must resolve a stored type on any device,
     * including one where the picker would no longer offer it. [descriptorFor]
     * goes to the registry unfiltered for exactly that reason, so an imported or
     * previously-saved rule still renders its component instead of going blank.
     */
    val triggerOptions: List<ComponentDescriptor>
        get() = registry.triggerDescriptors.filter(checker::isAvailable)

    val actionOptions: List<ComponentDescriptor>
        get() = registry.actionDescriptors.filter(checker::isAvailable)

    init {
        if (ruleId != null) {
            viewModelScope.launch {
                // A one-shot read: the editor works on a snapshot, so an external
                // change while editing cannot yank the form out from under typing.
                repository.rules().first().firstOrNull { it.id == ruleId }?.let { rule ->
                    _state.value = EditorState(rule.toDraft())
                }
            }
        }
    }

    fun descriptorFor(slot: Slot, type: String): ComponentDescriptor? = when (slot) {
        Slot.TRIGGER -> registry.triggerDescriptor(type)
        Slot.ACTION -> registry.actionDescriptor(type)
    }

    fun setName(name: String) = edit { copy(name = name) }

    fun setEnabled(enabled: Boolean) = edit { copy(enabled = enabled) }

    fun chooseTrigger(type: String) = edit {
        val fields = registry.triggerDescriptor(type)?.configFields.orEmpty()
        copy(
            trigger = ComponentDraft(
                type = type,
                // Keeps compatible settings when swapping between similar triggers.
                config = migrateConfig(trigger?.config.orEmpty(), fields),
            )
        )
    }

    fun addAction(type: String) = edit {
        val fields = registry.actionDescriptor(type)?.configFields.orEmpty()
        copy(actions = actions + ComponentDraft(type, defaultConfigFor(fields)))
    }

    fun changeActionType(index: Int, type: String) = edit {
        val fields = registry.actionDescriptor(type)?.configFields.orEmpty()
        copy(
            actions = actions.mapIndexed { i, action ->
                if (i == index) {
                    ComponentDraft(type, migrateConfig(action.config, fields))
                } else {
                    action
                }
            }
        )
    }

    fun removeAction(index: Int) = edit {
        copy(actions = actions.filterIndexed { i, _ -> i != index })
    }

    /** Order is semantic — actions run in sequence — so moving them must persist. */
    fun moveAction(from: Int, to: Int) = edit {
        if (from !in actions.indices || to !in actions.indices) return@edit this
        val reordered = actions.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        copy(actions = reordered)
    }

    fun setConfigValue(slot: Slot, index: Int, key: String, value: String?) = edit {
        // Null and blank both mean "not set", so the factory sees an absent key
        // rather than an empty string. Several components treat absence as
        // "match anything", and "" would not.
        fun update(config: Map<String, String>) =
            if (value.isNullOrEmpty()) config - key else config + (key to value)

        when (slot) {
            Slot.TRIGGER -> copy(trigger = trigger?.copy(config = update(trigger.config)))
            Slot.ACTION -> copy(
                actions = actions.mapIndexed { i, action ->
                    if (i == index) action.copy(config = update(action.config)) else action
                }
            )
        }
    }

    /**
     * Validates by construction, then persists.
     *
     * The factories are the authority on whether config is valid — they hold
     * cross-field rules the schema cannot express, such as the watchdog's
     * "poll must not exceed absence". Their error messages are already written
     * for people, so they are shown verbatim.
     */
    fun save() {
        val draft = _state.value.draft

        val rule = draft.toRuleOrNull() ?: run {
            fail(
                if (draft.name.isBlank()) "Give the rule a name." else "Choose a trigger."
            )
            return
        }
        if (rule.actions.isEmpty()) {
            fail("Add at least one action, or the rule will do nothing.")
            return
        }

        validate(rule)?.let { fail(it); return }

        viewModelScope.launch {
            repository.upsert(rule)
            _state.update { it.copy(saved = true, error = null) }
        }
    }

    /** @return a human-readable problem, or null if every component builds. */
    private fun validate(rule: Rule): String? {
        runCatching { registry.createTrigger(rule.trigger) }
            .exceptionOrNull()
            ?.let { return describe(it, registry.displayNameOf(rule.trigger.type)) }

        rule.actions.forEachIndexed { index, spec ->
            runCatching { registry.createAction(spec) }
                .exceptionOrNull()
                ?.let {
                    return describe(it, "${registry.displayNameOf(spec.type)} (action ${index + 1})")
                }
        }
        return null
    }

    private fun describe(error: Throwable, componentName: String): String =
        "$componentName: ${error.message ?: error::class.simpleName}"

    fun delete() {
        val id = _state.value.draft.id ?: return
        viewModelScope.launch {
            repository.delete(id)
            _state.update { it.copy(saved = true) }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(error = message) }

    private fun edit(block: RuleDraft.() -> RuleDraft) = _state.update {
        // Any edit clears a stale error, so the message never outlives its cause.
        it.copy(draft = it.draft.block(), error = null)
    }

    /** Requirements for everything currently in the draft, for inline warnings. */
    fun requirementsFor(slot: Slot, type: String) =
        descriptorFor(slot, type)?.requirements.orEmpty()

    companion object {
        fun factory(
            repository: RuleRepository,
            registry: Registry,
            checker: RequirementChecker,
            ruleId: String?,
        ) = viewModelFactory {
            initializer { RuleEditorViewModel(repository, registry, checker, ruleId) }
        }
    }
}
