package app.phueber.trigly.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [evaluateExpression], the language `set_variable`'s evaluate mode runs. See
 * `Expression.kt` for the grammar and why it stops where it does.
 *
 * `{{...}}` substitution has already run by the time any of these strings
 * exist, so every case here is written the way [Substitution.EXPRESSION]
 * would have produced it: a substituted number bare, a substituted string
 * quoted. [SubstitutionTest] and the `Substitution.EXPRESSION` cases below
 * cover that half.
 */
class ExpressionTest {

    private fun ok(source: String): String =
        (evaluateExpression(source) as ExpressionOutcome.Ok).value

    private fun failed(source: String): String =
        (evaluateExpression(source) as ExpressionOutcome.Failed).reason

    // --- numbers -----------------------------------------------------------------

    @Test
    fun `a bare number evaluates to itself`() {
        assertEquals("5", ok("5"))
    }

    @Test
    fun `a decimal number evaluates to itself`() {
        assertEquals("3.5", ok("3.5"))
    }

    @Test
    fun `a whole-number result drops the trailing zero`() {
        assertEquals("5", ok("2.5 + 2.5"))
    }

    @Test
    fun `a negative number via unary minus`() {
        assertEquals("-5", ok("-5"))
    }

    @Test
    fun `unary plus on a number is a no-op`() {
        assertEquals("5", ok("+5"))
    }

    @Test
    fun `unary plus on text fails`() {
        assertTrue(failed("+\"x\"").contains("number"))
    }

    // --- strings and joining -------------------------------------------------------

    @Test
    fun `a quoted string evaluates to its contents`() {
        assertEquals("hello", ok("\"hello\""))
    }

    @Test
    fun `a string with an escaped quote`() {
        assertEquals("he said \"hi\"", ok("\"he said \\\"hi\\\"\""))
    }

    @Test
    fun `a string with an escaped backslash`() {
        assertEquals("a\\b", ok("\"a\\\\b\""))
    }

    @Test
    fun `plus joins two strings`() {
        assertEquals("ab", ok("\"a\" + \"b\""))
    }

    @Test
    fun `plus joins a string and a number as text`() {
        assertEquals("Count 1", ok("\"Count \" + 1"))
    }

    @Test
    fun `plus adds two numbers, not joins them`() {
        assertEquals("3", ok("1 + 2"))
    }

    // --- arithmetic ------------------------------------------------------------------

    @Test
    fun `subtraction`() {
        assertEquals("1", ok("3 - 2"))
    }

    @Test
    fun `multiplication`() {
        assertEquals("6", ok("2 * 3"))
    }

    @Test
    fun `division`() {
        assertEquals("2.5", ok("5 / 2"))
    }

    @Test
    fun `division that does not terminate is rounded rather than failing`() {
        val result = ok("1 / 3")
        assertTrue(result, result.startsWith("0.333333"))
    }

    @Test
    fun `division by zero fails with a readable reason`() {
        assertTrue(failed("1 / 0").contains("zero"))
    }

    @Test
    fun `modulo`() {
        assertEquals("1", ok("7 % 2"))
    }

    @Test
    fun `modulo by zero fails with a readable reason`() {
        assertTrue(failed("7 % 0").contains("zero"))
    }

    @Test
    fun `repeated fractional addition does not drift the way binary floating point does`() {
        // 0.1 ten times is exactly 1 in BigDecimal, unlike in Double.
        val expr = (1..10).joinToString(" + ") { "0.1" }
        assertEquals("1", ok(expr))
    }

    // --- precedence and parentheses ---------------------------------------------------

    @Test
    fun `multiplication binds tighter than addition`() {
        assertEquals("7", ok("1 + 2 * 3"))
    }

    @Test
    fun `parentheses override precedence`() {
        assertEquals("9", ok("(1 + 2) * 3"))
    }

    @Test
    fun `nested parentheses`() {
        assertEquals("10", ok("((1 + 1) * (2 + 3))"))
    }

