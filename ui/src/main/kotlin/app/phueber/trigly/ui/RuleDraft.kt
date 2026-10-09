package app.phueber.trigly.ui

import app.phueber.trigly.core.ActionNode
import app.phueber.trigly.core.ActionPath
import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NO_TRIGGER
import app.phueber.trigly.core.NodePath
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.canStart
import app.phueber.trigly.core.companionKeys
import app.phueber.trigly.core.defaultValue
import app.phueber.trigly.core.isUnset
import app.phueber.trigly.core.leafActions
import app.phueber.trigly.core.leaves
import app.phueber.trigly.core.normalizeFolder
import app.phueber.trigly.core.unfilled

/**
 * Which half of a rule a component belongs to.
 *
 * No third value for a passive check: since [TriggerNode.Group] made a group
 * of triggers itself a trigger, a component asked for its state resolves
 * through the exact same registry lookup as one watched for an edge (see
 * `docs/conditions.md`'s "grouped under one component, transparently"), so
 * [RuleEditorViewModel.descriptorFor] and every node in the trigger tree,
 * whatever depth it sits at, simply pass [TRIGGER]. A third value would also
 * make every existing `when (slot)` non-exhaustive.
 */
enum class Slot { TRIGGER, ACTION }

/**
 * A group is a **trigger you pick**, not a button beside the trigger list.
 *
 * This is the whole point of the model, and it was got wrong twice before
 * arriving here: first as a separate "Only if" section with its own vocabulary,
 * then as an `Add gate` button in a block's footer. Both made a group a different
 * *kind* of thing from a trigger. It is not. You open the same picker you use for
 * `Bluetooth device`, and `All of these` is one of the options in it.
 *
 * These two type strings therefore have to travel through the same
 * `changeTriggerType` / `addTrigger` calls a real component's type does. The
 * screen must not know that some picker rows are special. The ViewModel is the
 * one place that reads them, through [groupOpFor], and turns them into a
 * [TriggerDraft.Group] instead of a [TriggerDraft.One].
 *
 * The dot is deliberate: no factory's type string contains one, so these cannot
 * collide with a component now or later.
 */
const val GROUP_ALL_TYPE = "group.all"
const val GROUP_ANY_TYPE = "group.any"

/** The operator [type] asks for, or null if it names an ordinary component. */
fun groupOpFor(type: String): TriggerNode.Op? = when (type) {
    GROUP_ALL_TYPE -> TriggerNode.Op.ALL
    GROUP_ANY_TYPE -> TriggerNode.Op.ANY
    else -> null
}

/**
 * The two group rows the trigger picker offers, alongside the real components.
 *
 * Descriptors built by hand rather than by a factory, because a group has no
 * factory to build it: its children are structure, and structure does not fit in
 * a `Map<String, String>` of config. `configFields` is empty for the same reason:
 * a group's only setting is its operator, and that is a control on the block
 * itself, not a field in a form.
 *
 * They carry no `warning` and no requirements. A group needs no permission; what
 * it holds might, and those blocks state their own.
 */
/**
 * The picker heading the group rows sit under, and the one the picker pins to the
 * top of its list. Declared here, beside the rows themselves, so the two cannot
 * drift into disagreeing about a bare string.
 */
const val GROUP_CATEGORY = "Groups"

/**
 * The picker row that adds a parallel block to the action list. The action-side
 * twin of [GROUP_OPTIONS], for the same reason: a block is something a person
 * picks from the same list as an action, and the ViewModel is the one place
 * that reads the type, through [isParallelType].
 *
 * Offered only by the top-level "Add action". A branch holds only actions, see
 * `ActionNode` in `:core`, so the picker inside a branch does not offer it.
 */
const val PARALLEL_TYPE = "group.parallel"

/** Whether [type] is the picker row for a parallel block. */
fun isParallelType(type: String): Boolean = type == PARALLEL_TYPE

val PARALLEL_OPTION: ComponentDescriptor = ComponentDescriptor(
    type = PARALLEL_TYPE,
    displayName = "At the same time",
    category = GROUP_CATEGORY,
    requirements = emptyList(),
    configFields = emptyList(),
    warning = null,
)

