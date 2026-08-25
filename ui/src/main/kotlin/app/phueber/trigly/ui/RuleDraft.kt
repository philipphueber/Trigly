package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.ConditionNode
import app.phueber.trigly.core.ConfigField
import app.phueber.trigly.core.Gate
import app.phueber.trigly.core.Rule
import app.phueber.trigly.core.RuleJson
import app.phueber.trigly.core.defaultValue

/**
 * Which half of a rule a component belongs to.
 *
 * No `CONDITION` entry, even though a condition check is a third kind of slot
 * conceptually: a check resolves through the exact same registry lookup as a
 * trigger — see `docs/conditions.md`'s "grouped under one component,
 * transparently" — so [RuleEditorViewModel.descriptorFor] and callers needing
 * a condition's descriptor simply pass [TRIGGER]. Adding a third value here
 * would also make every existing `when (slot)` non-exhaustive, including one
 * in an instrumented test this module does not own and must not edit.
 */
enum class Slot { TRIGGER, ACTION }

/**
 * A rule as it exists mid-edit.
 *
 * Separate from [Rule] because a half-built rule is not a valid one: the trigger
 * may be unchosen and config may be incomplete or unparseable. Keeping the
 * in-progress shape distinct means [Rule] never has to represent nonsense, and
 * the editor never has to construct a `Rule` it knows is invalid just to hold
 * the user's typing.
 */
data class ComponentDraft(
    val type: String,
    val config: Map<String, String> = emptyMap(),
)

/**
 * An editable mirror of [ConditionNode] — see `docs/conditions.md` for the model
 * this shadows.
 *
 * [Check] always carries a chosen [ComponentDraft], unlike a trigger or action
 * slot: a check is added by picking its component from an already-filtered
 * list (see [RuleEditorViewModel.addConditionCheck]), the same moment an action
 * is added, so there is no "chosen but nothing picked yet" state to represent.
 *
 * [Group] carries no minimum on [Group.children] the way [Gate] enforces one on
 * its triggers, because an imported rule can hold a vacuous or singleton
 * `All`/`Any` — see `ConditionNode.holds` — and the editor has to be able to
 * open one to look at it or remove it rather than refuse to render it. What the
 * *editor* itself never constructs is a group of fewer than two: adding a
 * sibling to a lone top-level check promotes it into a group, and removing a
 * child back down to one un-promotes the group into that child — see
 * [replaceCondition] — which is what keeps a rule with one condition free of
 * AND/OR chrome, the same way a rule with one trigger is free of OR chrome.
 */
sealed interface ConditionDraft {
    data class Check(val component: ComponentDraft) : ConditionDraft

    data class Group(
        val op: Op,
        val children: List<ConditionDraft> = emptyList(),
    ) : ConditionDraft

    /** Spelled out as words rather than reused from [ConditionNode]'s node names —
     * the toggle in the editor reads AND/OR, which is the question being asked
     * of the person building the rule, not the class name of the node it builds.
     */
    enum class Op { ALL, ANY }
}

data class RuleDraft(
    /** Null for a rule that has not been saved yet. */
    val id: String?,
    val name: String = "",
    val triggers: List<ComponentDraft> = emptyList(),
    val conditions: ConditionDraft? = null,
    val actions: List<ComponentDraft> = emptyList(),
    val enabled: Boolean = true,
) {
    /**
     * The single-trigger shape every rule had before gates existed.
     *
     * Kept because `copy(trigger = …)` is not how the primary constructor reads
     * any more, and several instrumented tests — which this file does not own
     * and must not edit — construct drafts this way. Exactly the same reasoning
     * as [Rule]'s own secondary constructor.
     */
    constructor(
        id: String?,
        name: String = "",
        trigger: ComponentDraft?,
        actions: List<ComponentDraft> = emptyList(),
        enabled: Boolean = true,
    ) : this(id, name, listOfNotNull(trigger), null, actions, enabled)

    val isNew: Boolean get() = id == null

    /**
     * The first trigger edge, or null before any has been chosen.
     *
     * The read half of the compatibility [trigger] constructor above, and for
     * the same reason: an instrumented test this file does not own reads a
     * one-trigger draft through this name, the same way [Rule.trigger] reads a
     * one-trigger [Rule].
     */
    val trigger: ComponentDraft? get() = triggers.firstOrNull()
}

fun Rule.toDraft() = RuleDraft(
    id = id,
    name = name,
    triggers = gate.triggers.map { ComponentDraft(it.type, it.config) },
    conditions = gate.conditions?.toDraft(),
    actions = actions.map { ComponentDraft(it.type, it.config) },
    enabled = enabled,
)

