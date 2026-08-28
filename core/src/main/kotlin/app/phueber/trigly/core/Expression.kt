package app.phueber.trigly.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The small expression language `set_variable`'s evaluate mode runs, after
 * `{{...}}` substitution has already turned every reference into a literal.
 * See [Substitution.EXPRESSION] for that half, and `docs/variables.md` for why
 * a value that computes was scoped as a closed grammar rather than an embedded
 * script.
 *
 * A rule is a file someone else can import onto their own phone. Embedded
 * JavaScript or Python would let a shared rule carry arbitrary code onto a
 * stranger's device, which is the reason this language has no variables of its
 * own, no loops, no functions a person can define, and nothing that reads or
 * writes anything outside the string it is given.
 *
 * ### Grammar
 *
 * ```
 * expression    := ternary
 * ternary       := or ( '?' expression ':' expression )?
 * or            := and ( 'or' and )*
 * and           := not ( 'and' not )*
 * not           := 'not' not | equality
 * equality      := comparison ( ( '==' | '!=' ) comparison )*
 * comparison    := additive ( ( '<' | '<=' | '>' | '>=' ) additive )*
 * additive      := multiplicative ( ( '+' | '-' ) multiplicative )*
 * multiplicative:= unary ( ( '*' | '/' | '%' ) unary )*
 * unary         := ( '-' | '+' ) unary | primary
 * primary       := NUMBER | STRING | 'true' | 'false'
 *                | IDENT '(' ( expression ( ',' expression )* )? ')'
 *                | '(' expression ')'
 * NUMBER        := '-'? DIGIT+ ( '.' DIGIT+ )?
 * STRING        := '"' ( any char except unescaped '"' | '\"' | '\\' | '\n' | '\t' )* '"'
 * ```
 *
 * `and`, `or` and `not` are keywords, not functions, and short-circuit: the
 * right side of `and`/`or` is evaluated only when the left side does not
 * already decide the result. That matters because this language has no other
 * way to guard a call, such as `false and contains({{x}}, "y")` when `{{x}}`
 * might not even be a string.
 *
 * `+` adds two numbers and joins anything else as text: `1 + 1` is `2`, and
 * `"Count: " + {{app.count}}` is text even though `{{app.count}}` reads as a
 * number, because one side is already a string.
 *
 * Six functions: [FUNCTION_UPPER], [FUNCTION_LOWER], [FUNCTION_TRIM],
 * [FUNCTION_LENGTH], [FUNCTION_CONTAINS] and [FUNCTION_ROUND], all string or
 * number in, nothing else. `round` was added past the five the feature was
 * chosen from, because `/` on two [BigDecimal]s can produce a value with more
 * digits than anyone building a rule wants read aloud or posted in a
 * notification, and there was no other way in this grammar to shorten one.
 * Resist adding more: every function is a fixed, reviewed piece of behaviour a
 * shared rule can invoke on someone else's phone, which is a different safety
 * question from a field that merely inserts a value.
 *
 * ### The one function that takes a mode
 *
 * [FUNCTION_CONTAINS] accepts a third argument, the match mode, and it is the
 * same [TextMatchMode] a trigger's text filter uses: `"contains"` is a literal
 * substring and stays the default, `"regex"` searches with a regular
 * expression. An argument on the function rather than a seventh function,
 * because every rule already saved says `contains(a, b)` and has to keep
 * meaning exactly that. A pattern is only a pattern where somebody asked for
 * one; `contains({{app.name}}, "a.b")` looks for a dot.
 *
 * The mode word is matched exactly, and an unknown one fails. [TextMatchMode.parse]
 * is lenient because it reads stored config, where a rule from a newer build
 * has to load anyway. That is the wrong answer here: an expression is text
 * somebody typed a moment ago, and reading `"rexeg"` as a literal substring
 * would give a wrong answer that looks like a right one.
 *
 * Both modes are case *sensitive*, where a trigger's filter is not. The
 * two-argument `contains` always was, and [FUNCTION_UPPER] and [FUNCTION_LOWER]
 * are how this language says otherwise. `(?i)` at the front of a pattern is the
 * regex way to ask for the same thing.
 *
 * A regex is searched with `containsMatchIn`, not `matches`, exactly as
 * [TextFilter.of] does it: a pattern reads like grep, and `^` and `$` are there
 * for anyone who wants the whole string.
 *
 * ### Arithmetic
 *
 * `+ - * %` and comparisons use [BigDecimal], the same choice
 * [addToVariable] made for `set_variable`'s `add` mode and for the same
 * reason: a running total built from repeated fractional additions drifts
 * visibly in binary floating point, and a rule that computes a total is
 * exactly the case this language exists for. `/` rounds to
 * [DIVISION_PRECISION] significant digits with [RoundingMode.HALF_UP], because
 * exact decimal division does not always terminate (`1 / 3`), and a division
 * that terminates is unaffected by rounding it never needed. A result is
 * formatted the same way [addToVariable] formats one: trailing zeros stripped,
 * so `5` reads as `5` and not `5.0`, the first time a counter passes through
 * an expression as much as the tenth.
 *
 * ### Safety is exactly three numbers
 *
 * This language cannot run away on its own: no loops, no recursion a person
 * can write, and no call that reaches outside the string it was given. Two of
 * the three numbers are there because a small piece of text can still hurt
 * the process that *reads* it through its own call stack, and neither of
 * those two needs a timeout or a thread to enforce. The third is there
 * because one function is not this language's own work, and it is a timeout
 * on a thread, which the other two are not.
 *
 * - [MAX_EXPRESSION_LENGTH] characters of input.
 * - [MAX_EXPRESSION_DEPTH] levels of nesting: a parenthesis, a function call,
 *   a ternary branch, or a chain of unary `-`, `+` or `not`.
 * - How long one regular expression search may run:
 *   `REGEX_GUARD_TIMEOUT_MILLIS`, on the one shared thread `RegexBudget.kt`'s
 *   [RegexGuard] gives every bounded search in this app. A backtracking engine
 *   is the one thing here that can do an unbounded amount of work on a bounded
 *   input, so it is the one thing here that needed a bound of its own. The
 *   paragraph below used to say that whoever added a feature like that had to
 *   design the replacement bound before shipping it. This is that bound.
 *
 *   The bound itself lives in `RegexBudget.kt`, not in this file: [TextFilter]'s
 *   `regex` mode needed the identical bound for the identical reason, so the
 *   number, the shared thread and the type that reports a refusal are shared
 *   rather than kept as two copies that could drift apart. What follows is why
 *   the bound is shaped the way it is; where it lives does not change that.
 *
 * **This bound used to be a rate, counted in characters read, and it was
 * wrong to become anything else.** That argument still holds and is worth
 * restating rather than quietly dropping: a bound in milliseconds lets a rule
 * work on a fast phone and fail on a slow one, which is the one failure this
 * project spends the most effort avoiding everywhere else it appears. A rate
 * does not have that problem, because it charges the same for the same work
 * on every device.
 *
 * **The mechanism a rate needs does not exist on Android, and there is no
 * third option that both counts work and runs on a phone.** The rate this
 * language used counted characters read through a wrapped `CharSequence`.
 * `java.util.regex.Matcher` converts its input to a `String` before it reads
 * a single character of it, whenever it is handed anything else, so the count
 * never moved and nothing was ever refused on a device. `docs/todo.md` T24 has
 * the full account, including the correctness bug the same conversion caused
 * before it was found. The project's choice, once that was known, was to
 * bound the wall clock instead: not because milliseconds became the right
 * unit, but because it is the only bound left that is real on the platform
 * this app ships to. A bound that is not real anywhere real rules run is
 * worth less than a bound that is real everywhere but imperfect on one axis.
 *
 * **The imperfection is real and is stated once, in `RegexBudget.kt`, rather
 * than here.** A wall-clock bound has exactly the failure mode the paragraph
 * above warns against: an honest pattern over an unusually large piece of
 * text can cost more, in milliseconds, on a slower device, and the measured
 * headroom is generous but not infinite. `RegexBudget.kt`'s KDoc has the
 * measurements and the exact size of that headroom; this file does not repeat
 * them, so that the numbers live in one place and cannot drift out of step
 * with the constant they justify.
 *
 * **One search being refused is not one evaluation being refused; it is the
 * whole evaluation failing, which is a stronger property than the old bound
 * had.** `regexFinds` turns a refusal into an [ExpressionError], which
 * [evaluateExpression] turns into [ExpressionOutcome.Failed] and nothing runs
 * after it. So a single expression can be charged for at most one refusal's
 * wait, never several: the old rate bound kept a whole evaluation's total read
 * count down by capping the one haystack every search in it could share: see
 * [MAX_EXPRESSION_LENGTH]. This bound keeps a whole evaluation's total wait
 * down by a different route, aborting on the first search that does not
 * answer in time rather than letting a second one start.
 *
 * **The textbook example is not the threat, and must not be read as
 * reassurance.** `(a+)+b` over thirty `a`s finishes in well under a
 * millisecond on the JDK these numbers were first measured on, because that
 * engine optimizes that exact shape away. That is one engine's optimization of
 * one shape; the pattern arrives in a rule somebody else wrote, and ART is not
 * that engine. What makes the claim is the bound, not any engine's good
 * behaviour on the famous example.
 *
 * **This claim is only true while the grammar stays this small.** The day
 * this language gains a loop, a user-defined function, or anything that reads
 * or writes state across one evaluation, all three bounds stop being the whole
 * safety story, and whoever adds that feature has to design what replaces
 * them before shipping it, not after.
 */
