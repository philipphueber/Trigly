package app.phueber.trigly.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trigger tree's pure logic: [TriggerNode.holds], [TriggerNode.canStart],
 * [TriggerNode.canHold], [TriggerNode.leaves], [TriggerNode.leafPaths] and
 * [TriggerNode.unknown].
 *
 * Worth testing exhaustively for one reason: every mistake in here is silent. A
 * tree that wrongly holds fires a rule nobody asked for; a tree that wrongly
 * fails produces a rule that never fires and looks exactly like one whose moment
 * has not come. Neither shows up as an error anywhere, and neither is observable
 * by watching a phone for an afternoon.
 *
 * The state lookup being a parameter is what makes all of this JVM-testable: no
 * device, no Wi-Fi to toggle, and the awkward cases (unknown state, empty
 * branches, the leaf that just fired) reachable at all.
 */
class GateTest {

    private fun one(type: String) = TriggerNode.One(ComponentSpec(type, emptyMap()))

    private fun all(vararg children: TriggerNode) = TriggerNode.Group(TriggerNode.Op.ALL, children.toList())

    private fun any(vararg children: TriggerNode) = TriggerNode.Group(TriggerNode.Op.ANY, children.toList())

    /** A lookup from a plain map: absent means "cannot be asked". */
    private fun states(vararg pairs: Pair<String, Boolean?>): suspend (ComponentSpec) -> Boolean? {
        val byType = pairs.toMap()
        return { spec -> byType[spec.type] }
    }

    /**
     * No child of an ALL/ANY group ever sits at the empty path. Only a lone
     * `One` used as a whole tree's root does, since it has no group above it.
     * The group tests below use this as "nothing fired". The lone-leaf tests
     * just below need a path that cannot be *that* leaf's own instead, since
     * for a bare `One` the empty path names the leaf itself.
     */
    private val notFired: NodePath = emptyList()
    private val someOtherLeaf: NodePath = listOf(0)

    // --- one leaf ----------------------------------------------------------------

    @Test
    fun `a leaf that did not fire holds when its state is true`() = runTest {
        assertTrue(one("wifi_state").holds(someOtherLeaf, states("wifi_state" to true)))
    }

    @Test
    fun `a leaf that did not fire does not hold when its state is false`() = runTest {
        assertFalse(one("wifi_state").holds(someOtherLeaf, states("wifi_state" to false)))
    }

    @Test
    fun `an unknown state does not hold`() = runTest {
        // Null means "cannot be asked", not "no". Treating it as holding would
        // fire a rule on a guess. For an app acting unattended, that is the worse
        // of the two failures by a distance.
        assertFalse(one("sms_received").holds(someOtherLeaf, states("sms_received" to null)))
        assertFalse("a state nobody supplied is also unknown", one("wifi_state").holds(someOtherLeaf, states()))
    }

    @Test
    fun `the leaf that fired holds without being asked`() = runTest {
        // The single most important property in this file: a component whose
        // state cannot be read (null, or nothing supplied at all) still
        // counts as true when it is the leaf that just fired. Without this, a
        // momentary trigger (a tap, a notification) could never satisfy a
        // group on its own, because it has no state to be asked for.
        val leaf = one("tap")
        assertTrue(leaf.holds(emptyList(), states()))
        assertTrue(leaf.holds(emptyList(), states("tap" to null)))
        // Even a state that would say "false" is overridden by having fired.
        assertTrue(leaf.holds(emptyList(), states("tap" to false)))
    }

    // --- ALL and ANY ---------------------------------------------------------------

    @Test
    fun `ALL holds only when every child does`() = runTest {
        val tree = all(one("a"), one("b"))

        assertTrue(tree.holds(notFired, states("a" to true, "b" to true)))
        assertFalse(tree.holds(notFired, states("a" to true, "b" to false)))
        assertFalse(tree.holds(notFired, states("a" to false, "b" to true)))
    }

    @Test
    fun `ANY holds when one child does`() = runTest {
        val tree = any(one("a"), one("b"))

        assertTrue(tree.holds(notFired, states("a" to false, "b" to true)))
        assertTrue(tree.holds(notFired, states("a" to true, "b" to false)))
        assertFalse(tree.holds(notFired, states("a" to false, "b" to false)))
    }

    @Test
    fun `one unknown child sinks an ALL`() = runTest {
        val tree = all(one("wifi_state"), one("sms_received"))

        assertFalse(tree.holds(notFired, states("wifi_state" to true, "sms_received" to null)))
    }