val GROUP_OPTIONS: List<ComponentDescriptor> = listOf(
    ComponentDescriptor(
        type = GROUP_ALL_TYPE,
        displayName = "All of these",
        category = GROUP_CATEGORY,
        requirements = emptyList(),
        configFields = emptyList(),
        warning = null,
    ),
    ComponentDescriptor(
        type = GROUP_ANY_TYPE,
        displayName = "Any of these",
        category = GROUP_CATEGORY,
        requirements = emptyList(),
        configFields = emptyList(),
        warning = null,
    ),
)

/**
 * A rule as it exists mid-edit.
 *
 * Separate from [Rule] because a half-built rule is not a valid one: the
 * trigger may be unchosen and config may be incomplete or unparseable. Keeping
 * the in-progress shape distinct means [Rule] never has to represent nonsense,
 * and the editor never has to construct a `Rule` it knows is invalid just to
 * hold the user's typing.
 */
data class ComponentDraft(
    val type: String,
    val config: Map<String, String> = emptyMap(),
) : ActionDraft

/**
 * An editable mirror of `ActionNode` in `:core`: one entry of the action list.
 *
 * [ComponentDraft] is the leaf, the same way `ComponentSpec` is the leaf on the
 * model side, and [Parallel] is the one inner node. A branch is a list of
 * [ComponentDraft]s, so a block in a block cannot be built here either.
 */
sealed interface ActionDraft {
    /**
     * Branches that run at the same time. The editor builds one with two
     * empty branches, and [removeActionAt] collapses one that drops to a
     * single branch into that branch's actions, the same way a trigger group
     * of one collapses into its child.
     */
    data class Parallel(val branches: List<List<ComponentDraft>>) : ActionDraft
}

/**
 * An editable mirror of [TriggerNode]. See `core/.../Gate.kt` for the model
 * this shadows and the reasoning for it being one tree rather than a trigger
 * list beside a separate condition tree.
 *
 * [One] holds a plain [ComponentDraft] rather than a [ComponentSpec], the same
 * reason [RuleDraft] holds [ComponentDraft]s rather than [ComponentSpec]s:
 * config under construction is not yet known to be config a factory accepts.
 *
 * Neither case carries a minimum on [Group.children] the way the editor's own
 * operations do. An imported or previously-saved rule can hold a vacuous or
 * singleton group, and the editor has to be able to load one to look at it or
 * remove it rather than refuse to render it. What the editor itself never
 * *constructs* is a group of fewer than two: adding a sibling to a lone
 * trigger promotes it into a group, and removing a child back down to one
 * un-promotes the group into that child (see [transformTrigger]), which is
 * what keeps a rule with one trigger free of AND/OR chrome.
 */
sealed interface TriggerDraft {
    data class One(val component: ComponentDraft) : TriggerDraft

    data class Group(val op: TriggerNode.Op, val children: List<TriggerDraft>) : TriggerDraft
}

data class RuleDraft(
    /** Null for a rule that has not been saved yet. */
    val id: String?,
    val name: String = "",
    /** Null when nothing has been chosen yet: the empty "choose a trigger" slot. */
    val trigger: TriggerDraft? = null,
    val actions: List<ActionDraft> = emptyList(),
    val enabled: Boolean = true,
    /**
     * "" means "no folder" here, unlike [Rule.folder]'s `null`. A text field has
     * no way to hold a value that isn't a string, so the draft stores exactly
     * what it can put in one. [toRuleOrNull] is where that collapses to `null`,
     * through the same [normalizeFolder] every other boundary around [Rule.folder]
     * uses, so the editor is not a second place that decides what counts as blank.
     */
    val folder: String = "",
) {
    val isNew: Boolean get() = id == null
}

fun Rule.toDraft() = RuleDraft(
    id = id,
    name = name,
    trigger = trigger.toDraft(),
    actions = actions.map { node ->
        when (node) {
            is ComponentSpec -> ComponentDraft(node.type, node.config)
            is ActionNode.Parallel -> ActionDraft.Parallel(
                node.branches.map { branch -> branch.map { ComponentDraft(it.type, it.config) } }
            )
        }
    },
    enabled = enabled,
    // The domain's null becomes the draft's "". See [RuleDraft.folder].
    folder = folder ?: "",
)

private fun TriggerNode.toDraft(): TriggerDraft = when (this) {
    is TriggerNode.One -> TriggerDraft.One(ComponentDraft(spec.type, spec.config))
    is TriggerNode.Group -> TriggerDraft.Group(op, children.map { it.toDraft() })
}