sealed interface ExpressionOutcome {

    data class Ok(val value: String) : ExpressionOutcome

    /**
     * [reason] names the position or the token at fault, the way
     * [Substituted.Failed] names the reference at fault, so a person editing
     * the rule can find what to fix without a stack trace.
     */
    data class Failed(val reason: String) : ExpressionOutcome
}

/** The input length this language accepts. See the "Safety" section above. */
const val MAX_EXPRESSION_LENGTH: Int = 2_000

/** The nesting depth this language accepts. See the "Safety" section above. */
const val MAX_EXPRESSION_DEPTH: Int = 64

// REGEX_GUARD_TIMEOUT_MILLIS, RegexGuard and RegexRun live in RegexBudget.kt.
// See the "Safety" section above for why this file's own bound and
// TextFilter's are the same bound.

const val FUNCTION_UPPER = "upper"
const val FUNCTION_LOWER = "lower"
const val FUNCTION_TRIM = "trim"
const val FUNCTION_LENGTH = "length"
const val FUNCTION_CONTAINS = "contains"
const val FUNCTION_ROUND = "round"

private const val DIVISION_PRECISION = 20

/**
 * Runs [source] as an expression and returns its result as a string, since
 * everything in Trigly is a string at the boundary. Never throws: a problem
 * anywhere in lexing, parsing or evaluating comes back as [ExpressionOutcome.Failed]
 * with a reason naming what was wrong, the same way an unresolvable `{{...}}`
 * reference does today. See [Substitution.EXPRESSION] for what runs before this.
 */
