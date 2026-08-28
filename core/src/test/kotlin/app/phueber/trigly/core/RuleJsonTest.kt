package app.phueber.trigly.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This codec is how rules survive a new phone, so a silent round-trip bug loses
 * user data. Runs on the JVM against the real `org.json` (android.jar ships
 * stubs that throw), which is close enough for flat string maps.
 */
class RuleJsonTest {

    private val rule = Rule(
        id = "rule-1",
        name = "Charger connected",
        trigger = ComponentSpec("power_connection", mapOf("state" to "connected")),
        actions = listOf(
            ComponentSpec("set_volume", mapOf("stream" to "media", "percent" to "70")),
            ComponentSpec("speak", mapOf("text" to "Charging")),
        ),
        enabled = true,
    )

    @Test
    fun `a rule survives a round trip intact`() {
        val decoded = RuleJson.decode(RuleJson.encode(rule))

        assertEquals(listOf(rule), decoded)
    }

    @Test
    fun `action order is preserved`() {
        // Order is semantic — actions run in sequence — so this is not cosmetic.
        val decoded = RuleJson.decode(RuleJson.encode(rule)).single()

        assertEquals(listOf("set_volume", "speak"), decoded.actions.map { it.type })
    }

    @Test
    fun `several rules round trip`() {
        val second = rule.copy(id = "rule-2", name = "Second", enabled = false)

        val decoded = RuleJson.decode(RuleJson.encode(listOf(rule, second)))

        assertEquals(listOf(rule, second), decoded)
    }

    @Test
    fun `awkward characters in config survive`() {
        // The reason to use a real JSON library rather than string concatenation.
        val awkward = rule.copy(
            actions = listOf(
                ComponentSpec(
                    "http_request",
                    mapOf(
                        "url" to "https://example.com/a?b=1&c=2",
                        "body" to """{"quoted": "value", "newline": "a\nb"}""",
                        "contentType" to "application/json; charset=utf-8",
                        "note" to "emoji 🔔 and ünïcode",
                    ),
                )
            )
        )

        assertEquals(awkward, RuleJson.decode(RuleJson.encode(awkward)).single())
    }

    @Test
    fun `an empty config map round trips as empty, not null`() {
        val bare = rule
            .withTrigger(ComponentSpec("screen_state", emptyMap()))
            .copy(actions = listOf(ComponentSpec("cancel_notification", emptyMap())))

        val decoded = RuleJson.decode(RuleJson.encode(bare)).single()

        assertEquals(emptyMap<String, String>(), (decoded.trigger as TriggerNode.One).spec.config)
        assertEquals(emptyMap<String, String>(), decoded.actions.single().config)
    }

    @Test
    fun `a rule with no actions round trips`() {
        val actionless = rule.copy(actions = emptyList())

        assertEquals(actionless, RuleJson.decode(RuleJson.encode(actionless)).single())
    }

    @Test
    fun `numbers written unquoted by hand are read as strings`() {
        // Authoring or editing an export by hand is a legitimate workflow, and
        // "threshold": 20 is what a human writes.
        val handWritten = """
            {"version":1,"rules":[{
              "name":"Low battery",
              "trigger":{"type":"battery_level","config":{"threshold":20,"direction":"below"}},
              "actions":[{"type":"vibrate","config":{"durationMillis":500}}]
            }]}
        """.trimIndent()

        val decoded = RuleJson.decode(handWritten).single()

        assertEquals("20", (decoded.trigger as TriggerNode.One).spec.config["threshold"])
        assertEquals("500", decoded.actions.single().config["durationMillis"])
    }

    @Test
    fun `a missing id is generated rather than rejected`() {
        val noId = """
            {"version":1,"rules":[{
              "name":"X","trigger":{"type":"screen_state","config":{"state":"on"}},"actions":[]
            }]}
        """.trimIndent()

        assertTrue(RuleJson.decode(noId).single().id.isNotBlank())
    }

    @Test
    fun `a missing enabled flag defaults to enabled`() {
        val noFlag = """
            {"version":1,"rules":[{
              "name":"X","trigger":{"type":"screen_state","config":{}},"actions":[]
            }]}
        """.trimIndent()

        assertTrue(RuleJson.decode(noFlag).single().enabled)
    }

