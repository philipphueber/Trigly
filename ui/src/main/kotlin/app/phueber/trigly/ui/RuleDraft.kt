package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentDescriptor
import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.NodePath
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.TriggerNode
import app.phueber.trigly.core.defaultValue

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
) {
    val isNew: Boolean get() = id == null
}

fun Rule.toDraft() = RuleDraft(
    id = id,
    name = name,
    trigger = trigger.toDraft(),
    actions = actions.map { ComponentDraft(it.type, it.config) },
    enabled = enabled,
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
    )
}

/**
 * The [TriggerNode] this draft node builds, or null for a group that — after
 * its own empty or vacuous children are pruned the same way — has nothing left
 * in it.
 *
 * A group of exactly one child unwraps to that child rather than saving as an
 * `ALL`/`ANY` of one: the editor never *builds* a singleton group (see
 * [TriggerDraft]'s KDoc), but pruning an empty grandchild out of a two-child
 * group can leave one behind here, and the alternative — saving a pointless
 * wrapper — is a shape nothing in the editor would have chosen on purpose.
 */
fun TriggerDraft.toNodeOrNull(): TriggerNode? = when (this) {
    is TriggerDraft.One -> TriggerNode.One(ComponentSpec(component.type, component.config))
    is TriggerDraft.Group -> {
        val kids = children.mapNotNull { it.toNodeOrNull() }
        when {
            kids.isEmpty() -> null
            kids.size == 1 -> kids.single()
            else -> TriggerNode.Group(op, kids)
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
 * Replaces the node at [path] by applying [transform] to whatever is there now
 * — null only when [path] is empty and [root] itself is null — and un-promotes
 * any group left with fewer than two children as the change bubbles back up.
 *
 * Mirrors the pair [TriggerNode.replaceAt]/[TriggerNode.removeAt], written
 * against [TriggerDraft] because a draft's [ComponentDraft] is not yet a
 * [ComponentSpec]. [transform] returning null is what a removal is; returning
 * a value is what every other edit is — one function rather than two, since
 * there is only ever one shape of node here to address.
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
    val newChildren = if (updatedChild == null) {
        group.children.filterIndexed { i, _ -> i != index }
    } else {
        group.children.toMutableList().also { it[index] = updatedChild }
    }

    return when (newChildren.size) {
        0 -> null
        1 -> newChildren.single()
        else -> group.copy(children = newChildren)
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
