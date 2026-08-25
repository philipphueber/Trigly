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
import app.phueber.trigly.core.checks
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
    private val ruleId: String?,
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

    /**
     * What the condition picker offers: the same device-availability filter as
     * [triggerOptions], narrowed to components whose descriptor says
     * `supportsCondition`. Filtered here rather than left to the picker or the
     * factory, because a component that cannot answer a state question would
     * build a rule that never fires — silently and permanently, the one failure
     * mode this project keeps designing against. See `docs/conditions.md`.
     */
    val conditionOptions: List<ComponentDescriptor>
        get() = registry.triggerDescriptors
            .filter { it.supportsCondition }
            .filter(checker::isAvailable)

    init {
        load()
    }

    /**
     * Puts the editor back to where it starts: empty for a new rule, and the
     * stored rule for an existing one.
     */
    private fun load() {
        _state.value = EditorState(RuleDraft(id = ruleId))

        if (ruleId == null) return
        viewModelScope.launch {
            // A one-shot read: the editor works on a snapshot, so an external
            // change while editing cannot yank the form out from under typing.
            repository.rules().first().firstOrNull { it.id == ruleId }?.let { rule ->
                _state.value = EditorState(rule.toDraft())
            }
        }
    }

    /**
     * Throws the current draft away.
     *
     * Called when the editor screen is genuinely left, because this ViewModel
     * outlives it — see `MainActivity.EditorHost`. Without it the draft for an
     * unsaved rule persisted under the one key an unsaved rule can have,
     * "editor-new", and reappeared the next time "New rule" was tapped.
     *
     * A running test is cancelled here too, and that is not incidental:
     * `play_alert` loops for up to a minute, and until now walking out of the
     * editor left it sounding with no way to stop it.
     */
    fun reset() {
        testJob?.cancel()
        testJob = null
        load()
    }

    // A condition check resolves through this same lookup, under Slot.TRIGGER —
    // see the KDoc on [Slot] for why there is no third value for it.
    fun descriptorFor(slot: Slot, type: String): ComponentDescriptor? = when (slot) {
        Slot.TRIGGER -> registry.triggerDescriptor(type)
        Slot.ACTION -> registry.actionDescriptor(type)
    }

    fun setName(name: String) = edit { copy(name = name) }

    fun setEnabled(enabled: Boolean) = edit { copy(enabled = enabled) }

    /**
     * Replaces the type of the trigger at [index], migrating compatible config
     * across the swap the same way [chooseTrigger] always has.
     *
     * Also how a not-yet-chosen first trigger gets its first type: [index] 0
     * against an empty list has nothing to replace, so it appends instead —
     * which is what lets [chooseTrigger] stay a thin wrapper over this rather
     * than a second copy of the same logic.
     */
    fun changeTriggerType(index: Int, type: String) = edit {
        val fields = registry.triggerDescriptor(type)?.configFields.orEmpty()
        val replacement = ComponentDraft(
            type = type,
            config = migrateConfig(triggers.getOrNull(index)?.config.orEmpty(), fields),
        )
        copy(
            triggers = if (index in triggers.indices) {
                triggers.mapIndexed { i, t -> if (i == index) replacement else t }
            } else {
                triggers + replacement
            }
        )
    }

    /**
     * Sets or replaces the *first* trigger edge.
     *
     * Kept as its own entry point, rather than folded into [changeTriggerType]
     * at every call site, because it is the one a one-trigger rule — still the
     * common case — is built and re-picked through, and because it is what the
     * rest of the app already calls by this name.
     */
    fun chooseTrigger(type: String) = changeTriggerType(0, type)

    fun addTrigger(type: String) = edit {
        val fields = registry.triggerDescriptor(type)?.configFields.orEmpty()
        copy(triggers = triggers + ComponentDraft(type, defaultConfigFor(fields)))
    }

    fun removeTrigger(index: Int) = edit {
        copy(triggers = triggers.filterIndexed { i, _ -> i != index })
    }

    /** Order has no engine meaning — the first level is an OR of edges — but a
     * rule with several edges still reads better with them in the order the
     * person arranged them, the same courtesy [moveAction] extends to actions.
     */
    fun moveTrigger(from: Int, to: Int) = edit {
        if (from !in triggers.indices || to !in triggers.indices) return@edit this
        val reordered = triggers.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        copy(triggers = reordered)
    }

    /**
     * Adds a check to whatever is at [path] — the root itself for an empty
     * path — picking its component from [conditionOptions] first.
     *
     * The one function behind three different-looking moments: filling the
     * empty tail beneath the trigger blocks with its first passive slot,
     * adding a second top-level slot (which promotes the first into a group),
     * and adding a slot inside an existing group. All three are "add this
     * child at this address," which is exactly what [addCondition] does.
     */
    fun addConditionCheck(path: List<Int>, type: String) = edit {
        val fields = registry.triggerDescriptor(type)?.configFields.orEmpty()
        val check = ConditionDraft.Check(ComponentDraft(type, defaultConfigFor(fields)))
        copy(conditions = addCondition(conditions, path, check))
    }

    /**
     * Adds an empty AND-group at [path]. Unlike a check, a group needs no
     * picker — there is nothing to choose yet, only somewhere to put what gets
     * added to it next.
     */
    fun addConditionGroup(path: List<Int>) = edit {
        val group = ConditionDraft.Group(ConditionDraft.Op.ALL, emptyList())
        copy(conditions = addCondition(conditions, path, group))
    }

    /**
     * Removes the node at [path], root included — an empty [path] clears the
     * whole section back to its empty state rather than leaving a node with
     * nothing in it.
     */
    fun removeCondition(path: List<Int>) = edit {
        copy(conditions = replaceCondition(conditions, path) { null })
    }

    fun setConditionOp(path: List<Int>, op: ConditionDraft.Op) = edit {
        copy(
            conditions = replaceCondition(conditions, path) { existing ->
                (existing as? ConditionDraft.Group)?.copy(op = op) ?: existing
            }
        )
    }

    /** Replaces the component a condition check asks about, migrating config
     * across the swap the same way [changeTriggerType] does for a trigger.
     */
    fun changeConditionType(path: List<Int>, type: String) = edit {
        val fields = registry.triggerDescriptor(type)?.configFields.orEmpty()
        copy(
            conditions = replaceCondition(conditions, path) { existing ->
                val oldConfig = (existing as? ConditionDraft.Check)?.component?.config.orEmpty()
                ConditionDraft.Check(ComponentDraft(type, migrateConfig(oldConfig, fields)))
            }
        )
    }

    fun setConditionConfigValue(path: List<Int>, key: String, value: String?) = edit {
        fun update(config: Map<String, String>) =
            if (value.isNullOrEmpty()) config - key else config + (key to value)
        copy(
            conditions = replaceCondition(conditions, path) { existing ->
                val check = existing as? ConditionDraft.Check
                if (check == null) {
                    existing
                } else {
                    check.copy(component = check.component.copy(config = update(check.component.config)))
                }
            }
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

    /**
     * Edits one config value on a trigger edge or an action. A condition
     * check's config goes through [setConditionConfigValue] instead — it needs
     * a tree path rather than a flat index, so it could not share this
     * signature and still address anything below the top level.
     */
    fun setConfigValue(slot: Slot, index: Int, key: String, value: String?) = edit {
        // Null and blank both mean "not set", so the factory sees an absent key
        // rather than an empty string. Several components treat absence as
        // "match anything", and "" would not.
        fun update(config: Map<String, String>) =
            if (value.isNullOrEmpty()) config - key else config + (key to value)

        when (slot) {
            Slot.TRIGGER -> copy(
                triggers = triggers.mapIndexed { i, t ->
                    if (i == index) t.copy(config = update(t.config)) else t
                }
            )
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

    /**
     * Stops a running test and leaves the draft alone.
     *
     * What the editor calls when it is left: [reset] would also throw the draft
     * away, which is wrong on exit — a rotation is an exit too, and the draft has
     * to survive it. Silencing a looping `play_alert` is the part that must
     * happen whenever the screen goes away.
     */
    fun stopTest() = cancelTest(null)

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
        // Unsuffixed for the common one-trigger rule, so its message reads
        // exactly as it always has; only a gate with several edges needs to say
        // which one is at fault.
        rule.gate.triggers.forEachIndexed { index, spec ->
            runCatching { registry.createTrigger(spec) }
                .exceptionOrNull()
                ?.let {
                    val name = registry.displayNameOf(spec.type)
                    val label = if (rule.gate.hasSeveralTriggers) "$name (trigger ${index + 1})" else name
                    return describe(it, label)
                }
        }

        // A passive slot is built the same way an edge is — it names the same
        // factory, only asked a different question — so the same construction
        // failure is possible and gets the same treatment. Labelled by what it
        // does ("must also be true") rather than by a name for the object, the
        // same choice the screen itself makes — see `docs/conditions.md`.
        val checks = rule.gate.conditions?.checks().orEmpty()
        checks.forEachIndexed { index, spec ->
            runCatching { registry.createTrigger(spec) }
                .exceptionOrNull()
                ?.let {
                    val name = registry.displayNameOf(spec.type)
                    val label = if (checks.size > 1) {
                        "$name (must also be true, ${index + 1})"
                    } else {
                        "$name (must also be true)"
                    }
                    return describe(it, label)
                }
        }

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
