package app.phueber.trigly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.phueber.trigly.core.ComponentRequirement
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.leaves

/**
 * Stateless by design: it takes the rules and reports actions back out. That is
 * what lets the instrumented test drive it without an Activity, ViewModel, or
 * repository.
 *
 * Laid out as a header slab, a scrolling column of blocks, and a bottom strip,
 * rather than with `Scaffold`: the design wants the orange band painted *behind*
 * the status bar, and `Scaffold`'s job is to keep content out of exactly that
 * area. The insets are handled instead by the two pieces that touch them:
 * [BlockHeader] and [BlockBottomBar].
 */
@Composable
fun RulesScreen(
    statuses: List<RuleStatus>,
    onEnabledChange: (Rule, Boolean) -> Unit,
    onResolve: (ComponentRequirement) -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onExportAll: () -> Unit,
    /**
     * Opens the saved values screen. Reached from this screen rather than from
     * the rule editor, because a saved value belongs to no rule: any rule can
     * read it and any rule can write it, which is the same reason "export all"
     * is here and "share" is on a rule.
     */
    onSavedValues: () -> Unit,
    /**
     * How many saved values exist, for the row that opens them.
     *
     * A count rather than nothing, because it is what earns the row its place:
     * it answers "is anything stored" without opening anything, and it is how a
     * person notices that a rule wrote a value while they were not looking.
     */
    savedValueCount: Int,
    /** Opens the settings screen. See [Screen.Settings] and [MoreMenu]. */
    onSettings: () -> Unit,
    onExportRule: (Rule) -> Unit,
    /** Saves a copy of the rule. See [RulesViewModel.duplicate]. */
    onDuplicateRule: (Rule) -> Unit = {},
    onImport: () -> Unit,
    describeComponent: (String) -> String,
    /**
     * Whether Android currently excuses Trigly from battery optimisation.
     * See [BatteryOptimizationNotice] for why this is read once for the whole
     * screen rather than folded into any one rule's requirements.
     */
    ignoringBatteryOptimizations: Boolean,
    onFixBatteryOptimization: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Both are view state, not rule data: what someone is typing into the
    // search field and which folders they last folded shut describe how this
    // screen is being looked at right now, not anything about a `Rule`.
    // `rememberSaveable` is the right amount of persistence for that. It
    // survives a rotation, which would otherwise reopen every folder, but
    // neither belongs in the ViewModel or gets written back to a rule.
    var query by rememberSaveable { mutableStateOf("") }
    var collapsedFolders by rememberSaveable(stateSaver = CollapsedFoldersSaver) {
        mutableStateOf(emptySet<String>())
    }

    // The starting value of `collapsedFolders` depends on `statuses`, which
    // arrives from a flow: the very first composition of this screen always
    // sees an empty list, before the flow has emitted the real one. Deciding
    // "start open" against that empty frame and never revisiting it would
    // quietly defeat the whole feature, since an empty list never has more
    // than three rules. So the decision waits for the first non-empty list,
    // then is locked for good by `decidedStartingFolders`. That flag is kept
    // in `rememberSaveable` for the same reason `OnFreshEntry` keeps its own
    // flag there: a rotation restores it as `true`, so the decision is not
    // repeated, and neither a fourth rule added later nor a rotation can
    // re-close a folder someone just opened by hand, or reopen one they just
    // closed. Leaving this screen and coming back is a fresh composition
    // with a fresh saved slot, so the decision runs again there. That is
    // wanted: it is a decision about what is in the database right now, not
    // a promise kept forever.
    //
    // This runs during composition, not from a `LaunchedEffect`, so the
    // first frame that has real data already reflects the decision instead
    // of drawing one open frame that then folds shut a moment later.
    var decidedStartingFolders by rememberSaveable { mutableStateOf(false) }
    if (!decidedStartingFolders && statuses.isNotEmpty()) {
        // More than three rules already in the database, and at least one
        // of them already filed into a folder: start every folder closed,
        // so opening an established list does not throw every rule at the
        // reader at once. Otherwise, start open. That is the same behaviour
        // this screen always had.
        if (statuses.size > FOLDER_COLLAPSE_THRESHOLD && statuses.any { it.rule.folder != null }) {
            collapsedFolders = folderKeysOf(statuses)
        }
        decidedStartingFolders = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        BlockHeader(
            title = stringResource(R.string.rules_title),
            // Import only. Export all moved to [MoreMenu]; see that KDoc for
            // why Import is the one action that stays here instead of moving
            // with it.
            actions = {
                BlockTextButton(stringResource(R.string.rules_import), onClick = onImport)
            },
        )

        // Placed here, above search and above the list, because it is true of
        // the whole screen rather than of any one rule on it. It shows for an
        // empty rule list too: the point is to be found before the first rule
        // is even added, not to wait for something to warn about.
        BatteryOptimizationNotice(
            ignoringBatteryOptimizations = ignoringBatteryOptimizations,
            onFix = onFixBatteryOptimization,
        )

        // Same reasoning as Export All above: search is pointless with nothing
        // to search, so it is hidden rather than offered disabled.
        if (statuses.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                // Uppercase, matching every other label in this chrome: the
                // name field's "NAME *" in the editor, the search field other
                // pickers already have (`ValuePickerDialog`'s `searchLabel`).
                label = { Text(stringResource(R.string.rules_search_label).uppercase()) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val filtered = statuses.filter { matchesQuery(it, query, describeComponent) }
        // Decided from every rule, not the filtered set: a search that happens
        // to match only unfoldered rules must not make the folder chrome
        // disappear, and (the case that matters most) someone who has never
        // typed a folder name sees exactly the flat list this screen showed
        // before folders existed, with or without a search in progress.
        val foldersInUse = statuses.any { it.rule.folder != null }
        val otherLabel = stringResource(R.string.rules_folder_other)

        when {
            statuses.isEmpty() -> RulesEmptyState(
                message = stringResource(R.string.rules_empty),
                modifier = Modifier.weight(1f),
            )

            // A search with no hits must say so. An empty list with no
            // explanation reads as "you have no rules", which is a lie the
            // moment a query is active.
            filtered.isEmpty() -> RulesEmptyState(
                message = stringResource(R.string.rules_no_matches, query),
                modifier = Modifier.weight(1f),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (foldersInUse) {
                    // A folder heading survives a search rather than
                    // disappearing with the rest of the chrome: it is how
                    // someone tells apart two rules that would otherwise both
                    // read "Driving mode" once the list is down to matches.
                    // Only non-empty sections are shown. A folder heading
                    // with nothing under it, in a filtered view, would claim
                    // a match that is not there.
                    groupByFolder(filtered, otherLabel).forEach { section ->
                        item(key = "folder:${section.key}") {
                            FolderHeader(
                                name = section.displayName,
                                count = section.statuses.size,
                                expanded = section.key !in collapsedFolders,
                                onToggleExpanded = {
                                    collapsedFolders = if (section.key in collapsedFolders) {
                                        collapsedFolders - section.key
                                    } else {
                                        collapsedFolders + section.key
                                    }
                                },
                            )
                        }
                        if (section.key !in collapsedFolders) {
                            items(items = section.statuses, key = { it.rule.id }) { status ->
                                RuleBlock(
                                    status = status,
                                    onEnabledChange = onEnabledChange,
                                    onEditRule = onEditRule,
                                    onExportRule = onExportRule,
                                    onDuplicateRule = onDuplicateRule,
                                    onResolve = onResolve,
                                    describeComponent = describeComponent,
                                )
                            }
                        }
                    }
                } else {
                    // Exactly what this screen rendered before folders existed:
                    // no headings, no chrome, whatever the search state.
                    items(items = filtered, key = { it.rule.id }) { status ->
                        RuleBlock(
                            status = status,
                            onEnabledChange = onEnabledChange,
                            onEditRule = onEditRule,
                            onExportRule = onExportRule,
                            onDuplicateRule = onDuplicateRule,
                            onResolve = onResolve,
                            describeComponent = describeComponent,
                        )
                    }
                }
            }
        }

        BlockBottomBar {
            BlockButton(
                text = stringResource(R.string.rules_new),
                onClick = onNewRule,
                modifier = Modifier.weight(1f),
            )
            MoreMenu(
                showExportAll = statuses.isNotEmpty(),
                onExportAll = onExportAll,
                savedValueCount = savedValueCount,
                onSavedValues = onSavedValues,
                onSettings = onSettings,
            )
        }
    }
}

/** The centred card used both for "no rules at all" and "no rules matched". */
@Composable
private fun RulesEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        BlockCard(fill = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * A rule's name is not the only way someone recognises it: "Driving mode" says
 * nothing about Bluetooth to whoever typed that word, but the rule may well be
 * built on a Bluetooth trigger. Matching only the name would silently hide it,
 * so this also checks every leaf of the trigger tree (the whole tree, not
 * just its first node, now that a trigger can nest) and every action, by the
 * same display name their own summary line already shows on screen. That is
 * why `describeComponent` is threaded all the way down here instead of
 * comparing against the raw type string nothing but this codebase ever sees.
 */
private fun matchesQuery(
    status: RuleStatus,
    query: String,
    describeComponent: (String) -> String,
): Boolean {
    if (query.isBlank()) return true
    val rule = status.rule
    if (rule.name.contains(query, ignoreCase = true)) return true

    val componentTypes = rule.trigger.leaves().map { it.type } + rule.actions.map { it.type }
    return componentTypes.any { type -> describeComponent(type).contains(query, ignoreCase = true) }
}

/** One folder's worth of rules to show under one heading, "Other" included. */
private data class FolderSection(
    val key: String,
    val displayName: String,
    val statuses: List<RuleStatus>,
)

/**
 * The reserved key for "no folder". Never a real folder name: `:core`'s
 * `normalizeFolder` guarantees a stored folder name is never blank, so an
 * empty string can only ever mean this bucket.
 */
private const val OTHER_KEY = ""

/**
 * Named folders alphabetically; "Other" always last, whatever alphabetical
 * order would otherwise do with the letter O. It is the leftovers, not a peer
 * folder, and a folder named e.g. "Zebra" must not push it earlier.
 */
private fun groupByFolder(statuses: List<RuleStatus>, otherLabel: String): List<FolderSection> {
    val byFolder = statuses.groupBy { it.rule.folder }
    val named = byFolder.keys.filterNotNull()
        .sortedBy { it.lowercase() }
        .map { name -> FolderSection(key = name, displayName = name, statuses = byFolder.getValue(name)) }
    val other = byFolder[null]
        ?.takeIf { it.isNotEmpty() }
        ?.let { listOf(FolderSection(key = OTHER_KEY, displayName = otherLabel, statuses = it)) }
        .orEmpty()
    return named + other
}

/**
 * More rules than this, plus at least one folder in use, is what starts every
 * folder closed. See the `decidedStartingFolders` block above for the reason
 * this cannot simply be decided once at first composition.
 */
private const val FOLDER_COLLAPSE_THRESHOLD = 3

/**
 * The same section keys [groupByFolder] would produce, without needing the
 * "Other" display name: the initial-collapse decision only ever needs to know
 * *which* sections will exist, never their labels.
 */
private fun folderKeysOf(statuses: List<RuleStatus>): Set<String> {
    val named = statuses.mapNotNull { it.rule.folder }.toSet()
    val hasOther = statuses.any { it.rule.folder == null }
    return if (hasOther) named + OTHER_KEY else named
}

/**
 * `rememberSaveable`'s default handling is not guaranteed for an arbitrary
 * `Set`; a `List` is. This just round-trips the collapsed-folder set through
 * one rather than trusting the platform default to cope with the type.
 */
private val CollapsedFoldersSaver: Saver<Set<String>, Any> = listSaver(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * A folder's own heading, never a rule, and must not read as one.
 * [SectionLabel] (`RuleEditorScreen.kt`) is the closest thing already in the
 * vocabulary: a solid tag in the chrome colour, no border, no shadow, unlike
 * every [BlockCard]. It is not reused as-is because it is a static,
 * wrap-content label with no count and no click; this is what it would need
 * to grow both. Worth promoting into `Blocks.kt` as a shared "counted,
 * collapsible heading" if another screen ever wants the same shape. This one
 * is kept local because only the rule list needs it today.
 *
 * Full width and clickable across its whole row, the same as [BlockCard]'s
 * clickable branch, rather than a small chevron button of its own: the fold
 * target is then a whole thumb-height row instead of a few dp of glyph.
 */
@Composable
private fun FolderHeader(
    name: String,
    count: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggleExpanded,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = BlockShape,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                // This row is one merged clickable node with the heading text
                // right beside it; a description here would have the same
                // thing announced twice.
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "${name.uppercase()} ($count)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The per-rule "send this rule elsewhere" control: the platform's own share
 * glyph rather than the word "Share", so a row of several rule actions reads
 * as share at a glance instead of as one more word to parse.
 *
 * Built the same way [ActionOrderButton] (`RuleEditorScreen.kt`) is, for the
 * same two reasons.
 *
 * `IconButton` is skipped, the reason [BlockExpandButton]'s KDoc gives: its
 * ripple is clipped to a circle, which is the Material affordance "Blocks,
 * not cards" (see the architecture doc) rejects everywhere else in this
 * chrome. A bare [Box] with `clickable` keeps the ripple bounded to this
 * control's own hard edges instead.
 *
 * The 48dp is **reserved**, not overhung the way [CaveatBadge] deliberately
 * overhangs its own. This control sits in a row beside "Duplicate", which is
 * itself a real button rather than a few dp of glyph. That matters because
 * the touch-target bug [BlockExpandButton]'s KDoc records was two overhanging
 * targets stealing each other's taps, which only happens when a control
 * reports a footprint smaller than where it actually catches touches.
 * Reserving the full 48dp here means this control's layout size and its
 * touch size are the same number, so it cannot steal a tap aimed at its
 * neighbour, or lose one to it.
 *
 * The content description reuses [R.string.rules_share] rather than adding a
 * second string: that resource said "Share" when it was the button's label,
 * and it says the same thing now that the label is a glyph instead. Several
 * instrumented tests already select this control by it.
 */
@Composable
private fun RuleShareButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.rules_share)
    Box(
        modifier = modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onClick, role = Role.Button)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = null,
                tint = MaterialTheme.extra.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * One rule as a block, split into cells by hard lines: what it does on top, the
 * per-rule actions below, and anything stopping it from firing under that.
 */
@Composable
private fun RuleBlock(
    status: RuleStatus,
    onEnabledChange: (Rule, Boolean) -> Unit,
    onEditRule: (String) -> Unit,
    onExportRule: (Rule) -> Unit,
    onDuplicateRule: (Rule) -> Unit,
    onResolve: (ComponentRequirement) -> Unit,
    describeComponent: (String) -> String,
) {
    // View state, not rule data: whether the trace dialog is open right now.
    // Local to this block rather than lifted, for the same reason `query` and
    // `collapsedFolders` above are not: it describes how this one rule is
    // being looked at, not anything RulesViewModel needs to know.
    var showingTrace by remember { mutableStateOf(false) }

    BlockCard(onClick = { onEditRule(status.rule.id) }) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    // Uppercase, because a rule name is a label in this design
                    // rather than a sentence.
                    Text(
                        text = status.rule.name.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = summarise(status.rule, describeComponent).uppercase(),
                        // Monospaced, so a screen of rules lines up into a
                        // column that can be scanned rather than read.
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                BlockToggle(
                    checked = status.rule.enabled,
                    onCheckedChange = { enabled -> onEnabledChange(status.rule, enabled) },
                )
            }

            BlockDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                RuleShareButton(onClick = { onExportRule(status.rule) })
                BlockTextButton(
                    text = stringResource(R.string.rules_duplicate),
                    contentColor = MaterialTheme.extra.accent,
                ) {
                    onDuplicateRule(status.rule)
                }
                // Only once a trace exists, guarded the same way LastFaultCell
                // is: RulesViewModel already nulls this out for a disabled
                // rule, but the rule's own flag is checked again here too, for
                // the same reason LastFaultCell's own KDoc gives for its two
                // guards.
                val trace = status.lastTrace
                if (trace != null && status.rule.enabled) {
                    BlockTextButton(
                        text = stringResource(R.string.rules_last_check),
                        contentColor = MaterialTheme.extra.accent,
                    ) {
                        showingTrace = true
                    }
                }
            }

            RequirementCell(status = status, onResolve = onResolve)
            LastFaultCell(status = status, describeComponent = describeComponent)
            UnfinishedRuleCell(status = status)
        }
    }

    val trace = status.lastTrace
    if (showingTrace && trace != null) {
        // Full-bleed, the same shape `RuleEditorScreen`'s notification
        // inspector uses and for the same reason: a tree several levels deep
        // needs the width. See that screen's own comment for the rest of the
        // reasoning; it applies here unchanged.
        //
        // **A dialog does not get the window insets, so this hands them in.**
        //
        // `BlockBottomBar` pads itself with `navigationBarsPadding` and
        // `BlockHeader` with `statusBarsPadding`, which is right on every
        // screen that is not in a dialog. Inside a dialog window both read
        // zero. The bar was therefore drawn straight over the gesture bar,
        // and the Back label lost its lower half. That is the fault this
        // fixes, and it was reported against the "Last check" screen.
        //
        // Two things were measured on an API 35 emulator rather than assumed,
        // because both are easy to get backwards:
        //
        // - `decorFitsSystemWindows = false` does **not** make the insets
        //   arrive. It moves the window instead. The dialog then spans y 128
        //   to 2400 on a 2400 pixel display: the system holds it clear of the
        //   status bar, and still lets it run under the navigation bar. So it
        //   fixes the top by accident and leaves the bottom exactly as broken.
        //   Sizing the content to `screenHeightDp` on top of that makes it
        //   worse again, because the display is taller than that window, and
        //   the bar is then pushed off the bottom edge entirely.
        // - Read outside the `Dialog` lambda, `WindowInsets.navigationBars`
        //   holds the real value, because this composable runs in the
        //   activity's own window where insets work normally.
        //
        // So the inset is read out here and applied as padding in there.
        // `BlockBottomBar`'s own zero-valued padding inside the dialog adds
        // nothing on top, so this does not double up.
        val systemBars = WindowInsets.systemBars.asPaddingValues()
        val dialogHeight = LocalConfiguration.current.screenHeightDp.dp -
            systemBars.calculateTopPadding() -
            systemBars.calculateBottomPadding()
        Dialog(
            onDismissRequest = { showingTrace = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(dialogHeight),
                color = MaterialTheme.colorScheme.background,
            ) {
                TriggerTraceScreen(
                    trace = trace,
                    onBack = { showingTrace = false },
                    describeComponent = describeComponent,
                )
            }
        }
    }
}

/**
 * Why the rule last did nothing.
 *
 * The gap this closes: a rule whose trigger fired and whose action then failed
 * looked exactly like a rule whose trigger never fired. Both did nothing and
 * both said nothing. The engine wrote a line to logcat, which needs a cable to
 * read, so the one question people actually ask, "why did my rule do nothing",
 * had no answer on the device.
 *
 * **The colour follows the tense, not the cell.** [RequirementCell] is the error
 * colour because it states a fault standing in the way right now: the rule
 * cannot run, and here is the permission to grant. A report about a run that
 * already happened is amber instead, the convention this design uses for "worth
 * knowing, not a fault in front of you". Two of the three kinds here are that
 * kind of report and the condition may well be gone by now.
 *
 * [RuleFault.Kind.COULD_NOT_START] is not. It says the rule was never built and
 * nothing is watching for it, which is as present a fault as a missing
 * permission and stays true until the rule is edited. So it takes the error
 * colour, in this cell rather than in [RequirementCell], because there is no
 * button to offer: what it needs is the rule fixed, and only its own message can
 * say how.
 *
 * Only for an enabled rule, and [RulesViewModel] enforces that as well by not
 * filling the field for a disabled one. Two guards for one rule, because the
 * cost of getting it wrong is accusing a rule nobody asked to run.
 *
 * [RuleFault.Kind.UNDECIDED] reaches here only once the engine has already
 * retried the component that could not answer and given up; a component that
 * answers late is a rule working, not a report.
 */
@Composable
private fun LastFaultCell(
    status: RuleStatus,
    describeComponent: (String) -> String,
) {
    val fault = status.lastFault ?: return
    if (!status.rule.enabled) return

    val present = fault.kind == RuleFault.Kind.COULD_NOT_START

    BlockDivider()
    Surface(
        color = if (present) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.extra.cautionContainer
        },
        contentColor = if (present) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.extra.onCautionContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            // One heading per kind, because they are three different claims and
            // sharing a sentence between them would blame the wrong thing. An
            // action failed. Or the rule fired and was dropped, so no action was
            // reached and there is none to name. Or the rule was never built,
            // which is not a run at all.
            //
            // The action is named in the first case because a rule with three of
            // them otherwise leaves the reader guessing which one this is about.
            Text(
                text = when (fault.kind) {
                    RuleFault.Kind.ACTION_FAILED -> stringResource(
                        R.string.rules_last_run_failed,
                        describeComponent(fault.actionType.orEmpty()),
                    )

                    RuleFault.Kind.UNDECIDED -> stringResource(R.string.rules_last_run_undecided)

                    RuleFault.Kind.COULD_NOT_START -> stringResource(R.string.rules_never_started)
                }.uppercase(),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = fault.reason,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Why a disabled rule cannot be switched on yet, shown without anyone having
 * to tap the switch to find out.
 *
 * A rule saved before it is finished (no trigger, no actions, or both)
 * defaults to off exactly so it does not become a [RuleFault.Kind.COULD_NOT_START]
 * the moment it is saved. That makes it quiet by design, and quiet is the
 * problem this cell exists to fix: [LastFaultCell] never runs for a disabled
 * rule, so without this, the only way to learn a rule is unfinished would be
 * to open it or tap the switch and read the toast. This is exactly the kind
 * of thing a person would want to see at a glance instead, the same reasoning
 * [LastFaultCell] itself exists for.
 *
 * [RulesViewModel] only fills [RuleStatus.enableRefusal] for a disabled rule,
 * and this checks the rule's own flag again for the same reason
 * [LastFaultCell]'s kdoc gives for its own two guards: the cost of getting it
 * wrong is accusing a rule nobody has tried to run yet.
 *
 * The caution colour, not the error one. [RequirementCell] and the
 * [RuleFault.Kind.COULD_NOT_START] row above both take the error colour
 * because they describe a rule that is switched on and failing right now.
 * Nothing here is switched on: this is the ordinary, expected shape of a rule
 * a person has not finished, which is "worth knowing, not a fault in front of
 * you", [LastFaultCell]'s own words for its amber rows.
 */
@Composable
private fun UnfinishedRuleCell(status: RuleStatus) {
    val message = status.enableRefusal ?: return
    if (status.rule.enabled) return

    BlockDivider()
    Surface(
        color = MaterialTheme.extra.cautionContainer,
        contentColor = MaterialTheme.extra.onCautionContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * Whether Android is free to stop Trigly for sitting idle. States a fact
 * about this device and this install of the app, never about one rule, which
 * is why it is drawn once above the whole list rather than as a row inside
 * [RequirementCell] or [RuleBlock].
 *
 * **Why not a requirement on a component.** [ComponentRequirement] describes
 * what one trigger or action needs before it can fire, and an unmet one is
 * scoped to the rule that has it. Battery optimisation is not scoped that
 * way: it stops the process every enabled rule runs in, so folding it into
 * [RequirementCell] would repeat the identical sentence on every enabled
 * rule's card, and it would still describe none of those rules correctly,
 * since the fault was never in any of them. One notice, read once, is the
 * honest shape of a fact that is true of the screen and not of a card on it.
 *
 * **Why here on the screen.** Above the search field and the list, beside
 * [BlockHeader]: the other things this screen states about itself rather
 * than about a rule live in that same band. It is shown even with an empty
 * rule list, on purpose. The point is to be seen before the first rule ever
 * needs it to have been read, not to wait for a rule that would otherwise go
 * quiet without an explanation.
 *
 * **Why it disappears once granted.** The same convention [RequirementCell]
 * already uses: a row about a thing that is already true trains people to
 * stop reading rows, and this one earns that trust or loses it exactly like
 * every other requirement in this screen.
 *
 * **Why the caution colour, not the error colour.** [RequirementCell] takes
 * the error colour because it names a rule that cannot fire *right now*.
 * This notice is not that: Trigly can be running perfectly well the moment
 * this is read, and the fault is a raised risk of being stopped later, once
 * the phone goes idle, not a block standing in front of anything at this
 * instant. That is exactly the "worth knowing, not a fault in front of you"
 * case [LastFaultCell]'s KDoc describes for its own amber rows.
 *
 * **What it does not promise.** Granting this never survives a force-stop:
 * a force-stop from Settings empties Trigly's process whatever this setting
 * says, for reasons this screen has no control over (see `docs/todo.md`
 * **R1**). The body text says so, because a notice that implies "fixed for
 * good" once tapped would be a worse failure than the one it replaces: a
 * false sense that the rules are now safe.
 */
@Composable
private fun BatteryOptimizationNotice(
    ignoringBatteryOptimizations: Boolean,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ignoringBatteryOptimizations) return

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(
            color = MaterialTheme.extra.cautionContainer,
            contentColor = MaterialTheme.extra.onCautionContainer,
            shape = BlockShape,
            modifier = Modifier.fillMaxWidth().hardShadow(BlockShape),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.battery_optimization_title).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = stringResource(R.string.battery_optimization_body),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    BlockTextButton(stringResource(R.string.battery_optimization_action)) { onFix() }
                }
            }
        }
    }
}