fun evaluateExpression(source: String): ExpressionOutcome {
    if (source.length > MAX_EXPRESSION_LENGTH) {
        return ExpressionOutcome.Failed(
            "This expression is ${source.length} characters long. The limit is " +
                "$MAX_EXPRESSION_LENGTH."
        )
    }
    return try {
        val tokens = Lexer(source).tokenize()
        val ast = Parser(tokens).parseProgram()
        ExpressionOutcome.Ok(format(Evaluator.eval(ast)))
    } catch (error: ExpressionError) {
        ExpressionOutcome.Failed(error.reason)
    }
}

/**
 * Whether [text] is this language's NUMBER token, so [Substitution.EXPRESSION]
 * can decide whether a substituted value is safe to insert bare.
 *
 * This and [Lexer.readNumber] have to agree. If this says yes and the lexer
 * disagrees, a value inserted bare on the strength of this check becomes a
 * syntax error the person who wrote `{{...}}` never typed and cannot see
 * coming.
 */
fun looksLikeExpressionNumber(text: String): Boolean = NUMBER_PATTERN.matches(text)

private val NUMBER_PATTERN = Regex("""-?\d+(\.\d+)?""")

/** Thrown only inside this file, and caught at [evaluateExpression]'s boundary. */
private class ExpressionError(val reason: String) : Exception(reason)

