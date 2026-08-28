package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every worked example in `docs/expressions.md`, run the way the app runs one:
 * `{{...}}` resolved with [Substitution.EXPRESSION] first, then the result
 * evaluated.
 *
 * The point of this file is that the reference page cannot drift. Those
 * examples are what a person copies into a rule, and an example that no longer
 * works is worse than no example: it reads as authoritative and it fails on
 * their phone, not here. [ExpressionTest] pins the language itself; this pins
 * the sentences that describe it.
 *
 * The two steps run together on purpose. Each example is written the way it is
 * typed into the field, quotes and fallbacks included, so a change to either
 * half breaks this: to the encoding that inserts a value, or to the grammar
 * that reads it back.
 */
class ExpressionExamplesTest {

    // --- The examples --------------------------------------------------------------

    @Test
    fun `a counter starts by itself on the first run`() {
        assertEquals("1", evaluate("{{app.count | 0}} + 1"))
    }

    @Test
    fun `a counter adds to what is stored`() {
        assertEquals("5", evaluate("{{app.count | 0}} + 1", "app.count" to "4"))
    }

    @Test
    fun `a rule-scope counter reads the same way`() {
        assertEquals("3", evaluate("{{mine.count | 0}} + 1", "mine.count" to "2"))
    }

    @Test
    fun `two texts join, and a number joins as text`() {
        assertEquals(
            "Charged to 82%",
            evaluate(
                "\"Charged to \" + {{battery_level.level}} + \"%\"",
                "battery_level.level" to "82",
            ),
        )
    }

    @Test
    fun `a nested ternary labels a number`() {
        val source = "{{app.level}} < 20 ? \"low\" : {{app.level}} < 60 ? \"middle\" : \"high\""

        assertEquals("low", evaluate(source, "app.level" to "8"))
        assertEquals("middle", evaluate(source, "app.level" to "42"))
        assertEquals("high", evaluate(source, "app.level" to "95"))
    }

    @Test
    fun `a percentage is shortened by round`() {
        assertEquals(
            "41.4%",
            evaluate(
                "round({{app.done}} / {{app.total}} * 100, 1) + \"%\"",
                "app.done" to "12",
                "app.total" to "29",
            ),
        )
    }

    /**
     * The example the per-instance namespaces exist for. Two "Turn a rule on or
     * off" actions in flip mode, each reporting where its own rule ended up, and
     * a third action reading both.
     */
    @Test
    fun `a value depends on two flipping actions`() {
        val source =
            "{{set_rule_enabled.enabled}} == \"on\" and " +
                "{{set_rule_enabled_2.enabled}} == \"on\" ? \"both on\" : " +
                "{{set_rule_enabled.enabled}} == {{set_rule_enabled_2.enabled}} " +
                "? \"both off\" : \"one of each\""

        fun outcome(first: String, second: String) = evaluate(
            source,
            "set_rule_enabled.enabled" to first,
            "set_rule_enabled_2.enabled" to second,
        )

        assertEquals("both on", outcome("on", "on"))
        assertEquals("both off", outcome("off", "off"))
        assertEquals("one of each", outcome("on", "off"))
        assertEquals("one of each", outcome("off", "on"))
    }

    @Test
    fun `a guard with a fallback on every reference survives an absent value`() {
        val source = "{{app.device | }} != \"\" and contains(lower({{app.device | }}), \"buds\")"

        assertEquals("false", evaluate(source))
        assertEquals("true", evaluate(source, "app.device" to "Pixel Buds"))
        assertEquals("false", evaluate(source, "app.device" to "Sony headset"))
    }

    /**
     * Two things worth knowing meet in this one example.
     *
     * A pattern is typed inside a quoted string, so `\d` has to survive this
     * language's own escapes. It does: an escape the language does not
     * recognise keeps its backslash, which is the whole reason a person does
     * not have to double every backslash in a pattern.
     *
     * And `+ ""` is not decoration. A stored value that reads as a number is
     * inserted *as* a number, so the value that most wants a digit pattern is
     * exactly the one that reaches `contains` as the wrong type. Joining an
     * empty string to it makes it text. See the test below this one.
     */
    @Test
    fun `a pattern decides whether a title is a six-digit code`() {
        val source = "contains({{trigger.title | }} + \"\", \"^\\d{6}\$\", \"regex\") " +
            "? \"a code\" : \"not a code\""

        assertEquals("a code", evaluate(source, "trigger.title" to "123456"))
        assertEquals("not a code", evaluate(source, "trigger.title" to "12345"))
        assertEquals("not a code", evaluate(source, "trigger.title" to "1234567"))
        // The fallback keeps the field working when nothing is stored yet.
        assertEquals("not a code", evaluate(source))
    }