/**
 * Builds a [Rule] from the draft. The only thing this refuses is a blank
 * name. No trigger, no actions, and an empty group anywhere in the tree are
 * all structurally fine, see [TriggerDraft.toNode], because a rule saved
 * before it is finished is a person mid-thought, not a mistake.
 *
 * Two different questions used to be answered by the same null: "can this be
 * built at all" and "is this rule finished". They are not the same question.
 * Whether the *config* on what is there is valid is a third, decided later
 * still by asking the factories, in [RuleEditorViewModel.save]. Whether the
 * result can ever be switched on is a fourth, answered by `enableRefusal`, not
 * by this function.
 */
fun RuleDraft.toRuleOrNull(): Rule? {
    if (name.isBlank()) return null

    return Rule(
        id = id ?: RuleJson.newId(),
        name = name.trim(),
        trigger = trigger.toNode(),
        actions = actions.toNodes(),
        enabled = enabled,
        folder = normalizeFolder(folder),
    )
}

/**
 * The action tree this draft describes. Total, like [toNode]: an empty branch
 * or an empty block is kept as it is, because a rule saved before it is
 * finished is a person mid-thought. An empty block runs nothing.
 */
fun List<ActionDraft>.toNodes(): List<ActionNode> = map { draft ->
    when (draft) {
        is ComponentDraft -> ComponentSpec(draft.type, draft.config)
        is ActionDraft.Parallel -> ActionNode.Parallel(
            draft.branches.map { branch -> branch.map { ComponentSpec(it.type, it.config) } }
        )
    }
}

/**
 * Every action in this list, at every depth, in the order the engine numbers
 * them. See `leafActions` in `:core`, which this mirrors for drafts.
 */
fun List<ActionDraft>.leafComponents(): List<ComponentDraft> = flatMap { draft ->
    when (draft) {
        is ComponentDraft -> listOf(draft)
        is ActionDraft.Parallel -> draft.branches.flatten()
    }
}

/** The action at [path], or null if [path] names a block, a branch or nothing. */
fun List<ActionDraft>.componentAt(path: ActionPath): ComponentDraft? = when (path.size) {
    1 -> getOrNull(path[0]) as? ComponentDraft
    3 -> (getOrNull(path[0]) as? ActionDraft.Parallel)
        ?.branches?.getOrNull(path[1])?.getOrNull(path[2])

    else -> null
}

/**
 * The same list with [transform] applied to the action at [path]. Unchanged if
 * [path] does not name an action.
 *
 * Only the nodes on [path] are rebuilt, and every other action is passed
 * through as the same object. `repairReferences` depends on that: it matches
 * an action before and after an edit by identity.
 */
fun List<ActionDraft>.updateComponentAt(
    path: ActionPath,
    transform: (ComponentDraft) -> ComponentDraft,
): List<ActionDraft> {
    val existing = componentAt(path) ?: return this
    val updated = transform(existing)
    return when (path.size) {
        1 -> toMutableList().also { it[path[0]] = updated }
        else -> updateBranch(path[0], path[1]) { branch ->
            branch.toMutableList().also { it[path[2]] = updated }
        }
    }
}

/**
 * The same list with the branch at [blockIndex], [branchIndex] replaced by
 * what [transform] makes of it. Unchanged if there is no such branch.
 */
private fun List<ActionDraft>.updateBranch(
    blockIndex: Int,
    branchIndex: Int,
    transform: (List<ComponentDraft>) -> List<ComponentDraft>,
): List<ActionDraft> {
    val block = getOrNull(blockIndex) as? ActionDraft.Parallel ?: return this
    val branch = block.branches.getOrNull(branchIndex) ?: return this
    val branches = block.branches.toMutableList().also { it[branchIndex] = transform(branch) }
    return toMutableList().also { it[blockIndex] = block.copy(branches = branches) }
}

/** The same list with [addition] at the end of the branch at [branchPath], `[block, branch]`. */
fun List<ActionDraft>.addToBranch(branchPath: ActionPath, addition: ComponentDraft): List<ActionDraft> {
    if (branchPath.size != 2) return this
    return updateBranch(branchPath[0], branchPath[1]) { it + addition }
}

/** The same list with one more empty branch at the end of the block at [blockIndex]. */
fun List<ActionDraft>.addBranch(blockIndex: Int): List<ActionDraft> {
    val block = getOrNull(blockIndex) as? ActionDraft.Parallel ?: return this
    return toMutableList().also { it[blockIndex] = block.copy(branches = block.branches + listOf(emptyList())) }
}