// --- Lexing ------------------------------------------------------------------------

private sealed interface Token {
    val position: Int

    data class NumberTok(val text: String, override val position: Int) : Token
    data class StringTok(val value: String, override val position: Int) : Token
    data class Ident(val text: String, override val position: Int) : Token
    data class Symbol(val text: String, override val position: Int) : Token
    data class End(override val position: Int) : Token
}

private fun describe(token: Token): String = when (token) {
    is Token.NumberTok -> token.text
    is Token.StringTok -> "\"${token.value}\""
    is Token.Ident -> token.text
    is Token.Symbol -> token.text
    is Token.End -> "the end of the expression"
}

private class Lexer(private val source: String) {
    private var pos = 0

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            skipWhitespace()
            if (pos >= source.length) {
                tokens += Token.End(pos)
                return tokens
            }
            val char = source[pos]
            tokens += when {
                char.isDigit() -> readNumber()
                char == '"' -> readString()
                char.isLetter() || char == '_' -> readIdent()
                else -> readSymbol()
            }
        }
    }

    private fun skipWhitespace() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }

    /** NUMBER, per the grammar in the file KDoc. Must agree with [NUMBER_PATTERN]. */
    private fun readNumber(): Token {
        val start = pos
        while (pos < source.length && source[pos].isDigit()) pos++
        if (pos < source.length && source[pos] == '.' &&
            pos + 1 < source.length && source[pos + 1].isDigit()
        ) {
            pos++
            while (pos < source.length && source[pos].isDigit()) pos++
        }
        return Token.NumberTok(source.substring(start, pos), start)
    }

    private fun readString(): Token {
        val start = pos
        pos++ // the opening quote
        val value = StringBuilder()
        while (true) {
            if (pos >= source.length) {
                throw ExpressionError("Unclosed string starting at position $start.")
            }
            val char = source[pos]
            when {
                char == '"' -> {
                    pos++
                    return Token.StringTok(value.toString(), start)
                }

                char == '\\' && pos + 1 < source.length -> {
                    when (val next = source[pos + 1]) {
                        '"' -> value.append('"')
                        '\\' -> value.append('\\')
                        'n' -> value.append('\n')
                        't' -> value.append('\t')
                        // An unrecognised escape keeps the backslash, rather
                        // than silently swallowing it: `\d` in a rule someone
                        // meant as a literal backslash stays visible as one.
                        else -> value.append('\\').append(next)
                    }
                    pos += 2
                }

                else -> {
                    value.append(char)
                    pos++
                }
            }
        }
    }

    private fun readIdent(): Token {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        return Token.Ident(source.substring(start, pos), start)
    }

    private fun readSymbol(): Token {
        val start = pos
        val two = source.substring(pos, minOf(pos + 2, source.length))
        if (two in TWO_CHAR_SYMBOLS) {
            pos += 2
            return Token.Symbol(two, start)
        }
        val one = source[pos]
        if (one in ONE_CHAR_SYMBOLS) {
            pos++
            return Token.Symbol(one.toString(), start)
        }
        throw ExpressionError("Unexpected character '$one' at position $start.")
    }

    private companion object {
        val TWO_CHAR_SYMBOLS = setOf("<=", ">=", "==", "!=")
        val ONE_CHAR_SYMBOLS = setOf('+', '-', '*', '/', '%', '<', '>', '(', ')', ',', '?', ':')
    }
}

// --- Parsing -------------------------------------------------------------------------

private sealed interface Node {
    data class NumberLit(val value: BigDecimal) : Node
    data class StringLit(val value: String) : Node
    data class BoolLit(val value: Boolean) : Node
    data class Unary(val op: String, val operand: Node) : Node
    data class Binary(val op: String, val left: Node, val right: Node) : Node
    data class Ternary(val condition: Node, val ifTrue: Node, val ifFalse: Node) : Node
    data class Call(val name: String, val args: List<Node>, val position: Int) : Node
}

