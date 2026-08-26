package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.shownWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writing, clearing and counting an app-scoped variable.
 *
 * `add` carries the two decisions `docs/variables.md` section 10 calls out:
 * an unset name starts a counter at zero, and a stored value that is not a
 * number fails loudly rather than being guessed past.
 */
class SetVariableActionTest {

    private val event = TriggerEvent(triggerType = "interval", firedAtMillis = 1_000)

    // --- set ---------------------------------------------------------------

    @Test
    fun `set writes a value`() = runTest {
        val store = InMemoryVariableStore()

        val result = SetVariableAction(store, "count", VariableWriteMode.SET, "7").execute(event)

        assertEquals(ActionResult.Success, result)
        assertEquals("7", store.get("count"))
    }

    @Test
    fun `set overwrites whatever was already there`() = runTest {
        val store = InMemoryVariableStore(mapOf("count" to "1"))

        SetVariableAction(store, "count", VariableWriteMode.SET, "2").execute(event)

        assertEquals("2", store.get("count"))
    }

    // --- clear ---------------------------------------------------------------

    @Test
    fun `clear removes the name`() = runTest {
        val store = InMemoryVariableStore(mapOf("count" to "1"))

        val result = SetVariableAction(store, "count", VariableWriteMode.CLEAR, "").execute(event)

        assertEquals(ActionResult.Success, result)
        assertNull(store.get("count"))
    }

    @Test
    fun `clearing a name that was never set still succeeds`() = runTest {
        // "Make sure this is cleared" must not fail just because there was
        // nothing to clear, the same reasoning set_rule_enabled's ENABLE gives.
        val store = InMemoryVariableStore()

        val result = SetVariableAction(store, "count", VariableWriteMode.CLEAR, "").execute(event)

        assertEquals(ActionResult.Success, result)
        assertNull(store.get("count"))
    }

    // --- add -----------------------------------------------------------------

    @Test
    fun `add on an unset name starts from zero`() = runTest {
        val store = InMemoryVariableStore()

        val result = SetVariableAction(store, "count", VariableWriteMode.ADD, "5").execute(event)

        assertEquals(ActionResult.Success, result)
        assertEquals("5", store.get("count"))
    }

    @Test
    fun `add increments a number already stored`() = runTest {
        val store = InMemoryVariableStore(mapOf("count" to "3"))

        val result = SetVariableAction(store, "count", VariableWriteMode.ADD, "4").execute(event)

        assertEquals(ActionResult.Success, result)
        assertEquals("7", store.get("count"))
    }

    @Test
    fun `add accepts a decimal value and keeps a whole result clean`() = runTest {
        val store = InMemoryVariableStore(mapOf("total" to "1.5"))

        val result = SetVariableAction(store, "total", VariableWriteMode.ADD, "2.5")
            .execute(event)

        assertEquals(ActionResult.Success, result)
        assertEquals("4", store.get("total"))
    }

    @Test
    fun `add on a value that is not a number fails and names it`() = runTest {
        val store = InMemoryVariableStore(mapOf("count" to "not a number"))

        val result = SetVariableAction(store, "count", VariableWriteMode.ADD, "1").execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        assertTrue(
            "the reason should name what was found: ${(result as ActionResult.Failure).reason}",
            result.reason.contains("not a number"),
        )
        // Nothing should have been overwritten with a guess.
        assertEquals("not a number", store.get("count"))
    }

    @Test
    fun `add with a value to add that is not a number also fails`() = runTest {
        val store = InMemoryVariableStore(mapOf("count" to "3"))

        val result = SetVariableAction(store, "count", VariableWriteMode.ADD, "a lot")
            .execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("a lot"))
        assertEquals("3", store.get("count"))
    }

    // --- addToVariable, on its own ---------------------------------------------

    @Test
    fun `addToVariable reads a missing value as zero`() {
        val outcome = addToVariable(stored = null, addend = "5")
        assertEquals(VariableAddOutcome.Added("5"), outcome)
    }

    @Test
    fun `addToVariable names the stored value when it is not a number`() {
        val outcome = addToVariable(stored = "banana", addend = "1")
        assertTrue(outcome is VariableAddOutcome.Failed)
        assertTrue((outcome as VariableAddOutcome.Failed).reason.contains("banana"))
    }

    // --- create() and the name check --------------------------------------------

    @Test
    fun `create refuses a name variableNameProblem refuses`() {
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            factory.create(
                mapOf(
                    SetVariableAction.CONFIG_NAME to "has spaces",
                    VariableWriteMode.CONFIG_KEY to VariableWriteMode.SET.configValue,
                    SetVariableAction.CONFIG_VALUE to "1",
                )
            )
        }
        assertTrue(thrown.message.orEmpty().contains("has spaces"))
    }

    @Test
    fun `create stores the name normalized`() = runTest {
        val store = InMemoryVariableStore()
        val factory = SetVariableActionFactory(store)

        val action = factory.create(
            mapOf(
                SetVariableAction.CONFIG_NAME to "  count  ",
                VariableWriteMode.CONFIG_KEY to VariableWriteMode.SET.configValue,
                SetVariableAction.CONFIG_VALUE to "1",
            )
        )
        action.execute(event)

        assertEquals("1", store.get("count"))
        assertNull("the untrimmed name must not also have been written", store.get("  count  "))
    }

    // --- shownWhen ---------------------------------------------------------------

    @Test
    fun `the value field is hidden when the mode is clear`() {
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val shown = factory.configFields.shownWith(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.CLEAR.configValue)
        )

        assertTrue(shown.none { it.key == SetVariableAction.CONFIG_VALUE })
    }

    @Test
    fun `the value field is shown for set and add`() {
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val shownForSet = factory.configFields.shownWith(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.SET.configValue)
        )
        val shownForAdd = factory.configFields.shownWith(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.ADD.configValue)
        )

        assertTrue(shownForSet.any { it.key == SetVariableAction.CONFIG_VALUE })
        assertTrue(shownForAdd.any { it.key == SetVariableAction.CONFIG_VALUE })
    }

    @Test
    fun `the value field is shown by default, before a mode has been chosen`() {
        // shownWith falls back to the sibling's own default when nothing has
        // been stored yet, which for the mode field is "set" — so a fresh rule
        // shows the value field from the start.
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val shown = factory.configFields.shownWith(emptyMap())

        assertTrue(shown.any { it.key == SetVariableAction.CONFIG_VALUE })
    }
}
