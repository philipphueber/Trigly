package app.phueber.trigly.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EventLookup]: the engine's [VariableLookup], reading a fired [TriggerEvent]
 * and the [Rule] that is running.
 *
 * The reason this file exists rather than a map lookup: "the Bluetooth trigger
 * is not what started this run" is an actionable reason, and an empty string
 * is not.
 */
class EventLookupTest {

    private val rule = Rule(
        id = "rule-1",
        name = "My rule",
        trigger = ComponentSpec("notification_posted"),
        actions = emptyList(),
    )

    private fun lookup(event: TriggerEvent, zone: ZoneId = ZoneId.of("UTC")) =
        EventLookup(rule, event, zone)

    // --- trigger scope: the leaf that fired -----------------------------------------

    @Test
    fun `trigger scope reads the fired event's payload`() {
        val event = TriggerEvent("notification_posted", 1L, mapOf("title" to "Hello"))

        val value = lookup(event).value(VariableRef("trigger", "title"))

        assertEquals(VariableValue.Present("Hello"), value)
    }

    @Test
    fun `trigger scope is absent for a key the event does not carry`() {
        val event = TriggerEvent("notification_posted", 1L, mapOf("title" to "Hello"))

        val value = lookup(event).value(VariableRef("trigger", "body")) as VariableValue.Absent

        // The reason names the type and the key, not just "absent".
        assertTrue(value.reason.contains("notification_posted"))
        assertTrue(value.reason.contains("body"))
    }

    // --- the type-qualified form -----------------------------------------------------

    @Test
    fun `a type-qualified reference reads the payload when that type fired`() {
        val event = TriggerEvent("notification_posted", 1L, mapOf("title" to "Hello"))

        val value = lookup(event).value(VariableRef("notification_posted", "title"))

        assertEquals(VariableValue.Present("Hello"), value)
    }

    @Test
    fun `a type-qualified reference is absent when a different type fired`() {
        // The case a rule with a trigger tree hits. The reason is the only way
        // a person learns why the value came back empty.
        val event = TriggerEvent("notification_posted", 1L, mapOf("title" to "Hello"))

        val ref = VariableRef("bluetooth_connected", "name")
        val value = lookup(event).value(ref) as VariableValue.Absent

        assertTrue(value.reason.contains("bluetooth_connected"))
        assertTrue(value.reason.contains("notification_posted"))
    }

    // --- engine facts: event and rule scope -------------------------------------------

    @Test
    fun `event type resolves to the trigger type that fired`() {
        val event = TriggerEvent("notification_posted", 1L)
        val value = lookup(event).value(VariableRef("event", "type"))

        assertEquals(VariableValue.Present("notification_posted"), value)
    }

    @Test
    fun `event timestamp resolves to the epoch milliseconds`() {
        val event = TriggerEvent("notification_posted", 1787900400000L)

        val value = lookup(event).value(VariableRef("event", "timestamp"))

        assertEquals(VariableValue.Present("1787900400000"), value)
    }

    @Test
    fun `event time formats the firing time as HH-mm in the zone it is given`() {
        // A fixed zone, not the machine's, or this fails on a different machine.
        val millis = Instant.parse("2024-01-01T07:15:00Z").toEpochMilli()
        val event = TriggerEvent("notification_posted", millis)

        val value = lookup(event, ZoneId.of("UTC")).value(VariableRef("event", "time"))

        assertEquals(VariableValue.Present("07:15"), value)
    }

    @Test
    fun `event time follows the zone, not a fixed offset from UTC`() {
        val millis = Instant.parse("2024-01-01T07:15:00Z").toEpochMilli()
        val event = TriggerEvent("notification_posted", millis)

        val value = lookup(event, ZoneId.of("America/New_York")).value(VariableRef("event", "time"))

        assertEquals(VariableValue.Present("02:15"), value)
    }

    @Test
    fun `rule name resolves`() {
        val event = TriggerEvent("notification_posted", 1L)
        val value = lookup(event).value(VariableRef("rule", "name"))

        assertEquals(VariableValue.Present("My rule"), value)
    }

    @Test
    fun `rule id resolves`() {
        val event = TriggerEvent("notification_posted", 1L)
        val value = lookup(event).value(VariableRef("rule", "id"))

        assertEquals(VariableValue.Present("rule-1"), value)
    }

    // --- app scope: resolved from the snapshot the engine hands in --------------------