/**
 * Recursive descent over the grammar in the file KDoc, one function per
 * precedence level.
 *
 * [depth] is the whole reason this class is not a set of free functions: it
 * has to be threaded through every recursive call, incremented before the
 * call and checked against [MAX_EXPRESSION_DEPTH] **before** that call is
 * made, not after. Checking after would still let the call happen, which is
 * the one thing a stack-overflow guard cannot do.
 */
private class Parser(private val tokens: List<Token>) {
    private var pos = 0
    private var depth = 0

    fun parseProgram(): Node {
        val expr = parseExpression()
        if (current() !is Token.End) {
            throw ExpressionError(
                "Unexpected '${describe(current())}' at position ${current().position}."
            )
        }
        return expr
    }

    private fun parseExpression(): Node = parseTernary()

    private fun parseTernary(): Node {
        val condition = parseOr()
        if (!peekSymbol("?")) return condition
        advance()
        return nested {
            val ifTrue = parseExpression()
            expectSymbol(":")
            val ifFalse = parseExpression()
            Node.Ternary(condition, ifTrue, ifFalse)
        }
    }

    private fun parseOr(): Node {
        var left = parseAnd()
        while (peekIdent("or")) {
            advance()
            left = Node.Binary("or", left, parseAnd())
        }
        return left
    }

    private fun parseAnd(): Node {
        var left = parseNot()
        while (peekIdent("and")) {
            advance()
            left = Node.Binary("and", left, parseNot())
        }
        return left
    }

    private fun parseNot(): Node {
        if (!peekIdent("not")) return parseEquality()
        advance()
        return nested { Node.Unary("not", parseNot()) }
    }

    private fun parseEquality(): Node {
        var left = parseComparison()
        while (true) {
            val op = peekSymbolOneOf("==", "!=") ?: return left
            advance()
            left = Node.Binary(op, left, parseComparison())
        }
    }

    private fun parseComparison(): Node {
        var left = parseAdditive()
        while (true) {
            val op = peekSymbolOneOf("<=", ">=", "<", ">") ?: return left
            advance()
            left = Node.Binary(op, left, parseAdditive())
        }
    }

    private fun parseAdditive(): Node {
        var left = parseMultiplicative()
        while (true) {
            val op = peekSymbolOneOf("+", "-") ?: return left
            advance()
            left = Node.Binary(op, left, parseMultiplicative())
        }
    }

    private fun parseMultiplicative(): Node {
        var left = parseUnary()
        while (true) {
            val op = peekSymbolOneOf("*", "/", "%") ?: return left
            advance()
            left = Node.Binary(op, left, parseUnary())
        }
    }

    private fun parseUnary(): Node {
        val op = peekSymbolOneOf("-", "+") ?: return parsePrimary()
        advance()
        return nested { Node.Unary(op, parseUnary()) }
    }

    private fun parsePrimary(): Node = when (val token = current()) {
        is Token.NumberTok -> {
            advance()
            Node.NumberLit(BigDecimal(token.text))
        }

        is Token.StringTok -> {
            advance()
            Node.StringLit(token.value)
        }

        is Token.Ident -> when (token.text) {
            "true" -> {
                advance()
                Node.BoolLit(true)
            }

            "false" -> {
                advance()
                Node.BoolLit(false)
            }

            else -> parseCall(token)
        }

        is Token.Symbol -> if (token.text == "(") {
            advance()
            nested { parseExpression().also { expectSymbol(")") } }
        } else {
            throw ExpressionError("Unexpected '${token.text}' at position ${token.position}.")
        }

        is Token.End -> throw ExpressionError("The expression ends unexpectedly.")
    }

