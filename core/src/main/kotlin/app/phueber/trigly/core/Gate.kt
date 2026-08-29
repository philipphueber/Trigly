package app.phueber.trigly.core

/**
 * The trigger side of a rule. **One** trigger, which may be a group of triggers.
 *
 * Full reasoning in `docs/conditions.md`. The shape to hold in your head:
 *
 * ```
 * One(bluetooth)                                    // when the car connects
 * Group(ALL, [One(bluetooth), One(time_window)])    // …and it is night
 * Group(ANY, [One(charger), One(headset)])          // when either happens
 * Group(ALL, [Group(ANY, [a, b]), One(time)])       // a sub-group, nested
 * ```
 *
 * ### Why this replaced `Gate(triggers, conditions)`
 *
 * The previous version modelled the two halves separately: a list of edges, and a
 * tree of conditions beside it. That made a *structural* distinction the user
 * never asked for — the editor grew a second region with its own vocabulary, and
 * "a condition is just a trigger, asked instead of watched" stopped being true of
 * the code even while the documentation claimed it. One tree says it properly: a
 * group is a trigger, it is chosen from the same picker as any other trigger, and
 * it can contain groups.
 *
 * ### Edges and levels still exist — as a property of a component, not a slot
 *
 * A trigger is an edge ("the screen turned on"); the same component asked for its
 * state is a level ("the screen is on"). Two edges are essentially never true at
 * the same instant, which is the one hard constraint on this tree: an [Op.ALL]
 * group whose children include two components that can *only* be edges can never
 * be satisfied. That is what [canStart] exists to detect, and what the editor uses
 * to decide which components a slot may offer.
 *
 * Nothing at runtime needs the distinction, which is the payoff. [holds] takes the
 * path of the leaf that fired and reads every other leaf as a level, so a
 * component that cannot produce events simply never starts a rule, and a
 * component that cannot answer a state simply never satisfies one. Neither needs a
 * special case.
 */
sealed interface TriggerNode {

    /** One component: a type string plus its settings, as stored. */
    data class One(val spec: ComponentSpec) : TriggerNode

    /**
     * A group of triggers, combined with [op].
     *
     * This is what the user picks as "All of" or "Any of" in the trigger picker.
     * A group holding a group is how "a and (b or c)" is expressed; there is no
     * separate node kind for nesting.
     */
    data class Group(val op: Op, val children: List<TriggerNode>) : TriggerNode

    /** How a [Group] combines its children. */
    enum class Op { ALL, ANY }
}

/**
 * Where a node sits in the tree, as child indices from the root.
 *
 * The empty path is the root; `[1]` is its second child; `[1, 0]` that child's
 * first. Used for two jobs that both need to name a node that has no identity of
 * its own: the editor addressing the node a control belongs to, and [holds]
 * naming the leaf that fired.
 *
 * Identity by position, not by value, because two leaves can hold the same
 * component with the same settings. Comparing specs would mark both of them as
 * fired, and in an [TriggerNode.Op.ALL] group that is the difference between a
 * rule that runs and one that cannot.
 */
typealias NodePath = List<Int>

/** The node at [path], or null if the path leads nowhere. */
fun TriggerNode.at(path: NodePath): TriggerNode? =
    path.fold(this as TriggerNode?) { node, index ->
        (node as? TriggerNode.Group)?.children?.getOrNull(index)
    }

/** Every component in this tree, depth first. */
fun TriggerNode.leaves(): List<ComponentSpec> = when (this) {
    is TriggerNode.One -> listOf(spec)
    is TriggerNode.Group -> children.flatMap { it.leaves() }
}

/** Every component in this tree with the path it sits at, depth first. */
fun TriggerNode.leafPaths(prefix: NodePath = emptyList()): List<Pair<NodePath, ComponentSpec>> =
    when (this) {
        is TriggerNode.One -> listOf(prefix to spec)
        is TriggerNode.Group -> children.flatMapIndexed { index, child ->
            child.leafPaths(prefix + index)
        }
    }

/**
 * The same tree with [transform] applied to every component in it.
 *
 * Structure preserved, which is the point: a caller that wants to change what
 * the leaves *hold* should not have to know how they are arranged. Used to fill
 * in config an older build left out, per `ComponentFactory.normalise`.
 */
fun TriggerNode.mapSpecs(transform: (ComponentSpec) -> ComponentSpec): TriggerNode = when (this) {
    is TriggerNode.One -> TriggerNode.One(transform(spec))
    is TriggerNode.Group -> TriggerNode.Group(op, children.map { it.mapSpecs(transform) })
}

