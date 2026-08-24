package app.phueber.trigly.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's condition logic.
 *
 * Worth testing exhaustively for one reason: every mistake in here is silent. A
 * tree that wrongly holds fires a rule nobody asked for; a tree that wrongly
 * fails produces a rule that never fires and looks exactly like one whose moment
 * has not come. Neither shows up as an error anywhere, and neither is observable
 * by watching a phone for an afternoon.
 *
 * The state lookup being a parameter is what makes all of this JVM-testable — no
 * device, no Wi-Fi to toggle, and the awkward cases (unknown state, empty
 * branches) reachable at all.
 */
class GateTest {

    private fun check(type: String) = ConditionNode.Check(ComponentSpec(type, emptyMap()))

    /** A lookup from a plain map: absent means "cannot be asked". */
    private fun states(vararg pairs: Pair<String, Boolean?>): suspend (ComponentSpec) -> Boolean? {
        val byType = pairs.toMap()
        return { spec -> byType[spec.type] }
    }

    // --- one check -------------------------------------------------------------

    @Test
    fun `a check holds when its state is true`() = runTest {
        assertTrue(check("wifi_state").holds(states("wifi_state" to true)))
    }

    @Test
    fun `a check does not hold when its state is false`() = runTest {
        assertFalse(check("wifi_state").holds(states("wifi_state" to false)))
    }

    @Test
    fun `an unknown state does not hold`() = runTest {
        // Null means "cannot be asked", not "no". Treating it as holding would
        // fire a rule on a guess — for an app acting unattended, the worse of the
        // two failures by a distance.
        assertFalse(check("sms_received").holds(states("sms_received" to null)))
        assertFalse("a state nobody supplied is also unknown", check("wifi_state").holds(states()))
    }

    // --- all and any -----------------------------------------------------------

    @Test
    fun `all holds only when every child does`() = runTest {
        val tree = ConditionNode.All(listOf(check("a"), check("b")))

        assertTrue(tree.holds(states("a" to true, "b" to true)))
        assertFalse(tree.holds(states("a" to true, "b" to false)))
        assertFalse(tree.holds(states("a" to false, "b" to true)))
    }

    @Test
    fun `any holds when one child does`() = runTest {
        val tree = ConditionNode.Any(listOf(check("a"), check("b")))

        assertTrue(tree.holds(states("a" to false, "b" to true)))
        assertTrue(tree.holds(states("a" to true, "b" to false)))
        assertFalse(tree.holds(states("a" to false, "b" to false)))
    }

    @Test
    fun `one unknown child sinks an all`() = runTest {
        val tree = ConditionNode.All(listOf(check("wifi_state"), check("sms_received")))

        assertFalse(tree.holds(states("wifi_state" to true, "sms_received" to null)))
    }

    @Test
    fun `an unknown child does not sink an any that is otherwise satisfied`() = runTest {
        val tree = ConditionNode.Any(listOf(check("sms_received"), check("wifi_state")))

        assertTrue(tree.holds(states("sms_received" to null, "wifi_state" to true)))
    }

    // --- grouping --------------------------------------------------------------

    @Test
    fun `a nested group is the sub-gate, and grouping changes the meaning`() = runTest {
        // A and (B or C) — true with only C.
        val grouped = ConditionNode.All(
            listOf(check("a"), ConditionNode.Any(listOf(check("b"), check("c")))),
        )
        assertTrue(grouped.holds(states("a" to true, "b" to false, "c" to true)))
        assertFalse(grouped.holds(states("a" to false, "b" to true, "c" to true)))

        // (A and B) or C — also true with only C, but for a different reason, and
        // false where the first was true. Flattening would silently pick one.
        val other = ConditionNode.Any(
            listOf(ConditionNode.All(listOf(check("a"), check("b"))), check("c")),
        )
        assertTrue(other.holds(states("a" to false, "b" to false, "c" to true)))
        assertFalse(other.holds(states("a" to true, "b" to false, "c" to false)))
    }

    @Test
    fun `nesting goes as deep as it is written`() = runTest {
        val deep = ConditionNode.All(
            listOf(
                check("a"),
                ConditionNode.Any(
                    listOf(
                        check("b"),
                        ConditionNode.All(listOf(check("c"), check("d"))),
                    ),
                ),
            ),
        )

        assertTrue(deep.holds(states("a" to true, "b" to false, "c" to true, "d" to true)))
        assertFalse(deep.holds(states("a" to true, "b" to false, "c" to true, "d" to false)))
    }

