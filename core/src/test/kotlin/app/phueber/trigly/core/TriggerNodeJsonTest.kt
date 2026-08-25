package app.phueber.trigly.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RuleJson]'s encoding of [TriggerNode] specifically: the v3 leaf/group shape,
 * the v1 shape it stays byte-compatible with, and the v2 edges-plus-conditions
 * shape it must still read. Runs on the JVM against the real `org.json` — see
 * `RuleJsonTest` for why.
 *
 * The promise this exists to check: a rule that uses no group is written
 * exactly as a pre-gate build would write it, and every file any released
 * version ever wrote still imports. Get either wrong and it is someone's rules,
 * silently, on the phone they just switched to.
 */
class TriggerNodeJsonTest {

    private fun spec(type: String, config: Map<String, String> = emptyMap()) = ComponentSpec(type, config)

    private val plainRule = Rule(
        id = "rule-1",
        name = "Plain",
        trigger = spec("power_connection", mapOf("state" to "connected")),
        actions = listOf(spec("speak", mapOf("text" to "hi"))),
    )

    // --- version 1: the backward-compatibility promise -------------------------

    @Test
    fun `a plain single-trigger rule is written in the version 1 shape`() {
        val json = JSONObject(RuleJson.encode(plainRule))

        assertEquals(1, json.getInt("version"))
        val ruleJson = json.getJSONArray("rules").getJSONObject(0)
        assertTrue("expected a 'trigger' object", ruleJson.has("trigger"))
        val trigger = ruleJson.getJSONObject("trigger")
        assertFalse("a leaf must not carry an 'op' discriminator", trigger.has("op"))
        assertEquals("power_connection", trigger.getString("type"))
    }

    @Test
    fun `a plain single-trigger rule round trips`() {
        assertEquals(plainRule, RuleJson.decode(RuleJson.encode(plainRule)).single())
    }

    // --- version 3: a group forces the new shape --------------------------------

    private val groupedRule = plainRule.copy(
        trigger = TriggerNode.Group(
            TriggerNode.Op.ANY,
            listOf(
                TriggerNode.One(spec("power_connection", mapOf("state" to "connected"))),
                TriggerNode.One(spec("headset_plug", mapOf("state" to "plugged"))),
            ),
        ),
    )

    @Test
    fun `a rule with a group is written as version 3 with an op and children`() {
        val json = JSONObject(RuleJson.encode(groupedRule))

        assertEquals(3, json.getInt("version"))
        val trigger = json.getJSONArray("rules").getJSONObject(0).getJSONObject("trigger")
        assertEquals("any", trigger.getString("op"))
        assertEquals(2, trigger.getJSONArray("children").length())
    }

    @Test
    fun `a rule with a group round trips`() {
        assertEquals(groupedRule, RuleJson.decode(RuleJson.encode(groupedRule)).single())
    }

    @Test
    fun `nesting at least three groups deep round trips`() {
        val deep = TriggerNode.Group(
            TriggerNode.Op.ALL,
            listOf(
                TriggerNode.Group(
                    TriggerNode.Op.ANY,
                    listOf(
                        TriggerNode.Group(
                            TriggerNode.Op.ALL,
                            listOf(
                                TriggerNode.One(spec("time_window", mapOf("from" to "22:00", "to" to "07:00"))),
                                TriggerNode.One(spec("wifi_state", mapOf("state" to "connected"))),
                            ),
                        ),
                        TriggerNode.One(spec("bluetooth_connected")),
                    ),
                ),
                TriggerNode.One(spec("power_connection", mapOf("state" to "connected"))),
            ),
        )
        val rule = plainRule.copy(trigger = deep)

        val decoded = RuleJson.decode(RuleJson.encode(rule)).single()

        assertEquals(rule, decoded)
        assertEquals(3, JSONObject(RuleJson.encode(rule)).getInt("version"))
    }

    @Test
    fun `a group of one child survives a round trip`() {
        // The editor can never build this, but an imported file can hold one —
        // e.g. after removing a sibling from a group elsewhere in the tree.
        val oneChildGroup = TriggerNode.Group(
            TriggerNode.Op.ANY,
            listOf(TriggerNode.One(spec("screen_state", mapOf("state" to "on")))),
        )

        assertEquals(oneChildGroup, RuleJson.decodeNode(RuleJson.encodeNode(oneChildGroup)))
    }