/**
 * Whether this tree is satisfied, given the leaf that just fired.
 *
 * [firedPath] is the leaf whose event started this evaluation. It counts as true
 * without being asked, because it just happened — asking a component whether it
 * *is* connected right after it reported connecting would fail for anything
 * momentary, and "a tap happened" has no state to read at all.
 *
 * Every other leaf is asked for its current state through [stateOf]. The state
 * lookup is a parameter rather than a dependency so this is testable without a
 * device: every mistake in here is a rule that never runs or runs when it should
 * not, and both are silent.
 *
 * **Null does not satisfy.** A component that cannot answer, or that fails while
 * trying, is unknown — and unknown is not yes. The alternative is unattended
 * actions running on a guess, which for this app is the worse failure by a
 * distance.
 *
 * The empty cases follow from the words: [TriggerNode.Op.ALL] of nothing holds,
 * because nothing failed; [TriggerNode.Op.ANY] of nothing does not, because
 * nothing satisfied it. The editor cannot build either, and an imported file can.
 *
 * **This signature is unchanged by [TriggerTrace]'s arrival, on purpose.**
 * `TriggerEngineTest` and every `GateTest` call [holds] exactly as before; a
 * rule's own evaluation is still a plain suspend function to a `Boolean`, no
 * richer type involved. What changed is underneath: this now delegates to
 * [holdsAt], which builds a full [TriggerTrace] and this simply reads
 * [TriggerTrace.held] off the root of it, discarding the rest. That was chosen
 * over a second, hand-written traversal that only computes a `Boolean`.
 * Short-circuiting here is a promise, not an optimisation, per [TriggerNode.Group],
 * and two independent copies of that promise are two places for it to quietly
 * drift apart. One traversal, built once, is what
 * [GateTest]'s existing short-circuit and empty-case tests now exercise
 * directly, since they call [holds], which calls it.
 */
suspend fun TriggerNode.holds(
    firedPath: NodePath,
    stateOf: suspend (ComponentSpec) -> Boolean?,
): Boolean = holdsAt(emptyList(), firedPath, stateOf).held == true

/**
 * The same evaluation [holds] runs, kept as a tree instead of collapsed to one
 * [Boolean].
 *
 * [TriggerEngine.resolveHolds] is the caller that wants this: "the rule did not
 * fire" used to be the whole story once a leaf plainly answered no, and this is
 * what lets a person see *which* leaf, in a tree shaped like the rule they
 * built rather than as a sentence about it. See [TriggerTrace].
 */
suspend fun TriggerNode.traced(
    firedPath: NodePath,
    stateOf: suspend (ComponentSpec) -> Boolean?,
): TriggerTrace = holdsAt(emptyList(), firedPath, stateOf)

private suspend fun TriggerNode.holdsAt(
    here: NodePath,
    firedPath: NodePath,
    stateOf: suspend (ComponentSpec) -> Boolean?,
): TriggerTrace = when (this) {
    is TriggerNode.One -> {
        val outcome = if (here == firedPath) {
            LeafOutcome.FIRED
        } else {
            when (stateOf(spec)) {
                true -> LeafOutcome.YES
                false -> LeafOutcome.NO
                null -> LeafOutcome.UNREADABLE
            }
        }
        TriggerTrace.Leaf(spec, outcome)
    }

    // Short-circuits, and that is a promise rather than an optimisation: a
    // location check costs a position read, so a group whose earlier child has
    // already decided the answer must not pay for the rest. A child this loop
    // never reaches is marked with notConsulted() rather than asked. See its
    // own KDoc for why that must not call stateOf either.
    is TriggerNode.Group -> {
        val traces = mutableListOf<TriggerTrace>()
        var decided: Boolean? = null
        for ((i, child) in children.withIndex()) {
            if (decided != null) {
                traces += child.notConsulted()
                continue
            }
            val childTrace = child.holdsAt(here + i, firedPath, stateOf)
            traces += childTrace
            val childHeld = childTrace.held == true
            decided = when (op) {
                // A false child sinks an ALL at once; a true one is not yet
                // the whole answer, so keep going (null = "still deciding").
                TriggerNode.Op.ALL -> if (!childHeld) false else null
                // A true child carries an ANY at once; a false one is not
                // yet the whole answer either.
                TriggerNode.Op.ANY -> if (childHeld) true else null
            }
        }
        // The loop ran out without any child deciding it: ALL held all the
        // way through, or ANY never found one that did.
        val held = decided ?: (op == TriggerNode.Op.ALL)
        TriggerTrace.Group(op, traces, held)
    }
}