/**
 * Everything the rules screen offers that is not "make a rule". Three entries
 * today: "Export all", "Saved values" and "Settings".
 *
 * **The three homes "Saved values" has had, because each move was caused by
 * the last.** It began as a third action in `BlockHeader`, which broke the
 * header: `BlockHeader` gives the title the remaining width and lays the
 * actions after it, so a third one neither wraps nor collapses, it runs off
 * the edge. It became a full-width row beside the battery notice, which
 * fitted and said how many values were stored, at the cost of vertical space
 * above the rule list on every visit, forever, for something a person opens
 * rarely.
 *
 * An overflow menu was rejected at that point, and the reason it was rejected
 * no longer applies. The objection was that a menu in the *header* would have
 * had to swallow Import and Export all to make room, which is a worse trade
 * than a little vertical space. Here the menu sits beside the primary action in
 * the bottom bar, so it displaces nothing: the header keeps both its actions,
 * and the list keeps the row's worth of height.
 *
 * The count moves into the entry itself rather than being lost. A menu entry
 * can carry it as well as a row could, and it is the thing worth saying: that a
 * rule has written a value is how a person finds out this screen exists at all.
 *
 * **"Settings" landed here rather than earning a third home of its own.**
 * This menu is already the answer to "where does something belong that is
 * about the whole app rather than one rule", which a backup switch is. A menu
 * that already holds one such entry costs nothing to hold a second, and the
 * settings screen behind it has room for a third setting later without this
 * menu needing to grow past two rows first.
 *
 * **"Export all" joined later, and "Import" did not follow it.** The header
 * used to hold both, as a matched pair. But they were never a matched pair in
 * how often either is needed: Import is guarded by nothing, because it is how
 * a phone with zero rules gets its first one, while Export all is guarded by
 * `statuses.isNotEmpty()` above, because it is pointless until a rule exists
 * to export. That is the same asymmetry that already let "Saved values" stay
 * out of the header while it lived in a full-width row: a control that is
 * only ever useful once the screen has content earns no header space at all,
 * let alone a permanent one. Export all belongs beside Saved values, both
 * reached the same way and both idle until there is something for them to
 * act on; Import belongs beside "New rule" in the bottom bar's weight, both
 * of them a rule's way into an empty list. Moving Import into this menu
 * would have hidden the one thing a fresh install needs behind a tap it has
 * no reason yet to make. So the header keeps exactly the one action that
 * earns a permanent seat, and this menu gains the one that does not: shown
 * first, above Saved values, because it was the header's second action and
 * this keeps the same reading order the header used.
 */
