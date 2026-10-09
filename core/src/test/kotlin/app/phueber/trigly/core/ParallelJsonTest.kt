package app.phueber.trigly.core

import app.phueber.trigly.core.storage.ComponentEntity
import app.phueber.trigly.core.storage.ComponentRole
import app.phueber.trigly.core.storage.RuleEntity
import app.phueber.trigly.core.storage.RuleWithComponents
import app.phueber.trigly.core.storage.toComponentEntities
import app.phueber.trigly.core.storage.toEntity
import app.phueber.trigly.core.storage.toRuleOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parallel block on disk: in an export file through [RuleJson], and in the
 * database row through `RuleEntity.actionsJson`.
 *
 * Three promises. A file or a row from before the block existed reads exactly
 * as it always did. A file with a block in it says version 4, so a build that
 * cannot read it refuses the whole file instead of running the rule without
 * the block. And a block inside a block is refused by name, never skipped.
 */
class ParallelJsonTest {

    private val parallelRule = Rule(
        id = "r1",
        name = "Both at once",
        trigger = TriggerNode.One(ComponentSpec("screen_state", mapOf("state" to "on"))),
        actions = listOf(
            ComponentSpec("toast", mapOf("text" to "start")),
            ActionNode.Parallel(
                listOf(
                    listOf(ComponentSpec("speak", mapOf("text" to "hi")), ComponentSpec("vibrate")),
                    listOf(ComponentSpec("flashlight")),
                )
            ),
            ComponentSpec("toast", mapOf("text" to "done")),
        ),
    )

    private val flatRule = Rule(
        id = "r2",
        name = "Flat",
        trigger = ComponentSpec("screen_state"),
        actions = listOf(ComponentSpec("toast", mapOf("text" to "x"))),
    )

    // --- the export file --------------------------------------------------------

    @Test
    fun `a rule with a parallel block round trips through a file`() {
        assertEquals(listOf(parallelRule), RuleJson.decode(RuleJson.encode(parallelRule)))
    }

    @Test
    fun `a file with a parallel block is written as version 4`() {
        val written = JSONObject(RuleJson.encode(listOf(flatRule, parallelRule)))
        assertEquals(4, written.getInt("version"))
    }

    @Test
    fun `a file with no parallel block is written as it was before the block existed`() {
        assertEquals(1, JSONObject(RuleJson.encode(flatRule)).getInt("version"))
        val grouped = flatRule.copy(
            trigger = TriggerNode.Group(TriggerNode.Op.ANY, listOf(TriggerNode.One(ComponentSpec("a")))),
        )
        assertEquals(3, JSONObject(RuleJson.encode(grouped)).getInt("version"))
    }

    @Test
    fun `a plain action is written as the same spec object every older version wrote`() {
        val action = JSONObject(RuleJson.encode(parallelRule))
            .getJSONArray("rules").getJSONObject(0)
            .getJSONArray("actions").getJSONObject(0)
        assertEquals(setOf("type", "config"), action.keys().asSequence().toSet())
    }

    @Test
    fun `a version 3 file with a flat action list still loads unchanged`() {
        val v3 = """
            {"version":3,"rules":[{
              "name":"Old",
              "trigger":{"op":"all","children":[{"type":"a","config":{}}]},
              "actions":[{"type":"toast","config":{"text":"x"}},{"type":"speak","config":{}}]
            }]}
        """.trimIndent()

        val actions = RuleJson.decode(v3).single().actions
        assertEquals(listOf(ComponentSpec("toast", mapOf("text" to "x")), ComponentSpec("speak")), actions)
    }

    @Test
    fun `a block inside a branch is refused by name, not skipped`() {
        val nested = """
            {"version":4,"rules":[{
              "name":"Nested",
              "trigger":{"type":"a","config":{}},
              "actions":[{"op":"parallel","branches":[
                [{"op":"parallel","branches":[[{"type":"toast","config":{}}]]}],
                []
              ]}]
            }]}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) { RuleJson.decode(nested) }
        assertTrue(error.message, error.message.orEmpty().contains("holds another block"))
        assertTrue(error.message, error.message.orEmpty().contains("Nested"))
    }

    @Test
    fun `an action block of a kind this build does not know is refused`() {
        val unknown = """
            {"version":4,"rules":[{
              "name":"Later",
              "trigger":{"type":"a","config":{}},
              "actions":[{"op":"choose","branches":[]}]
            }]}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) { RuleJson.decode(unknown) }
        assertTrue(error.message, error.message.orEmpty().contains("choose"))
    }

    @Test
    fun `a parallel block with no branches list is refused`() {
        val broken = """
            {"version":4,"rules":[{
              "name":"Broken",
              "trigger":{"type":"a","config":{}},
              "actions":[{"op":"parallel"}]
            }]}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { RuleJson.decode(broken) }
    }

    // --- the database row -------------------------------------------------------

    private fun Rule.roundTrip(): Rule? =
        RuleWithComponents(toEntity(position = 0), toComponentEntities()).toRuleOrNull()

    @Test
    fun `a rule with a parallel block survives the entity round trip`() {
        assertEquals(parallelRule, parallelRule.roundTrip())
    }

    @Test
    fun `a saved rule writes its actions to the column and no component rows`() {
        assertTrue(parallelRule.toComponentEntities().isEmpty())
        assertEquals(parallelRule.actions, RuleJson.decodeActions(parallelRule.toEntity(0).actionsJson!!))
    }

    @Test
    fun `a row from before the column existed reads its actions from the component rows`() {
        val legacy = RuleWithComponents(
            RuleEntity(
                id = "old",
                name = "Old",
                enabled = true,
                position = 0,
                triggerJson = """{"type":"screen_state","config":{}}""",
                actionsJson = null,
            ),
            listOf(
                ComponentEntity(ruleId = "old", role = ComponentRole.ACTION, ordinal = 1, type = "speak", configJson = "{}"),
                ComponentEntity(ruleId = "old", role = ComponentRole.ACTION, ordinal = 0, type = "toast", configJson = """{"text":"x"}"""),
            ),
        )

        assertEquals(
            listOf(ComponentSpec("toast", mapOf("text" to "x")), ComponentSpec("speak")),
            legacy.toRuleOrNull()!!.actions,
        )
    }

    @Test
    fun `a row whose action tree cannot be read is dropped, not shown without its actions`() {
        val corrupt = RuleWithComponents(
            RuleEntity(
                id = "bad",
                name = "Bad",
                enabled = true,
                position = 0,
                triggerJson = """{"type":"screen_state","config":{}}""",
                actionsJson = "not json",
            ),
            emptyList(),
        )

        assertNull(corrupt.toRuleOrNull())
    }
}
