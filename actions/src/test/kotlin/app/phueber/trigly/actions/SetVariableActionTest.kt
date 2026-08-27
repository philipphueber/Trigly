package app.phueber.trigly.actions

import app.phueber.trigly.core.ActionResult
import app.phueber.trigly.core.InMemoryRuleVariableStore
import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.RunScope
import app.phueber.trigly.core.Substitution
import app.phueber.trigly.core.TriggerEvent
import app.phueber.trigly.core.shownWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    // Fresh per test: JUnit builds a new instance of this class for each one.
    // The scope tests below use these; the older tests declare their own store
    // locally, which is left as it was.
    private val store = InMemoryVariableStore()
    private val ruleStore = InMemoryRuleVariableStore()

    // --- set ---------------------------------------------------------------

    @Test
    fun `set writes a value`() = runTest {
        val store = InMemoryVariableStore()

        val result = SetVariableAction(store, "count", VariableWriteMode.SET, "7").execute(event)

        assertEquals(
            ActionResult.Success(outputs = mapOf(SetVariableAction.OUTPUT_VALUE to "7")),
            result,
        )
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

        // No output either: nothing is stored to report any more.
        assertEquals(ActionResult.Success(), result)
        assertNull(store.get("count"))
    }

    @Test
    fun `clearing a name that was never set still succeeds`() = runTest {
        // "Make sure this is cleared" must not fail just because there was
        // nothing to clear, the same reasoning set_rule_enabled's ENABLE gives.
        val store = InMemoryVariableStore()

        val result = SetVariableAction(store, "count", VariableWriteMode.CLEAR, "").execute(event)

        assertEquals(ActionResult.Success(), result)
        assertNull(store.get("count"))
    }

    // --- add -----------------------------------------------------------------

    @Test
    fun `add on an unset name starts from zero`() = runTest {
        val store = InMemoryVariableStore()

        val result = SetVariableAction(store, "count", VariableWriteMode.ADD, "5").execute(event)

        assertEquals(
            ActionResult.Success(outputs = mapOf(SetVariableAction.OUTPUT_VALUE to "5")),
            result,
        )
        assertEquals("5", store.get("count"))
    }

    @Test
    fun `add increments a number already stored`() = runTest {
        val store = InMemoryVariableStore(mapOf("count" to "3"))

        val result = SetVariableAction(store, "count", VariableWriteMode.ADD, "4").execute(event)

        // add reports what it actually stored, the running total, not the
        // addend: what makes "Trip 4 recorded" possible without a second read.
        assertEquals(
            ActionResult.Success(outputs = mapOf(SetVariableAction.OUTPUT_VALUE to "7")),
            result,
        )
        assertEquals("7", store.get("count"))
    }

    @Test
    fun `add accepts a decimal value and keeps a whole result clean`() = runTest {
        val store = InMemoryVariableStore(mapOf("total" to "1.5"))

        val result = SetVariableAction(store, "total", VariableWriteMode.ADD, "2.5")
            .execute(event)

        assertEquals(
            ActionResult.Success(outputs = mapOf(SetVariableAction.OUTPUT_VALUE to "4")),
            result,
        )
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

    // --- evaluate --------------------------------------------------------------

    @Test
    fun `evaluate stores a computed result`() = runTest {
        val store = InMemoryVariableStore()

        val result = SetVariableAction(store, "total", VariableWriteMode.EVALUATE, "1 + 2")
            .execute(event)

        assertEquals(
            ActionResult.Success(outputs = mapOf(SetVariableAction.OUTPUT_VALUE to "3")),
            result,
        )
        assertEquals("3", store.get("total"))
    }

    @Test
    fun `evaluate runs a string function`() = runTest {
        val store = InMemoryVariableStore()

        val result = SetVariableAction(
            store,
            "shout",
            VariableWriteMode.EVALUATE,
            "upper(\"pixel buds\")",
        ).execute(event)

        assertEquals(
            ActionResult.Success(outputs = mapOf(SetVariableAction.OUTPUT_VALUE to "PIXEL BUDS")),
            result,
        )
        assertEquals("PIXEL BUDS", store.get("shout"))
    }

    @Test
    fun `evaluate runs a ternary against what substitution already inserted`() = runTest {
        // Stands in for the substituted field "{{battery.level}} < 20 ?
        // \"low\" : \"ok\"", which by the time set_variable sees it is
        // already plain text with the reference replaced.
        val store = InMemoryVariableStore()

        val result = SetVariableAction(
            store,
            "status",
            VariableWriteMode.EVALUATE,
            "15 < 20 ? \"low\" : \"ok\"",
        ).execute(event)

        assertEquals("low", store.get("status"))
        assertEquals(
            ActionResult.Success(outputs = mapOf(SetVariableAction.OUTPUT_VALUE to "low")),
            result,
        )
    }

    @Test
    fun `a bad expression fails with a readable reason and stores nothing`() = runTest {
        val store = InMemoryVariableStore()

        val result = SetVariableAction(store, "total", VariableWriteMode.EVALUATE, "1 +")
            .execute(event)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
        assertNull(store.get("total"))
    }

    @Test
    fun `an unknown function fails and names it, and stores nothing`() = runTest {
        val store = InMemoryVariableStore(mapOf("total" to "old"))

        val result = SetVariableAction(store, "total", VariableWriteMode.EVALUATE, "shout(\"hi\")")
            .execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue((result as ActionResult.Failure).reason.contains("shout"))
        // Nothing should have overwritten what was already stored.
        assertEquals("old", store.get("total"))
    }

    @Test
    fun `evaluate reading another app variable through plus`() = runTest {
        val store = InMemoryVariableStore(mapOf("count" to "41"))

        // "{{app.count}} + 1" after substitution, with count reading as a
        // number and so inserted bare by Substitution.EXPRESSION.
        val result = SetVariableAction(store, "count", VariableWriteMode.EVALUATE, "41 + 1")
            .execute(event)

        assertEquals("42", store.get("count"))
        assertEquals(
            ActionResult.Success(outputs = mapOf(SetVariableAction.OUTPUT_VALUE to "42")),
            result,
        )
    }

    // --- substitutionsFor: the value field's escaping depends on the mode -------------

    @Test
    fun `the value field is declared TEXT when the mode is set`() {
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val substitutions = factory.substitutionsFor(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.SET.configValue)
        )

        assertEquals(Substitution.TEXT, substitutions[SetVariableAction.CONFIG_VALUE])
    }

    @Test
    fun `the value field is declared TEXT when the mode is add`() {
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val substitutions = factory.substitutionsFor(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.ADD.configValue)
        )

        assertEquals(Substitution.TEXT, substitutions[SetVariableAction.CONFIG_VALUE])
    }

    @Test
    fun `the value field is declared EXPRESSION only when the mode is evaluate`() {
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val substitutions = factory.substitutionsFor(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.EVALUATE.configValue)
        )

        assertEquals(Substitution.EXPRESSION, substitutions[SetVariableAction.CONFIG_VALUE])
    }

    @Test
    fun `the value field defaults to TEXT before a mode has been chosen`() {
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val substitutions = factory.substitutionsFor(emptyMap())

        assertEquals(Substitution.TEXT, substitutions[SetVariableAction.CONFIG_VALUE])
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
    fun `the value field is shown for set, add and evaluate`() {
        val factory = SetVariableActionFactory(InMemoryVariableStore())

        val shownForSet = factory.configFields.shownWith(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.SET.configValue)
        )
        val shownForAdd = factory.configFields.shownWith(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.ADD.configValue)
        )
        val shownForEvaluate = factory.configFields.shownWith(
            mapOf(VariableWriteMode.CONFIG_KEY to VariableWriteMode.EVALUATE.configValue)
        )

        assertTrue(shownForSet.any { it.key == SetVariableAction.CONFIG_VALUE })
        assertTrue(shownForAdd.any { it.key == SetVariableAction.CONFIG_VALUE })
        assertTrue(shownForEvaluate.any { it.key == SetVariableAction.CONFIG_VALUE })
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
    // --- the three scopes -------------------------------------------------------------

    /**
     * The default has to be app scope, because that is where every
     * `set_variable` action saved before this field existed put its value. A
     * rule with no `scope` key in its config is one of those, and reading a
     * missing key as anything else would silently move where its value goes.
     */
    @Test
    fun `no scope in the config means the shared scope`() = runTest {
        val factory = SetVariableActionFactory(store, ruleStore)

        val action = factory.create(mapOf("name" to "count", "value" to "1"))
        withContext(RunScope("rule-1")) { action.execute(event) }

        assertEquals("1", store.get("count"))
        assertNull("nothing should reach the rule scope", ruleStore.get("rule-1", "count"))
    }

    @Test
    fun `a rule-scope write goes to this rule and nowhere else`() = runTest {
        val action = SetVariableAction(
            store = store,
            name = "count",
            mode = VariableWriteMode.SET,
            value = "1",
            scope = VariableWriteScope.RULE,
            ruleStore = ruleStore,
        )

        withContext(RunScope("rule-1")) { action.execute(event) }

        assertEquals("1", ruleStore.get("rule-1", "count"))
        assertNull("another rule must not see it", ruleStore.get("rule-2", "count"))
        assertNull("and the shared scope must not either", store.get("count"))
    }

    /**
     * Two rules keeping a `count` each is the case this scope exists for, and
     * neither has to know the other exists.
     */
    @Test
    fun `two rules can both keep the same name`() = runTest {
        val action = SetVariableAction(
            store = store,
            name = "count",
            mode = VariableWriteMode.ADD,
            value = "1",
            scope = VariableWriteScope.RULE,
            ruleStore = ruleStore,
        )

        withContext(RunScope("rule-1")) { action.execute(event) }
        withContext(RunScope("rule-1")) { action.execute(event) }
        withContext(RunScope("rule-2")) { action.execute(event) }

        assertEquals("2", ruleStore.get("rule-1", "count"))
        assertEquals("1", ruleStore.get("rule-2", "count"))
    }

    @Test
    fun `a run-scope write is readable in the same run and stored nowhere`() = runTest {
        val action = SetVariableAction(
            store = store,
            name = "total",
            mode = VariableWriteMode.SET,
            value = "12",
            scope = VariableWriteScope.RUN,
            ruleStore = ruleStore,
        )
        val run = RunScope("rule-1")

        withContext(run) { action.execute(event) }

        assertEquals("12", run.snapshot()["total"])
        assertNull(store.get("total"))
        assertNull(ruleStore.get("rule-1", "total"))
    }

    @Test
    fun `a run-scope counter adds within one run`() = runTest {
        val action = SetVariableAction(
            store = store,
            name = "total",
            mode = VariableWriteMode.ADD,
            value = "5",
            scope = VariableWriteScope.RUN,
            ruleStore = ruleStore,
        )
        val run = RunScope("rule-1")

        withContext(run) {
            action.execute(event)
            action.execute(event)
        }

        assertEquals("10", run.snapshot()["total"])
    }

    /**
     * A fresh run starts empty. That is the whole difference between this scope
     * and the other two, so it is worth pinning rather than assuming.
     */
    @Test
    fun `a run-scope value does not survive into the next run`() = runTest {
        val action = SetVariableAction(
            store = store,
            name = "total",
            mode = VariableWriteMode.ADD,
            value = "5",
            scope = VariableWriteScope.RUN,
            ruleStore = ruleStore,
        )

        withContext(RunScope("rule-1")) { action.execute(event) }
        val second = RunScope("rule-1")
        withContext(second) { action.execute(event) }

        assertEquals("the second run counted from nothing", "5", second.snapshot()["total"])
    }

    /**
     * Neither of the new scopes exists outside a firing, and the failure has to
     * say so rather than appearing to work. The editor's Test button installs a
     * run scope for exactly this reason; see `RuleEditorViewModel.testAction`.
     */
    @Test
    fun `a rule-scope write with no run refuses and says why`() = runTest {
        val action = SetVariableAction(
            store = store,
            name = "count",
            mode = VariableWriteMode.SET,
            value = "1",
            scope = VariableWriteScope.RULE,
            ruleStore = ruleStore,
        )

        val result = action.execute(event)

        assertTrue(result is ActionResult.Failure)
        assertTrue(
            "was: ${(result as ActionResult.Failure).reason}",
            result.reason.contains("while a rule is running"),
        )
    }

    /**
     * An unrecognised scope falls back to the shared one, which is the opposite
     * call from an unrecognised *mode*. A mode this build cannot perform must
     * refuse, because guessing would do the wrong thing to a stored value. A
     * scope from a newer build is most likely one this build never had, and the
     * honest answer to "where does this go" is where it has always gone.
     */
    @Test
    fun `an unknown scope falls back to the shared one`() {
        assertEquals(VariableWriteScope.APP, VariableWriteScope.parse("galaxy"))
        assertEquals(VariableWriteScope.APP, VariableWriteScope.parse(null))
        assertEquals(VariableWriteScope.RULE, VariableWriteScope.parse("rule"))
        assertEquals(VariableWriteScope.RUN, VariableWriteScope.parse(" RUN "))
    }

}