    @Test
    fun `encodeNode and decodeNode round trip a leaf for the storage column`() {
        val leaf = TriggerNode.One(spec("power_connection", mapOf("state" to "connected")))

        assertEquals(leaf, RuleJson.decodeNode(RuleJson.encodeNode(leaf)))
    }

    // --- the version a whole file is written as is the max any rule needs -------

    @Test
    fun `a mixed set of rules is written as version 3, the version the file needs`() {
        val json = JSONObject(RuleJson.encode(listOf(plainRule, groupedRule)))

        assertEquals(3, json.getInt("version"))
        // And the plain rule keeps its old leaf shape within that file — only
        // the rule that actually needs a group gets the new shape.
        val plainInFile = json.getJSONArray("rules").getJSONObject(0).getJSONObject("trigger")
        assertFalse(plainInFile.has("op"))
        assertEquals("power_connection", plainInFile.getString("type"))
    }

    // --- reading an old export: version 1 ---------------------------------------

    @Test
    fun `decoding a hand-written v1 document reads the trigger as a plain leaf`() {
        // Exactly what a 0.0.1-0.0.3 export looked like.
        val v1Export = """
            {"version":1,"rules":[{
              "id":"rule-9","name":"Old export","enabled":true,
              "trigger":{"type":"power_connection","config":{"state":"connected"}},
              "actions":[{"type":"speak","config":{"text":"hi"}}]
            }]}
        """.trimIndent()

        val decoded = RuleJson.decode(v1Export).single()

        assertEquals(
            TriggerNode.One(spec("power_connection", mapOf("state" to "connected"))),
            decoded.trigger,
        )
    }

    // --- reading an old export: version 2 ---------------------------------------

    @Test
    fun `decoding a hand-written v2 document with several edges and no conditions maps to an ANY group`() {
        val v2Export = """
            {"version":2,"rules":[{
              "name":"Several edges",
              "triggers":[
                {"type":"power_connection","config":{"state":"connected"}},
                {"type":"headset_plug","config":{"state":"plugged"}}
              ],
              "actions":[]
            }]}
        """.trimIndent()

        val decoded = RuleJson.decode(v2Export).single()

        assertEquals(
            TriggerNode.Group(
                TriggerNode.Op.ANY,
                listOf(
                    TriggerNode.One(spec("power_connection", mapOf("state" to "connected"))),
                    TriggerNode.One(spec("headset_plug", mapOf("state" to "plugged"))),
                ),
            ),
            decoded.trigger,
        )
    }

    @Test
    fun `decoding a hand-written v2 document with a single edge and no conditions maps to a plain leaf`() {
        val v2Export = """
            {"version":2,"rules":[{
              "name":"One edge",
              "trigger":{"type":"power_connection","config":{"state":"connected"}},
              "actions":[]
            }]}
        """.trimIndent()

        val decoded = RuleJson.decode(v2Export).single()

        assertEquals(TriggerNode.One(spec("power_connection", mapOf("state" to "connected"))), decoded.trigger)
    }

    @Test
    fun `decoding a hand-written v2 document with edges and conditions ANDs the two halves`() {
        // The old model: the rule fires when any edge fires AND every condition
        // holds. That is Group(ALL, [edgesNode, conditionsNode]).
        val v2Export = """
            {"version":2,"rules":[{
              "name":"Edges and conditions",
              "triggers":[
                {"type":"power_connection","config":{"state":"connected"}},
                {"type":"headset_plug","config":{"state":"plugged"}}
              ],
              "conditions":{
                "node":"all",
                "children":[
                  {"node":"check","type":"time_window","config":{"from":"22:00","to":"07:00"}},
                  {
                    "node":"any",
                    "children":[
                      {"node":"check","type":"wifi_state","config":{"state":"connected"}},
                      {"node":"check","type":"bluetooth_connected","config":{}}
                    ]
                  }
                ]
              },
              "actions":[]
            }]}
        """.trimIndent()

        val decoded = RuleJson.decode(v2Export).single()

        val expectedEdges = TriggerNode.Group(
            TriggerNode.Op.ANY,
            listOf(
                TriggerNode.One(spec("power_connection", mapOf("state" to "connected"))),
                TriggerNode.One(spec("headset_plug", mapOf("state" to "plugged"))),
            ),
        )
        val expectedConditions = TriggerNode.Group(
            TriggerNode.Op.ALL,
            listOf(
                TriggerNode.One(spec("time_window", mapOf("from" to "22:00", "to" to "07:00"))),
                TriggerNode.Group(
                    TriggerNode.Op.ANY,
                    listOf(
                        TriggerNode.One(spec("wifi_state", mapOf("state" to "connected"))),
                        TriggerNode.One(spec("bluetooth_connected")),
                    ),
                ),
            ),
        )
        assertEquals(
            TriggerNode.Group(TriggerNode.Op.ALL, listOf(expectedEdges, expectedConditions)),
            decoded.trigger,
        )
    }