/**
 * The trace for a subtree the evaluation never reached, because a sibling
 * already decided the group's answer.
 *
 * **Calls neither [TriggerNode.holdsAt]'s `stateOf` nor anything else that
 * reads a component.** That is the whole point: [TriggerNode.canStart]'s
 * short-circuit promise says a component the evaluation never reached is not
 * one that failed to answer, and a trace that quietly asked it anyway, only to
 * throw the answer away, would still have paid the cost the promise exists to
 * avoid. Every leaf under here reads as [LeafOutcome.NOT_CONSULTED], including
 * one that happens to be the leaf that fired: if a sibling elsewhere in an
 * `ALL` group already failed, that this leaf also fired is true but irrelevant,
 * and the honest report is that this part of the tree was never asked at all.
 */
private fun TriggerNode.notConsulted(): TriggerTrace = when (this) {
    is TriggerNode.One -> TriggerTrace.Leaf(spec, LeafOutcome.NOT_CONSULTED)
    is TriggerNode.Group -> TriggerTrace.Group(op, children.map { it.notConsulted() }, held = null)
}

/**
 * A record of one evaluation of a [TriggerNode], shaped exactly like the tree
 * it came from: a [Leaf] for every [TriggerNode.One], a [Group] for every
 * [TriggerNode.Group]. Built by [TriggerNode.traced], which is the same
 * traversal [TriggerNode.holds] runs. See that function's KDoc for why this
 * is one implementation and not two.
 *
 * This is what lets a person see which leaf of an `ALL` said no, which leaf of
 * an `ANY` carried it, and which leaves the tree never even asked, rather than
 * only the tree's single collapsed answer. See `docs/todo.md`'s trace entry
 * for the gap this closes.
 */
sealed interface TriggerTrace {
    /**
     * Whether this node's part of the tree was satisfied, or `null` if it was
     * never consulted at all. See [Group.held] and [LeafOutcome.NOT_CONSULTED].
     */
    val held: Boolean?

    /** One component, and how it answered. */
    data class Leaf(val spec: ComponentSpec, val outcome: LeafOutcome) : TriggerTrace {
        override val held: Boolean? = when (outcome) {
            LeafOutcome.FIRED, LeafOutcome.YES -> true
            LeafOutcome.NO, LeafOutcome.UNREADABLE -> false
            LeafOutcome.NOT_CONSULTED -> null
        }
    }

    /**
     * A group of children, combined with [op].
     *
     * [held] is `null` exactly when this whole subgroup was skipped by a
     * sibling's short-circuit, per [notConsulted], and a `Boolean` in every
     * other case, [TriggerNode.holds]'s empty-group rule included: `ALL` of
     * nothing is `true`, `ANY` of nothing is `false`.
     */
    data class Group(
        val op: TriggerNode.Op,
        val children: List<TriggerTrace>,
        override val held: Boolean?,
    ) : TriggerTrace
}

/**
 * How one leaf answered in a [TriggerTrace], as five distinct facts rather than
 * a `Boolean?` plus a separate flag.
 *
 * The distinction [NOT_CONSULTED] draws is the one this whole type exists for:
 * without it, a leaf the tree never asked and a leaf that answered no read as
 * the same "no" on screen, which is exactly the honesty gap a trace is meant
 * to close. [FIRED] is a third state for the same reason: the leaf that
 * started this evaluation is true by construction and was never asked either,
 * which is a different fact from "asked, and said yes".
 */
enum class LeafOutcome {
    /** The leaf whose event started this evaluation. True by construction, never read. */
    FIRED,

    /** Asked, and said yes. */
    YES,

    /** Asked, and said no. */
    NO,

    /** Asked, and could not answer: a `null` from the component's own read. */
    UNREADABLE,

    /**
     * Never asked, because a sibling already decided the group's answer
     * before the traversal reached this leaf. Not the same claim as
     * [NO]: nothing here says this leaf would have failed, only that its
     * answer was never needed.
     */
    NOT_CONSULTED,
}

