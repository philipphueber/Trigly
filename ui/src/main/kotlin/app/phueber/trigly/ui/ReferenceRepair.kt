package app.phueber.trigly.ui

import app.phueber.trigly.core.ComponentSpec
import app.phueber.trigly.core.Registry
import app.phueber.trigly.core.instanceRenames
import app.phueber.trigly.core.rewriteInstanceReferences
import app.phueber.trigly.core.shortFormRenames

/**
 * Keeping a rule's `{{...}}` references pointing where they were written to
 * point, when an edit moves the numbering underneath them.
 *
 * `componentInstanceNames` in `:core` numbers a component by its position among
 * the components of its type. That is what makes the namespace readable with no
 * stored identity, and it means a delete or a reorder can change what an
 * existing reference means without changing a character of it. `:core` has the
 * rewrite itself and says why it cannot be caught anywhere downstream. This is
 * the half that can only live here: the editor is the one place that holds the
 * rule as it was *and* as it now is, which is exactly what a rename needs.
 *
 * **Old and new components are matched by object identity, not by position or
 * by value.** Position is the thing that changed, so it cannot be the key. Value
 * cannot be either: two `toast` actions with the same text are equal data
 * classes, and matching them by value would pair the wrong one and produce a
 * rename that is silently backwards. Identity works because every draft
 * transform in [RuleDraft] rebuilds only the nodes on the path it was given and
 * passes the rest through untouched, so a component that survived an edit is
 * still the same object afterwards.
 */

/**
 * [after] with its references repaired, given [before] as it was.
 *
 * Rewrites only the config keys the registry says accept a reference, so a
 * field holding a package name or a URI that happens to contain braces is never
 * touched. Returns [after] unchanged when nothing moved, which is the common
 * case and costs one comparison.
 */
fun repairReferences(before: RuleDraft, after: RuleDraft, registry: Registry): RuleDraft {
    val renames = referenceRenames(before, after)
    if (renames.isEmpty()) return after

    return after.copy(
        trigger = after.trigger?.let { rewriteTrigger(it, renames, registry) },
        actions = after.actions.map { rewriteComponent(it, renames, registry) },
    )
}

/**
 * Every namespace that changed meaning between [before] and [after].
 *
 * Trigger leaves and actions are numbered independently, so they are matched
 * independently and the two maps merged. A collision between them is not
 * possible in practice, because a trigger type and an action type are never the
 * same string, and `PinnedTypeStringsTest` is what keeps that true.
 *
 * The short-form repair is added only on the one transition that needs it: a
 * rule going from exactly one leaf to more than one. That is when
 * `{{trigger.x}}` stops being offered, so that is when it has to become the
 * name of the leaf it already meant.
 */
private fun referenceRenames(before: RuleDraft, after: RuleDraft): Map<String, String> {
    val beforeLeaves = before.trigger?.leafComponents().orEmpty()
    val afterLeaves = after.trigger?.leafComponents().orEmpty()

    val leafRenames = instanceRenames(
        oldTypes = beforeLeaves.map { it.type },
        survivors = survivingIndices(beforeLeaves, afterLeaves),
    )
    val actionRenames = instanceRenames(
        oldTypes = before.actions.map { it.type },
        survivors = survivingIndices(before.actions, after.actions),
    )
    val shortForm = if (beforeLeaves.size == 1 && afterLeaves.size > 1) {
        shortFormRenames(beforeLeaves.single().type)
    } else {
        emptyMap()
    }

    return leafRenames + actionRenames + shortForm
}

/**
 * The indices into [before] of the components that are still in [after], in
 * their new order.
 *
 * A component in [after] that is not the same object as anything in [before] is
 * new, and is left out: it has no old namespace, so there is nothing to rename.
 * A component of [before] that appears nowhere in [after] was deleted, and
 * falls out of the result the same way, which is what leaves its namespace
 * dangling for validation to catch.
 */
private fun survivingIndices(
    before: List<ComponentDraft>,
    after: List<ComponentDraft>,
): List<Int> = after.mapNotNull { survivor ->
    before.indexOfFirst { it === survivor }.takeIf { it >= 0 }
}

/** Every leaf's component, in the order the engine numbers them. */
private fun TriggerDraft.leafComponents(): List<ComponentDraft> = when (this) {
    is TriggerDraft.One -> listOf(component)
    is TriggerDraft.Group -> children.flatMap { it.leafComponents() }
}

private fun rewriteTrigger(
    draft: TriggerDraft,
    renames: Map<String, String>,
    registry: Registry,
): TriggerDraft = when (draft) {
    is TriggerDraft.One -> draft.copy(component = rewriteComponent(draft.component, renames, registry))
    is TriggerDraft.Group ->
        draft.copy(children = draft.children.map { rewriteTrigger(it, renames, registry) })
}

/**
 * One component's substitutable fields, rewritten.
 *
 * Asked of the registry per component rather than per type, because whether a
 * field takes a reference can depend on the configuration: `set_variable`'s
 * value field is expression source in one mode and prose in another. Both
 * accept a reference, so both are rewritten, and asking properly is what keeps
 * that true if a mode is ever added that does not.
 */
private fun rewriteComponent(
    draft: ComponentDraft,
    renames: Map<String, String>,
    registry: Registry,
): ComponentDraft {
    val substitutable = registry.substitutionsFor(ComponentSpec(draft.type, draft.config)).keys
    if (substitutable.isEmpty()) return draft

    var config = draft.config
    for (key in substitutable) {
        val value = config[key] ?: continue
        val rewritten = rewriteInstanceReferences(value, renames)
        if (rewritten != value) config = config + (key to rewritten)
    }
    return if (config === draft.config) draft else draft.copy(config = config)
}