/**
 * The same list with the node at [path] removed: an action, a whole block
 * (`[i]` naming a block), or one branch (`[block, branch]`).
 *
 * **A block that drops to one branch collapses into that branch's actions**,
 * in place, in order. A block of one branch runs its actions one after the
 * other, which is exactly what the same actions do with no block around them,
 * so the box is drawn around nothing. The same rule `transformTrigger` applies
 * to a trigger group of one. A block that loses its last branch is removed.
 *
 * Removing the last action of a branch keeps the branch, empty. A branch is
 * removed with its own control, and an empty branch is what a new block starts
 * with, so emptying one is not a sign that the person wants it gone.
 */
fun List<ActionDraft>.removeActionAt(path: ActionPath): List<ActionDraft> = when (path.size) {
    1 -> if (path[0] in indices) filterIndexed { i, _ -> i != path[0] } else this

    2 -> {
        val block = getOrNull(path[0]) as? ActionDraft.Parallel
        if (block == null || path[1] !in block.branches.indices) {
            this
        } else {
            val remaining = block.branches.filterIndexed { b, _ -> b != path[1] }
            val replacement: List<ActionDraft> = when (remaining.size) {
                0 -> emptyList()
                1 -> remaining.single()
                else -> listOf(block.copy(branches = remaining))
            }
            take(path[0]) + replacement + drop(path[0] + 1)
        }
    }

    3 -> if (componentAt(path) == null) {
        this
    } else {
        updateBranch(path[0], path[1]) { branch -> branch.filterIndexed { j, _ -> j != path[2] } }
    }

    else -> this
}

/**
 * The same list with the node at [from] moved to [to], inside the one list
 * both name: the top level (`[i]` to `[k]`), or one branch (`[b0, b1, j]` to
 * `[b0, b1, k]`). A move between two lists is refused and returns this list
 * unchanged. The editor offers only up and down inside a list, and a move
 * into or out of a branch changes what runs at the same time as what, which
 * deserves its own gesture rather than a side effect of an arrow.
 */
fun List<ActionDraft>.moveActionAt(from: ActionPath, to: ActionPath): List<ActionDraft> {
    if (from.size != to.size || from.dropLast(1) != to.dropLast(1)) return this
    return when (from.size) {
        1 -> {
            if (from[0] !in indices || to[0] !in indices) return this
            toMutableList().also { it.add(to[0], it.removeAt(from[0])) }
        }

        3 -> updateBranch(from[0], from[1]) { branch ->
            if (from[2] !in branch.indices || to[2] !in branch.indices) {
                branch
            } else {
                branch.toMutableList().also { it.add(to[2], it.removeAt(from[2])) }
            }
        }

        else -> this
    }
}

/**
 * The [TriggerNode] this draft describes. Total: unlike the `toNodeOrNull`
 * this replaced, nothing about the *shape* of a trigger tree can make this
 * refuse any more; only [RuleDraft.name] can make [toRuleOrNull] refuse a
 * save now. See [Rule]'s kdoc for [NO_TRIGGER], the value an absent trigger
 * becomes.
 *
 * Two things this used to lose by collapsing them into one null are now kept
 * as exactly what they are:
 *
 * **No trigger chosen at all**: a null [TriggerDraft] receiver, the empty
 * "choose a trigger" slot, becomes [NO_TRIGGER]. A rule that has not had a
 * trigger picked yet is a person mid-thought, which is exactly what lets it
 * hold a value in a field that is not nullable.
 *
 * **A group with nothing in it**, at the root or nested anywhere under a
 * trigger that is otherwise built, is kept as exactly that: an empty
 * [TriggerNode.Group], not pruned and not refused. Reachable in one tap (a
 * group is picked from the trigger picker and arrives empty), so it is the
 * same "not finished yet" as an absent trigger, only spelled one level
 * deeper. [TriggerNode.canStart] already reads an empty group as unable to
 * start a rule, wherever it sits in the tree, so nothing here needs a second
 * opinion about what "empty" means. Whether the result can be *switched on*
 * is a separate question, answered by `enableRefusal`, not by this
 * conversion. See `RuleEditorViewModel.save`.
 *
 * **A group of one child is still kept, not unwrapped**: unchanged from
 * before, and for the same reason it always was. A group is picked empty and
 * holds exactly one child for as long as it takes to add the second, and
 * unwrapping it mid-build would silently turn `ALL(a, ANY(b))` into
 * `ALL(a, b)` the moment `b` was added and nothing else had happened yet.
 *
 * A group that loses children to *removal* still collapses, and that is a
 * different question, answered in [transformTrigger]: removing one of two OR
 * branches leaves the other, and an OR of one thing is that thing. The
 * difference is intent. One child because a second was removed is a finished
 * edit. One child because a second is not added yet is a rule in progress.
 */
