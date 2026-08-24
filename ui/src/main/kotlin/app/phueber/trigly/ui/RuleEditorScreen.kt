package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.shownWith
import app.phueber.trigly.core.companionKeys

/**
 * Create or edit one rule.
 *
 * Everything on one scrolling screen rather than a wizard: the intended user
 * builds rules repeatedly and knows what they want, so paging through steps
 * costs them time on every rule.
 *
 * Save and Delete live in a bottom strip rather than at the end of the scroll. A
 * rule with six actions is taller than a screen, and "where did Save go" is not
 * a question a dense power-user UI should ask. The strip also owns the
 * navigation-bar inset, and the whole screen takes `imePadding` so the keyboard
 * pushes it up instead of covering it.
 *
 * Stateless, like [RulesScreen] — it takes the draft and emits intents, which is
 * what lets the instrumented tests drive it without a ViewModel or a database.
 */
@Composable
fun RuleEditorScreen(
    state: EditorState,
    triggerOptions: List<ComponentDescriptor>,
    actionOptions: List<ComponentDescriptor>,
    /**
     * What a condition's picker offers — trigger-shaped components already
     * narrowed to `supportsCondition`. See `RuleEditorViewModel.conditionOptions`
     * and `docs/conditions.md`; filtered before it reaches here for the same
     * reason [triggerOptions] is: a slot must never show, then reject.
     *
     * Defaulted, along with every other new parameter below the trigger and
     * condition sections needed — see `docs/conditions.md`'s phasing and the
     * report accompanying this change: an existing caller (an instrumented
     * test this module does not own) still calls this composable with only the
     * pre-gate parameter set, and it is not this change's place to make that
     * stop compiling. A caller that omits them gets a "When" section that
     * cannot grow past one trigger and an "Only if" section that cannot be
     * added to — inert, not broken.
     */
    conditionOptions: List<ComponentDescriptor> = emptyList(),
    descriptorFor: (Slot, String) -> ComponentDescriptor?,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onChooseTrigger: (String) -> Unit,
    /** Adds a second (or third, …) trigger edge — the gate's first level is an
     * OR of these, per `docs/conditions.md`.
     */
    onAddTrigger: (String) -> Unit = {},
    onChangeTriggerType: (Int, String) -> Unit = { _, _ -> },
    onRemoveTrigger: (Int) -> Unit = {},
    onMoveTrigger: (Int, Int) -> Unit = { _, _ -> },
    onAddAction: (String) -> Unit,
    onChangeActionType: (Int, String) -> Unit,
    onRemoveAction: (Int) -> Unit,
    onMoveAction: (Int, Int) -> Unit,
    onConfigChange: (Slot, Int, String, String?) -> Unit,
    /**
     * The condition tree's edits, each addressed by a path of child indices
     * walked from the root through nested groups — the empty path means the
     * root itself. See [ConditionDraft] and [replaceCondition] for why a path
     * rather than a flat index: a tree of arbitrary depth has no flat index to
     * give it.
     */
    onAddConditionCheck: (List<Int>, String) -> Unit = { _, _ -> },
    onAddConditionGroup: (List<Int>) -> Unit = {},
    onRemoveCondition: (List<Int>) -> Unit = {},
    onSetConditionOp: (List<Int>, ConditionDraft.Op) -> Unit = { _, _ -> },
    onChangeConditionType: (List<Int>, String) -> Unit = { _, _ -> },
    onConditionConfigChange: (List<Int>, String, String?) -> Unit = { _, _, _ -> },
    onTestAction: (Int) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    /**
     * Whether a requirement is already met. Passed in rather than checked here
     * because it reads live device state — a permission granted in system
     * settings, an access toggled — which a stateless screen has no business
     * reaching for, and which the activity re-evaluates when it resumes.
     */
    isRequirementSatisfied: (ComponentRequirement) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<Picking?>(null) }
    val draft = state.draft

    // Which blocks are folded shut. Kept as the collapsed set rather than the
    // expanded one so that everything starts open: a rule with one action should
    // look exactly as it did before this existed, and folding is something the
    // user does rather than something they undo.
    //
    // Keyed by *position* — "trigger", "action-0" — because a `ComponentDraft`
    // has no identity of its own. So Up and Down move an action out from under
    // its own fold: the slot stays shut, not the action. That is the right way
    // round for the job it does, which is getting a long rule down to a list of
    // headings you can reorder.
    //
    // Saveable, so a rotation does not unfold the whole rule again.
    val collapsed = rememberSaveable(saver = stringListSaver) { mutableStateListOf<String>() }
    fun toggle(key: String) {
        if (!collapsed.remove(key)) collapsed += key
    }

    // Which blocks are currently showing their caveat prose. Separate from the
    // fold and separate from the block itself: the caveat is hidden by default
    // even in an open block, and the one way to see it is to tap its badge. Kept
    // out here, keyed by position like the fold, so it survives a rotation and so
    // Up/Down move the *slot's* state rather than the action's — the same choice,
    // for the same reason, as [collapsed].
    val shownCaveats = rememberSaveable(saver = stringListSaver) { mutableStateListOf<String>() }
    fun toggleCaveat(key: String) {
        if (!shownCaveats.remove(key)) shownCaveats += key
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        BlockHeader(
            title = if (draft.isNew) "New rule" else "Edit rule",
            leading = {
                // Discoverable back, for the half of Android that navigates by
                // gesture and never learned the edge swipe.
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = onNameChange,
                label = { Text("NAME *", style = MaterialTheme.typography.labelMedium) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlockToggle(checked = draft.enabled, onCheckedChange = onEnabledChange)
                Text(
                    text = "ENABLED",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }

            // A refused save, unlike a component caveat, is a fault: it gets the
            // error colour and a block of its own rather than a line of text.
            state.error?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        2.dp,
                        MaterialTheme.colorScheme.error,
                    ),
                    shape = BlockShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .hardShadow(BlockShape),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            // What a test run reported. Deliberately not the error colour: a
            // test that fails is information, not a fault in the rule — and one
            // that succeeds still needs saying, or pressing Test on a silent
            // action looks like nothing happened.
            state.testResult?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(
                        2.dp,
                        MaterialTheme.colorScheme.outline,
                    ),
                    shape = BlockShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .hardShadow(BlockShape),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            SectionLabel("When")
            if (draft.triggers.isEmpty()) {
                // Unchosen: exactly the one block this section has always shown,
                // with no footer and no "Add trigger" beneath it. A second edge
                // is something you add to a trigger you already have, not
                // something offered before you have picked the first one.
                ComponentBlock(
                    chosenType = null,
                    descriptor = null,
                    config = emptyMap(),
                    emptyLabel = "Choose a trigger",
                    onChoose = { picking = Picking.NewTrigger },
                    onConfigChange = { _, _ -> },
                    onResolveRequirement = onResolveRequirement,
                    expanded = TRIGGER_KEY !in collapsed,
                    onToggleExpanded = { toggle(TRIGGER_KEY) },
                    caveatShown = TRIGGER_KEY in shownCaveats,
                    onToggleCaveat = { toggleCaveat(TRIGGER_KEY) },
                    isRequirementSatisfied = isRequirementSatisfied,
                )
            } else {
                // The OR only exists to read at all once there is a second edge
                // to be one of — a single trigger carries no hint that this text
                // could ever appear, which is the whole point of it being absent
                // until then.
                if (draft.triggers.size > 1) {
                    Text(
                        text = "Any of these fire the rule.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                draft.triggers.forEachIndexed { index, trigger ->
                    // No footer at all for the common one-trigger case — see the
                    // KDoc on this call site's caller and `docs/conditions.md`:
                    // a single edge must look exactly as it did before several
                    // were possible, and that includes not gaining a Remove
                    // button it never had.
                    val footer: (@Composable () -> Unit)? = if (draft.triggers.size > 1) {
                        {
                            if (index > 0) {
                                BlockTextButton("Up") { onMoveTrigger(index, index - 1) }
                            }
                            if (index < draft.triggers.lastIndex) {
                                BlockTextButton("Down") { onMoveTrigger(index, index + 1) }
                            }
                            BlockTextButton("Remove") { onRemoveTrigger(index) }
                        }
                    } else {
                        null
                    }
                    ComponentBlock(
                        chosenType = trigger.type,
                        descriptor = descriptorFor(Slot.TRIGGER, trigger.type),
                        config = trigger.config,
                        emptyLabel = "Choose a trigger",
                        onChoose = { picking = Picking.TriggerType(index) },
                        onConfigChange = { key, value -> onConfigChange(Slot.TRIGGER, index, key, value) },
                        onResolveRequirement = onResolveRequirement,
                        modifier = Modifier.padding(bottom = 12.dp),
                        footer = footer,
                        expanded = triggerKey(index) !in collapsed,
                        onToggleExpanded = { toggle(triggerKey(index)) },
                        caveatShown = triggerKey(index) in shownCaveats,
                        onToggleCaveat = { toggleCaveat(triggerKey(index)) },
                        isRequirementSatisfied = isRequirementSatisfied,
                    )
                }
                BlockOutlineButton(
                    text = "Add trigger",
                    onClick = { picking = Picking.NewTrigger },
                    fillWidth = true,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
            }

            GateEditor(
                conditions = draft.conditions,
                // A condition check resolves through the same lookup a trigger
                // does — see the KDoc on [Slot] for why there is no third value
                // to ask for here.
                descriptorFor = { type -> descriptorFor(Slot.TRIGGER, type) },
                onAddCheckRequested = { path -> picking = Picking.NewCondition(path) },
                onChangeTypeRequested = { path -> picking = Picking.ConditionType(path) },
                onAddGroup = onAddConditionGroup,
                onRemove = onRemoveCondition,
                onSetOp = onSetConditionOp,
                onConfigChange = onConditionConfigChange,
                onResolveRequirement = onResolveRequirement,
                isRequirementSatisfied = isRequirementSatisfied,
                isExpanded = { key -> key !in collapsed },
                onToggleExpanded = ::toggle,
                isCaveatShown = { key -> key in shownCaveats },
                onToggleCaveat = ::toggleCaveat,
            )

            SectionLabel("Then")
            draft.actions.forEachIndexed { index, action ->
                ComponentBlock(
                    chosenType = action.type,
                    descriptor = descriptorFor(Slot.ACTION, action.type),
                    config = action.config,
                    emptyLabel = "Choose an action",
                    onChoose = { picking = Picking.ActionType(index) },
                    onConfigChange = { key, value ->
                        onConfigChange(Slot.ACTION, index, key, value)
                    },
                    onResolveRequirement = onResolveRequirement,
                    modifier = Modifier.padding(bottom = 12.dp),
                    footer = {
                        // Runs it now, because half of what an action does is
                        // sensory — which sound, how loud, how the spoken text
                        // reads — and the alternative is saving, waiting for the
                        // real trigger, and guessing. Doubles as a stop button
                        // while running: `play_alert` loops for up to a minute.
                        BlockTextButton(
                            if (state.testing == index) "Stop" else "Test"
                        ) { onTestAction(index) }
                        // Order matters — actions run in sequence.
                        if (index > 0) {
                            BlockTextButton("Up") { onMoveAction(index, index - 1) }
                        }
                        if (index < draft.actions.lastIndex) {
                            BlockTextButton("Down") { onMoveAction(index, index + 1) }
                        }
                        BlockTextButton("Remove") { onRemoveAction(index) }
                    },
                    expanded = actionKey(index) !in collapsed,
                    onToggleExpanded = { toggle(actionKey(index)) },
                    caveatShown = actionKey(index) in shownCaveats,
                    onToggleCaveat = { toggleCaveat(actionKey(index)) },
                    isRequirementSatisfied = isRequirementSatisfied,
                )
            }

            // Full width, like the trigger block above it: both are the "pick a
            // component" affordance for their section, and a shrink-wrapped box
            // floating at the left read as a stray control rather than the
            // counterpart to "Choose a trigger".
            BlockOutlineButton(
                text = "Add action",
                onClick = { picking = Picking.NewAction },
                fillWidth = true,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }

        BlockBottomBar {
            BlockButton(text = "Save", onClick = onSave, modifier = Modifier.weight(1f))
            if (!draft.isNew) {
                BlockOutlineButton(
                    text = "Delete rule",
                    onClick = onDelete,
                    contentColor = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    when (val target = picking) {
        null -> Unit

        // The very first trigger, unchosen, still goes through [onChooseTrigger]
        // rather than [onAddTrigger] — see the ViewModel's [chooseTrigger] KDoc.
        Picking.NewTrigger -> ComponentPickerDialog(
            title = if (draft.triggers.isEmpty()) "Choose a trigger" else "Add trigger",
            options = triggerOptions,
            onPick = {
                picking = null
                if (draft.triggers.isEmpty()) onChooseTrigger(it) else onAddTrigger(it)
            },
            onDismiss = { picking = null },
        )

        is Picking.TriggerType -> ComponentPickerDialog(
            title = "Change trigger",
            options = triggerOptions,
            onPick = { picking = null; onChangeTriggerType(target.index, it) },
            onDismiss = { picking = null },
        )

        is Picking.NewCondition -> ComponentPickerDialog(
            title = "Add a condition",
            options = conditionOptions,
            onPick = { picking = null; onAddConditionCheck(target.path, it) },
            onDismiss = { picking = null },
        )

        is Picking.ConditionType -> ComponentPickerDialog(
            title = "Change condition",
            options = conditionOptions,
            onPick = { picking = null; onChangeConditionType(target.path, it) },
            onDismiss = { picking = null },
        )

        Picking.NewAction -> ComponentPickerDialog(
            title = "Add an action",
            options = actionOptions,
            onPick = { picking = null; onAddAction(it) },
            onDismiss = { picking = null },
        )

        is Picking.ActionType -> ComponentPickerDialog(
            title = "Change action",
            options = actionOptions,
            onPick = { picking = null; onChangeActionType(target.index, it) },
            onDismiss = { picking = null },
        )
    }
}

private sealed interface Picking {
    /** The first trigger, unchosen, or a new edge beneath an existing one — the
     * picker dialog itself decides which by asking whether the draft has any
     * triggers yet, since both open the same dialog.
     */
    data object NewTrigger : Picking
    data class TriggerType(val index: Int) : Picking
    data class NewCondition(val path: List<Int>) : Picking
    data class ConditionType(val path: List<Int>) : Picking
    data object NewAction : Picking
    data class ActionType(val index: Int) : Picking
}

/** A solid tag rather than a heading: the section names are part of the blocks. */
@Composable
internal fun SectionLabel(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = BlockShape,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * One trigger or action, as a block split into cells: what it is, what to know
 * about it, its settings, what it needs, and what can be done to it.
 *
 * [expanded] folds the middle away. What it hides is what you *read and fill in*
 * — the settings that apply, and the requirements not yet met. A setting a
 * sibling has made irrelevant, and a permission already granted, are not hidden
 * by the fold: they are not drawn at all. What it keeps is the heading, the caveat
 * badge, the controls that act on the block, and any fault: a rule with six
 * actions is taller than several screens, and the thing worth compressing is the
 * reading, while Up, Down, Remove and "this component is not available" are
 * exactly what you still want to reach in a folded list.
 *
 * The caveat is not part of that middle. Its prose is hidden by default whether
 * the block is open or shut, and [caveatShown] — driven by the badge in the
 * header — is the only thing that reveals it. So it sits above the fold, reachable
 * even from a folded block: a caveat is worth reading before reordering a rule,
 * not only while filling one in.
 *
 * The fold is not offered when there would be nothing behind it — a chosen
 * component with no applicable settings and nothing left to grant has no middle,
 * and a button that visibly does nothing is worse than no button. A lone caveat does not bring the
 * fold back, because the caveat has its own control.
 *
 * `internal` rather than `private`: [GateEditor] renders a condition check with
 * the same block, since a check is a component chosen from a filtered list the
 * same way a trigger or action is, and the two must never drift into looking
 * like different kinds of thing.
 */
@Composable
internal fun ComponentBlock(
    chosenType: String?,
    descriptor: ComponentDescriptor?,
    config: Map<String, String>,
    emptyLabel: String,
    onChoose: () -> Unit,
    onConfigChange: (String, String?) -> Unit,
    onResolveRequirement: (ComponentRequirement) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    caveatShown: Boolean,
    onToggleCaveat: () -> Unit,
    isRequirementSatisfied: (ComponentRequirement) -> Boolean,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    // Only what applies right now. A setting a sibling has made irrelevant is
    // not drawn at all, and a requirement already granted has nothing left to
    // say — see the fold's KDoc above.
    val fields = descriptor?.configFields.orEmpty().shownWith(config)
    val unmet = descriptor?.requirements.orEmpty().filterNot(isRequirementSatisfied)

    val hasMiddle = descriptor != null && (fields.isNotEmpty() || unmet.isNotEmpty())

    BlockCard(modifier = modifier) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The block's main affordance, so it takes the accent.
                BlockTextButton(
                    text = descriptor?.displayName ?: chosenType ?: emptyLabel,
                    modifier = Modifier.weight(1f),
                    contentColor = MaterialTheme.extra.accent,
                    onClick = onChoose,
                )
                // The one control for the caveat, in the header so it stays with
                // the block whether it is folded or open, and beside the fold
                // button so the two reads — "show me the settings", "tell me the
                // catch" — sit together.
                if (descriptor?.warning != null) {
                    CaveatBadge(shown = caveatShown, onToggle = onToggleCaveat)
                }
                if (hasMiddle) {
                    // Says what pressing it does, not what the state is: "Hide"
                    // while the settings are showing. The alternative reading of
                    // a chevron or a state label is a coin toss, and this design
                    // has no icon vocabulary to lean on.
                    BlockTextButton(
                        text = if (expanded) "Hide" else "Show",
                        onClick = onToggleExpanded,
                    )
                }
            }

            // The caveat prose, hidden until the badge above is tapped, and shown
            // regardless of the fold — above the early returns below on purpose,
            // so a folded block can still surface its catch. The picker only ever
            // marks that this exists; here is where the sentence lives.
            if (descriptor?.warning != null && caveatShown) {
                BlockDivider()
                Surface(
                    color = MaterialTheme.extra.cautionContainer,
                    contentColor = MaterialTheme.extra.onCautionContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = descriptor.warning!!,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            if (descriptor == null) {
                // A stored rule can name a component this build does not have —
                // after a downgrade, or an import from a newer version.
                chosenType?.let {
                    BlockDivider()
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "\"$it\" is not available in this version of Trigly.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
                Footer(footer)
                return@Column
            }

            // Folded: heading and controls only. An early return rather than
            // wrapping the three cells below in a condition, which is the same
            // shape the unavailable-component case above already uses.
            if (!expanded) {
                Footer(footer)
                return@Column
            }

            if (fields.isNotEmpty()) {
                BlockDivider()
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    fields.forEach { field ->
                        // Some kinds own more than one config key, because the
                        // values are one decision: a pattern and its match mode,
                        // an hour and its minute, a latitude and its longitude,
                        // a button and the notification it belongs to.
                        ConfigFieldEditor(
                            field = field,
                            value = config[field.key],
                            onValueChange = { onConfigChange(field.key, it) },
                            companions = field.companionKeys()
                                .associateWith { key -> config[key] },
                            onCompanionChange = onConfigChange,
                        )
                    }
                }
            }

            // Stated while the rule is being built, so nobody saves something
            // that cannot fire and then wonders why.
            if (unmet.isNotEmpty()) {
                BlockDivider()
                Column(modifier = Modifier.fillMaxWidth()) {
                    unmet.forEach { requirement ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = requirement.describe(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(end = 8.dp),
                            )
                            if (requirement.isResolvable) {
                                BlockTextButton("Grant") { onResolveRequirement(requirement) }
                            }
                        }
                    }
                }
            }

            Footer(footer)
        }
    }
}

@Composable
private fun Footer(footer: (@Composable () -> Unit)?) {
    if (footer == null) return
    BlockDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        footer()
    }
}

/** The unchosen trigger placeholder's fold key — there is only ever one of it. */
private const val TRIGGER_KEY = "trigger"

private fun triggerKey(index: Int) = "trigger-$index"

private fun actionKey(index: Int) = "action-$index"

/**
 * Saves a set of position keys across a configuration change — the folded blocks
 * and the blocks whose caveat is open both use it.
 *
 * A `SnapshotStateList` is not saveable on its own, and the contents are plain
 * strings, so the list is what gets stored and `toMutableStateList` puts the
 * observability back on the way in.
 */
private val stringListSaver: Saver<SnapshotStateList<String>, Any> = listSaver(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)