    private fun parseCall(nameToken: Token.Ident): Node {
        advance() // the function name
        expectSymbol("(")
        return nested {
            val args = mutableListOf<Node>()
            if (!peekSymbol(")")) {
                args += parseExpression()
                while (peekSymbol(",")) {
                    advance()
                    args += parseExpression()
                }
            }
            expectSymbol(")")
            Node.Call(nameToken.text, args, nameToken.position)
        }
    }

    /** Runs [block] one nesting level deeper, enforcing [MAX_EXPRESSION_DEPTH]. */
    private inline fun <T> nested(block: () -> T): T {
        depth++
        if (depth > MAX_EXPRESSION_DEPTH) {
            throw ExpressionError(
                "This expression is nested more than $MAX_EXPRESSION_DEPTH levels deep."
            )
        }
        try {
            return block()
        } finally {
            depth--
        }
    }

    private fun current(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos++]

    private fun peekSymbol(text: String): Boolean =
        current().let { it is Token.Symbol && it.text == text }

    private fun peekSymbolOneOf(vararg options: String): String? =
        current().let { if (it is Token.Symbol && it.text in options) it.text else null }

    private fun peekIdent(text: String): Boolean =
        current().let { it is Token.Ident && it.text == text }

    private fun expectSymbol(text: String) {
        if (!peekSymbol(text)) {
            val found = describe(current())
            throw ExpressionError(
                "Expected '$text' at position ${current().position}, found '$found'."
            )
        }
        advance()
    }
}

// --- Evaluation ------------------------------------------------------------------------

private sealed interface Value {
    data class Num(val value: BigDecimal) : Value
    data class Str(val value: String) : Value
    data class Bool(val value: Boolean) : Value
}

private fun format(value: Value): String = when (value) {
    is Value.Num -> formatNumber(value.value)
    is Value.Str -> value.value
    is Value.Bool -> if (value.value) "true" else "false"
}

/**
 * Trailing zeros stripped, the same as [addToVariable] formats its result, so
 * a whole number reads as `5` rather than `5.0` whether it came from `add` or
 * from this language.
 */
private fun formatNumber(value: BigDecimal): String =
    if (value.compareTo(BigDecimal.ZERO) == 0) "0" else value.stripTrailingZeros().toPlainString()

private object Evaluator {

    fun eval(node: Node): Value = when (node) {
        is Node.NumberLit -> Value.Num(node.value)
        is Node.StringLit -> Value.Str(node.value)
        is Node.BoolLit -> Value.Bool(node.value)
        is Node.Unary -> evalUnary(node)
        is Node.Binary -> evalBinary(node)
        is Node.Ternary -> if (asBool(eval(node.condition), "the ternary condition")) {
            eval(node.ifTrue)
        } else {
            eval(node.ifFalse)
        }

        is Node.Call -> evalCall(node)
    }

    private fun evalUnary(node: Node.Unary): Value = when (node.op) {
        "-" -> Value.Num(asNumber(eval(node.operand), "unary -").negate())
        "+" -> Value.Num(asNumber(eval(node.operand), "unary +"))
        "not" -> Value.Bool(!asBool(eval(node.operand), "not"))
        else -> throw IllegalStateException("unreachable unary operator ${node.op}")
    }

    private fun evalBinary(node: Node.Binary): Value = when (node.op) {
        // Short-circuit: the right side is not even evaluated once the left
        // side has decided the result. See the file KDoc.
        "and" -> if (!asBool(eval(node.left), "and")) {
            Value.Bool(false)
        } else {
            Value.Bool(asBool(eval(node.right), "and"))
        }

        "or" -> if (asBool(eval(node.left), "or")) {
            Value.Bool(true)
        } else {
            Value.Bool(asBool(eval(node.right), "or"))
        }

        else -> evalOperator(node.op, eval(node.left), eval(node.right))
    }

    private fun evalOperator(op: String, left: Value, right: Value): Value = when (op) {
        "+" -> if (left is Value.Num && right is Value.Num) {
            Value.Num(left.value.add(right.value))
        } else {
            Value.Str(format(left) + format(right))
        }

        "-" -> Value.Num(asNumber(left, "-").subtract(asNumber(right, "-")))
        "*" -> Value.Num(asNumber(left, "*").multiply(asNumber(right, "*")))

        "/" -> {
            val divisor = asNumber(right, "/")
            if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                throw ExpressionError("Division by zero.")
            }
            val precision = MathContext(DIVISION_PRECISION, RoundingMode.HALF_UP)
            Value.Num(asNumber(left, "/").divide(divisor, precision))
        }