    // --- comparisons -----------------------------------------------------------------

    @Test
    fun `numeric less than`() {
        assertEquals("true", ok("5 < 20"))
        assertEquals("false", ok("25 < 20"))
    }

    @Test
    fun `numeric comparisons treat 5 and 5point0 as equal`() {
        assertEquals("true", ok("5 == 5.0"))
    }

    @Test
    fun `string comparison is lexicographic`() {
        assertEquals("true", ok("\"apple\" < \"banana\""))
        assertEquals("false", ok("\"banana\" < \"apple\""))
    }

    @Test
    fun `string equality`() {
        assertEquals("true", ok("\"a\" == \"a\""))
        assertEquals("false", ok("\"a\" == \"b\""))
    }

    @Test
    fun `comparing a number and a string fails with a readable reason`() {
        val reason = failed("5 < \"a\"")
        assertTrue(reason, reason.contains("compare"))
    }

    @Test
    fun `a number and a string of different types are never equal`() {
        assertEquals("false", ok("5 == \"5\""))
        assertEquals("true", ok("5 != \"5\""))
    }

    // --- boolean and, or, not -----------------------------------------------------

    @Test
    fun `and`() {
        assertEquals("true", ok("true and true"))
        assertEquals("false", ok("true and false"))
    }

    @Test
    fun `or`() {
        assertEquals("true", ok("false or true"))
        assertEquals("false", ok("false or false"))
    }

    @Test
    fun `not`() {
        assertEquals("false", ok("not true"))
        assertEquals("true", ok("not false"))
    }

    @Test
    fun `and short-circuits so the right side is never evaluated`() {
        // An unknown function on the right would fail if it were evaluated.
        assertEquals("false", ok("false and nope()"))
    }

    @Test
    fun `or short-circuits so the right side is never evaluated`() {
        assertEquals("true", ok("true or nope()"))
    }

    @Test
    fun `and binds tighter than or`() {
        // true or (false and false) = true, not (true or false) and false = false
        assertEquals("true", ok("true or false and false"))
    }

    // --- ternary ---------------------------------------------------------------------

    @Test
    fun `ternary picks the true branch`() {
        assertEquals("low", ok("15 < 20 ? \"low\" : \"ok\""))
    }

    @Test
    fun `ternary picks the false branch`() {
        assertEquals("ok", ok("25 < 20 ? \"low\" : \"ok\""))
    }

    @Test
    fun `ternary is right-associative, chaining without parentheses`() {
        val expr = "1 == 1 ? \"a\" : 1 == 2 ? \"b\" : \"c\""
        assertEquals("a", ok(expr))
    }

    @Test
    fun `a non-boolean ternary condition fails with a readable reason`() {
        assertTrue(failed("1 ? \"a\" : \"b\"").contains("ternary condition"))
    }

    // --- functions ---------------------------------------------------------------------

    @Test
    fun `upper`() {
        assertEquals("PIXEL BUDS", ok("upper(\"Pixel Buds\")"))
    }

    @Test
    fun `lower`() {
        assertEquals("pixel buds", ok("lower(\"Pixel Buds\")"))
    }

    @Test
    fun `trim`() {
        assertEquals("hi", ok("trim(\"  hi  \")"))
    }

    @Test
    fun `length`() {
        assertEquals("5", ok("length(\"hello\")"))
    }

    @Test
    fun `contains true`() {
        assertEquals("true", ok("contains(\"hello world\", \"world\")"))
    }

    @Test
    fun `contains false`() {
        assertEquals("false", ok("contains(\"hello world\", \"bye\")"))
    }

    @Test
    fun `round`() {
        assertEquals("3.14", ok("round(3.14159, 2)"))
    }

    @Test
    fun `round to zero places`() {
        assertEquals("3", ok("round(2.5, 0)"))
    }

    @Test
    fun `a function composed with an operator`() {
        assertEquals("PIXEL BUDS!", ok("upper(\"Pixel Buds\") + \"!\""))
    }