@Composable
private fun MoreMenu(
    showExportAll: Boolean,
    onExportAll: () -> Unit,
    savedValueCount: Int,
    onSavedValues: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        BlockButton(
            text = stringResource(R.string.rules_more),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Same guard the header used: pointless with nothing to export.
            if (showExportAll) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.rules_export_all),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        expanded = false
                        onExportAll()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.rules_saved_values),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (savedValueCount == 0) {
                                stringResource(R.string.saved_values_menu_empty)
                            } else {
                                pluralStringResource(
                                    R.plurals.saved_values_entry_count,
                                    savedValueCount,
                                    savedValueCount,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onSavedValues()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.rules_settings),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    expanded = false
                    onSettings()
                },
            )
        }
    }
}

/**
 * The point of the whole requirement model: an enabled rule that cannot fire
 * says so, instead of looking identical to one that is simply waiting.
 *
 * Its own cell in the error colour, rather than a line of small red text: this is
 * a fault in the rule, unlike a component's caveat, which is amber and merely
 * informative.
 *
 * Only shown for enabled rules. A disabled rule not firing is not a mystery
 * that needs explaining.
 */
@Composable
private fun RequirementCell(
    status: RuleStatus,
    onResolve: (ComponentRequirement) -> Unit,
) {
    if (status.canFire || !status.rule.enabled) return

    BlockDivider()
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            status.unmet.forEach { requirement ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = requirement.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    if (requirement.isResolvable) {
                        BlockTextButton(stringResource(R.string.requirement_grant)) {
                            onResolve(requirement)
                        }
                    }
                }
            }
            // Granted, but not bound right now: a different fault from the
            // ones above, so it gets its own rows rather than folding into
            // `unmet` with the same "Grant" button that a settings screen
            // would show as already on.
            status.notLive.forEach { requirement ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = requirement.describeNotLive(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    BlockTextButton(stringResource(R.string.requirement_check_settings)) {
                        onResolve(requirement)
                    }
                }
            }
        }
    }
}

