package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NodePath
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.defaultValue
import app.phueber.trigly.core.normalizeFolder

/**
 * Which half of a rule a component belongs to.
 *
 * No third value for a passive check: since [TriggerNode.Group] made a group
 * of triggers itself a trigger, a component asked for its state resolves
 * through the exact same registry lookup as one watched for an edge — see
 * `docs/conditions.md`'s "grouped under one component, transparently" — so
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
 * `changeTriggerType` / `addTrigger` calls a real component's type does — the
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
 * a `Map<String, String>` of config. `configFields` is empty for the same reason —
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
)

/**
 * An editable mirror of [TriggerNode] — see `core/.../Gate.kt` for the model
 * this shadows and the reasoning for it being one tree rather than a trigger
 * list beside a separate condition tree.
 *
 * [One] holds a plain [ComponentDraft] rather than a [ComponentSpec], the same
 * reason [RuleDraft] holds [ComponentDraft]s rather than [ComponentSpec]s:
 * config under construction is not yet known to be config a factory accepts.
 *
 * Neither case carries a minimum on [Group.children] the way the editor's own
 * operations do — an imported or previously-saved rule can hold a vacuous or
 * singleton group, and the editor has to be able to load one to look at it or
 * remove it rather than refuse to render it. What the editor itself never
 * *constructs* is a group of fewer than two: adding a sibling to a lone
 * trigger promotes it into a group, and removing a child back down to one
 * un-promotes the group into that child — see [transformTrigger] — which is
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
    /** Null when nothing has been chosen yet — the empty "choose a trigger" slot. */
    val trigger: TriggerDraft? = null,
    val actions: List<ComponentDraft> = emptyList(),
    val enabled: Boolean = true,
    /**
     * "" means "no folder" here, unlike [Rule.folder]'s `null` — a text field has
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
    actions = actions.map { ComponentDraft(it.type, it.config) },
    enabled = enabled,
    // The domain's null becomes the draft's "" — see [RuleDraft.folder].
    folder = folder ?: "",
)

private fun TriggerNode.toDraft(): TriggerDraft = when (this) {
    is TriggerNode.One -> TriggerDraft.One(ComponentDraft(spec.type, spec.config))
    is TriggerNode.Group -> TriggerDraft.Group(op, children.map { it.toDraft() })
}

/**
 * Builds a [Rule] from the draft. Structural completeness only — whether the
 * *config* is valid is decided by asking the factories, in
 * [RuleEditorViewModel.save].
 */
fun RuleDraft.toRuleOrNull(): Rule? {
    val node = trigger?.toNodeOrNull() ?: return null
    if (name.isBlank()) return null

    return Rule(
        id = id ?: RuleJson.newId(),
        name = name.trim(),
        trigger = node,
        actions = actions.map { ComponentSpec(it.type, it.config) },
        enabled = enabled,
        folder = normalizeFolder(folder),
    )
}

/**
 * The [TriggerNode] this draft node builds, or null when a group in it has
 * nothing to build from.
 *
 * This function used to do two things that both lost a group without saying so,
 * and both are fixed here.
 *
 * **A group of one child is kept, not unwrapped.** The old code unwrapped it,
 * on the stated grounds that the editor never builds a singleton group. It
 * does, on the way to every group a person makes: a group is picked from the
 * trigger picker and arrives empty, so it holds exactly one child for as long
 * as it takes to add the second. Saving in that state replaced the group with
 * its one child, which is the same tree as a predicate and a different rule to
 * look at. Someone who built `ALL(screen on, ANY(...))`, added the first branch
 * of the OR, and saved, reopened the rule to find the OR gone. An `ANY` of one
 * evaluates exactly like the child, so keeping it costs nothing at runtime and
 * keeps what the person built.
 *
 * **An empty group refuses the save rather than disappearing from it.** The old
 * code pruned it with `mapNotNull` and carried on, so a group with nothing in it
 * was dropped and the parent saved without it. The refusal message existed
 * already but could only fire when the *root* was the empty group, which is the
 * one case where pruning happened to produce a null tree. Now any empty group
 * anywhere makes the whole conversion null, and
 * [RuleEditorViewModel.save] says which problem it is. A rule that looks saved
 * and quietly lost a piece of itself is the failure this project is built to
 * avoid.
 *
 * A group that loses children to *removal* still collapses, and that is a
 * different question, answered in [transformTrigger]: removing one of two OR
 * branches leaves the other, and an OR of one thing is that thing. The
 * difference is intent. One child because a second was removed is a finished
 * edit. One child because a second is not added yet is a rule in progress.
 */
fun TriggerDraft.toNodeOrNull(): TriggerNode? = when (this) {
    is TriggerDraft.One -> TriggerNode.One(ComponentSpec(component.type, component.config))
    is TriggerDraft.Group -> {
        // An empty group, or any descendant of one, makes the whole tree null.
        // `mapNotNull` here is what silently dropped it before.
        if (children.isEmpty()) {
            null
        } else {
            val kids = children.map { it.toNodeOrNull() }
            if (kids.any { it == null }) null else TriggerNode.Group(op, kids.filterNotNull())
        }
    }
}

/**
 * Seeds a newly chosen component with the defaults its schema declares.
 *
 * Fields whose blankness is meaningful get no value at all — see
 * [ConfigField.Text.blankMeaning]. Supplying "" for those would look identical
 * to a deliberate choice while actually being an accident of the editor.
 */
fun defaultConfigFor(fields: List<ConfigField>): Map<String, String> =
    fields.mapNotNull { field ->
        when (field) {
            // Minted here, once, because a fresh identifier cannot come from
            // `defaultValue()` — a pure function asked twice would answer twice
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
 * `enabled`/`disabled` choice rather than blanking the form — the two share the
 * key and the vocabulary. Switching to something unrelated correctly drops
 * everything.
 */
fun migrateConfig(
    existing: Map<String, String>,
    newFields: List<ConfigField>,
): Map<String, String> {
    val allowedKeys = newFields.map { it.key }.toSet()
    val kept = existing.filterKeys { it in allowedKeys }
    // Defaults fill only the gaps, so a carried-over value always wins.
    return defaultConfigFor(newFields) + kept
}

/**
 * The node at [path] in the tree rooted at [this], or null if [path] leads
 * nowhere. The empty path is the root itself — mirrors [TriggerNode.at].
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
 * purpose is a different thing, is kept, and is what `toNodeOrNull` refuses to
 * save until something is in it.
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
 * a sibling to, so adding one wraps both under [op] — the only place a group
 * of exactly two children gets built by hand, mirroring [TriggerNode.addAt].
 * `null` only happens at the root before anything has been chosen, and the
 * addition simply becomes the tree — the shape [RuleEditorViewModel.chooseTrigger]
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
 * Not a second opinion on [toNodeOrNull], a different question. Saving asks
 * "is this rule finished", and an empty group means no, which is why
 * [toNodeOrNull] refuses it. The trigger picker asks "if I put this component
 * here, could the rule start", and a group the person has not filled yet is
 * simply not part of that question. Answering it strictly would empty the
 * picker: with `ALL(screen on, ANY())` on screen, every candidate for the root
 * would convert to null and be filtered out, so the one control that fills the
 * empty group would offer nothing at all.
 *
 * So this prunes, exactly the way [toNodeOrNull] used to for everybody, and it
 * is used only where pruning is the honest reading.
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