fun TriggerDraft?.toNode(): TriggerNode = when (this) {
    null -> NO_TRIGGER
    is TriggerDraft.One -> TriggerNode.One(ComponentSpec(component.type, component.config))
    is TriggerDraft.Group -> TriggerNode.Group(op, children.map { it.toNode() })
}

/**
 * Why [this] rule cannot be switched on right now, or null if it can.
 *
 * Three different problems share this one gate, told apart because they read
 * differently to someone looking at the switch, and because a person fixes
 * each one a different way:
 *
 * - **Nothing there yet**: no trigger, no actions, or both. This is exactly
 *   what [RuleDraft.toRuleOrNull] now lets through at save time: a rule
 *   mid-thought, savable, just not runnable yet. The message names what is
 *   missing, because "add a trigger" is something a person can act on and
 *   "this rule is not valid" is not.
 * - **Something is there, but not filled in.** A trigger or action a person
 *   picked, with a required field still empty. Also unfinished, not broken,
 *   which is why `RuleEditorViewModel.validate` no longer refuses the save
 *   over it either. Named by component here rather than folded into "add a
 *   trigger" or "add an action": a person who already added an action and is
 *   then told to add one would reasonably think the app is broken.
 * - **A trigger that is there and filled in, but can never fire.**
 *   [TriggerNode.canStart] catches this: two components that only ever
 *   produce events sitting together under one `ALL`, or a leaf whose own
 *   "only check, never watch" setting was turned on after it was already the
 *   rule's one trigger. This used to be a save-time refusal. It moves here
 *   because the trigger is not *incomplete* in this case, only unable to
 *   start the rule it is attached to, and that is an enable question, a
 *   person can only act on it by changing what is already there, not by
 *   adding or finishing something. Checked last, because a component that is
 *   not even filled in yet cannot usefully be judged by this question.
 *
 * See the [RuleDraft] overload for the one place that has to ask this before
 * anything has been saved: the editor's own "enabled" switch.
 */
fun Rule.enableRefusal(registry: Registry): String? = enableRefusal(trigger, actions, registry)

/** The same question [Rule.enableRefusal] answers, asked of a draft that may
 * not have been saved yet: what the editor's own "enabled" switch checks
 * before it lets itself move. */
fun RuleDraft.enableRefusal(registry: Registry): String? =
    enableRefusal(trigger.toNode(), actions.toNodes(), registry)

private fun enableRefusal(trigger: TriggerNode, tree: List<ActionNode>, registry: Registry): String? {
    // Every action at every depth. A rule whose only entry is an empty
    // parallel block has no action to run, the same as a rule with no entry.
    val actions = tree.leafActions()
    val hasNoTrigger = trigger.isUnset()
    val hasNoActions = actions.isEmpty()

    if (hasNoTrigger || hasNoActions) {
        val missing = listOfNotNull(
            "a trigger".takeIf { hasNoTrigger },
            "an action".takeIf { hasNoActions },
        ).joinToString(" and ")
        return "Add $missing before switching this on."
    }

    val unfinished = listOfNotNull(
        unfinishedTriggerLabel(trigger, registry),
        unfinishedActionLabel(actions, registry),
    )
    if (unfinished.isNotEmpty()) {
        return "Finish setting up ${unfinished.joinToString(" and ")} before switching this on."
    }

    if (!trigger.canStart(registry::producesEvents, registry::supportsCondition)) {
        return "This rule can never start. One trigger must start it, and the " +
            "others are only checked when it does. A trigger set to only " +
            "check never starts a rule."
    }

    return null
}

/**
 * The first trigger leaf with a required field nobody has filled in, named
 * for the enable refusal, or null if every leaf is filled in. See
 * [ConfigField.unfilled] for what "filled in" means.
 */