    @Test
    fun `an unknown child does not sink an ANY that is otherwise satisfied`() = runTest {
        val tree = any(one("sms_received"), one("wifi_state"))

        assertTrue(tree.holds(notFired, states("sms_received" to null, "wifi_state" to true)))
    }

    @Test
    fun `in an ALL group, the fired leaf counts true even though its own state is unknown`() = runTest {
        // "the notification arrived AND it is night": the notification leaf
        // has no state at all, so if firing did not override the state read,
        // this group could never be satisfied by its own first leaf.
        val tree = all(one("notification"), one("time_window"))

        assertTrue(tree.holds(listOf(0), states("time_window" to true)))
        // Same states, but nothing fired. That contrast shows the true above
        // came from the path match, not a lucky default.
        assertFalse(tree.holds(notFired, states("time_window" to true)))
    }

    // --- grouping and nesting --------------------------------------------------------

    @Test
    fun `a nested group changes the meaning of the tree, same leaves`() = runTest {
        // A and (B or C): true with only C.
        val grouped = all(one("a"), any(one("b"), one("c")))
        assertTrue(grouped.holds(notFired, states("a" to true, "b" to false, "c" to true)))
        assertFalse(grouped.holds(notFired, states("a" to false, "b" to true, "c" to true)))

        // (A and B) or C: also true with only C, but for a different reason,
        // and false where the first was true. Flattening would silently pick
        // one reading.
        val other = any(all(one("a"), one("b")), one("c"))
        assertTrue(other.holds(notFired, states("a" to false, "b" to false, "c" to true)))
        assertFalse(other.holds(notFired, states("a" to true, "b" to false, "c" to false)))
    }

    @Test
    fun `nesting three deep, mixing ALL and ANY, evaluates by structure not depth`() = runTest {
        // a AND (b OR (c AND d)): ALL wrapping an ANY wrapping an ALL.
        val tree = all(one("a"), any(one("b"), all(one("c"), one("d"))))

        assertTrue(tree.holds(notFired, states("a" to true, "b" to false, "c" to true, "d" to true)))
        assertFalse(tree.holds(notFired, states("a" to true, "b" to false, "c" to true, "d" to false)))
        assertTrue(tree.holds(notFired, states("a" to true, "b" to true, "c" to false, "d" to false)))
        assertFalse(tree.holds(notFired, states("a" to false, "b" to true, "c" to true, "d" to true)))
    }

    // --- the empty cases, which only an imported file can produce ------------------

    @Test
    fun `ALL of nothing holds and ANY of nothing does not`() = runTest {
        // From what the words mean, not from convenience: nothing failed,
        // versus nothing satisfied it. Unreachable from the editor, reachable
        // from a file.
        assertTrue(all().holds(notFired, states()))
        assertFalse(any().holds(notFired, states()))
    }

    // --- short-circuiting is a promise, not an optimisation -------------------------

    @Test
    fun `ALL stops asking after the first failure`() = runTest {
        // A location check costs a GPS read. An ALL whose earlier child has
        // already failed must not pay for the rest.
        val asked = mutableListOf<String>()
        val tree = all(one("cheap"), one("expensive"))

        tree.holds(notFired) { spec ->
            asked += spec.type
            spec.type == "expensive"
        }

        assertEquals(listOf("cheap"), asked)
    }

    @Test
    fun `ANY stops asking after the first success`() = runTest {
        val asked = mutableListOf<String>()
        val tree = any(one("cheap"), one("expensive"))

        tree.holds(notFired) { spec ->
            asked += spec.type
            true
        }

        assertEquals(listOf("cheap"), asked)
    }

    // --- what the tree carries -------------------------------------------------------

    @Test
    fun `leaves lists every component in the tree, depth first`() {
        val tree = all(one("a"), any(one("b"), one("c")))

        assertEquals(listOf("a", "b", "c"), tree.leaves().map { it.type })
    }

    @Test
    fun `leafPaths pairs each leaf with its position`() {
        val tree = all(one("a"), any(one("b"), one("c")))

        assertEquals(
            listOf(listOf(0) to "a", listOf(1, 0) to "b", listOf(1, 1) to "c"),
            tree.leafPaths().map { (path, spec) -> path to spec.type },
        )
    }

    @Test
    fun `unknown names the leaves no installed factory knows`() {
        // The editor stops these being built. This catches the other way in:
        // an imported rule, or one saved by a newer version, naming a
        // component this build does not have.
        val tree = all(one("wifi_state"), any(one("ui_click")))

        val offenders = tree.unknown { type -> type == "wifi_state" }

        assertEquals(listOf("ui_click"), offenders.map { it.type })
    }