/**
 * The one line that says what a rule does, in display names rather than type
 * strings.
 *
 * **It must describe the whole tree.** It used to read `rule.gate.triggers` (the
 * first-level edges, joined with "or") and append a bare count of conditions.
 * That was accurate only while "condition" was a separate, flatter thing beside
 * the triggers; once a gate became one [TriggerNode] that can nest to any depth,
 * the old join could state something the rule does not do: a two-deep "all of"
 * read the same as a two-deep "any of", and a rule three levels deep read no
 * differently from one two levels deep, just fewer or more words in an unordered
 * list. This line is where someone checks what a rule does without opening the
 * editor, so a summary that misdescribes is worse than one that is merely terse.
 *
 * A [TriggerNode.Group] is parenthesised and its children joined by "and" or
 * "or" depending on [TriggerNode.Op]. This is the same mark of grouping the tree
 * itself uses, so "a and (b or c)" reads exactly as nested as it is.
 *
 * **Never fewer triggers than the rule has.** A long tree can run this line past
 * the point of being scannable, but cutting it short must not make the rule read
 * as simpler than it is. That is the bug this replaced. So a cut string is never
 * handed back on its own: it is always suffixed with the true leaf count, however
 * the text before it was truncated, which is what keeps a truncated summary
 * honest about having been truncated rather than looking complete.
 */
