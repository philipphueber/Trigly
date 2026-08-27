package app.phueber.trigly.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.NodePath
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.RequirementChecker
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleRepository
import app.phueber.trigly.core.SampleLookup
import app.phueber.trigly.core.ScopedVariable
import app.phueber.trigly.core.Substituted
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.core.canStart
import app.phueber.trigly.core.leaves
import app.phueber.trigly.core.parseTemplate
import app.phueber.trigly.core.scoped
import app.phueber.trigly.core.substitute
import app.phueber.trigly.core.RuleVariableStore
import app.phueber.trigly.core.RunScope
import app.phueber.trigly.core.scopedFor
import app.phueber.trigly.core.variableProblems
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /**
     * App scope, as of the store's last emission. See
     * [RuleEditorViewModel.availableVariables] for why this lives in the
     * state rather than being read from the store on every access: the store
     * hands out a `Flow`, and this is where that flow is collected into
     * something a plain getter can read.
     *
     * Carried forward by [RuleEditorViewModel.load] across a reset, rather
     * than defaulting to empty here, because [load] replaces the whole state
     * for a reason that has nothing to do with app scope: opening a
     * different rule, or throwing an unsaved draft away. Losing what the
     * store already reported would make the picker forget every app
     * variable until the store happened to emit again.
     */
    val appVariables: List<ScopedVariable> = emptyList(),
    /**
     * This rule's own `{{mine.*}}` values, as of the store's last emission.
     * Collected the same way [appVariables] is and carried across a reset for
     * the same reason.
     *
     * Only this rule's. That is the scope's whole point, and it is why this is
     * keyed by the rule being edited rather than being the whole table filtered
     * in the screen.
     */
    val ruleVariables: List<ScopedVariable> = emptyList(),
)