private fun ConditionNode.toDraft(): ConditionDraft = when (this) {
    is ConditionNode.Check -> ConditionDraft.Check(ComponentDraft(spec.type, spec.config))
    is ConditionNode.All ->
        ConditionDraft.Group(ConditionDraft.Op.ALL, children.map { it.toDraft() })
    is ConditionNode.Any ->
        ConditionDraft.Group(ConditionDraft.Op.ANY, children.map { it.toDraft() })
}

/**
 * Builds a [Rule] from the draft. Structural completeness only — whether the
 * *config* is valid is decided by asking the factories, in
 * [RuleEditorViewModel.save].
 */
fun RuleDraft.toRuleOrNull(): Rule? {
    if (triggers.isEmpty()) return null
    if (name.isBlank()) return null

    return Rule(
        id = id ?: RuleJson.newId(),
        name = name.trim(),
        gate = Gate(
            triggers = triggers.map { ComponentSpec(it.type, it.config) },
            conditions = conditions?.toConditionNodeOrNull(),
        ),
        actions = actions.map { ComponentSpec(it.type, it.config) },
        enabled = enabled,
    )
}

/**
 * The [ConditionNode] this draft node builds, or null for a group that — after
 * its own empty or vacuous children are pruned the same way — has nothing left
 * in it.
 *
 * A group of exactly one child unwraps to that child rather than saving as an
 * `All`/`Any` of one: the editor never *builds* a singleton group (see
 * [ConditionDraft]'s KDoc), but pruning an empty grandchild out of a two-child
 * group can leave one behind here, and the alternative — saving a pointless
 * wrapper — is a shape nothing in the editor would have chosen on purpose.
 */
private fun ConditionDraft.toConditionNodeOrNull(): ConditionNode? = when (this) {
    is ConditionDraft.Check -> ConditionNode.Check(ComponentSpec(component.type, component.config))
    is ConditionDraft.Group -> {
        val kids = children.mapNotNull { it.toConditionNodeOrNull() }
        when {
            kids.isEmpty() -> null
            kids.size == 1 -> kids.single()
            op == ConditionDraft.Op.ALL -> ConditionNode.All(kids)
            else -> ConditionNode.Any(kids)
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
 * Adds [child] to whatever is at [path] in the tree rooted at [root].
 *
 * The three branches of "whatever is there" matter for different reasons.
 * `null` only happens at the root with nothing in it yet, and the new child
 * simply becomes the tree. An existing [ConditionDraft.Group] just gains a
 * sibling. An existing [ConditionDraft.Check] is the promotion case: a lone
 * top-level check has no group of its own to add a sibling to, so adding one
 * wraps both under a fresh `AND` — which is the only place in the editor a
 * [ConditionDraft.Group] of exactly two children gets built by hand, and why it
 * is safe to assume every group [replaceCondition] is asked to shrink already
 * has at least that many.
 */
fun addCondition(root: ConditionDraft?, path: List<Int>, child: ConditionDraft): ConditionDraft? =
    replaceCondition(root, path) { existing ->
        when (existing) {
            null -> child
            is ConditionDraft.Group -> existing.copy(children = existing.children + child)
            is ConditionDraft.Check ->
                ConditionDraft.Group(ConditionDraft.Op.ALL, listOf(existing, child))
        }
    }

/**
 * Replaces the node at [path] by applying [transform] to whatever is there now
 * — null only when [path] is empty and the tree itself is empty — and un-
 * promotes any group left with fewer than two children as the change bubbles
 * back up.
 *
 * That un-promotion is what keeps "two conditions, remove one" land back on the
 * unwrapped single-check shape rather than a group holding one thing, and what
 * keeps "remove the only condition" clear the section entirely rather than
 * leaving an empty group behind. [path] is a list of child indices walked from
 * the root through nested groups; the empty path means the root itself, which
 * is also the address every "add a sibling to whatever is here" call at the top
 * of the section uses — see [addCondition].
 */
fun replaceCondition(
    root: ConditionDraft?,
    path: List<Int>,
    transform: (ConditionDraft?) -> ConditionDraft?,
): ConditionDraft? {
    if (path.isEmpty()) return transform(root)

    val group = root as? ConditionDraft.Group ?: return root
    val index = path.first()
    if (index !in group.children.indices) return root

    val updatedChild = replaceCondition(group.children[index], path.drop(1), transform)
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
