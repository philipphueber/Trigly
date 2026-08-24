package app.phueber.trigly.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's export format: [Gate] added a `triggers` list and a `conditions`
 * tree to what `RuleJsonTest` already covers for a bare trigger, and both are a
 * version bump that must not break an old file or write a needlessly new-shaped
 * one. Runs on the JVM against the real `org.json` — see `RuleJsonTest` for why.
 *
 * The promise this exists to check: a rule set that uses none of the gate
 * features is written exactly as a pre-gate build would write it, so it stays
 * importable by that build. Get the "what version does this file need" logic
 * wrong and every ordinary user's export silently stops being backward
 * compatible — nothing on screen would say so.
 */
class GateJsonTest {

    private fun spec(type: String, config: Map<String, String> = emptyMap()) = ComponentSpec(type, config)

    private val plainRule = Rule(
        id = "rule-1",
        name = "Plain",
        trigger = spec("power_connection", mapOf("state" to "connected")),
        actions = listOf(spec("speak", mapOf("text" to "hi"))),
    )

    // --- version 1: the backward-compatibility promise -------------------------

    @Test
    fun `a single-trigger rule with no conditions is written in the version 1 shape`() {
        val json = JSONObject(RuleJson.encode(plainRule))

        assertEquals(1, json.getInt("version"))
        val ruleJson = json.getJSONArray("rules").getJSONObject(0)
        assertTrue("expected a 'trigger' object", ruleJson.has("trigger"))
        assertFalse("must not write 'triggers' for the single-edge case", ruleJson.has("triggers"))
        assertFalse("no conditions means no 'conditions' key", ruleJson.has("conditions"))
    }

    @Test
    fun `a single-trigger rule with no conditions round trips`() {
        assertEquals(plainRule, RuleJson.decode(RuleJson.encode(plainRule)).single())
    }

    // --- version 2: several edges -----------------------------------------------

    private val severalEdgesRule = plainRule.copy(
        gate = Gate(
            triggers = listOf(
                spec("power_connection", mapOf("state" to "connected")),
                spec("headset_plug", mapOf("state" to "plugged")),
            ),
        ),
    )

    @Test
    fun `a rule with several edges is written as version 2 with a triggers array`() {
        val json = JSONObject(RuleJson.encode(severalEdgesRule))

        assertEquals(2, json.getInt("version"))
        val ruleJson = json.getJSONArray("rules").getJSONObject(0)
        assertFalse("the single-trigger shape must not also be written", ruleJson.has("trigger"))
        assertEquals(2, ruleJson.getJSONArray("triggers").length())
    }

    @Test
    fun `a rule with several edges round trips`() {
        assertEquals(severalEdgesRule, RuleJson.decode(RuleJson.encode(severalEdgesRule)).single())
    }

    // --- version 2: conditions --------------------------------------------------

    /** All(check, Any(check, check)) — nesting, not just one bare check. */
    private val nestedConditions = ConditionNode.All(
        listOf(
            ConditionNode.Check(spec("time_window", mapOf("from" to "22:00", "to" to "07:00"))),
            ConditionNode.Any(
                listOf(
                    ConditionNode.Check(spec("wifi_state", mapOf("state" to "connected"))),
                    ConditionNode.Check(spec("bluetooth_connected")),
                ),
            ),
        ),
    )

    private val conditionedRule = plainRule.copy(gate = Gate(plainRule.trigger, nestedConditions))

    @Test
    fun `a rule with conditions is written as version 2 with a conditions tree`() {
        val json = JSONObject(RuleJson.encode(conditionedRule))

        assertEquals(2, json.getInt("version"))
        val conditions = json.getJSONArray("rules").getJSONObject(0).getJSONObject("conditions")
        assertEquals("all", conditions.getString("node"))
        val children = conditions.getJSONArray("children")
        assertEquals(2, children.length())
        // The second child is the nested Any, not flattened into the All.
        assertEquals("any", children.getJSONObject(1).getString("node"))
    }

    @Test
    fun `a rule with conditions round trips, nesting included`() {
        val decoded = RuleJson.decode(RuleJson.encode(conditionedRule)).single()

        assertEquals(conditionedRule, decoded)
        // Depth-first order of the nested tree specifically, not merely equal by
        // coincidence of the two trees having the same checks.
        assertEquals(
            listOf("time_window", "wifi_state", "bluetooth_connected"),
            decoded.gate.conditions!!.checks().map { it.type },
        )
    }

    // --- the version a whole file is written as is the max any rule needs -------

    @Test
    fun `a mixed set of rules is written as version 2, the version the file needs`() {
        val json = JSONObject(RuleJson.encode(listOf(plainRule, conditionedRule)))

        assertEquals(2, json.getInt("version"))
        // And the plain rule keeps its old shape within that file — only the
        // rule that actually needs the new shape gets it.
        val plainInFile = json.getJSONArray("rules").getJSONObject(0)
        assertTrue(plainInFile.has("trigger"))
        assertFalse(plainInFile.has("conditions"))
    }

    // --- reading an old export ----------------------------------------------------

    @Test
    fun `decoding accepts the old 'trigger' shape written by a pre-gate build`() {
        // Exactly what a 0.0.3 export looked like, written by hand rather than
        // produced by the encoder under test — the file format's actual promise
        // is about files like this, not about round-tripping its own output.
        val preGateExport = """
            {"version":1,"rules":[{
              "id":"rule-9","name":"Old export","enabled":true,
              "trigger":{"type":"power_connection","config":{"state":"connected"}},
              "actions":[{"type":"speak","config":{"text":"hi"}}]
            }]}
        """.trimIndent()

        val decoded = RuleJson.decode(preGateExport).single()

        assertEquals(1, decoded.gate.triggers.size)
        assertEquals("power_connection", decoded.trigger.type)
        assertNull(decoded.gate.conditions)
    }

    // --- rejecting rather than silently misreading a file ------------------------

    @Test
    fun `an unknown condition node kind is rejected, not skipped, and the message names it`() {
        // Dropping the offending node (an 'All' branch, say) instead of rejecting
        // it would make the rule fire in cases its author excluded — the exact
        // failure this refusal exists to prevent.
        val badConditions = """
            {"version":2,"rules":[{
              "name":"Bad conditions",
              "trigger":{"type":"screen_state","config":{}},
              "conditions":{"node":"nope","type":"x","config":{}},
              "actions":[]
            }]}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode(badConditions)
        }
        assertTrue(
            "message should name the offending kind, was: ${error.message}",
            error.message!!.contains("nope"),
        )
    }

    @Test
    fun `an empty triggers array is rejected`() {
        // A gate the editor could never build, reachable only from a hand-edited
        // or downgraded-then-upgraded file — and a gate with no edge can never
        // fire, so this must fail the import rather than produce a dead rule.
        val emptyTriggers = """
            {"version":2,"rules":[{
              "name":"No edges","triggers":[],"actions":[]
            }]}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode(emptyTriggers)
        }
        assertTrue(error.message.orEmpty().contains("empty trigger"))
    }
}