    @Test
    fun `an empty triggers array in a v2 document is rejected`() {
        // A gate the editor could never build, reachable only from a hand-edited
        // or downgraded-then-upgraded file — and a trigger with no edge can
        // never fire, so this must fail the import rather than produce a dead
        // rule.
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

    @Test
    fun `an unknown v2 condition node kind is rejected, not skipped, and the message names it`() {
        // Dropping the offending node (an 'all' branch, say) instead of
        // rejecting it would make the rule fire in cases its author excluded —
        // the exact failure this refusal exists to prevent.
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

    // --- refusing a file this build cannot understand ---------------------------

    @Test
    fun `a v4 document is refused with a message naming both versions`() {
        val v4Export = """{"version":4,"rules":[]}"""

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode(v4Export)
        }
        assertTrue(error.message.orEmpty().contains("newer version"))
        assertTrue(error.message.orEmpty().contains("4"))
        assertTrue(error.message.orEmpty().contains(RuleJson.VERSION.toString()))
    }

    // --- malformed v3 nodes -------------------------------------------------------

    @Test
    fun `a v3 group missing children fails with a message naming the group`() {
        val badGroup = """
            {"version":3,"rules":[{
              "name":"Broken group",
              "trigger":{"op":"all"},
              "actions":[]
            }]}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode(badGroup)
        }
        assertTrue(
            "should say a group has no children, was: ${error.message}",
            error.message.orEmpty().contains("no children"),
        )
        assertTrue(
            "should name the rule, was: ${error.message}",
            error.message.orEmpty().contains("Broken group"),
        )
    }

    @Test
    fun `a v3 group with an empty children list is refused`() {
        // Distinct from the missing-children case above, and the reason it is
        // tested separately: an empty array is well-formed JSON and decodes to a
        // group of nothing, which the model permits on purpose. What must not
        // happen is that arriving from a file, because "any of nothing" is a rule
        // that can never start — the failure this design exists to prevent.
        val emptyGroup = """
            {"version":3,"rules":[{
              "name":"Empty group",
              "trigger":{"op":"any","children":[]},
              "actions":[]
            }]}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode(emptyGroup)
        }
        assertTrue(
            "should say the group is empty, was: ${error.message}",
            error.message.orEmpty().contains("no triggers in it"),
        )
        assertTrue(
            "should name the rule, was: ${error.message}",
            error.message.orEmpty().contains("Empty group"),
        )
    }

    @Test
    fun `a v3 child that is neither a leaf nor a group fails naming its position`() {
        val badChild = """
            {"version":3,"rules":[{
              "name":"Broken child",
              "trigger":{"op":"any","children":[
                {"type":"screen_state","config":{}},
                {}
              ]},
              "actions":[]
            }]}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode(badChild)
        }
        assertTrue(
            "should say no type was given, was: ${error.message}",
            error.message.orEmpty().contains("No type given"),
        )
        assertTrue(
            "should name which child, was: ${error.message}",
            error.message.orEmpty().contains("child 2"),
        )
    }

    @Test
    fun `decodeNode fails for a malformed group naming where`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decodeNode("""{"op":"all"}""")
        }
        assertTrue(error.message.orEmpty().contains("no children"))
    }
}