private fun unfinishedTriggerLabel(trigger: TriggerNode, registry: Registry): String? {
    val leaves = trigger.leaves()
    val index = leaves.indexOfFirst { spec ->
        registry.triggerDescriptor(spec.type)?.configFields?.unfilled(spec.config)?.isNotEmpty() == true
    }
    if (index == -1) return null
    val name = registry.displayNameOf(leaves[index].type)
    return if (leaves.size > 1) "$name (trigger ${index + 1})" else name
}

/** [unfinishedTriggerLabel]'s counterpart for actions, always numbered: an
 * action's own position is meaningful even when there is only one, the same
 * convention `RuleEditorViewModel.validate` already uses for its labels. */
private fun unfinishedActionLabel(actions: List<ComponentSpec>, registry: Registry): String? {
    val index = actions.indexOfFirst { spec ->
        registry.actionDescriptor(spec.type)?.configFields?.unfilled(spec.config)?.isNotEmpty() == true
    }
    if (index == -1) return null
    return "${registry.displayNameOf(actions[index].type)} (action ${index + 1})"
}

/**
 * Seeds a newly chosen component with the defaults its schema declares.
 *
 * Fields whose blankness is meaningful get no value at all. See
 * [ConfigField.Text.blankMeaning]. Supplying "" for those would look identical
 * to a deliberate choice while actually being an accident of the editor.
 */
fun defaultConfigFor(fields: List<ConfigField>): Map<String, String> =
    fields.mapNotNull { field ->
        when (field) {
            // Minted here, once, because a fresh identifier cannot come from
            // `defaultValue()`: a pure function asked twice would answer twice
            // differently. This is the only value the editor invents rather than
            // reads, and it is invisible: `GeneratedId` draws no control.
            is ConfigField.GeneratedId -> field.key to RuleJson.newId()
            else -> field.defaultValue()?.let { field.key to it }
        }
    }.toMap()

/**
 * Carries config across a type change, keeping only keys the new type knows.
 *
 * Switching `wifi_state` to `bluetooth_adapter_state` should keep the
 * `enabled`/`disabled` choice rather than blanking the form. The two share the
 * key and the vocabulary. Switching to something unrelated correctly drops
 * everything.
 */
fun migrateConfig(
    existing: Map<String, String>,
    newFields: List<ConfigField>,
): Map<String, String> {
    // Companion keys count as the field's own, because they are, with one
    // exception that does no harm here. A field can own more than one key when
    // the values are one answer: a latitude and its longitude, an hour and its
    // minute, a pattern and its match mode, a button and the notification it
    // belongs to. Keeping only `it.key` kept half of each of those and dropped
    // the other half without a word, so swapping a block between two components
    // that share such a field lost the second half of an answer the person had
    // already given. Found by swapping "Enter or leave an area" for "Is in an
    // area", which share a `Coordinates` field: the latitude survived and the
    // longitude came back null. A `ConfigField.Text.helpWhen` key is the one
    // case that is not owned this way, only read. But it names a sibling that
    // is already in `newFields` with its own entry, so it is already kept or
    // dropped on its own account and listing it here a second time changes
    // nothing.

    val allowedKeys = newFields.flatMap { field -> listOf(field.key) + field.companionKeys() }
        .toSet()
    val kept = existing.filterKeys { it in allowedKeys }
    // Defaults fill only the gaps, so a carried-over value always wins.
    return defaultConfigFor(newFields) + kept
}

/**
 * The node at [path] in the tree rooted at [this], or null if [path] leads
 * nowhere. The empty path is the root itself. This mirrors [TriggerNode.at].
 */
private fun TriggerDraft.at(path: NodePath): TriggerDraft? =
    path.fold(this as TriggerDraft?) { node, index ->
        (node as? TriggerDraft.Group)?.children?.getOrNull(index)
    }