    @Test
    fun `a tree of known leaves has no unknown offenders`() {
        val tree = all(one("wifi_state"), one("screen_state"))

        assertTrue(tree.unknown { true }.isEmpty())
    }

    // --- canStart and canHold --------------------------------------------------------

    /** "edge-*" and "tap"/"notification" only ever fire; "time_window" only answers a state. */
    private val eventOnlyTypes = setOf("tap", "notification", "edge-a", "edge-b")
    private val stateOnlyTypes = setOf("time_window")
    private val bothTypes = setOf("bluetooth")

    private fun hasEvents(type: String) = type in eventOnlyTypes || type in bothTypes
    private fun hasState(type: String) = type in stateOnlyTypes || type in bothTypes

    @Test
    fun `a lone leaf can start when its component produces events`() {
        assertTrue(one("tap").canStart(::hasEvents, ::hasState))
    }

    @Test
    fun `a lone leaf cannot start when its component only ever answers a state`() {
        assertFalse(one("time_window").canStart(::hasEvents, ::hasState))
    }

    @Test
    fun `ANY can start if any child can`() {
        assertTrue(any(one("time_window"), one("tap")).canStart(::hasEvents, ::hasState))
    }

    @Test
    fun `ANY cannot start if no child can`() {
        assertFalse(any(one("time_window")).canStart(::hasEvents, ::hasState))
    }

    @Test
    fun `ALL can start with one edge and any number of levels`() {
        assertTrue(all(one("tap"), one("time_window")).canStart(::hasEvents, ::hasState))
    }

    @Test
    fun `ALL of two event-only components can never start`() {
        // Two edges describe two instants that never coincide: whichever one
        // fires, the other is asked for a state it does not have, answers
        // unknown, and the group fails. Forever, with no message on screen
        // to say why. This is the one mistake canStart exists to catch.
        assertFalse(all(one("edge-a"), one("edge-b")).canStart(::hasEvents, ::hasState))
    }

    @Test
    fun `ALL cannot start if the would-be other child cannot be asked either`() {
        // "tap" can start the group on its own, but "edge-a" has no state to
        // fall back on once "tap" is the one that fired. That is the same
        // failure as two bare edges, just with only one of them able to start
        // at all.
        assertFalse(all(one("tap"), one("edge-a")).canStart(::hasEvents, ::hasState))
    }

    @Test
    fun `the starting child of an ALL group may itself be a group`() {
        // ALL(ANY(tap, time_window), bluetooth): the ANY sub-group can start
        // (through "tap"), and "bluetooth" can be asked for its state. Same
        // shape as one edge and any number of levels, just with the edge
        // nested one level down.
        val tree = all(any(one("tap"), one("time_window")), one("bluetooth"))

        assertTrue(tree.canStart(::hasEvents, ::hasState))
    }

    /**
     * An empty `ANY` group beside a real edge, reachable in the editor by
     * picking "Any of these" next to an existing trigger and never filling it
     * in. `canHold` alone says this can start: an empty group is always
     * askable, it just always answers "no". See [TriggerNode.canEverHold]'s
     * own kdoc. That "no" is a hard-coded constant, not a runtime unknown, so
     * `ALL(tap, ANY())` can never actually fire, the same as `ALL` of two
     * bare edges. Found by a connected-suite failure where a rule shaped
     * exactly like this saved enabled, because this was wrongly `true`.
     */
    @Test
    fun `ALL beside an empty ANY group can never start`() {
        assertFalse(all(one("tap"), any()).canStart(::hasEvents, ::hasState))
    }

    /** The mirror image: the empty group is the one that would have to start it. */
    @Test
    fun `an empty ANY group offered as the starter cannot start it either`() {
        assertFalse(all(any(), one("time_window")).canStart(::hasEvents, ::hasState))
    }

    /**
     * An empty `ALL` group beside a real edge is not the same trap: "`ALL` of
     * nothing" holds unconditionally, so it never drags the outer `ALL` down.
     * The single edge is free to start the rule on its own, exactly as if the
     * empty group were not there.
     */
    @Test
    fun `ALL beside an empty ALL group can still start`() {
        assertTrue(all(one("tap"), all()).canStart(::hasEvents, ::hasState))
    }

    @Test
    fun `canEverHold is true for a leaf regardless of the tree's shape`() {
        assertTrue(one("time_window").canEverHold())
        assertTrue(one("tap").canEverHold())
    }

    @Test
    fun `canEverHold is false only for an empty ANY, never an empty ALL`() {
        assertFalse(any().canEverHold())
        assertTrue(all().canEverHold())
    }

    @Test
    fun `canEverHold propagates an unfillable ANY up through a nested ALL`() {
        // ALL(empty ANY, a real leaf) can never be true: one of its two
        // required halves is a hard-coded "no".
        assertFalse(all(any(), one("time_window")).canEverHold())
    }