    @Test
    fun `app scope reads the name from the snapshot it was given`() {
        val event = TriggerEvent("notification_posted", 1L)
        val ref = VariableRef("app", "trip_count")

        val value = EventLookup(rule, event, appVariables = mapOf("trip_count" to "7")).value(ref)

        assertEquals(VariableValue.Present("7"), value)
    }

    @Test
    fun `app scope is absent for a name the snapshot does not hold, and says it is not set`() {
        val event = TriggerEvent("notification_posted", 1L)
        val ref = VariableRef("app", "trip_count")

        val value = lookup(event).value(ref) as VariableValue.Absent

        assertTrue(value.reason.contains("trip_count"))
        assertTrue(value.reason.contains("not set"))
    }

    @Test
    fun `a fallback still applies to an app variable the snapshot does not hold`() {
        val event = TriggerEvent("notification_posted", 1L)
        val template = parseTemplate("{{app.trip_count|0}}")

        val substituted = template.substitute(lookup(event), Substitution.TEXT)

        assertEquals(Substituted.Ok("0"), substituted)
    }

    // --- an unknown name under a known scope ------------------------------------------

    @Test
    fun `an unknown name under event scope is absent`() {
        val ref = VariableRef("event", "nonsense")

        val value = lookup(TriggerEvent("notification_posted", 1L)).value(ref)

        assertTrue(value is VariableValue.Absent)
    }

    // --- action scope: what an earlier action in this run produced --------------------

    @Test
    fun `action scope reads the most recent producer of a name`() {
        val outputs = ActionOutputs.EMPTY.plus("set_variable", mapOf("value" to "4"))
        val event = TriggerEvent("notification_posted", 1L)

        val value = EventLookup(rule, event, actionOutputs = outputs)
            .value(VariableRef("action", "value"))

        assertEquals(VariableValue.Present("4"), value)
    }

    @Test
    fun `action scope is absent when nothing has produced that name yet, and says so`() {
        val event = TriggerEvent("notification_posted", 1L)

        val value = lookup(event).value(VariableRef("action", "value")) as VariableValue.Absent

        // This is the case an action reading ahead of its own producer hits:
        // the accumulator simply does not have the name yet. The reason has to
        // read as "not yet", not as "never", since a later action producing it
        // is the ordinary case, not a mistake.
        assertTrue(value.reason.contains("value"))
        assertTrue(value.reason.contains("run"))
    }

    @Test
    fun `action scope takes the most recent of two producers of the same name`() {
        val outputs = ActionOutputs.EMPTY
            .plus("set_variable", mapOf("value" to "first"))
            .plus("set_rule_enabled", mapOf("value" to "second"))
        val event = TriggerEvent("notification_posted", 1L)

        val value = EventLookup(rule, event, actionOutputs = outputs)
            .value(VariableRef("action", "value"))

        assertEquals(VariableValue.Present("second"), value)
    }

    @Test
    fun `a type-qualified action reference reads what that action type produced`() {
        val outputs = ActionOutputs.EMPTY
            .plus("set_variable", mapOf("value" to "first"))
            .plus("set_rule_enabled", mapOf("value" to "second"))
        val event = TriggerEvent("notification_posted", 1L)
        val lookup = EventLookup(rule, event, actionOutputs = outputs)

        assertEquals(
            VariableValue.Present("first"),
            lookup.value(VariableRef("set_variable", "value")),
        )
        assertEquals(
            VariableValue.Present("second"),
            lookup.value(VariableRef("set_rule_enabled", "value")),
        )
    }

    @Test
    fun `a type-qualified action reference is absent when that type has not produced it`() {
        val event = TriggerEvent("notification_posted", 1L)

        val value = lookup(event)
            .value(VariableRef("set_rule_enabled", "enabled")) as VariableValue.Absent

        assertTrue(value.reason.contains("set_rule_enabled"))
    }
    // --- instance namespaces: which leaf, and which action ---------------------------

    /**
     * The point of numbering. Two leaves of one type, and a reference has to be
     * able to say which of them it means. Only the leaf that fired resolves;
     * the other one has no payload to give, however well configured it is.
     */
    @Test
    fun `a numbered leaf namespace reads only when that leaf fired`() {
        val event = TriggerEvent("notification_posted", 1L, mapOf("title" to "From the second"))
        val lookup = EventLookup(
            rule,
            event,
            firedTriggerInstance = "notification_posted_2",
        )

        assertEquals(
            VariableValue.Present("From the second"),
            lookup.value(VariableRef("notification_posted_2", "title")),
        )
        assertTrue(
            lookup.value(VariableRef("notification_posted", "title")) is VariableValue.Absent,
        )
    }

