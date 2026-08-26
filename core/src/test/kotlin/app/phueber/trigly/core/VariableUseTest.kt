package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What deleting a saved value is allowed to do depends entirely on this
 * answering correctly. A miss here does not look like a bug in this function: it
 * looks like a value that deleted cleanly and three rules that quietly stopped
 * working afterwards.
 */
class VariableUseTest {

    /** Only a text field declares that it accepts a reference. See `docs/variables.md`. */
    private val substitutions: (ComponentSpec) -> Map<String, Substitution> = { spec ->
        when (spec.type) {
            "post_notification" -> mapOf("title" to Substitution.TEXT, "body" to Substitution.TEXT)
            "http_request" -> mapOf("url" to Substitution.URL)
            else -> emptyMap()
        }
    }

    private fun rule(vararg actions: ComponentSpec, name: String = "rule") = Rule(
        id = name,
        name = name,
        trigger = ComponentSpec("screen_state", mapOf("state" to "on")),
        actions = actions.toList(),
    )

    @Test
    fun `a rule with no references reads nothing`() {
        val rule = rule(ComponentSpec("post_notification", mapOf("title" to "Screen on")))

        assertEquals(emptySet<String>(), rule.appVariablesRead(substitutions))
    }

    @Test
    fun `an app reference in an action is found`() {
        val rule = rule(
            ComponentSpec("post_notification", mapOf("body" to "Trips: {{app.trip_count}}")),
        )

        assertEquals(setOf("trip_count"), rule.appVariablesRead(substitutions))
    }

    @Test
    fun `references across several fields and actions are all found`() {
        val rule = rule(
            ComponentSpec(
                "post_notification",
                mapOf("title" to "{{app.mode}}", "body" to "Trips: {{app.trip_count}}"),
            ),
            ComponentSpec("http_request", mapOf("url" to "https://x/?m={{app.mode}}")),
        )

        assertEquals(setOf("mode", "trip_count"), rule.appVariablesRead(substitutions))
    }

    /**
     * Only what the component declares as substitutable counts. A `{{app.x}}`
     * typed into a field that never accepts a reference is literal text, and the
     * engine will never resolve it, so reporting it as a read would warn about a
     * dependency that does not exist.
     */
    @Test
    fun `a reference in a field that does not accept one is not a read`() {
        val rule = rule(
            ComponentSpec("post_notification", mapOf("channel" to "{{app.mode}}")),
        )

        assertEquals(emptySet<String>(), rule.appVariablesRead(substitutions))
    }

    /** Other scopes are somebody else's business. Deleting a saved value cannot break them. */
    @Test
    fun `a reference to another scope is not an app read`() {
        val rule = rule(
            ComponentSpec("post_notification", mapOf("body" to "{{trigger.text}} {{event.time}}")),
        )

        assertEquals(emptySet<String>(), rule.appVariablesRead(substitutions))
    }

    /**
     * No trigger declares a substitutable field today, so this finds nothing.
     * It is asserted anyway, because the day one does, this is the assertion that
     * says whether it was covered without anybody having to remember.
     */
    @Test
    fun `every component is asked, trigger leaves included`() {
        val withTriggerField: (ComponentSpec) -> Map<String, Substitution> = { spec ->
            if (spec.type == "screen_state") mapOf("note" to Substitution.TEXT)
            else substitutions(spec)
        }
        val rule = Rule(
            id = "r",
            name = "r",
            trigger = ComponentSpec("screen_state", mapOf("note" to "{{app.mode}}")),
            actions = emptyList(),
        )

        assertEquals(setOf("mode"), rule.appVariablesRead(withTriggerField))
    }

    @Test
    fun `rulesReading names only the rules that read that value`() {
        val reader = rule(
            ComponentSpec("post_notification", mapOf("body" to "{{app.trip_count}}")),
            name = "reader",
        )
        val otherReader = rule(
            ComponentSpec("http_request", mapOf("url" to "https://x/?t={{app.trip_count}}")),
            name = "other reader",
        )
        val unrelated = rule(
            ComponentSpec("post_notification", mapOf("body" to "{{app.mode}}")),
            name = "unrelated",
        )

        val reading = listOf(reader, otherReader, unrelated)
            .rulesReading("trip_count", substitutions)

        assertEquals(listOf("reader", "other reader"), reading.map { it.name })
    }

    @Test
    fun `a fallback does not hide the reference`() {
        // The fallback is what a person writes so an unset value is acceptable.
        // It says nothing about whether the rule depends on the name.
        val rule = rule(
            ComponentSpec("post_notification", mapOf("body" to "{{app.mode | unknown}}")),
        )

        assertEquals(setOf("mode"), rule.appVariablesRead(substitutions))
    }
}