    @Test
    fun `canEverHold survives an unfillable sibling inside a nested ANY`() {
        // ANY(empty ANY, a real leaf) can still be true through the leaf.
        assertTrue(any(any(), one("time_window")).canEverHold())
    }

    @Test
    fun `canHold requires every child answerable, whatever the operator`() {
        assertTrue(any(one("time_window"), one("bluetooth")).canHold(::hasState))
        // "tap" cannot be asked for a state at all, so the ALL cannot either.
        // Asking "is (a and b) true now" needs both a and b to be answerable.
        assertFalse(all(one("time_window"), one("tap")).canHold(::hasState))
    }

    // --- traced: the same evaluation, kept as a tree ------------------------------

    @Test
    fun `traced marks the leaf that said no in an ALL, and its sibling as yes`() = runTest {
        val tree = all(one("a"), one("b"))

        val trace = tree.traced(notFired, states("a" to true, "b" to false)) as TriggerTrace.Group

        assertFalse(trace.held!!)
        val (a, b) = trace.children.map { it as TriggerTrace.Leaf }
        assertEquals(LeafOutcome.YES, a.outcome)
        assertEquals(LeafOutcome.NO, b.outcome)
    }

    @Test
    fun `traced marks the leaf that carried an ANY, and its sibling as no`() = runTest {
        val tree = any(one("a"), one("b"))

        val trace = tree.traced(notFired, states("a" to false, "b" to true)) as TriggerTrace.Group

        assertTrue(trace.held!!)
        val (a, b) = trace.children.map { it as TriggerTrace.Leaf }
        assertEquals(LeafOutcome.NO, a.outcome)
        assertEquals(LeafOutcome.YES, b.outcome)
    }

    @Test
    fun `traced marks a short-circuited sibling as not consulted, never as no`() = runTest {
        // ALL(cheap, expensive): cheap fails, so expensive must never be asked.
        // The trace must say so, rather than reading as a second "no".
        val tree = all(one("cheap"), one("expensive"))

        val trace = tree.traced(notFired, states("cheap" to false, "expensive" to true)) as TriggerTrace.Group

        val (cheap, expensive) = trace.children.map { it as TriggerTrace.Leaf }
        assertEquals(LeafOutcome.NO, cheap.outcome)
        assertEquals(LeafOutcome.NOT_CONSULTED, expensive.outcome)
        // Not-consulted is not a false answer: it carries no opinion at all.
        assertEquals(null, expensive.held)
    }

    @Test
    fun `traced marks the whole unreached side of an ANY as not consulted`() = runTest {
        // ANY(cheap, expensive): cheap already holds, so the whole expensive
        // side (a group of its own) is never reached, not just its top leaf.
        val tree = any(one("cheap"), all(one("expensive"), one("very-expensive")))

        val trace = tree.traced(notFired, states("cheap" to true)) as TriggerTrace.Group

        val skippedGroup = trace.children[1] as TriggerTrace.Group
        assertEquals(null, skippedGroup.held)
        assertTrue(skippedGroup.children.all { (it as TriggerTrace.Leaf).outcome == LeafOutcome.NOT_CONSULTED })
    }

    @Test
    fun `traced marks the fired leaf as fired, not as yes`() = runTest {
        val tree = all(one("notification"), one("time_window"))

        val trace = tree.traced(listOf(0), states("time_window" to true)) as TriggerTrace.Group

        val (notification, timeWindow) = trace.children.map { it as TriggerTrace.Leaf }
        assertEquals(LeafOutcome.FIRED, notification.outcome)
        assertEquals(LeafOutcome.YES, timeWindow.outcome)
        assertTrue(trace.held!!)
    }

    @Test
    fun `traced marks a leaf nobody could read as unreadable, not as no`() = runTest {
        val tree = all(one("wifi_state"), one("sms_received"))

        val trace = tree.traced(notFired, states("wifi_state" to true, "sms_received" to null)) as TriggerTrace.Group

        val sms = trace.children[1] as TriggerTrace.Leaf
        assertEquals(LeafOutcome.UNREADABLE, sms.outcome)
        assertFalse(sms.held!!)
    }

    @Test
    fun `traced on a nested ALL of ANY reaches the same held answer as holds`() = runTest {
        val tree = all(one("a"), any(one("b"), one("c")))
        val lookup = states("a" to true, "b" to false, "c" to true)

        val trace = tree.traced(notFired, lookup)

        assertEquals(tree.holds(notFired, lookup), trace.held)
    }
}