    /**
     * And the reason names the fix rather than merely reporting emptiness: a
     * person who wrote the wrong number needs to be told which number fired.
     */
    @Test
    fun `the wrong leaf number says which one fired`() {
        val event = TriggerEvent("notification_posted", 1L, mapOf("title" to "x"))
        val lookup = EventLookup(rule, event, firedTriggerInstance = "notification_posted_2")

        val absent = lookup.value(VariableRef("notification_posted", "title"))

        assertTrue(absent is VariableValue.Absent)
        assertTrue(
            "was: ${(absent as VariableValue.Absent).reason}",
            absent.reason.contains("notification_posted_2"),
        )
    }

    /**
     * With no tree to number, the bare type is the fired instance. That is what
     * `{{bluetooth_connected.name}}` meant before instances existed, so a rule
     * saved then keeps working with no migration.
     */
    @Test
    fun `without a fired instance the bare type still reads the payload`() {
        val event = TriggerEvent("notification_posted", 1L, mapOf("title" to "Hello"))

        val value = EventLookup(rule, event).value(VariableRef("notification_posted", "title"))

        assertEquals(VariableValue.Present("Hello"), value)
    }

    @Test
    fun `two actions of one type keep their outputs apart`() {
        val event = TriggerEvent("notification_posted", 1L)
        val outputs = ActionOutputs.EMPTY
            .plus("set_variable", mapOf("value" to "first"))
            .plus("set_variable_2", mapOf("value" to "second"))
        val lookup = EventLookup(rule, event, actionOutputs = outputs)

        assertEquals(
            VariableValue.Present("first"),
            lookup.value(VariableRef("set_variable", "value")),
        )
        assertEquals(
            VariableValue.Present("second"),
            lookup.value(VariableRef("set_variable_2", "value")),
        )
        // The unnumbered form is the most recent producer, which is the second.
        assertEquals(
            VariableValue.Present("second"),
            lookup.value(VariableRef(VariableScope.ACTION, "value")),
        )
    }

    // --- the run and rule scopes ------------------------------------------------------

    @Test
    fun `local scope reads what this run has written`() {
        val event = TriggerEvent("notification_posted", 1L)
        val lookup = EventLookup(rule, event, localVariables = mapOf("total" to "12"))

        assertEquals(VariableValue.Present("12"), lookup.value(VariableRef("local", "total")))
    }

    /**
     * A run value that was never written says so in the terms that make it
     * fixable: it is not "missing", it is "nothing above this action wrote it".
     */
    @Test
    fun `an unset local value explains that a run value has to be written first`() {
        val event = TriggerEvent("notification_posted", 1L)

        val value = EventLookup(rule, event).value(VariableRef("local", "total"))

        assertTrue(value is VariableValue.Absent)
        assertTrue((value as VariableValue.Absent).reason.contains("this run"))
    }

    @Test
    fun `mine scope reads this rule's own values`() {
        val event = TriggerEvent("notification_posted", 1L)
        val lookup = EventLookup(rule, event, ruleVariables = mapOf("count" to "3"))

        assertEquals(VariableValue.Present("3"), lookup.value(VariableRef("mine", "count")))
    }

    @Test
    fun `an unset mine value says it is unset for this rule`() {
        val event = TriggerEvent("notification_posted", 1L)

        val value = EventLookup(rule, event).value(VariableRef("mine", "count"))

        assertTrue(value is VariableValue.Absent)
        assertTrue((value as VariableValue.Absent).reason.contains("this rule"))
    }

    /**
     * The three writable scopes are separate namespaces, not three views of one
     * store. A rule keeping its own `count` must not see another rule's, and
     * must not see the shared one either.
     */
    @Test
    fun `the three writable scopes do not see each other`() {
        val event = TriggerEvent("notification_posted", 1L)
        val lookup = EventLookup(
            rule,
            event,
            appVariables = mapOf("count" to "app"),
            localVariables = mapOf("count" to "run"),
            ruleVariables = mapOf("count" to "rule"),
        )

        assertEquals(VariableValue.Present("app"), lookup.value(VariableRef("app", "count")))
        assertEquals(VariableValue.Present("run"), lookup.value(VariableRef("local", "count")))
        assertEquals(VariableValue.Present("rule"), lookup.value(VariableRef("mine", "count")))
    }

}