    /**
     * The trap the example above works around, on its own so it is impossible
     * to miss: the same reference is a number to the language and text to the
     * person who stored it.
     */
    @Test
    fun `a numeric value has to be joined before a pattern can match it`() {
        val digits = "\"^\\d+\$\""

        val problem = failure("contains({{app.code}}, $digits, \"regex\")", "app.code" to "123456")
        assertTrue(problem, problem.contains("needs text"))

        assertEquals(
            "true",
            evaluate("contains({{app.code}} + \"\", $digits, \"regex\")", "app.code" to "123456"),
        )
    }

    /** One pattern where three `contains` calls joined by `or` used to be needed. */
    @Test
    fun `one pattern replaces three contains calls`() {
        val source = "contains(lower({{trigger.text | }}), " +
            "\"delivered|shipped|out for delivery\", \"regex\")"

        assertEquals("true", evaluate(source, "trigger.text" to "Your parcel was SHIPPED"))
        assertEquals("true", evaluate(source, "trigger.text" to "Out for delivery today"))
        assertEquals("false", evaluate(source, "trigger.text" to "Order received"))
        assertEquals("false", evaluate(source))
    }

    // --- The claims the page makes about the two steps -----------------------------

    /**
     * The correction this file caught: short circuiting cannot rescue an absent
     * reference, because step 1 resolves every reference in the field before
     * step 2 evaluates anything. A guard therefore needs a fallback on the side
     * it is guarding, which is why the example above has two.
     */
    @Test
    fun `a reference on the unreached side of and still fails the field`() {
        val problem = failure("false and contains({{app.device}}, \"buds\")")

        assertTrue(problem.contains("{{app.device}}"))
    }

    @Test
    fun `text is inserted quoted, so a field must not add quotes of its own`() {
        assertEquals("true", evaluate("{{app.state}} == \"on\"", "app.state" to "on"))
        assertTrue(failure("\"{{app.state}}\" == \"on\"", "app.state" to "on").isNotEmpty())
    }

    @Test
    fun `a number is inserted bare, so it stays a number`() {
        assertEquals("43", evaluate("{{app.count}} + 1", "app.count" to "42"))
        // The same value as text would join instead of adding.
        assertEquals("421", evaluate("{{app.count}} + \"1\"", "app.count" to "42"))
    }

    // --- Running an example the way the app does -----------------------------------

    private fun evaluate(source: String, vararg values: Pair<String, String>): String =
        when (val outcome = evaluateExpression(substituted(source, values.toMap()))) {
            is ExpressionOutcome.Ok -> outcome.value
            is ExpressionOutcome.Failed -> throw AssertionError(
                "'$source' did not evaluate: ${outcome.reason}"
            )
        }

    /** The reason [source] fails, from whichever of the two steps rejects it. */
    private fun failure(source: String, vararg values: Pair<String, String>): String {
        val lookup = lookup(values.toMap())
        val step1 = parseTemplate(source).substitute(lookup, Substitution.EXPRESSION)
        if (step1 is Substituted.Failed) return step1.reason
        val step2 = evaluateExpression((step1 as Substituted.Ok).value)
        return when (step2) {
            is ExpressionOutcome.Failed -> step2.reason
            is ExpressionOutcome.Ok ->
                throw AssertionError("'$source' was expected to fail, gave ${step2.value}")
        }
    }

    private fun substituted(source: String, values: Map<String, String>): String =
        when (val result = parseTemplate(source).substitute(lookup(values), Substitution.EXPRESSION)) {
            is Substituted.Ok -> result.value
            is Substituted.Failed ->
                throw AssertionError("'$source' did not resolve: ${result.reason}")
        }

    private fun lookup(values: Map<String, String>) = VariableLookup { ref ->
        when (val value = values["${ref.scope}.${ref.name}"]) {
            null -> VariableValue.Absent("nothing stored under this name")
            else -> VariableValue.Present(value)
        }
    }
}