/**
 * Replaces the node at [path] by applying [transform] to whatever is there now.
 * Null only when [path] is empty and [root] itself is null.
 *
 * Mirrors the pair [TriggerNode.replaceAt]/[TriggerNode.removeAt], written
 * against [TriggerDraft] because a draft's [ComponentDraft] is not yet a
 * [ComponentSpec]. [transform] returning null is what a removal is. Returning a
 * value is what every other edit is. One function rather than two, since there
 * is only ever one shape of node here to address.
 *
 * **A removal can collapse a group. Any other edit never changes the shape.**
 * That distinction is the whole of this function's care, and it used to be
 * missing: the un-promotion was applied to the result of every recursion, so a
 * group holding one child lost the group the moment anything inside it was
 * touched. Typing a value into the one trigger in a new OR group deleted the OR
 * group, in the editor, while the person was still filling it in. The tree they
 * were building changed under them and nothing said so.
 *
 * Removal still collapses, and that is deliberate rather than tolerated:
 * removing one of two OR branches leaves the other, and an OR of one thing is
 * that thing. The difference is what the person just did. Removing a child is a
 * finished edit and the group has served its purpose. Editing a child is not
 * about the group at all.
 *
 * A group left with no children at all disappears, because the last removal from
 * a group is the removal of the group. An *empty* group that a person made on
 * purpose is a different thing and is kept: it saves exactly as it is, and it
 * is what `enableRefusal` refuses to switch on until something is in it.
 */
fun transformTrigger(
    root: TriggerDraft?,
    path: NodePath,
    transform: (TriggerDraft?) -> TriggerDraft?,
): TriggerDraft? {
    if (path.isEmpty()) return transform(root)

    val group = root as? TriggerDraft.Group ?: return root
    val index = path.first()
    if (index !in group.children.indices) return root

    val updatedChild = transformTrigger(group.children[index], path.drop(1), transform)

    // An edit: same shape, one child replaced. No collapse, whatever the child
    // count is. A deeper removal has already collapsed whatever it needed to and
    // hands back a node, so it arrives here as an ordinary replacement.
    if (updatedChild != null) {
        return group.copy(
            children = group.children.toMutableList().also { it[index] = updatedChild }
        )
    }

    // A removal: the child is gone, and the group may have nothing left to be.
    val remaining = group.children.filterIndexed { i, _ -> i != index }
    return when (remaining.size) {
        0 -> null
        1 -> remaining.single()
        else -> group.copy(children = remaining)
    }
}

/**
 * The same tree with [addition] appended to whatever is at [path].
 *
 * A [TriggerDraft.Group] there just gains a sibling. A [TriggerDraft.One]
 * there is the promotion case: a lone trigger has no group of its own to add
 * a sibling to, so adding one wraps both under [op]. This is the only place a
 * group of exactly two children gets built by hand, mirroring [TriggerNode.addAt].
 * `null` only happens at the root before anything has been chosen, and the
 * addition simply becomes the tree. That is the shape [RuleEditorViewModel.chooseTrigger]
 * would have produced directly, had the slot been filled that way instead.
 */
fun addTriggerChild(
    root: TriggerDraft?,
    path: NodePath,
    addition: TriggerDraft,
    op: TriggerNode.Op,
): TriggerDraft? {
    if (root == null) return if (path.isEmpty()) addition else null

    val target = root.at(path) ?: return root
    val grown = when (target) {
        is TriggerDraft.Group -> target.copy(children = target.children + addition)
        is TriggerDraft.One -> TriggerDraft.Group(op, listOf(target, addition))
    }
    return transformTrigger(root, path) { grown }
}

/**
 * The tree this draft describes with any empty group left out of it, or null if
 * nothing is left.
 *
 * Not a second opinion on [toNode], a different question. [toNode] is what a
 * save builds from, and it keeps an empty group exactly as it is, because
 * saving no longer asks whether the trigger is finished, only whether the
 * rule has a name. See [RuleDraft.toRuleOrNull]. The trigger picker asks
 * something else: "if I put this component here, could the rule start", and a
 * group the person has not filled yet is simply not part of that question.
 * Answering it strictly would empty the picker: with `ALL(screen on, ANY())`
 * on screen, every candidate for the root would convert to a tree that still
 * has an empty group in it and be filtered out, so the one control that fills
 * the empty group would offer nothing at all.
 *
 * So this prunes, which [toNode] deliberately does not, and it is used only
 * where pruning is the honest reading.
 */
fun TriggerDraft.toNodeIgnoringEmptyGroups(): TriggerNode? = when (this) {
    is TriggerDraft.One -> TriggerNode.One(ComponentSpec(component.type, component.config))
    is TriggerDraft.Group -> {
        val kids = children.mapNotNull { it.toNodeIgnoringEmptyGroups() }
        when {
            kids.isEmpty() -> null
            kids.size == 1 -> kids.single()
            else -> TriggerNode.Group(op, kids)
        }
    }
}