        "%" -> {
            val divisor = asNumber(right, "%")
            if (divisor.compareTo(BigDecimal.ZERO) == 0) {
                throw ExpressionError("Division by zero.")
            }
            Value.Num(asNumber(left, "%").remainder(divisor))
        }

        "==" -> Value.Bool(valuesEqual(left, right))
        "!=" -> Value.Bool(!valuesEqual(left, right))

        "<", "<=", ">", ">=" -> Value.Bool(compareValues(op, left, right))

        else -> throw IllegalStateException("unreachable binary operator $op")
    }

    private fun valuesEqual(left: Value, right: Value): Boolean = when {
        left is Value.Num && right is Value.Num -> left.value.compareTo(right.value) == 0
        left is Value.Str && right is Value.Str -> left.value == right.value
        left is Value.Bool && right is Value.Bool -> left.value == right.value
        // Two different types are never equal to each other. This language
        // has no cast, so there is no honest way to make "5" equal 5.
        else -> false
    }

    private fun compareValues(op: String, left: Value, right: Value): Boolean {
        val order = when {
            left is Value.Num && right is Value.Num -> left.value.compareTo(right.value)
            left is Value.Str && right is Value.Str -> left.value.compareTo(right.value)
            else -> throw ExpressionError(
                "Cannot compare ${typeName(left)} and ${typeName(right)} with '$op'."
            )
        }
        return when (op) {
            "<" -> order < 0
            "<=" -> order <= 0
            ">" -> order > 0
            ">=" -> order >= 0
            else -> throw IllegalStateException("unreachable comparison operator $op")
        }
    }

    private fun evalCall(node: Node.Call): Value {
        val args = node.args.map(::eval)
        fun arg(index: Int) = args[index]
        fun requireArity(count: Int, upTo: Int = count) {
            if (args.size in count..upTo) return
            val takes = if (count == upTo) {
                "$count argument${if (count == 1) "" else "s"}"
            } else {
                "$count or $upTo arguments"
            }
            throw ExpressionError(
                "'${node.name}' takes $takes, got ${args.size}, " +
                    "at position ${node.position}."
            )
        }

        return when (node.name) {
            FUNCTION_UPPER -> {
                requireArity(1)
                Value.Str(asString(arg(0), FUNCTION_UPPER).uppercase())
            }

            FUNCTION_LOWER -> {
                requireArity(1)
                Value.Str(asString(arg(0), FUNCTION_LOWER).lowercase())
            }

            FUNCTION_TRIM -> {
                requireArity(1)
                Value.Str(asString(arg(0), FUNCTION_TRIM).trim())
            }

            FUNCTION_LENGTH -> {
                requireArity(1)
                Value.Num(BigDecimal(asString(arg(0), FUNCTION_LENGTH).length))
            }

            FUNCTION_CONTAINS -> {
                requireArity(2, 3)
                val haystack = asString(arg(0), FUNCTION_CONTAINS)
                val needle = asString(arg(1), FUNCTION_CONTAINS)
                val mode = if (args.size == 3) {
                    matchMode(asString(arg(2), FUNCTION_CONTAINS), node.position)
                } else {
                    // What every rule saved before the mode existed means, and
                    // what a two-argument call keeps meaning.
                    TextMatchMode.CONTAINS
                }
                Value.Bool(
                    when (mode) {
                        // Case sensitive, unlike the same mode on a trigger's
                        // text filter. See the file KDoc: upper() and lower()
                        // are how this language asks for the other thing.
                        TextMatchMode.CONTAINS -> haystack.contains(needle)
                        TextMatchMode.REGEX -> regexFinds(needle, haystack, node.position)
                    }
                )
            }

            FUNCTION_ROUND -> {
                requireArity(2)
                val places = asNumber(arg(1), FUNCTION_ROUND).intValueExact()
                Value.Num(
                    asNumber(arg(0), FUNCTION_ROUND).setScale(places, RoundingMode.HALF_UP)
                )
            }

            else -> throw ExpressionError(
                "Unknown function '${node.name}' at position ${node.position}."
            )
        }
    }

    private fun asNumber(value: Value, context: String): BigDecimal = when (value) {
        is Value.Num -> value.value
        else -> throw ExpressionError("'$context' needs a number, found ${describeValue(value)}.")
    }

    private fun asString(value: Value, context: String): String = when (value) {
        is Value.Str -> value.value
        else -> throw ExpressionError("'$context' needs text, found ${describeValue(value)}.")
    }

    private fun asBool(value: Value, context: String): Boolean = when (value) {
        is Value.Bool -> value.value
        else -> throw ExpressionError(
            "$context needs true or false, found ${describeValue(value)}."
        )
    }

    private fun typeName(value: Value): String = when (value) {
        is Value.Num -> "a number"
        is Value.Str -> "text"
        is Value.Bool -> "true or false"
    }

    private fun describeValue(value: Value): String = "${typeName(value)} ('${format(value)}')"

    /**
     * [FUNCTION_CONTAINS]'s third argument. Exact, and deliberately not
     * [TextMatchMode.parse]: see the file KDoc for why leniency is right for
     * stored config and wrong for a word somebody just typed.
     */
    private fun matchMode(raw: String, position: Int): TextMatchMode =
        TextMatchMode.entries.firstOrNull { it.configValue == raw }
            ?: throw ExpressionError(
                "'$FUNCTION_CONTAINS' takes \"${TextMatchMode.CONTAINS.configValue}\" or " +
                    "\"${TextMatchMode.REGEX.configValue}\" as its third argument, found " +
                    "${describeValue(Value.Str(raw))}, at position $position."
            )

    /**
     * Whether [pattern] is found anywhere in [candidate].
     *
     * Compiled on every evaluation, where [TextFilter] compiles once when the
     * rule is built. The difference is deliberate rather than an oversight: a
     * text filter is matched against every event a hot trigger sees, and an
     * expression is lexed and parsed from scratch each time it runs anyway, so
     * caching the compile would save a small part of a cost already paid.
     *
     * A refusal becomes an [ExpressionError], never `false`. `contains`
     * answering `false` for an honest miss and `false` for "this pattern ran
     * out of time" would be the same silent-failure shape `TextFilter.matches`
     * accepts for its own reason, stated in that file's KDoc. This function has
     * a channel that reason does not: throwing here **fails the expression**,
     * so `set_variable`'s evaluate mode and `run_rule`'s "only if" both report
     * the rule did not run rather than quietly producing a wrong answer. See
     * the file KDoc's "Safety is exactly three numbers" for why that failure
     * is also what keeps one evaluation from paying the timeout more than
     * once.
     */
    private fun regexFinds(pattern: String, candidate: String, position: Int): Boolean {
        val compiled = try {
            Regex(pattern)
        } catch (invalid: IllegalArgumentException) {
            // The engine's own message, which names the offending position in
            // the pattern and is better than anything this could invent. The
            // same choice TextFilter.of makes.
            throw ExpressionError(
                "'$pattern' at position $position is not a valid regular " +
                    "expression: ${invalid.message}"
            )
        }
        // RegexGuard and RegexRun are RegexBudget.kt's, shared with
        // TextFilter's regex mode. See that file for the number and the
        // mechanism.
        return when (val run = RegexGuard.runBounded { compiled.containsMatchIn(candidate) }) {
            is RegexRun.Completed -> run.value
            RegexRun.Refused -> throw ExpressionError(
                "The regular expression at position $position took too long against " +
                    "${candidate.length} characters of text. A pattern with two of '.*' " +
                    "in it, such as '.*a.*b', can cost that much: 'contains' already " +
                    "searches the whole text, so a leading '.*' is never needed, and " +
                    "'^' anchors the search when you do want the start."
            )
        }
    }
}