    @Test
    fun `a newer format version is refused rather than half-read`() {
        val future = """{"version":${RuleJson.VERSION + 1},"rules":[]}"""

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode(future)
        }
        assertTrue(error.message!!.contains("newer version"))
    }

    @Test
    fun `malformed input fails with a message fit to show a user`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode("this is not json")
        }
        assertTrue(error.message!!.contains("not valid JSON"))
    }

    @Test
    fun `a file from something else is rejected for having no version`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode("""{"rules":[]}""")
        }
        assertTrue(error.message!!.contains("no version"))
    }

    @Test
    fun `errors name the offending rule so it can be found in the file`() {
        val secondRuleBroken = """
            {"version":1,"rules":[
              {"name":"Fine","trigger":{"type":"screen_state","config":{}},"actions":[]},
              {"name":"Broken","actions":[]}
            ]}
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            RuleJson.decode(secondRuleBroken)
        }
        assertTrue("was: ${error.message}", error.message!!.contains("Broken"))
        assertTrue("was: ${error.message}", error.message!!.contains("trigger"))
    }

    @Test
    fun `a nameless rule is rejected`() {
        val nameless = """
            {"version":1,"rules":[{"trigger":{"type":"x","config":{}},"actions":[]}]}
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { RuleJson.decode(nameless) }
    }

    @Test
    fun `importing assigns a fresh id, keeps the name, and switches the rule off`() {
        // A fresh id means import never overwrites an existing rule. The name
        // is left alone because it is not this app's to edit. Off whatever the
        // file said, because a file is a program written by someone this
        // device has not necessarily met. See `Rule.imported` for the reasons
        // in full; an empty registry is enough here, since none of them
        // depend on a real component schema.
        val imported = RuleJson.decode(RuleJson.encode(rule))
            .map { it.imported(Registry(emptyList(), emptyList())) }

        assertNotEquals(rule.id, imported.single().id)
        assertEquals(rule.name, imported.single().name)
        assertFalse(imported.single().enabled)
    }

    @Test
    fun `config encoding round trips independently for storage`() {
        val config = mapOf("a" to "1", "b" to """with "quotes" and \backslash\""")

        assertEquals(config, RuleJson.decodeConfig(RuleJson.encodeConfig(config)))
    }

    @Test
    fun `blank stored config decodes to empty`() {
        assertEquals(emptyMap<String, String>(), RuleJson.decodeConfig(""))
    }

    // --- folder -------------------------------------------------------------

    @Test
    fun `a rule with a folder round trips`() {
        val foldered = rule.copy(folder = "Car")

        val decoded = RuleJson.decode(RuleJson.encode(foldered)).single()

        assertEquals("Car", decoded.folder)
        assertEquals(foldered, decoded)
    }

    @Test
    fun `a rule with no folder round trips as no folder`() {
        val decoded = RuleJson.decode(RuleJson.encode(rule)).single()

        assertEquals(null, decoded.folder)
    }

    @Test
    fun `a blank folder normalizes to null across the round trip`() {
        // "" and " " are not a spelling of a folder named nothing — they must
        // collapse to null on the way in, same as `Rule`'s own invariant.
        val blank = rule.copy(folder = "   ")

        val decoded = RuleJson.decode(RuleJson.encode(blank)).single()

        assertEquals(null, decoded.folder)
    }

    @Test
    fun `a rule with no folder writes no folder key`() {
        // The byte-identical-export promise: an ungrouped rule must look
        // exactly like it did before folders existed.
        val json = JSONObject(RuleJson.encode(rule))
        val ruleJson = json.getJSONArray("rules").getJSONObject(0)

        assertFalse("expected no 'folder' key", ruleJson.has("folder"))
    }

    @Test
    fun `an older document with no folder key decodes with a null folder`() {
        val v1Export = """
            {"version":1,"rules":[{
              "name":"Old export","trigger":{"type":"screen_state","config":{}},"actions":[]
            }]}
        """.trimIndent()

        assertEquals(null, RuleJson.decode(v1Export).single().folder)
    }
}
