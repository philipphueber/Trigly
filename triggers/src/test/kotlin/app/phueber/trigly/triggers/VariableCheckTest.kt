package app.phueber.trigly.triggers

import app.phueber.trigly.core.InMemoryVariableStore
import app.phueber.trigly.core.VariableStore
import app.phueber.trigly.core.shownWith
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [variableCheckHolds], the pure decision behind [VariableCheck], and the
 * factory's declarations. See `docs/variables.md`, section 10, and
 * `docs/conditions.md` for what a `null` answer must and must not mean.
 */
class VariableCheckTest {

    // --- an absent name: a definite answer, not an unknown one --------------
    // docs/variables.md, section 10: "A name that is not in the store is a
    // definite false for every comparison, and a definite true for 'is empty'."

    @Test
    fun `an absent name is empty`() {
        assertTrue(variableCheckHolds(null, VariableComparison.IS_EMPTY, null))
    }

    @Test
    fun `an absent name is not set`() {
        assertFalse(variableCheckHolds(null, VariableComparison.IS_SET, null))
    }

    @Test
    fun `an absent name does not equal anything, even a blank value`() {
        assertFalse(variableCheckHolds(null, VariableComparison.EQUALS, ""))
        assertFalse(variableCheckHolds(null, VariableComparison.EQUALS, "x"))
    }

    @Test
    fun `an absent name does not fail 'does not equal' either`() {
        // Deliberately false, not true: only "is empty" reads an absent name
        // as satisfied. A looser reading here would let a rule fire on a
        // variable nobody has set yet, which is the mistake this rule exists
        // to prevent.
        assertFalse(variableCheckHolds(null, VariableComparison.NOT_EQUALS, "x"))
    }

    @Test
    fun `an absent name does not contain anything`() {
        assertFalse(variableCheckHolds(null, VariableComparison.CONTAINS, "x"))
    }

    @Test
    fun `an absent name is neither above nor below anything`() {
        assertFalse(variableCheckHolds(null, VariableComparison.ABOVE, "0"))
        assertFalse(variableCheckHolds(null, VariableComparison.BELOW, "0"))
    }

    // --- is set / is empty, on a stored value --------------------------------

    @Test
    fun `a stored non-blank value is set and not empty`() {
        assertTrue(variableCheckHolds("hello", VariableComparison.IS_SET, null))
        assertFalse(variableCheckHolds("hello", VariableComparison.IS_EMPTY, null))
    }

    @Test
    fun `a stored blank value is set and empty at once`() {
        // Both true: "is set" asks whether the name is in the store at all,
        // "is empty" asks about its content. A name deliberately set to "" is
        // both.
        assertTrue(variableCheckHolds("", VariableComparison.IS_SET, null))
        assertTrue(variableCheckHolds("", VariableComparison.IS_EMPTY, null))
    }

    // --- equals / does not equal, and case sensitivity -----------------------
    // Case-insensitive, the same default `TextMatchMode` documents for a text
    // filter: a stored "On" should still match a comparison value of "on".

    @Test
    fun `equals matches regardless of case`() {
        assertTrue(variableCheckHolds("On", VariableComparison.EQUALS, "on"))
        assertTrue(variableCheckHolds("on", VariableComparison.EQUALS, "ON"))
    }

    @Test
    fun `equals does not match a different value`() {
        assertFalse(variableCheckHolds("on", VariableComparison.EQUALS, "off"))
    }

    @Test
    fun `does not equal is the exact complement of equals`() {
        assertFalse(variableCheckHolds("on", VariableComparison.NOT_EQUALS, "ON"))
        assertTrue(variableCheckHolds("on", VariableComparison.NOT_EQUALS, "off"))
    }

    // --- contains, and case sensitivity --------------------------------------

    @Test
    fun `contains matches a substring regardless of case`() {
        assertTrue(variableCheckHolds("Downtown Office", VariableComparison.CONTAINS, "office"))
    }

    @Test
    fun `contains does not match a substring that is not there`() {
        assertFalse(variableCheckHolds("Downtown Office", VariableComparison.CONTAINS, "home"))
    }