private fun summarise(rule: Rule, describeComponent: (String) -> String): String {
    val trigger = describeTrigger(rule.trigger, describeComponent)

    val actions = if (rule.actions.isEmpty()) {
        "nothing"
    } else {
        rule.actions.joinToString { describeComponent(it.type) }
    }
    return "$trigger → $actions"
}

/** Past this many characters the tree is truncated rather than spelled out in full. */
private const val MAX_TRIGGER_SUMMARY_LENGTH = 60

private fun describeTrigger(node: TriggerNode, describeComponent: (String) -> String): String {
    val full = renderTrigger(node, describeComponent)
    if (full.length <= MAX_TRIGGER_SUMMARY_LENGTH) return full

    // A cut mid-tree can drop a whole child, or a whole side of an "or". That
    // is exactly the shape of the bug this line exists to prevent. Naming the
    // true count after the cut is what makes the truncation honest regardless
    // of where the text happened to break.
    val total = node.leaves().size
    val cut = full.take(MAX_TRIGGER_SUMMARY_LENGTH).trimEnd()
    val noun = if (total == 1) "trigger" else "triggers"
    return "$cut… ($total $noun)"
}

private fun renderTrigger(node: TriggerNode, describeComponent: (String) -> String): String = when (node) {
    is TriggerNode.One -> describeComponent(node.spec.type)

    is TriggerNode.Group -> {
        val joiner = when (node.op) {
            TriggerNode.Op.ALL -> " and "
            TriggerNode.Op.ANY -> " or "
        }
        node.children.joinToString(separator = joiner, prefix = "(", postfix = ")") {
            renderTrigger(it, describeComponent)
        }
    }
}