    // --- the empty cases, which only an imported file can produce --------------

    @Test
    fun `all of nothing holds and any of nothing does not`() = runTest {
        // From what the words mean, not from convenience: nothing failed, versus
        // nothing satisfied it. Unreachable from the editor, reachable from a file.
        assertTrue(ConditionNode.All(emptyList()).holds(states()))
        assertFalse(ConditionNode.Any(emptyList()).holds(states()))
    }

    // --- short-circuiting is a promise, not an optimisation ---------------------

    @Test
    fun `all stops asking after the first failure`() = runTest {
        // A location check costs a GPS read. An `All` whose earlier child has
        // already failed must not pay for the rest.
        val asked = mutableListOf<String>()
        val tree = ConditionNode.All(listOf(check("cheap"), check("expensive")))

        tree.holds { spec ->
            asked += spec.type
            spec.type == "expensive"
        }

        assertEquals(listOf("cheap"), asked)
    }

    @Test
    fun `any stops asking after the first success`() = runTest {
        val asked = mutableListOf<String>()
        val tree = ConditionNode.Any(listOf(check("cheap"), check("expensive")))

        tree.holds { spec ->
            asked += spec.type
            true
        }

        assertEquals(listOf("cheap"), asked)
    }

    // --- what the gate carries -------------------------------------------------

    @Test
    fun `a gate with no conditions is just a trigger`() {
        val gate = Gate(trigger = ComponentSpec("power_connection", mapOf("state" to "connected")))

        assertEquals(null, gate.conditions)
        assertEquals(1, gate.triggers.size)
        assertFalse(gate.hasSeveralTriggers)
    }

    @Test
    fun `the first level can be an OR of several edges`() {
        // "when the charger is plugged in *or* the headset goes in" — several
        // edges, any of which fires. The single-trigger case needs no wrapper,
        // which is why this is a list rather than a mandatory Any node.
        val gate = Gate(
            triggers = listOf(
                ComponentSpec("power_connection", mapOf("state" to "connected")),
                ComponentSpec("headset_plug", mapOf("state" to "plugged")),
            ),
        )

        assertEquals(2, gate.triggers.size)
        assertTrue(gate.hasSeveralTriggers)
    }

    @Test
    fun `several edges compose with conditions`() {
        // The shape the whole design is for: any of these edges, gated on states.
        val gate = Gate(
            triggers = listOf(
                ComponentSpec("power_connection", mapOf("state" to "connected")),
                ComponentSpec("bluetooth_connected", emptyMap()),
            ),
            conditions = ConditionNode.All(listOf(check("time_window"), check("location_here"))),
        )

        assertTrue(gate.hasSeveralTriggers)
        assertEquals(listOf("time_window", "location_here"), gate.conditions!!.checks().map { it.type })
    }

    @Test
    fun `a gate with no trigger is refused where it is built`() {
        // Unreachable from the editor, reachable from an imported file. Refused
        // here rather than diagnosed later as a rule that mysteriously does
        // nothing.
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            Gate(triggers = emptyList())
        }
        assertTrue(thrown.message.orEmpty().contains("at least one trigger"))
    }

    @Test
    fun `checks lists every spec in the tree, depth first`() {
        val tree = ConditionNode.All(
            listOf(check("a"), ConditionNode.Any(listOf(check("b"), check("c")))),
        )

        assertEquals(listOf("a", "b", "c"), tree.checks().map { it.type })
    }

    @Test
    fun `unaskable names the checks that cannot answer`() {
        // The editor stops these being built; this catches the other way in — an
        // imported rule, or one saved by a newer version, naming a component that
        // cannot be asked. A boolean would leave the caller unable to say which.
        val tree = ConditionNode.All(
            listOf(check("wifi_state"), ConditionNode.Any(listOf(check("ui_click")))),
        )

        val offenders = tree.unaskable { type -> type == "wifi_state" }

        assertEquals(listOf("ui_click"), offenders.map { it.type })
    }

    @Test
    fun `a tree of askable checks has no offenders`() {
        val tree = ConditionNode.All(listOf(check("wifi_state"), check("screen_state")))

        assertTrue(tree.unaskable { true }.isEmpty())
    }
}
