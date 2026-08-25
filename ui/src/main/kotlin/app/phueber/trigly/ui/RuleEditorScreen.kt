package app.phueber.trigly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.phueber.trigly.core.ActiveNotification
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.ComponentTool
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NodePath
import app.phueber.trigly.core.TriggerNode
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
    /**
     * What a trigger picker offers at a given point in the tree — the empty
     * path for the root, a group's own path for a sibling inside it. A
     * function rather than a flat list because availability is path-dependent:
     * `TriggerNode.canStart`'s "one edge, any number of levels" rule means a
     * slot beside an existing edge must not offer a second one, so a slot must
     * never show a component only to refuse it once picked. See
     * `RuleEditorViewModel.triggerOptionsFor`.
     */
    triggerOptionsFor: (NodePath) -> List<ComponentDescriptor>,
    actionOptions: List<ComponentDescriptor>,
    descriptorFor: (Slot, String) -> ComponentDescriptor?,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    /**
     * Folder names already used by other rules — not this one's own current
     * value, which is [EditorState.draft]'s own [RuleDraft.folder] — offered so
     * a repeat name is a pick rather than a retyped, and possibly mistyped, one.
     * The caller draws this from the saved rules; an editor working from its own
     * one-rule snapshot has no way to know what else exists. Defaults to empty,
     * so a rule with nothing to offer still gets a working "type a new one" field.
     */
    existingFolders: List<String> = emptyList(),
    /** Sets the rule's folder — see [existingFolders] and [FolderField]. */
    onFolderChange: (String) -> Unit = {},
    /** Sets the first trigger, from the empty "Choose a trigger" slot. */
    onChooseTrigger: (String) -> Unit,
    /**
     * Replaces the type of the node at this path, migrating compatible config
     * across the swap the same way it always has.
     */
    onChangeTriggerType: (NodePath, String) -> Unit = { _, _ -> },
    /**
     * Adds a sibling beside the node at this path. A leaf there becomes a
     * group of two — see `TriggerNode.addAt` — which is how a group comes
     * into existence without the user first having to choose a container.
     */
    onAddTrigger: (NodePath, String) -> Unit = { _, _ -> },
    /** Adds a nested group at this path, holding one freshly-picked leaf. */
    /** Flips a group between "all of" and "any of". */
    onSetTriggerOp: (NodePath, TriggerNode.Op) -> Unit = { _, _ -> },
    /**
     * Removes the node at this path. An empty path clears the whole "When"
     * section back to its unchosen state — see `TriggerNode.removeAt`.
     */
    onRemoveTrigger: (NodePath) -> Unit = {},
    /** Edits one config value on the leaf at this path. */
    onSetTriggerConfigValue: (NodePath, String, String?) -> Unit = { _, _, _ -> },
    onAddAction: (String) -> Unit,
    onChangeActionType: (Int, String) -> Unit,
    onRemoveAction: (Int) -> Unit,
    onMoveAction: (Int, Int) -> Unit,
    onConfigChange: (Slot, Int, String, String?) -> Unit,
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
    /**
     * Asks the launcher to pin a home-screen button for a shortcut trigger.
     * Takes the trigger's whole config because the id, the name and the icon all
     * live there, and the screen has no business picking them apart.
     */
    onPinShortcut: (Map<String, String>) -> Unit = {},
    /**
     * What tools a component offers on its block — see [ComponentTool].
     *
     * Asked per component *and per configuration*, rather than derived from a type
     * string, which is how this screen stopped knowing that shortcut triggers can
     * be pinned and that actions can be tested. Defaults to nothing, so a preview
     * or a test renders plain blocks.
     */
    toolsFor: (String, Map<String, String>) -> List<ComponentTool> = { _, _ -> emptyList() },
    /** Live notifications for the inspector, shown over the editor rather than navigated to. */
    inspectorNotifications: () -> List<ActiveNotification> = { emptyList() },
    /** Whether the notification listener is bound — an empty list means two different things. */
    inspectorConnected: () -> Boolean = { false },
    describeApp: (String) -> String = { it },
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<Picking?>(null) }

    // The inspector opens *over* the editor rather than as a destination, and that
    // is not cosmetic: leaving the editor's composition would fire the
    // fresh-entry reset that keeps a new rule empty, discarding the draft someone
    // is halfway through writing. A reference you consult while filling a field
    // has no business costing you the field.
    var inspecting by remember { mutableStateOf(false) }
    val draft = state.draft

    // Which blocks are folded shut. Kept as the collapsed set rather than the
    // expanded one so that everything starts open: a rule with one action should
    // look exactly as it did before this existed, and folding is something the
    // user does rather than something they undo.
    //
    // Keyed by *position* — "trigger", "action-0" — because a `ComponentDraft`
    // has no identity of its own. That is the right way round for the job
    // folding does, which is getting a long rule down to a list of headings.
    //
    // A trigger group is the one exception, seeded shut here rather than left
    // to the same "everything starts open" default: it is what makes a rule
    // with several nested gates one line until asked, rather than the whole
    // tree dumped on screen the moment its rule is opened. The seeding only
    // ever runs once — inside `rememberSaveable`'s initial-value lambda — so it
    // captures the tree exactly as this editor was entered with. A group added
    // afterwards, in this same session, is not in that snapshot and so stays
    // open: the person just built it and is still working in it.
    //
    // Saveable, so a rotation does not unfold the whole rule again.
    val collapsed = rememberSaveable(saver = stringListSaver) {
        mutableStateListOf<String>().apply { addAll(initiallyCollapsedTriggerGroups(draft.trigger)) }
    }
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

    // Scoped to this screen's own content rather than provided by the
    // activity: only the editor knows which rule is currently open, and
    // that changes every time the editor does. See `LocalCurrentRuleId`'s
    // KDoc in `RulePicker.kt` for why a rule-reference field needs this at
    // all: it's what lets the picker mark this rule as the one being
    // edited, rather than excluding it (a deliberate, documented feature
    // — see `SetRuleEnabledActionFactory`).
    CompositionLocalProvider(LocalCurrentRuleId provides draft.id) {
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

                // The rule's own property, next to the name it sits beside — not a
                // component's config field, so it does not go through `ConfigField`
                // or `ComponentBlock`.
                FolderField(
                    folder = draft.folder,
                    existingFolders = existingFolders,
                    onFolderChange = onFolderChange,
                    modifier = Modifier.padding(top = 16.dp),
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
                // The whole trigger side is one slot: nothing chosen, one
                // component, or a group of them — see [TriggerDraft]. There is
                // deliberately no second "must also be true" region beneath
                // this any more; a gate is a trigger, and it lives here.
                when (val trigger = draft.trigger) {
                    null -> ComponentBlock(
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

                    else -> TriggerNodeBlock(
                        node = trigger,
                        path = emptyList(),
                        descriptorFor = { type -> descriptorFor(Slot.TRIGGER, type) },
                        // Whatever this component says it offers — a notification
                        // trigger's Inspect, a shortcut trigger's pin — the same
                        // as any other trigger block, wherever in the tree it sits.
                        tools = { type, config ->
                            ComponentTools(
                                tools = toolsFor(type, config),
                                config = config,
                                onPinShortcut = onPinShortcut,
                                onInspect = { inspecting = true },
                            )
                        },
                        onChangeTypeRequested = { path -> picking = Picking.ChangeTriggerType(path) },
                        onAddTriggerRequested = { path -> picking = Picking.AddTrigger(path) },
                        onSetOp = onSetTriggerOp,
                        onRemove = onRemoveTrigger,
                        onConfigChange = onSetTriggerConfigValue,
                        onResolveRequirement = onResolveRequirement,
                        isRequirementSatisfied = isRequirementSatisfied,
                        isExpanded = { key -> key !in collapsed },
                        onToggleExpanded = ::toggle,
                        isCaveatShown = { key -> key in shownCaveats },
                        onToggleCaveat = ::toggleCaveat,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

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
                            // Test is no longer written here: every action declares
                            // it through `ActionFactory.toolsFor`, and the ones with
                            // more to offer — the notification actions, which can
                            // open the inspector — declare that alongside it. The
                            // running/Stop state stays the screen's business,
                            // because only the screen knows which action is running.
                            ComponentTools(
                                tools = toolsFor(action.type, action.config),
                                config = action.config,
                                onPinShortcut = onPinShortcut,
                                onInspect = { inspecting = true },
                                testLabel = if (state.testing == index) "Stop" else "Test",
                                onTest = { onTestAction(index) },
                            )
                            // Order matters — actions run in sequence, so the
                            // mapping from icon to effect is fixed: Up is always
                            // `index - 1`, never the reverse, because an icon that
                            // is ambiguous about direction is worse than the text
                            // it replaces.
                            if (index > 0) {
                                ActionOrderButton(
                                    icon = Icons.Filled.KeyboardArrowUp,
                                    label = "Move up",
                                    onClick = { onMoveAction(index, index - 1) },
                                )
                            }
                            if (index < draft.actions.lastIndex) {
                                ActionOrderButton(
                                    icon = Icons.Filled.KeyboardArrowDown,
                                    label = "Move down",
                                    onClick = { onMoveAction(index, index + 1) },
                                )
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
    }

    when (val target = picking) {
        null -> Unit

        // The very first trigger, unchosen — the only picking moment that has
        // no path of its own, since there is nothing yet to be a sibling of.
        Picking.NewTrigger -> ComponentPickerDialog(
            title = "Choose a trigger",
            options = triggerOptionsFor(emptyList()),
            onPick = { picking = null; onChooseTrigger(it) },
            onDismiss = { picking = null },
        )

        is Picking.ChangeTriggerType -> ComponentPickerDialog(
            title = "Change trigger",
            options = triggerOptionsFor(target.path),
            onPick = { picking = null; onChangeTriggerType(target.path, it) },
            onDismiss = { picking = null },
        )

        is Picking.AddTrigger -> ComponentPickerDialog(
            title = "Add trigger",
            options = triggerOptionsFor(target.path),
            onPick = { picking = null; onAddTrigger(target.path, it) },
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

    if (inspecting) {
        // Full-bleed rather than an inset dialog: it is a screen's worth of
        // content — several notifications, each with every field a matcher can
        // read — and squeezing that into a dialog's default width would make the
        // one thing it exists to show, the exact strings, wrap into mush.
        Dialog(
            onDismissRequest = { inspecting = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // `heightIn(max = …)`, not `fillMaxSize()`, and this is the whole fix
            // for a bottom bar that ran off the screen.
            //
            // `usePlatformDefaultWidth = false` makes the dialog window
            // full-width, but its height stays wrap-content. So the content is
            // measured with an *unbounded* maximum height, and two things follow
            // that both look like the layout working: `fillMaxSize` has no
            // bounded height to fill, so it behaves as wrap; and the inspector's
            // `LazyColumn`, which takes `weight(1f)` of the remaining space,
            // instead measures to the full height of every notification it holds.
            // The window then grows taller than the display and the bottom bar —
            // the only way back out — is pushed past the bottom edge. One
            // notification hides it; six show it.
            //
            // Bounding the height gives the weight something to divide. The
            // maximum is the window's own height, and the direction of any error
            // matters: a dialog measured slightly *shorter* than the screen just
            // leaves a strip of the editor visible behind it, while one measured
            // taller loses the Back button. So this uses the configuration's
            // screen height, which never exceeds the window.
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = screenHeight),
                color = MaterialTheme.colorScheme.background,
            ) {
                // Read on open and on Refresh, never held: what is posted changes
                // while this is up, and a stale list is the one thing a diagnostic
                // must not show.
                var seen by remember { mutableStateOf(inspectorNotifications()) }
                NotificationInspectorScreen(
                    notifications = seen,
                    listenerConnected = inspectorConnected(),
                    onRefresh = { seen = inspectorNotifications() },
                    onBack = { inspecting = false },
                    describeApp = describeApp,
                )
            }
        }
    }
}

/**
 * The tools a component declares, rendered without knowing which component it is.
 *
 * This exists so that "this block has a button on it" is a fact a factory states
 * rather than a name this screen recognises. Before it, testing an action was
 * hardcoded here and pinning a shortcut was keyed off a config key that happened
 * to be unique — two special cases, and the inspector would have been a third.
 *
 * A tool that needs state only the screen has — Test doubles as Stop while an
 * action runs — takes it as a parameter. A tool nobody passes a handler for is
 * simply not drawn, which is why [onTest] is nullable: triggers declare no Test
 * and would have nothing to run.
 */
@Composable
private fun ComponentTools(
    tools: List<ComponentTool>,
    config: Map<String, String>,
    onPinShortcut: (Map<String, String>) -> Unit,
    onInspect: () -> Unit,
    testLabel: String = "Test",
    onTest: (() -> Unit)? = null,
) {
    tools.forEach { tool ->
        when (tool) {
            // Half of what an action does is sensory — which sound, how loud, how
            // the spoken text reads — and the alternative is saving, waiting for
            // the real trigger, and guessing.
            ComponentTool.Test ->
                if (onTest != null) BlockTextButton(testLabel, onClick = onTest)

            // Offered by everything that reads or writes notifications, because
            // every one of them is configured against fields nobody can see from
            // outside: the package, what the platform calls the title versus the
            // text, what a button is named under its icon.
            ComponentTool.InspectNotifications ->
                BlockTextButton("Inspect", onClick = onInspect)

            // A shortcut trigger cannot fire until a launcher button exists, so
            // without this the rule saves, looks healthy, and never fires.
            ComponentTool.PinShortcut ->
                BlockTextButton("Add to home screen") { onPinShortcut(config) }
        }
    }
}

/**
 * One end of the action footer's reordering pair — a directional arrow in place
 * of the "Up" / "Down" text button it replaces.
 *
 * [icon] carries the direction this performs, [label] what it says to a screen
 * reader; the two call sites in [RuleEditorScreen] fix `KeyboardArrowUp` to
 * `index - 1` and `KeyboardArrowDown` to `index + 1` and must never be allowed
 * to drift apart — actions run in sequence, so a reordering control that is
 * ambiguous about which way is "earlier" is worse than the text it replaces.
 */
@Composable
private fun ActionOrderButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same split `CaveatBadge` and `BlockExpandButton` use in Blocks.kt, and for
    // the same reason: reserving a real 48dp `IconButton` per arrow would grow
    // this footer row the way that function's KDoc documents being rejected
    // twice already — once for a 28-item picker row, once for a block's own fold
    // control. So the glyph stays small and reports that small size upward; the
    // real, tappable 48dp box overhangs it instead of claiming the space.
    //
    // `IconButton` is skipped for a second reason, the one `BlockExpandButton`
    // gives: its ripple is clipped to a circle, which is exactly the Material
    // affordance "Blocks, not cards" (see the architecture doc) rejects
    // elsewhere. A bare `Box` with `clickable` keeps the default ripple bounded
    // to the box's own hard edges instead of drawing one of its own.
    OverflowingTouchTarget(visualSize = 22.dp, touchSize = 48.dp, modifier = modifier) {
        Box(
            modifier = Modifier
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * The rule's folder: a name the person types, with the folders other rules
 * already use offered so a repeat is a pick rather than a second, possibly
 * mistyped, spelling of the same one.
 *
 * Built on [PickerValueBox] and [ValuePickerDialog] — the same "pick, don't
 * type" shape [AppPackageField], [SoundUriField] and [BluetoothAddressField]
 * already use for a package, a sound and a device — rather than a second
 * convention for what is, underneath, the same shape: a searchable list, an
 * escape hatch for a value the list does not contain, and a box showing what
 * is chosen now. A plain text field was the other option and would have been
 * *worse* here, not merely different: `Rule.folder`'s comparison is an exact,
 * case-sensitive string match (see its KDoc in `:core`), so "Car" and "car"
 * group under two different headings rather than one — exactly the failure a
 * free-typed field invites on a second rule, once the first name is a few
 * days old and half-remembered.
 *
 * Manual entry still works: the dialog's search field doubles as "type a new
 * name" the same way [AppPickerDialog] treats an unlisted package, and any
 * non-blank text is offered back as a typed option once it matches nothing
 * already in the list.
 */
@Composable
private fun FolderField(
    folder: String,
    existingFolders: List<String>,
    onFolderChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }

    PickerValueBox(
        label = "FOLDER",
        primary = folder.ifBlank { "No folder" },
        secondary = null,
        onClick = { picking = true },
        modifier = modifier,
    )

    if (picking) {
        ValuePickerDialog(
            title = "Folder",
            searchLabel = "PICK A FOLDER, OR TYPE A NEW NAME",
            options = existingFolders.map { PickerOption(value = it, primary = it) },
            // Always offered, unlike the optional-field pickers elsewhere: every
            // rule can leave its folder, whether or not it started in one.
            clearLabel = "No folder",
            // Reads as the one control here that makes something, because that
            // is what it does. It said `Use "Car"` before, which is the same
            // sentence the picker could have used for an existing folder: the
            // row that creates a folder and the row that selects one looked
            // alike, and the only difference was whether the name happened to
            // be in the list above. The "+" and the word "New" say which one
            // this is without the person having to work it out from what is
            // missing from the list.
            typedOption = { typed ->
                typed.takeIf { it.isNotBlank() }
                    ?.let {
                        PickerOption(
                            value = it,
                            primary = "+  New folder \"$it\"",
                            secondary = "No folder has this name yet.",
                        )
                    }
            },
            onPick = { picked ->
                picking = false
                onFolderChange(picked ?: "")
            },
            onDismiss = { picking = false },
        )
    }
}

private sealed interface Picking {
    /** The very first trigger, from the empty "When" slot. */
    data object NewTrigger : Picking
    data class ChangeTriggerType(val path: NodePath) : Picking
    data class AddTrigger(val path: NodePath) : Picking
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
 * `internal` rather than `private`: [TriggerNodeBlock] renders a leaf of the
 * trigger tree with this same block, whether that leaf sits at the root or
 * nested several gates deep — a trigger is a trigger, and the two must never
 * drift into looking like different kinds of thing.
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
                    // A chevron in place of the "Show" / "Hide" text this used to
                    // read: [BlockExpandButton] is the shared control, built once
                    // in `Blocks.kt` so a trigger node's own fold (see
                    // `TriggerNodeBlock`) cannot drift from this one.
                    BlockExpandButton(
                        expanded = expanded,
                        onToggleExpanded = onToggleExpanded,
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

/**
 * The unchosen trigger placeholder's fold key, and the root node's once
 * something is chosen — there is only ever one node at the empty path.
 */
private const val TRIGGER_KEY = "trigger"

/** A node's fold/caveat key, from its path in the trigger tree. */
internal fun triggerKey(path: NodePath): String =
    if (path.isEmpty()) TRIGGER_KEY else "trigger-" + path.joinToString("-")

private fun actionKey(index: Int) = "action-$index"

/**
 * Every group already in [node] when the editor is entered, as fold keys —
 * what seeds [RuleEditorScreen]'s `collapsed` set so a saved rule opens with
 * its gates shut. Walked once, from the tree the screen was handed on this
 * composition's first pass; see the KDoc on `collapsed` for why running this
 * again later would be wrong.
 */
private fun initiallyCollapsedTriggerGroups(
    node: TriggerDraft?,
    path: NodePath = emptyList(),
): List<String> = when (node) {
    null, is TriggerDraft.One -> emptyList()
    is TriggerDraft.Group -> listOf(triggerKey(path)) +
        node.children.flatMapIndexed { index, child ->
            initiallyCollapsedTriggerGroups(child, path + index)
        }
}

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

