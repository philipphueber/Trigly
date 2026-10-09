package app.phueber.trigly.core

/**
 * One entry in a rule's action list: one action, or a block of branches that
 * run at the same time.
 *
 * ```
 * [toast, speak]                                   // two actions, in order
 * [toast, Parallel([[speak], [vibrate, delay]]), post_notification]
 *                                                  // toast, then speak and
 *                                                  // (vibrate, delay) at the
 *                                                  // same time, then the
 *                                                  // notification when both
 *                                                  // branches are finished
 * ```
 *
 * ### A tree with one kind of inner node
 *
 * The action list used to be flat. It is now a tree, and [Parallel] is the only
 * inner node. A [Parallel] block is a fork and a join: every branch starts when
 * the block starts, and the action after the block starts when every branch is
 * finished. It does not choose which actions run. Every action in every branch
 * runs on every firing, so this is not the branching inside a rule that
 * `docs/variables.md` section 14 and `docs/todo.md` R6 refuse. There is no
 * condition on a branch and there will not be one.
 *
 * ### The leaf is the spec itself
 *
 * On the trigger side a leaf is [TriggerNode.One], a wrapper around a
 * [ComponentSpec]. Here [ComponentSpec] is itself the leaf. A wrapper would
 * change nothing in what the tree can say, and it would change every place that
 * builds a rule with a plain list of actions, which is most of the code and
 * nearly every test. With the spec as the leaf, a flat `List<ComponentSpec>` is
 * already a valid action tree, so a rule from before this block existed is
 * the same value it always was. A reader that walks the list and expects only
 * specs does not compile any more, which is how every such reader was found.
 *
 * ### One level, by type
 *
 * A branch is a list of [ComponentSpec]s, not of [ActionNode]s, so a block
 * cannot hold a block. The bound is in the type rather than in a check, so
 * nothing has to enforce it at run time: the codec refuses a file that nests
 * one, and the editor offers no control to build one. The trigger tree nests
 * because "a and (b or c)" needs it. A fork inside a fork is a shape no rule
 * has asked for, and each level costs the editor a rail of width and the
 * engine a second join. `run_parallel`, which runs two whole rules at the same
 * time, is still there for a branch that needs a fork of its own.
 */
sealed interface ActionNode {

    /**
     * A fork and a join. Each list in [branches] runs in order, and all of the
     * lists run at the same time.
     *
     * No minimum or maximum on [branches] here. The editor builds a block with
     * two branches and collapses one that drops to one branch, but a file can
     * hold a block with one branch or none, and the engine reads both plainly:
     * one branch runs as if it were inline, and no branches runs nothing. The
     * same choice [TriggerNode.Group] makes for a group with one child or none.
     */
    data class Parallel(val branches: List<List<ComponentSpec>>) : ActionNode
}

/**
 * Where an action sits in a rule's action tree, as indices from the top.
 *
 * `[i]` is the entry at index `i` of [Rule.actions], an action or a block.
 * `[i, b]` is branch `b` of the block at `i`. `[i, b, j]` is action `j` of that
 * branch. The same scheme as [NodePath], for the same reason: an action has no
 * identity of its own, and two actions with the same settings are equal values.
 */
typealias ActionPath = List<Int>

/**
 * Every action in this tree, in the order the editor and the engine number
 * them: the top level in order, and inside a block, the first branch in order,
 * then the second, and so on.
 *
 * This order is a contract, for the reason [componentInstanceNames] gives. The
 * picker, save-time validation and the engine all number an action by its
 * position in this list, and they must agree.
 */
fun List<ActionNode>.leafActions(): List<ComponentSpec> = leafActionPaths().map { it.second }

/** [leafActions], each with the path it sits at. */
fun List<ActionNode>.leafActionPaths(): List<Pair<ActionPath, ComponentSpec>> =
    flatMapIndexed { index, node ->
        when (node) {
            is ComponentSpec -> listOf(listOf(index) to node)
            is ActionNode.Parallel -> node.branches.flatMapIndexed { branch, actions ->
                actions.mapIndexed { position, spec -> listOf(index, branch, position) to spec }
            }
        }
    }

/** The action at [path], or null if [path] names a block, a branch or nothing. */
fun List<ActionNode>.actionAt(path: ActionPath): ComponentSpec? = when (path.size) {
    1 -> getOrNull(path[0]) as? ComponentSpec
    3 -> (getOrNull(path[0]) as? ActionNode.Parallel)
        ?.branches?.getOrNull(path[1])?.getOrNull(path[2])

    else -> null
}

/**
 * The position of the action at [path] in [leafActions], or -1 if [path] does
 * not name an action. The bridge from the editor, which addresses an action by
 * path, to [ReadPoint.Action], which counts.
 */
fun List<ActionNode>.leafIndexOf(path: ActionPath): Int =
    leafActionPaths().indexOfFirst { it.first == path }

/**
 * The same tree with [transform] applied to every action in it, shape kept.
 * The action-side twin of [TriggerNode.mapSpecs].
 */
fun List<ActionNode>.mapActionSpecs(transform: (ComponentSpec) -> ComponentSpec): List<ActionNode> =
    map { node ->
        when (node) {
            is ComponentSpec -> transform(node)
            is ActionNode.Parallel -> ActionNode.Parallel(
                node.branches.map { branch -> branch.map(transform) }
            )
        }
    }

/** Whether this tree holds a [ActionNode.Parallel] block anywhere. */
fun List<ActionNode>.hasParallel(): Boolean = any { it is ActionNode.Parallel }

/**
 * Whether the action at position [earlier] in [leafActions] is always finished
 * before the action at position [later] starts.
 *
 * This is the one question that decides what an action can read from another
 * one in the same run: an `{{action.*}}` output and a `{{local.*}}` value both
 * exist only once the action that makes them has returned. In a flat list the
 * answer is "[earlier] comes first". A block adds one exception. Two actions in
 * different branches of the same block run at the same time, so neither is
 * finished before the other starts, whichever comes first in the list.
 *
 * Everything else follows from the join. An action before a block is finished
 * before every branch starts, and every action in a block is finished before
 * the action after the block starts.
 */
fun List<ActionNode>.finishesBefore(earlier: Int, later: Int): Boolean {
    if (earlier >= later) return false
    val paths = leafActionPaths()
    val first = paths.getOrNull(earlier)?.first ?: return false
    val second = paths.getOrNull(later)?.first ?: return false
    val sameBlock = first.size == 3 && second.size == 3 && first[0] == second[0]
    return !(sameBlock && first[1] != second[1])
}