    @Test
    fun `an unknown function fails and names it`() {
        val reason = failed("shout(\"hi\")")
        assertTrue(reason, reason.contains("shout"))
    }

    @Test
    fun `a function called with the wrong number of arguments fails`() {
        val reason = failed("upper(\"a\", \"b\")")
        assertTrue(reason, reason.contains("upper"))
    }

    @Test
    fun `a string function called on a number fails with a readable reason`() {
        assertTrue(failed("upper(5)").contains("text"))
    }

    // --- malformed expressions ----------------------------------------------------

    @Test
    fun `an unclosed string fails and names where it started`() {
        val reason = failed("\"never closes")
        assertTrue(reason, reason.contains("Unclosed"))
    }

    @Test
    fun `a trailing operator fails to parse`() {
        assertTrue(evaluateExpression("1 +") is ExpressionOutcome.Failed)
    }

    @Test
    fun `an empty expression fails to parse`() {
        assertTrue(evaluateExpression("") is ExpressionOutcome.Failed)
    }

    @Test
    fun `a stray character fails to parse`() {
        assertTrue(evaluateExpression("1 @ 2") is ExpressionOutcome.Failed)
    }

    @Test
    fun `unbalanced parentheses fail to parse`() {
        assertTrue(evaluateExpression("(1 + 2").let { it is ExpressionOutcome.Failed })
    }

    // --- the two bounds -----------------------------------------------------------------

    @Test
    fun `an expression at the length limit still evaluates`() {
        // "1" repeated up to the limit is a huge, but valid, number literal.
        val source = "1".repeat(MAX_EXPRESSION_LENGTH)
        assertTrue(evaluateExpression(source) is ExpressionOutcome.Ok)
    }

    @Test
    fun `an expression over the length limit is refused before it is parsed`() {
        val source = "1".repeat(MAX_EXPRESSION_LENGTH + 1)
        val reason = failed(source)
        assertTrue(reason, reason.contains(MAX_EXPRESSION_LENGTH.toString()))
    }

    @Test
    fun `an expression within the depth limit still evaluates`() {
        val depth = MAX_EXPRESSION_DEPTH - 1
        val source = "(".repeat(depth) + "1" + ")".repeat(depth)
        assertTrue(evaluateExpression(source) is ExpressionOutcome.Ok)
    }

    @Test
    fun `an expression over the depth limit is refused rather than overflowing the stack`() {
        val depth = MAX_EXPRESSION_DEPTH * 4
        val source = "(".repeat(depth) + "1" + ")".repeat(depth)
        val reason = failed(source)
        assertTrue(reason, reason.contains("nested"))
    }

    @Test
    fun `a long chain of unary operators also hits the depth limit`() {
        val source = "not ".repeat(MAX_EXPRESSION_DEPTH * 4) + "true"
        assertTrue(evaluateExpression(source) is ExpressionOutcome.Failed)
    }

    // --- looksLikeExpressionNumber, which Substitution.EXPRESSION relies on -------------

    @Test
    fun `looksLikeExpressionNumber accepts a whole number`() {
        assertTrue(looksLikeExpressionNumber("42"))
    }

    @Test
    fun `looksLikeExpressionNumber accepts a decimal`() {
        assertTrue(looksLikeExpressionNumber("3.14"))
    }

    @Test
    fun `looksLikeExpressionNumber accepts a negative number`() {
        assertTrue(looksLikeExpressionNumber("-5"))
    }

    @Test
    fun `looksLikeExpressionNumber rejects text`() {
        assertTrue(!looksLikeExpressionNumber("Pixel Buds"))
    }

    @Test
    fun `looksLikeExpressionNumber rejects a trailing dot with no digits`() {
        assertTrue(!looksLikeExpressionNumber("5."))
    }

    @Test
    fun `looksLikeExpressionNumber rejects an empty string`() {
        assertTrue(!looksLikeExpressionNumber(""))
    }
}