    @Test
    fun `contains with a blank value matches every stored value`() {
        // The same convention TextFilter uses for a blank pattern.
        assertTrue(variableCheckHolds("anything", VariableComparison.CONTAINS, ""))
        assertTrue(variableCheckHolds("anything", VariableComparison.CONTAINS, null))
    }

    // --- above / below, and the non-numeric case -----------------------------

    @Test
    fun `above and below are ordinary numeric comparisons`() {
        assertTrue(variableCheckHolds("12", VariableComparison.ABOVE, "10"))
        assertFalse(variableCheckHolds("8", VariableComparison.ABOVE, "10"))
        assertTrue(variableCheckHolds("8", VariableComparison.BELOW, "10"))
        assertFalse(variableCheckHolds("12", VariableComparison.BELOW, "10"))
    }

    @Test
    fun `above and below parse decimals, not only whole numbers`() {
        assertTrue(variableCheckHolds("3.5", VariableComparison.ABOVE, "3"))
        assertTrue(variableCheckHolds("2.5", VariableComparison.BELOW, "3"))
    }

    @Test
    fun `a stored value that is not a number is neither above nor below`() {
        // False, not an exception and not a guess: this gates whether
        // unattended actions run, and a comparison that cannot be made must
        // not accidentally read as satisfied.
        assertFalse(variableCheckHolds("not a number", VariableComparison.ABOVE, "10"))
        assertFalse(variableCheckHolds("not a number", VariableComparison.BELOW, "10"))
    }

    @Test
    fun `a configured value that is not a number is neither above nor below`() {
        assertFalse(variableCheckHolds("10", VariableComparison.ABOVE, "not a number"))
        assertFalse(variableCheckHolds("10", VariableComparison.BELOW, "not a number"))
    }

    @Test
    fun `both sides non-numeric is still false, not an accidental match`() {
        assertFalse(variableCheckHolds("nope", VariableComparison.ABOVE, "nope"))
        assertFalse(variableCheckHolds("nope", VariableComparison.BELOW, "nope"))
    }

    // --- VariableComparison.parse: a default for absence, a refusal for junk -

    @Test
    fun `parse reads a known value back`() {
        assertEquals(VariableComparison.CONTAINS, VariableComparison.parse("contains"))
        assertEquals(VariableComparison.IS_SET, VariableComparison.parse("is_set"))
        assertEquals(VariableComparison.ABOVE, VariableComparison.parse("above"))
    }

    @Test
    fun `parse refuses a comparison this build does not know`() {
        // Null rather than a fallback, so the factory can refuse the rule. A
        // comparison nobody in this build understands can only come from a
        // hand-edited file or a newer build, and guessing at it would make the
        // rule gate unattended actions on a question its author did not write.
        assertEquals(null, VariableComparison.parse("sideways"))
    }

    /**
     * The other half of the refusal, at the level a rule actually meets it: the
     * factory has to turn the null into a refused build, so the rule reports
     * that it could not start instead of running on a guess.
     */
    @Test
    fun `the factory refuses a comparison this build does not know`() {
        val factory = VariableCheckFactory(InMemoryVariableStore())

        val thrown = runCatching {
            factory.create(
                mapOf(
                    VariableCheck.CONFIG_NAME to "trips",
                    VariableCheck.CONFIG_COMPARISON to "sideways",
                    VariableCheck.CONFIG_VALUE to "3",
                )
            )
        }.exceptionOrNull()

        assertNotNull("an unknown comparison must refuse the build", thrown)
        val message = thrown?.message.orEmpty()
        assertTrue("the message must name the comparison: $message", message.contains("sideways"))
        assertTrue(
            "the message must say what it does know: $message",
            message.contains(VariableComparison.EQUALS.configValue),
        )
    }

    fun `parse reads absence as the declared default`() {
        // Absence is ordinary: a Choice field declares a default, the editor
        // draws it, and normalise writes it down.
        assertEquals(VariableComparison.EQUALS, VariableComparison.parse(null))
        assertEquals(VariableComparison.EQUALS, VariableComparison.parse(""))
        assertEquals(VariableComparison.EQUALS, VariableComparison.parse("   "))
    }