class RuleEditorViewModel(
    private val repository: RuleRepository,
    private val registry: Registry,
    val checker: RequirementChecker,
    private val variableStore: VariableStore,
    private val ruleVariableStore: RuleVariableStore,
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
     * What this draft's trigger tree, as currently built, lets an action read,
     * plus whatever app scope currently holds.
     *
     * The trigger half is recomputed from the live draft rather than cached, so
     * it tracks a trigger swap or a second leaf being added while the editor is
     * open. Empty groups are ignored the same way [triggerOptionsFor] ignores
     * them. A group the person has not filled in yet offers nothing to read,
     * but it should not make the variables the *other* leaves already offer
     * disappear from the picker while it sits there unfinished.
     *
     * The app half is [EditorState.appVariables], already collected into state
     * because [VariableStore.scoped] hands out a `Flow` and this is a plain
     * getter. [VariableStore.scoped] marks every entry as sometimes-absent,
     * which is honest: a variable exists only once some rule has written it,
     * and a rule that reads one before that rule has run is the ordinary case,
     * not an edge case. See `docs/variables.md` section 12.
     */
    val availableVariables: List<ScopedVariable>
        get() {
            val state = _state.value
            return registry.availableVariables(state.draft.trigger?.toNodeIgnoringEmptyGroups()) +
                state.appVariables +
                state.ruleVariables
        }

    /**
     * What the action at [index] can read: [availableVariables] plus what the
     * actions before it produce. See
     * [app.phueber.trigly.core.availableActionOutputs].
     *
     * Per action rather than once per screen, because the answer genuinely
     * differs down the list: the first action has no earlier action to read
     * from, and the last can read every producing one above it. A single list
     * for the whole editor would have to pick between offering the first
     * action names that can never resolve and hiding from the last action
     * names that always can.
     *
     * Read from the live draft, the same as [availableVariables], so adding,
     * removing or reordering an action changes what the ones below it are
     * offered without anything having to invalidate a cache.
     */
    fun availableVariablesForAction(index: Int): List<ScopedVariable> =
        availableVariables + registry.availableActionOutputs(
            _state.value.draft.actions.map { it.type },
            index,
        )

    init {
        load()
        // App scope is collected once, for the life of this ViewModel, rather
        // than re-subscribed by [load]: opening a different rule or discarding
        // a draft has nothing to do with what the store holds, and [load]
        // carries the last emission forward for exactly that reason.
        viewModelScope.launch {
            variableStore.scoped().collect { scoped ->
                _state.update { it.copy(appVariables = scoped) }
            }
        }
        // Only for a rule that exists. A draft has no id until it is saved, so
        // there is nothing to key a rule-scope value to and nothing to offer:
        // the first save is what makes this scope reachable at all.
        ruleId?.let { id ->
            viewModelScope.launch {
                ruleVariableStore.scopedFor(id).collect { scoped ->
                    _state.update { it.copy(ruleVariables = scoped) }
                }
            }
        }
    }

    /**
     * Puts the editor back to where it starts: empty for a new rule, and the
     * stored rule for an existing one.
     */
    private fun load() {
        _state.update { EditorState(RuleDraft(id = ruleId), appVariables = it.appVariables) }

        if (ruleId == null) return
        viewModelScope.launch {
            // A one-shot read: the editor works on a snapshot, so an external
            // change while editing cannot yank the form out from under typing.
            repository.rules().first().firstOrNull { it.id == ruleId }?.let { rule ->
                // Filled in before it is drawn, so the form shows what the rule
                // matches on rather than what the schema would default to. A key
                // a component added after this rule was saved is absent here, and
                // a `shownWhen` condition can only read a stored value; without
                // this the editor hides the filter that is deciding every match.
                // See `ComponentFactory.normalise`.
                _state.update {
                    EditorState(registry.normalise(rule).toDraft(), appVariables = it.appVariables)
                }
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

    // Every node in the trigger tree resolves through this same lookup, under
    // Slot.TRIGGER, whatever depth it sits at — see the KDoc on [Slot] for why
    // there is no third value for it.
    fun descriptorFor(slot: Slot, type: String): ComponentDescriptor? = when (slot) {
        Slot.TRIGGER -> registry.triggerDescriptor(type)
        Slot.ACTION -> registry.actionDescriptor(type)
    }

    fun setName(name: String) = edit { copy(name = name) }

    fun setEnabled(enabled: Boolean) = edit { copy(enabled = enabled) }

    /**
     * The rule's own folder name, typed or picked from an existing one — see
     * [RuleEditorScreen]'s `existingFolders`. Stored as-is; [RuleDraft.folder]'s
     * own KDoc is where blank collapses to "no folder", at save time, not here.
     */
    fun setFolder(folder: String) = edit { copy(folder = folder) }

    /**
     * Replaces whatever is at [path] with a fresh trigger of [type], migrating
     * compatible config across the swap the same way [changeActionType] does
     * for an action. Whatever was there before — a lone trigger, or (rarer,
     * but not refused) a whole group — is discarded; there is no sensible
     * config to carry over from a group, and none is lost that the person did
     * not just ask to replace.
     *
     * [chooseTrigger] is this at the empty root path, kept as its own entry
     * point only because it is what the rest of the app already calls by that
     * name for the common one-trigger case.
     */
    fun changeTriggerType(path: NodePath, type: String) = edit {
        // A group arrives here as an ordinary picked type — see [GROUP_OPTIONS] —
        // because the screen must not know that two of the picker's rows mean
        // something structural. This is the one place that reads them.
        groupOpFor(type)?.let { op ->
            return@edit copy(
                trigger = transformTrigger(trigger, path) { existing ->
                    when (existing) {
                        // Already a group: the pick changed the operator, which is
                        // the same edit the block's AND/OR control makes.
                        is TriggerDraft.Group -> existing.copy(op = op)
                        // A trigger becomes a group *holding that trigger*, rather
                        // than being replaced by an empty one. Changing the type of
                        // a leaf has never destroyed what was configured there, and
                        // "put this inside a group" is what someone picking a group
                        // on top of a trigger means.
                        is TriggerDraft.One -> TriggerDraft.Group(op, listOf(existing))
                        // Nothing chosen yet: an empty group, which the block
                        // renders with its own "Add trigger". Saving is refused
                        // until something is in it.
                        null -> TriggerDraft.Group(op, emptyList())
                    }
                }
            )
        }

        val fields = registry.triggerDescriptor(type)?.configFields.orEmpty()
        copy(
            trigger = transformTrigger(trigger, path) { existing ->
                val oldConfig = (existing as? TriggerDraft.One)?.component?.config.orEmpty()
                TriggerDraft.One(ComponentDraft(type, migrateConfig(oldConfig, fields)))
            }
        )
    }

    /** Sets or replaces the trigger at the root — the whole tree, when the
     * rule has only ever had one. See [changeTriggerType], which this is a
     * thin wrapper over.
     */
    fun chooseTrigger(type: String) = changeTriggerType(emptyList(), type)

    /**
     * Appends a new trigger of [type] to whatever is at [path].
     *
     * A [TriggerDraft.Group] there just gains a sibling. A [TriggerDraft.One]
     * there — including the common case of a rule with a single root trigger,
     * addressed by the empty path — has no group of its own yet, so adding a
     * second promotes it into one, combined with [TriggerNode.Op.ALL]: see
     * [addTriggerChild].
     */
    fun addTrigger(path: NodePath, type: String) = edit {
        // Picking a group here adds an empty one to fill in, for the same reason
        // as in [changeTriggerType]: a group is a trigger you pick.
        val addition = groupOpFor(type)?.let { op -> TriggerDraft.Group(op, emptyList()) }
            ?: TriggerDraft.One(
                ComponentDraft(
                    type,
                    defaultConfigFor(registry.triggerDescriptor(type)?.configFields.orEmpty()),
                )
            )
        copy(trigger = addTriggerChild(trigger, path, addition, TriggerNode.Op.ALL))
    }

    fun setTriggerOp(path: NodePath, op: TriggerNode.Op) = edit {
        copy(
            trigger = transformTrigger(trigger, path) { existing ->
                (existing as? TriggerDraft.Group)?.copy(op = op) ?: existing
            }
        )
    }

    /**
     * Removes the node at [path], root included — an empty [path] clears the
     * whole trigger back to its empty "choose a trigger" state rather than
     * leaving a node with nothing in it. A group that drops to one child
     * un-promotes into that child; see [transformTrigger].
     */
    fun removeTrigger(path: NodePath) = edit {
        copy(trigger = transformTrigger(trigger, path) { null })
    }

    fun setTriggerConfigValue(path: NodePath, key: String, value: String?) = edit {
        fun update(config: Map<String, String>) =
            if (value.isNullOrEmpty()) config - key else config + (key to value)
        copy(
            trigger = transformTrigger(trigger, path) { existing ->
                if (existing is TriggerDraft.One) {
                    existing.copy(component = existing.component.copy(config = update(existing.component.config)))
                } else {
                    existing
                }
            }
        )
    }

    /**
     * What a picker opened at [path] offers: only components that would leave
     * the tree still able to start — see [TriggerNode.canStart] for why an
     * `ALL of` group can fail even though every part in it looks fine alone.
     *
     * Derived rather than listed, on purpose: each candidate is inserted at
     * [path] for real, the same way [addTrigger] would (a lone trigger
     * promotes into a group, a group gains a child, an empty root becomes the
     * trigger outright), and kept only if the resulting tree still
     * [TriggerNode.canStart]. That single check is what a hand-written pair of
     * rules — "`time_window` cannot be a rule's only trigger", "a second
     * event-only component cannot join an `ALL` group" — would otherwise have
     * to state twice and would eventually let drift apart from each other;
     * both are exactly this same tree, after exactly this same insertion,
     * failing to start.
     */
    fun triggerOptionsFor(path: NodePath): List<ComponentDescriptor> {
        val root = _state.value.draft.trigger
        // The group rows are offered everywhere and are not put through the
        // can-this-still-start test below, because a group arrives empty: there is
        // nothing in it yet to start anything. An empty group is an unfinished
        // draft, not an invalid rule, and `save()` is what refuses it — the same
        // way it refuses a rule with no trigger at all.
        return GROUP_OPTIONS + triggerOptions.filter { descriptor ->
            val addition = TriggerDraft.One(ComponentDraft(descriptor.type))
            // Empty groups ignored rather than refused here: see
            // [toNodeIgnoringEmptyGroups]. A group waiting to be filled is not a
            // reason to stop offering the components that would fill it.
            val candidate = addTriggerChild(root, path, addition, TriggerNode.Op.ALL)
                ?.toNodeIgnoringEmptyGroups()
            candidate != null && candidate.canStart(::hasEvents, ::hasState)
        }
    }

    private fun hasEvents(type: String): Boolean =
        registry.triggerDescriptor(type)?.producesEvents ?: false

    private fun hasState(type: String): Boolean =
        registry.triggerDescriptor(type)?.supportsCondition ?: false

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
     * Edits one config value on an action, addressed by its flat index.
     *
     * A trigger node's config goes through [setTriggerConfigValue] instead —
     * it needs a tree path, not a flat index, now that a trigger is a tree
     * rather than a list; there is no longer an index for [Slot.TRIGGER] to
     * mean. [Slot] is kept as a parameter anyway, an exhaustive `when` with a
     * no-op [Slot.TRIGGER] branch rather than dropped from the signature,
     * because this is the one *index*-addressed action method other code
     * outside this file already calls by this exact name and shape — see
     * [Slot]'s own KDoc for why it keeps both values regardless.
     */
    fun setConfigValue(slot: Slot, index: Int, key: String, value: String?) = edit {
        // Null and blank both mean "not set", so the factory sees an absent key
        // rather than an empty string. Several components treat absence as
        // "match anything", and "" would not.
        fun update(config: Map<String, String>) =
            if (value.isNullOrEmpty()) config - key else config + (key to value)

        when (slot) {
            Slot.TRIGGER -> this
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
                when {
                    draft.name.isBlank() -> "Give the rule a name."
                    draft.trigger == null -> "Choose a trigger."
                    // A group with nothing in it. Reachable in one tap — a group
                    // is picked from the trigger picker and arrives empty — so it
                    // needs its own sentence rather than being told to choose a
                    // trigger it can see it already has.
                    else -> "A group has no triggers in it. Add one, or remove the group."
                }
            )
            return
        }
        if (rule.actions.isEmpty()) {
            fail("Add at least one action, or the rule will do nothing.")
            return
        }

        // The picker cannot prevent this one. It filters what a slot offers, so
        // a tree that cannot start is unbuildable *by adding a trigger*. A
        // setting changed afterwards is a different route to the same tree: the
        // location component's "only check, never watch" switch turns off the
        // events of a leaf that is already there, and a rule whose only trigger
        // is set to check can never fire. Nothing else would say so. The rule
        // would sit in the list looking enabled and waiting.
        //
        // Refused rather than warned, for the same reason the picker filters
        // rather than warns: a stored rule that can never run is the failure
        // this whole model exists to make impossible, and a warning someone can
        // save past is how it gets stored anyway.
        if (!rule.trigger.canStart(::hasEvents, ::hasState)) {
            fail(
                "This rule can never start. One trigger must start it, and the " +
                    "others are only checked when it does. A trigger set to only " +
                    "check never starts a rule."
            )
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
     * Two things worth knowing about what this run means. Every substitutable
     * field is resolved against [SampleLookup] before the action is built, using
     * the same samples the picker shows, so a rule that reads
     * `{{trigger.title}}` is tested with a realistic title rather than nothing.
     * See `docs/variables.md` section 12. The screen states this on the result,
     * because a green result that looked like real data would be worse than the
     * old honest emptiness. And a test runs while the app is on screen, which is
     * exactly the condition under which the background-start restriction does
     * *not* apply: an "open" action can pass here and still do nothing when the
     * rule fires for real. The screen says so rather than letting a green result
     * imply more than it means.
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

        // Every field the action's own schema marks as substitutable is filled
        // in from samples before anything is built. A field that cannot resolve
        // is reported as the test result rather than run with a hole in it. That
        // is the same "refuse rather than run wrong" rule `Template.substitute`
        // states for the engine itself.
        // Includes app scope, the same as [availableVariables]: a test run
        // that could not resolve `{{app.something}}` because this list forgot
        // app scope would be the sample path silently narrower than the save
        // path it is supposed to stand in for.
        // Per index, not the screen-wide list: an action that reads
        // `{{action.value}}` from the one above it has to resolve to that
        // action's declared sample here, or a test run would report a
        // reference the real firing resolves without trouble as unfillable.
        val lookup = SampleLookup(availableVariablesForAction(index))
        val resolvedConfig = spec.config.toMutableMap()
        var readsAVariable = false
        for ((key, encoding) in registry.substitutionsFor(spec)) {
            val raw = spec.config[key] ?: continue
            val template = parseTemplate(raw)
            // A field that holds no reference is left exactly as it was typed,
            // and it is what decides whether the result mentions samples at all.
            if (!template.hasReferences) continue
            readsAVariable = true
            when (val resolved = template.substitute(lookup, encoding)) {
                is Substituted.Ok -> resolvedConfig[key] = resolved.value
                is Substituted.Failed -> {
                    val reason = "could not fill in a sample for '$key'. ${resolved.reason}"
                    _state.update { it.copy(testing = null, testResult = "$name: $reason") }
                    return
                }
            }
        }
        val resolvedSpec = spec.copy(config = resolvedConfig)

        // Built here rather than inside the coroutine so config the factory
        // refuses is reported as such, instead of as a failed run.
        val action = runCatching { registry.createAction(resolvedSpec) }.getOrElse { cause ->
            _state.update { it.copy(testing = null, testResult = describe(cause, name)) }
            return
        }

        _state.update { it.copy(testing = index, testResult = null) }
        testJob = viewModelScope.launch {
            // A run scope, so an action writing to `this run` or `this rule`
            // does the real thing here instead of reporting that no rule is
            // running. The Test button exists to answer "does this work", and a
            // scope that only works outside the editor would make it answer a
            // narrower question than the person is asking.
            //
            // Only for a saved rule. A draft has no id, so a rule-scope write
            // has nothing to key to and the foreign key would refuse it; the
            // action's own "only exists while a rule is running" failure is the
            // honest answer then, and it points at the fix, which is to save.
            val runScope = _state.value.draft.id?.let { RunScope(it) }
            val outcome = runCatching {
                if (runScope == null) {
                    action.execute(testEvent())
                } else {
                    withContext(runScope) { action.execute(testEvent()) }
                }
            }
            val message = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is ActionResult.Success -> "$name ran."
                        is ActionResult.Failure -> "$name failed. ${result.reason}"
                    }
                },
                // An action that throws rather than reporting is a bug in the
                // action, and saying which one is the useful part.
                onFailure = { "$name threw ${it::class.simpleName}: ${it.message}" },
            )
            // Stated on every outcome of an action that reads one, not only on
            // success: a failure or a thrown exception can still be read as
            // "that is what would really happen", and the values it happened
            // with were samples either way. Stated on nothing else, because an
            // action with no variable in it did not use a sample, and a line
            // saying otherwise on every test would train people to skip it.
            val note = if (readsAVariable) " $SAMPLE_VALUES_NOTE" else ""
            _state.update { it.copy(testing = null, testResult = "$message$note") }
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
        // Every leaf resolves through the same factory lookup whether it is
        // the edge that starts the rule or a passive node nested under it —
        // see `docs/conditions.md`'s "grouped under one component,
        // transparently" — so they get one loop and one label, unsuffixed for
        // the common one-leaf rule so its message reads exactly as it always
        // has; only a tree with several leaves needs to say which one is at
        // fault.
        val leaves = rule.trigger.leaves()
        leaves.forEachIndexed { index, spec ->
            runCatching { registry.createTrigger(spec) }
                .exceptionOrNull()
                ?.let { return describe(it, triggerLabel(spec, index, leaves.size)) }
        }

        rule.actions.forEachIndexed { index, spec ->
            runCatching { registry.createAction(spec) }
                .exceptionOrNull()
                ?.let {
                    return describe(it, "${registry.displayNameOf(spec.type)} (action ${index + 1})")
                }
        }

        // Every `{{...}}` reference in the rule has to name a variable this
        // rule actually offers. See `docs/variables.md` section 9. One check,
        // asked of every component, trigger leaves and actions alike: which
        // keys are substitutable is `registry.substitutionsFor`'s answer, not a
        // list this loop keeps of its own, so a trigger that later declares a
        // substitutable field is covered here without this changing. A
        // reference to a value that is only sometimes present is not a problem
        // in this check. That is what the picker's mark is for, not save-time
        // validation.
        val available = registry.availableVariables(rule.trigger)
        leaves.forEachIndexed { index, spec ->
            val label = triggerLabel(spec, index, leaves.size)
            variableProblem(spec, available)?.let { return "$label: $it" }
        }
        // An action gets the trigger tree's variables *and* what the actions
        // above it produce, which is why this is not the one `available` list
        // the leaves share. A trigger is never offered an action's output: a
        // trigger's configuration decides whether the rule runs at all, so
        // there is no earlier action for it to have read from. Checked against
        // `rule.actions`, the list about to be saved, so a reference that only
        // resolved before an action was moved or deleted is refused here
        // rather than failing on every firing afterwards.
        val actionTypes = rule.actions.map { it.type }
        rule.actions.forEachIndexed { index, spec ->
            val label = "${registry.displayNameOf(spec.type)} (action ${index + 1})"
            val forThisAction =
                available + registry.availableActionOutputs(actionTypes, index)
            variableProblem(spec, forThisAction)?.let { return "$label: $it" }
        }

        return null
    }

    /** A trigger leaf's label for a validation message: plain when it is the
     * rule's only trigger, numbered once a tree has more than one. */
    private fun triggerLabel(spec: ComponentSpec, index: Int, leafCount: Int): String {
        val name = registry.displayNameOf(spec.type)
        return if (leafCount > 1) "$name (trigger ${index + 1})" else name
    }

    /** The first thing wrong with a reference in [spec]'s own fields, or null. */
    private fun variableProblem(spec: ComponentSpec, available: List<ScopedVariable>): String? =
        registry.substitutionsFor(spec).keys.firstNotNullOfOrNull { key ->
            spec.config[key]?.let { value -> variableProblems(value, available).firstOrNull() }
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

    /**
     * Applies one change to the draft, and repairs the rule's references
     * afterwards. See [repairReferences].
     *
     * **The repair belongs here, in the one funnel, and not at the handful of
     * call sites that can actually move the numbering.** A component's
     * namespace comes from its position among the components of its type, so a
     * delete, a reorder or a type change can alter what an existing
     * `{{...}}` reference means without touching the reference. A mutation
     * added later that forgets to ask for the repair would reintroduce that
     * silently, and silently is the whole problem: the rule still saves and
     * still runs, and reads the wrong component. Doing it for every edit
     * cannot be forgotten.
     *
     * It costs a walk of the trigger tree and two small list comparisons per
     * edit, including the edits that cannot possibly move anything, such as a
     * keystroke in a text field. That is bounded by the size of one rule and
     * is not measurable next to the recomposition the same update triggers.
     * [repairReferences] returns the draft unchanged when nothing moved, which
     * is nearly always.
     */
    private fun edit(block: RuleDraft.() -> RuleDraft) = _state.update {
        val before = it.draft
        val after = before.block()
        // Any edit clears a stale error, so the message never outlives its cause.
        it.copy(draft = repairReferences(before, after, registry), error = null)
    }

    /** Requirements for everything currently in the draft, for inline warnings. */
    fun requirementsFor(slot: Slot, type: String) =
        descriptorFor(slot, type)?.requirements.orEmpty()

    companion object {
        fun factory(
            repository: RuleRepository,
            registry: Registry,
            checker: RequirementChecker,
            variableStore: VariableStore,
            ruleVariableStore: RuleVariableStore,
            ruleId: String?,
        ) = viewModelFactory {
            initializer {
                RuleEditorViewModel(
                    repository, registry, checker, variableStore, ruleVariableStore, ruleId,
                )
            }
        }
    }
}

/**
 * Appended to the result of testing an action that reads a variable. Such a run
 * fills the field in from [SampleLookup] rather than from a real event, and a
 * result that did not say so would read as a report about the phone's actual
 * state.
 */
private const val SAMPLE_VALUES_NOTE = "Any variable used a sample value, not a real one."
