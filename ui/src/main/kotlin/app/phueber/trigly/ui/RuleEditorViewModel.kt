package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.TriggerEvent
import kotlinx.coroutines.Job
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
    /**
     * "This editor is finished with" — set by a successful save or a delete.
     *
     * A one-shot signal, and [RuleEditorViewModel.exitHandled] is the other half
     * of it. It has to be, because this ViewModel is keyed by rule id and lives
     * in the activity's store, so it outlives the screen: a flag left standing
     * means the *next* time that rule is opened the editor reads "already
     * finished" and closes before it is drawn. Which looks like the rule refusing
     * to open, and leaves a back press to be swallowed by a screen that has come
     * and gone.
     */
    val finished: Boolean = false,
    /** Index of the action currently being test-run, if any. */
    val testing: Int? = null,
    /** What the last test run reported. Replaced by the next one. */
    val testResult: String? = null,
)

class RuleEditorViewModel(
    private val repository: RuleRepository,
    private val registry: Registry,
    val checker: RequirementChecker,
    ruleId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState(RuleDraft(id = ruleId)))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    /** The in-flight test run, so a second press can stop it. */
    private var testJob: Job? = null

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
            _state.update { it.copy(finished = true, error = null) }
        }
    }

    /**
     * Runs one action now, so it can be judged by ear rather than by reading it
     * back.
     *
     * The reason this earns its place: half the settings on an action are
     * *sensory* — which sound, how loud, how long, what the spoken text sounds
     * like — and the alternative to a Test button is saving the rule, waiting for
     * the real trigger, and inferring what happened. Picking a sound and hearing
     * it is one tap.
     *
     * **Pressing it again stops it**, which is not a nicety. `play_alert` loops
     * for up to a minute by design, and until now the only way to cut one short
     * was to disable the whole rule; a test that cannot be stopped would be a
     * worse version of the same trap.
     *
     * Two things it deliberately does not pretend. The event is synthetic and
     * carries no payload, so an action that reads trigger payload sees nothing —
     * fine today, and the thing to revisit when payload substitution lands. And a
     * test runs while the app is on screen, which is exactly the condition under
     * which the background-start restriction does *not* apply: an "open" action
     * can pass here and still do nothing when the rule fires for real. The screen
     * says so rather than letting a green result imply more than it means.
     */
    fun testAction(index: Int) {
        // A second press on the running action is a stop button.
        if (_state.value.testing == index) {
            cancelTest("Stopped.")
            return
        }
        cancelTest(null)

        val draft = _state.value.draft.actions.getOrNull(index) ?: return
        val spec = ComponentSpec(draft.type, draft.config)
        val name = registry.displayNameOf(spec.type)

        // Built here rather than inside the coroutine so config the factory
        // refuses is reported as such, instead of as a failed run.
        val action = runCatching { registry.createAction(spec) }.getOrElse { cause ->
            _state.update { it.copy(testing = null, testResult = describe(cause, name)) }
            return
        }

        _state.update { it.copy(testing = index, testResult = null) }
        testJob = viewModelScope.launch {
            val outcome = runCatching { action.execute(testEvent()) }
            val message = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is ActionResult.Success -> "$name ran."
                        is ActionResult.Failure -> "$name failed: ${result.reason}"
                    }
                },
                // An action that throws rather than reporting is a bug in the
                // action, and saying which one is the useful part.
                onFailure = { "$name threw ${it::class.simpleName}: ${it.message}" },
            )
            _state.update { it.copy(testing = null, testResult = message) }
        }
    }

    fun clearTestResult() = _state.update { it.copy(testResult = null) }

    private fun cancelTest(message: String?) {
        testJob?.cancel()
        testJob = null
        _state.update { it.copy(testing = null, testResult = message ?: it.testResult) }
    }

    /**
     * The event a test run hands the action.
     *
     * A distinct trigger type rather than a plausible-looking one, so anything
     * that logs it says plainly that this was a test and not a rule firing.
     */
    private fun testEvent() = TriggerEvent(
        triggerType = "test",
        firedAtMillis = System.currentTimeMillis(),
    )

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
            _state.update { it.copy(finished = true) }
        }
    }

    /**
     * Acknowledges [EditorState.finished], so the signal fires once.
     *
     * The same shape as `RulesViewModel.clearMessage()`, and for the same reason:
     * a StateFlow is the wrong tool for an event, and the fix is to let the
     * consumer say it has dealt with it rather than leaving the flag set for the
     * next reader to trip over.
     */
    fun exitHandled() = _state.update { it.copy(finished = false) }

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