    // --- VariableCheck.currentlyHolds against a real store -------------------

    @Test
    fun `a set name reads the store's value`() = runTest {
        val store = InMemoryVariableStore(mapOf("mode" to "away"))
        val check = VariableCheck(
            name = "mode",
            comparison = VariableComparison.EQUALS,
            value = "away",
            store = store,
        )

        assertEquals(true, check.currentlyHolds())
    }

    @Test
    fun `an unset name is a definite false for equals`() = runTest {
        val store = InMemoryVariableStore()
        val check = VariableCheck(
            name = "mode",
            comparison = VariableComparison.EQUALS,
            value = "away",
            store = store,
        )

        assertEquals(false, check.currentlyHolds())
    }

    @Test
    fun `an unset name is a definite true for is empty`() = runTest {
        val store = InMemoryVariableStore()
        val check = VariableCheck(
            name = "mode",
            comparison = VariableComparison.IS_EMPTY,
            value = null,
            store = store,
        )

        assertEquals(true, check.currentlyHolds())
    }

    @Test
    fun `an unset name is a definite false for is set`() = runTest {
        val store = InMemoryVariableStore()
        val check = VariableCheck(
            name = "mode",
            comparison = VariableComparison.IS_SET,
            value = null,
            store = store,
        )

        assertEquals(false, check.currentlyHolds())
    }

    @Test
    fun `a store that fails to read answers null, not false`() = runTest {
        val failingStore = object : VariableStore {
            override fun history() = error("not used by this test")
            override suspend fun get(name: String): String? = error("the read failed")
            override suspend fun set(name: String, value: String) = error("not used by this test")
            override suspend fun remove(name: String) = error("not used by this test")
        }
        val check = VariableCheck(
            name = "mode",
            comparison = VariableComparison.IS_SET,
            value = null,
            store = failingStore,
        )

        assertNull(check.currentlyHolds())
    }

    @Test
    fun `events is empty, this can only ever be asked`() = runTest {
        val check = VariableCheck(
            name = "mode",
            comparison = VariableComparison.IS_SET,
            value = null,
            store = InMemoryVariableStore(),
        )

        assertTrue(check.events().toList().isEmpty())
    }

    // --- the factory's declarations -------------------------------------

    @Test
    fun `the factory is a condition, never a way to start a rule`() {
        val factory = VariableCheckFactory()

        assertFalse(factory.producesEvents)
        assertTrue(factory.supportsCondition)
    }

    @Test
    fun `the value field is hidden for is set and is empty, shown otherwise`() {
        val factory = VariableCheckFactory()
        fun shownKeysFor(comparison: VariableComparison) =
            factory.configFields.shownWith(
                mapOf(VariableCheck.CONFIG_COMPARISON to comparison.configValue),
            ).map { it.key }

        assertFalse(VariableCheck.CONFIG_VALUE in shownKeysFor(VariableComparison.IS_SET))
        assertFalse(VariableCheck.CONFIG_VALUE in shownKeysFor(VariableComparison.IS_EMPTY))

        assertTrue(VariableCheck.CONFIG_VALUE in shownKeysFor(VariableComparison.EQUALS))
        assertTrue(VariableCheck.CONFIG_VALUE in shownKeysFor(VariableComparison.NOT_EQUALS))
        assertTrue(VariableCheck.CONFIG_VALUE in shownKeysFor(VariableComparison.CONTAINS))
        assertTrue(VariableCheck.CONFIG_VALUE in shownKeysFor(VariableComparison.ABOVE))
        assertTrue(VariableCheck.CONFIG_VALUE in shownKeysFor(VariableComparison.BELOW))
    }

    @Test
    fun `an untouched rule shows the value field, matching the default comparison`() {
        // The gating key is absent from the config while the editor is
        // showing the default comparison, "equals" — see FieldVisibilityTest
        // for why the sibling's default has to count here too.
        val factory = VariableCheckFactory()

        val shown = factory.configFields.shownWith(emptyMap()).map { it.key }

        assertTrue(VariableCheck.CONFIG_VALUE in shown)
    }
}
