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

    // --- app scope: reserved, not built yet -------------------------------------------

    @Test
    fun `app scope is absent, saying this version has no app variables`() {
        val event = TriggerEvent("notification_posted", 1L)
        val ref = VariableRef("app", "trip_count")

        val value = lookup(event).value(ref) as VariableValue.Absent

        assertTrue(value.reason.contains("app variables"))
    }

    // --- an unknown name under a known scope ------------------------------------------

    @Test
    fun `an unknown name under event scope is absent`() {
        val ref = VariableRef("event", "nonsense")

        val value = lookup(TriggerEvent("notification_posted", 1L)).value(ref)

        assertTrue(value is VariableValue.Absent)
    }
}
