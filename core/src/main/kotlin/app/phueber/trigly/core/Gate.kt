package app.phueber.trigly.core

/**
 * The trigger side of a rule: the edges that start it, and what must be true when
 * one arrives.
 *
 * Full reasoning in `docs/conditions.md`. The two things to know before touching
 * this:
 *
 * **The first level holds edges; everything below holds levels.** [triggers] is
 * an OR of edges — any one of them fires the rule — and [conditions] is a tree of
 * states that must hold when one does. A single trigger needs no wrapper, which
 * is the common case and why this is a list rather than a mandatory `Any` node.
 *
 * **That split is what keeps edges and levels apart.** A trigger is an edge —
 * "the screen turned on" — and a condition is a level — "the screen is on". Two
 * edges are essentially never true at the same instant, so `screen_on AND
 * bluetooth_connected` only means anything if the second is read as a state. This
 * type has no position where an edge could be asked to hold, or a state asked to
 * fire, which is why the editor needs no rules about it.
 */
data class Gate(
    val triggers: List<ComponentSpec>,
    val conditions: ConditionNode? = null,
) {
    init {
        // A gate with no edge can never fire. Unreachable from the editor and
        // reachable from an imported file, so it is refused where it is built
        // rather than diagnosed later as a rule that mysteriously does nothing.
        require(triggers.isNotEmpty()) { "a gate needs at least one trigger" }
    }

    /** The single-trigger case, which is most of them. */
    constructor(trigger: ComponentSpec, conditions: ConditionNode? = null) :
        this(listOf(trigger), conditions)

    /** True when the first level is an OR of several edges rather than one. */
    val hasSeveralTriggers: Boolean get() = triggers.size > 1
}

/**
 * What must hold when one of the gate's triggers fires.
 *
 * A tree rather than a list, because "A and (B or C)" is the shape people
 * actually want and flattening it would silently change the meaning. A nested
 * [All] or [Any] *is* the sub-gate — there is no separate node kind for grouping.
 *
 * No `Not`. Most state-capable triggers already carry their own two-word state
 * choice — `connected`/`disconnected`, `enabled`/`disabled` — so "if not
 * charging" is a setting on the check rather than a wrapper around it, and a
 * negation node would need an editor affordance to express something already
 * expressible.
 */
sealed interface ConditionNode {

    /**
     * One trigger, asked for its current state rather than watched.
     *
     * [spec] is an ordinary component spec: the same type string and config a
     * trigger slot would hold. That is deliberate — a component appears once in
     * the picker and the slot decides which question is asked of it, so `solar`
     * in the trigger slot means "at sunset" and the same `solar` here means "it
     * is after sunset".
     */
    data class Check(val spec: ComponentSpec) : ConditionNode

    /** Every child must hold. */
    data class All(val children: List<ConditionNode>) : ConditionNode

    /** At least one child must hold. */
    data class Any(val children: List<ConditionNode>) : ConditionNode
}

/**
 * Whether this tree holds, given a way to read one check's state.
 *
 * The state lookup is a parameter rather than a dependency so the whole of this
 * is testable without a device. Every mistake in here produces a rule that either
 * never fires or fires when it should not, and both are silent — which is exactly
 * the kind of logic that must not be exercised only by hand on a phone.
 *
 * **Null does not hold.** A check that cannot answer the question, or that fails
 * while trying, is unknown — and an unknown state is not a satisfied one. The
 * alternative is a rule that fires on a guess, which for an app whose whole
 * purpose is unattended action is the worse failure by a distance.
 *
 * The empty cases follow from what the words mean rather than from convenience:
 * [All] of nothing holds, because nothing failed; [Any] of nothing does not,
 * because nothing satisfied it. Neither is reachable from the editor, and both
 * are reachable from an imported file.
 */
suspend fun ConditionNode.holds(stateOf: suspend (ComponentSpec) -> Boolean?): Boolean =
    when (this) {
        is ConditionNode.Check -> stateOf(spec) == true
        // Short-circuits, and that is a promise rather than an optimisation: a
        // location check costs a GPS read, so an `All` whose earlier child has
        // already failed must not pay for the rest.
        is ConditionNode.All -> children.all { it.holds(stateOf) }
        is ConditionNode.Any -> children.any { it.holds(stateOf) }
    }

/** Every check in this tree, depth-first. */
fun ConditionNode.checks(): List<ComponentSpec> = when (this) {
    is ConditionNode.Check -> listOf(spec)
    is ConditionNode.All -> children.flatMap { it.checks() }
    is ConditionNode.Any -> children.flatMap { it.checks() }
}

/**
 * The checks in this tree whose component cannot answer a state question.
 *
 * A rule containing one can never fire, silently and permanently —
 * indistinguishable from "it has not happened yet", which is the failure mode this
 * project keeps designing against. The editor prevents it by only offering
 * state-capable components in condition slots; this exists for the other way in,
 * an imported or downgraded rule naming a component that cannot be asked.
 *
 * Returns the offenders rather than a boolean so the caller can name them.
 */
fun ConditionNode.unaskable(supportsCondition: (String) -> Boolean): List<ComponentSpec> =
    checks().filterNot { supportsCondition(it.type) }
