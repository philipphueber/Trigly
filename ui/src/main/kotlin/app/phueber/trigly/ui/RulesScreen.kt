package app.phueber.trigly.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
 * area. The insets are handled instead by the two pieces that touch them —
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
    onExportRule: (Rule) -> Unit,
    /** Saves a copy of the rule. See [RulesViewModel.duplicate]. */
    onDuplicateRule: (Rule) -> Unit = {},
    onImport: () -> Unit,
    describeComponent: (String) -> String,
    modifier: Modifier = Modifier,
) {
    // Both are view state, not rule data: what someone is typing into the
    // search field and which folders they last folded shut describe how this
    // screen is being looked at right now, not anything about a `Rule`.
    // `rememberSaveable` is the right amount of persistence for that — it
    // survives a rotation, which would otherwise reopen every folder, but
    // neither belongs in the ViewModel or gets written back to a rule.
    var query by rememberSaveable { mutableStateOf("") }
    var collapsedFolders by rememberSaveable(stateSaver = CollapsedFoldersSaver) {
        mutableStateOf(emptySet<String>())
    }

    Column(modifier = modifier.fillMaxSize()) {
        BlockHeader(
            title = stringResource(R.string.rules_title),
            actions = {
                BlockTextButton(stringResource(R.string.rules_import), onClick = onImport)
                // Export is pointless with nothing to export.
                if (statuses.isNotEmpty()) {
                    BlockTextButton(
                        text = stringResource(R.string.rules_export_all),
                        onClick = onExportAll,
                    )
                }
            },
        )

        // Same reasoning as Export All above: search is pointless with nothing
        // to search, so it is hidden rather than offered disabled.
        if (statuses.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                // Uppercase, matching every other label in this chrome — the
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
        // disappear, and — the case that matters most — someone who has never
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
                    // Only non-empty sections are shown — a folder heading
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
 * so this also checks every leaf of the trigger tree — the whole tree, not
 * just its first node, now that a trigger can nest — and every action, by the
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

/** One folder's worth of rules to show under one heading — "Other" included. */
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
 * `rememberSaveable`'s default handling is not guaranteed for an arbitrary
 * `Set`; a `List` is. This just round-trips the collapsed-folder set through
 * one rather than trusting the platform default to cope with the type.
 */
private val CollapsedFoldersSaver: Saver<Set<String>, Any> = listSaver(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * A folder's own heading — never a rule, and must not read as one.
 * [SectionLabel] (`RuleEditorScreen.kt`) is the closest thing already in the
 * vocabulary: a solid tag in the chrome colour, no border, no shadow, unlike
 * every [BlockCard]. It is not reused as-is because it is a static,
 * wrap-content label with no count and no click; this is what it would need
 * to grow both. Worth promoting into `Blocks.kt` as a shared "counted,
 * collapsible heading" if another screen ever wants the same shape — this one
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
                BlockTextButton(
                    text = stringResource(R.string.rules_share),
                    contentColor = MaterialTheme.extra.accent,
                ) {
                    onExportRule(status.rule)
                }
                BlockTextButton(
                    text = stringResource(R.string.rules_duplicate),
                    contentColor = MaterialTheme.extra.accent,
                ) {
                    onDuplicateRule(status.rule)
                }
            }

            RequirementCell(status = status, onResolve = onResolve)
            LastFaultCell(status = status, describeComponent = describeComponent)
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
 * The point of the whole requirement model: an enabled rule that cannot fire
 * says so, instead of looking identical to one that is simply waiting.
 *
 * Its own cell in the error colour, rather than a line of small red text: this is
 * a fault in the rule, unlike a component's caveat, which is amber and merely
 * informative.
 *
 * Only shown for enabled rules — a disabled rule not firing is not a mystery
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
        }
    }
}

/**
 * The one line that says what a rule does, in display names rather than type
 * strings.
 *
 * **It must describe the whole tree.** It used to read `rule.gate.triggers` — the
 * first-level edges, joined with "or" — and append a bare count of conditions.
 * That was accurate only while "condition" was a separate, flatter thing beside
 * the triggers; once a gate became one [TriggerNode] that can nest to any depth,
 * the old join could state something the rule does not do — a two-deep "all of"
 * read the same as a two-deep "any of", and a rule three levels deep read no
 * differently from one two levels deep, just fewer or more words in an unordered
 * list. This line is where someone checks what a rule does without opening the
 * editor, so a summary that misdescribes is worse than one that is merely terse.
 *
 * A [TriggerNode.Group] is parenthesised and its children joined by "and" or
 * "or" depending on [TriggerNode.Op] — the same mark of grouping the tree itself
 * uses, so "a and (b or c)" reads exactly as nested as it is.
 *
 * **Never fewer triggers than the rule has.** A long tree can run this line past
 * the point of being scannable, but cutting it short must not make the rule read
 * as simpler than it is — that is the bug this replaced. So a cut string is never
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

    // A cut mid-tree can drop a whole child, or a whole side of an "or" — which
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