/**
 * Whether this tree can ever start a rule.
 *
 * The one thing a person can build here that silently cannot work. Two components
 * that only ever produce events, in the same [TriggerNode.Op.ALL] group, describe
 * two instants that never coincide: whichever one fires, the other is asked for a
 * state it does not have, answers unknown, and the group fails. Forever, with no
 * message — the failure this project keeps designing against.
 *
 * So it is computed rather than assumed:
 *
 * - `One` can start if its component produces events.
 * - `ANY` can start if any child can.
 * - `ALL` can start if one child can start *and* every other child can be asked
 *   for a state. One edge and any number of levels is the useful rule; a second
 *   edge is the mistake.
 *
 * [hasEvents] and [hasState] come from the component's factory — see
 * `Registry`. Both are passed in rather than read here, because `:core`'s model
 * must not need the registry to describe itself.
 *
 * Both are asked about a type string, because whether a component can start a
 * rule is a property of the component. A component that only ever answers a
 * question says so as itself: `time_window` and `location_check` both declare
 * `producesEvents = false`, and the second exists as its own type rather than as
 * a switch on the watching one precisely so that this stays a question about the
 * type. See `docs/triggers.md`.
 */
fun TriggerNode.canStart(
    hasEvents: (String) -> Boolean,
    hasState: (String) -> Boolean,
): Boolean = when (this) {
    is TriggerNode.One -> hasEvents(spec.type)

    is TriggerNode.Group -> when (op) {
        TriggerNode.Op.ANY -> children.any { it.canStart(hasEvents, hasState) }

        TriggerNode.Op.ALL -> children.indices.any { i ->
            children[i].canStart(hasEvents, hasState) &&
                children.filterIndexed { j, _ -> j != i }.all { it.canHold(hasState) }
        }
    }
}

/**
 * Whether this tree can be asked for a state — the other half of [canStart].
 *
 * A group can be asked if all of its children can, whatever the operator: asking
 * "is (a or b) true now" is answerable exactly when both a and b are.
 */
fun TriggerNode.canHold(hasState: (String) -> Boolean): Boolean = when (this) {
    is TriggerNode.One -> hasState(spec.type)
    is TriggerNode.Group -> children.all { it.canHold(hasState) }
}

/**
 * The components in this tree that no installed factory knows.
 *
 * Reachable only through an imported file or a downgrade, and worth naming rather
 * than counting: "this rule needs a trigger this version does not have" is
 * actionable, "this rule is invalid" is not.
 */
fun TriggerNode.unknown(isKnown: (String) -> Boolean): List<ComponentSpec> =
    leaves().filterNot { isKnown(it.type) }

/**
 * The same tree with the node at [path] replaced by [replacement].
 *
 * Returns the tree unchanged if the path leads nowhere. Editing by path rather
 * than by mutation keeps the draft a value, which is what lets the editor's undo
 * be "keep the previous one" rather than a reverse operation per control.
 */
fun TriggerNode.replaceAt(path: NodePath, replacement: TriggerNode): TriggerNode {
    if (path.isEmpty()) return replacement
    val group = this as? TriggerNode.Group ?: return this
    val index = path.first()
    val child = group.children.getOrNull(index) ?: return this
    return group.copy(
        children = group.children.toMutableList().also {
            it[index] = child.replaceAt(path.drop(1), replacement)
        },
    )
}

/**
 * The same tree with the node at [path] removed.
 *
 * A group that loses its second-to-last child collapses into its remaining child,
 * because "all of: one thing" is a box drawn around nothing. A group that loses
 * its last child is removed in turn, up to the root — and removing the root
 * returns null, which the caller renders as the empty "choose a trigger" slot.
 */
fun TriggerNode.removeAt(path: NodePath): TriggerNode? {
    if (path.isEmpty()) return null
    val group = this as? TriggerNode.Group ?: return this
    val index = path.first()
    val child = group.children.getOrNull(index) ?: return this

    val remaining = if (path.size == 1) {
        group.children.filterIndexed { i, _ -> i != index }
    } else {
        val edited = child.removeAt(path.drop(1))
        if (edited == null) {
            group.children.filterIndexed { i, _ -> i != index }
        } else {
            group.children.toMutableList().also { it[index] = edited }
        }
    }

    return when (remaining.size) {
        0 -> null
        1 -> remaining.single()
        else -> group.copy(children = remaining)
    }
}

/**
 * The same tree with [addition] appended to the group at [path].
 *
 * If [path] names a leaf rather than a group, that leaf becomes a group of two:
 * adding a second trigger to a single one is how a group comes into existence
 * without the user having to choose a container first. [op] is used only in that
 * case, since an existing group already has one.
 */
fun TriggerNode.addAt(
    path: NodePath,
    addition: TriggerNode,
    op: TriggerNode.Op = TriggerNode.Op.ALL,
): TriggerNode {
    val target = at(path) ?: return this
    val grown = when (target) {
        is TriggerNode.Group -> target.copy(children = target.children + addition)
        is TriggerNode.One -> TriggerNode.Group(op, listOf(target, addition))
    }
    return replaceAt(path, grown)
}
