# The variable language, in full

`docs/variables.md` says **why** variables work the way they do, phase by
phase. This file says **what** they can do, completely. If an operation is not
on this page, it does not exist: there is no hidden syntax, no seventh function,
and no way to write a loop.

Everything a rule can do with a value is one of four operations.

| Operation | Where it lives | Section |
| --- | --- | --- |
| Read a value | any field that accepts variables | [Reading a value](#reading-a-value) |
| Write a value | "Set a variable" | [Writing a value](#writing-a-value) |
| Ask about a value | the "Variable" condition | [Asking about a value](#asking-about-a-value) |
| Compute a value | "Set a variable", mode "compute it" | [Computing a value](#computing-a-value) |
| Decide with a value | "Run another rule", the "only if" field | [The two fields that run one](#the-two-fields-that-run-one) |

## Reading a value

One production, and no more:

    {{ <scope> . <name> [ | <fallback> ] }}

Spaces around the scope, the name and the fallback are ignored, so
`{{ app.count }}` and `{{app.count}}` are the same reference. An unbalanced
`{{` is literal text, which is why no escape character is needed: the only
string you cannot write literally is a complete, well formed reference, and
nobody types one of those by accident.

**A reference with no value fails the field, and the action says which
reference it was.** It never becomes an empty string. An empty notification is
cosmetic; an empty webhook URL or an empty phone number is a wrong action taken
in silence. The fallback after `|` is how you accept absence, in the field,
where a person editing the rule can see it:

    {{app.count | 0}}
    {{trigger.name | an unknown device}}

### The scopes

| Prefix | Holds | Lives |
| --- | --- | --- |
| `trigger` | the payload of the leaf that fired | one run |
| `<trigger_type>`, `<trigger_type>_2` | one named trigger leaf | one run |
| `event` | `type`, `time`, `timestamp` | one run |
| `rule` | `name`, `id` | one run |
| `action` | what an earlier action in this run produced | one run |
| `<action_type>`, `<action_type>_2` | one named action | one run |
| `local` | what this run wrote with scope "this run only" | one run |
| `mine` | what this rule keeps with scope "this rule" | until deleted, one rule |
| `app` | what any rule wrote with scope "every rule" | until deleted, every rule |

Seven of those words are reserved as scope names: `trigger`, `event`, `rule`,
`app`, `action`, `local` and `mine`. The other two rows are a component's own
name, which is its type, numbered from the second one of a kind.

**A name is trimmed and then compared exactly.** `count` and `Count` are two
variables, because the person typed the name and a store that merged them would
be guessing. Letters, digits and the underscore are what a name can hold, and
the editor refuses a name that no rule could read back.

### Which names a field really offers

The "Insert variable" list is not a catalogue of everything in the app. It is
what *that field*, in *that position*, can read.

- **One trigger leaf of a kind, so `trigger.*`.** With two or more leaves the
  short form disappears and each leaf is named: `notification_posted.title` and
  `notification_posted_2.title`. The short form cannot say which of the two
  arrived, so it is not offered rather than being offered and guessing.
- **An action is offered what the actions above it produce.** Not what the
  actions below it produce, because those have not run. `action.enabled` is the
  most recent action that produced an `enabled`, and
  `set_rule_enabled_2.enabled` is one named action.
- The first component of a kind keeps the plain name, and the second gets `_2`.
  Delete or reorder a component and Trigly rewrites the references in that
  rule, so a rule cannot quietly start reading a different trigger while it
  looks unchanged.

## Writing a value

"Set a variable" is the only action that writes one. Four fields: where it
lives, the name, the mode, and the value.

### Where it lives

| Choice | Reads back as | Lives | Visible to |
| --- | --- | --- | --- |
| this run only | `{{local.name}}` | one firing | this run |
| this rule | `{{mine.name}}` | until deleted | this rule |
| every rule | `{{app.name}}` | until deleted | every rule |

"every rule" is the default and is what the action always did. Only "every
rule" appears in Saved values as a shared value; "this rule" appears there
under "Kept by one rule", and deleting the rule deletes its values. "this run
only" is never written to storage at all.

"this run only" and "this rule" need a rule to be running. An action of that
kind that was not run by a rule fails and says so, rather than writing
somewhere else.

### The mode

| Mode | What it does | Writes | Reports |
| --- | --- | --- | --- |
| set it | stores the value as text | always | `value` |
| clear it | removes the name | always | nothing |
| add to it | adds a number to what is stored | on success | `value` |
| compute it | runs the value as an expression | on success | `value` |

- **clear it** is not an error when nothing was stored. "Make sure this is
  cleared" must not fail because it was already clear. There is no output,
  because there is no stored value left to report.
- **add to it** treats absent or empty as `0`. A stored value that is not a
  number, or an amount that is not a number, fails the action and **writes
  nothing**: the counter stops visibly rather than being silently reset. The
  result is written with trailing zeros stripped, so a counter reads `5` and not
  `5.0`.
- **compute it** fails the action and writes nothing when the expression fails.
- The value field accepts `{{...}}` in "set it", "add to it" and "compute it".
  It is hidden for "clear it".

## Asking about a value

The "Variable" condition. It is a condition and not a trigger: nothing about a
stored value is an instant, so it cannot start a rule, only decide whether one
proceeds.

| Comparison | True when | Reads the value field |
| --- | --- | --- |
| is set | the name is in the store | no |
| is empty | the name is absent, or holds an empty string | no |
| equals | the stored value equals the value, ignoring case | yes |
| does not equal | it does not, ignoring case | yes |
| contains | the stored value contains the value, ignoring case | yes |
| is above | both sides read as numbers and the stored one is greater | yes |
| is below | both sides read as numbers and the stored one is smaller | yes |

Five rules cover every edge, and each is a definite answer rather than an
unknown one:

1. **A name that is not in the store is "is empty" and nothing else.** Not "is
   set", not "equals" whatever you typed, and not "does not equal" it either.
2. **Text comparisons ignore case.** A stored `On` matches a comparison value of
   `on`. This is the opposite of the expression language's `contains`, which is
   case sensitive. A variable's *name* is always exact; only a *value* is
   compared this way.
3. **"is above" and "is below" answer false when either side is not a number.**
   Not unknown and not true, because this decides whether unattended actions
   run.
4. **A blank value with "contains" matches every stored value**, because every
   string contains the empty one.
5. **A comparison this build does not know makes the rule refuse to start**, and
   say so. That can only come from a hand edited file or a newer build, and a
   guess there would make the rule do something its author did not write.

**It reads `app` scope only.** A `mine` or a `local` value cannot be checked
here. To branch on one, compute the answer instead, with `{{mine.count}} > 3`
inside a "compute it" action, and let the actions below it read what it wrote.

## Computing a value

### The two fields that run one

Two fields in the whole app evaluate an expression, and they do different things
with the answer.

| Field | The answer is | A failure |
| --- | --- | --- |
| "Set a variable", value, mode "compute it" | stored, as text | fails the action, and writes nothing |
| "Run another rule", "only if" | the word `true` runs the rule, anything else does not | fails the action |

The "only if" field wants a true or false and compares the formatted result
against the exact word `true`. `false`, a number and a piece of text all mean
"do not run", and that is a success, not a failure: the action reports
`ran = no` and the rule carries on with the next action. A blank "only if"
always runs. An expression that cannot be worked out is the separate case, and
it fails the action with the reason.

Everything below applies to both fields.

### The editor draws it as code

Both fields colour what you type, and only while the field is set to run it.
"Set a variable" shows an ordinary text box until you choose the mode "compute
it". The colour is the signal that the box stopped holding text and started
holding code: from that moment a stray word in it is a failure, not a word.

Each part gets its own colour:

- A `{{...}}` reference, whole, in the brand accent.
- A piece of text in quotes, and a number, in two different colours.
- A keyword (`and`, `or`, `not`, `true`, `false`) and each of the six
  functions, in bold.
- The operators and the brackets, dimmer than the values they work on.
- Everything else plain.

Two of those tell you something you cannot see otherwise:

- **A number and a piece of text are different colours**, because they are
  different types and they never compare equal. A reference that arrives as a
  number where you expected text is visible before you save the rule.
- **A reference keeps its colour inside quotes.** Substitution does not respect
  quotes, so `"{{app.state}}"` still resolves, and it makes the error the next
  section describes. The colour there is the warning.

A word Trigly does not know stays plain. The editor does not mark it as wrong,
because plain is what "does nothing" looks like everywhere else in the app. A
message under the field is the right place to say more, and there is none yet.

The box also stops the keyboard from capitalising the first word and from
correcting what you type. Both are on by default, and both break an
expression: `And` is not a keyword, and a corrected `{{app.count}}` is not a
reference.

### The order of the two steps

1. Every `{{...}}` is replaced, as a **literal** this language can read.
2. What is left is parsed and evaluated as one expression.

A value that reads as a number goes in bare. Anything else goes in as a double
quoted string, with its own `"` and `\` escaped. So a stored `Pixel Buds`
becomes `"Pixel Buds"`, and a stored `42` becomes `42`.

**Do not put your own quotes around a reference.** `"{{app.state}}"` becomes
`""on""`, which is a syntax error nobody typed. Write this instead:

    {{app.state}} == "on"

**A reference with no value fails the action before the expression runs.** In an
expression field that matters more than elsewhere, because a counter's first run
is exactly that case. `{{app.count | 0}} + 1` is how a counter starts.

Step 1 resolves **every** reference in the field, including the ones in a branch
that will not be taken and the ones on the right of an `and` that will not be
reached. Short circuiting happens in step 2, so it can never rescue a reference
that had no value. A fallback is the only thing that does.

### Three types, and no casts

A number, text, or true and false. That is all there is.

- **There is no truthiness.** `and`, `or`, `not` and the `?` condition need an
  actual true or false. `1` is not true, and `""` is not false; both fail with a
  message naming what was found instead.
- **Two different types are never equal.** `"5" == 5` is false, because this
  language has no cast and there is no honest way to make them equal.
- `<`, `<=`, `>` and `>=` need two numbers or two texts. Mixing the two fails
  rather than answering. Two texts compare in code point order.

### The operators

Lowest precedence first. Every binary operator is left associative.

| Operators | Form | Takes | Gives |
| --- | --- | --- | --- |
| `? :` | `c ? a : b` | `c` is true or false | one branch; the other is not evaluated |
| `or` | `a or b` | true or false | true or false, short circuit |
| `and` | `a and b` | true or false | true or false, short circuit |
| `not` | `not a` | true or false | true or false |
| `==` `!=` | `a == b` | any two values | true or false |
| `<` `<=` `>` `>=` | `a < b` | two numbers, or two texts | true or false |
| `+` | `a + b` | two numbers, or anything | the sum, or the two joined as text |
| `-` | `a - b` | two numbers | a number |
| `*` `/` `%` | `a * b` | two numbers | a number |
| `-` `+` | `-a` | a number | a number |
| `( )` | `(a + b) * c` | anything | what is inside |

`+` is the one operator that changes meaning with its arguments: `1 + 1` is
`2`, and `"Count: " + 1` is `Count: 1`. Two numbers add, and anything else
joins as text.

**`and` and `or` short circuit.** The right side is not evaluated once the left
side decides the answer. That is the only way to guard a call in this language,
and it guards against the wrong *type*, not against an absent value:

    {{app.name | }} != "" and contains({{app.name | }}, "x")

**A nested `?:` is the only else-if.** The branch after `:` is a whole
expression, so this reads left to right and stops at the first true condition:

    {{app.level}} < 20 ? "low" : {{app.level}} < 60 ? "middle" : "high"

There is no `&&`, no `||`, no `!`, no `**`, no `++`, and no assignment. The
words are `and`, `or` and `not`.

### The literals

- **A number** is digits, with an optional `.` and more digits: `5`, `3.14`.
  There is no exponent form, no thousands separator, and no leading `.`, so
  `.5` and `1e3` are both errors. A minus sign in front of a number is the
  unary operator rather than part of the number, which never changes a result.
- **Text** is double quoted. Four escapes: `\"`, `\\`, `\n` and `\t`. An
  unknown escape keeps its backslash, so `\d` stays `\d` rather than becoming
  `d`. Single quotes are not string quotes, and a `'` outside a string is an
  error.
- **`true`** and **`false`**, spelled in lower case.

### The six functions

There are six, and a rule cannot define a seventh. Each one is a fixed piece of
behaviour that a shared rule can invoke on a stranger's phone, which is why the
list is short and stays short. One of the six takes an optional third argument.

| Call | Takes | Gives |
| --- | --- | --- |
| `upper(text)` | text | the same text in upper case |
| `lower(text)` | text | the same text in lower case |
| `trim(text)` | text | the text without leading or trailing whitespace |
| `length(text)` | text | a number, the count of characters |
| `contains(text, text)` | two texts | true or false, **case sensitive** |
| `contains(text, pattern, mode)` | two texts and a mode word | true or false. See the next section |
| `round(number, places)` | a number and a whole number | the number rounded half up |

A wrong argument count and a wrong argument type both fail with a message
naming the function. Two notes worth knowing before you use them:

- **`contains` is case sensitive here**, unlike the "Variable" condition's
  contains. Write `contains(lower({{trigger.title}}), "bedtime")` when case
  should not matter.
- **`round`'s second argument has to be a whole number.** `round(1.234, 2)` is
  `1.23`. A fractional count of places fails the action with a message from the
  arithmetic rather than from this language, and a very large one makes the
  expression do a great deal of work. Keep it small: two or three is what a
  person reading a value wants. `docs/todo.md` T22 covers tightening this.

### A pattern inside `contains`

`contains` takes a third argument, the mode. It is the same pair of words a
trigger's text filter uses:

| Mode | What the second argument is |
| --- | --- |
| `"contains"` | a piece of text to look for. The default |
| `"regex"` | a regular expression to search with |

    contains({{trigger.title | }}, "code", "regex")

**Two arguments keep their old meaning exactly.** `contains(a, b)` looks for
text, and a `.` in it is a dot. A pattern is a pattern only where you ask for
one, so every rule saved before the mode existed still does what it did.

**The mode word has to be exact.** `"Regex"` and `"rexeg"` both fail with a
message naming the two words the function accepts. This is stricter than a
trigger's stored mode, which reads an unknown word as `contains` so that an
imported rule still loads. An expression is code you typed a moment ago, and a
typo that quietly became a text search would give you a wrong answer that looks
like a right one.

**A pattern is searched anywhere in the text.** This is `containsMatchIn`, the
same as a trigger's filter: it reads like grep. `^` anchors it to the start and
`$` to the end, so `"^\d{6}$"` asks for six digits and nothing else.

**A pattern is case sensitive here.** A trigger's regex filter is not, and this
one is, because the two-argument `contains` always was. Use `lower(...)` around
the text, or put `(?i)` at the front of the pattern.

**A backslash reaches the pattern.** `\d` stays `\d`, because this language
keeps the backslash on an escape it does not know. You do not have to double a
backslash in a pattern. `\\` also works, and gives one backslash.

**A pattern that does not compile fails the action**, with the message from the
regex engine, which names the position in the pattern.

**A number is not text.** `contains` needs text, and a stored value that reads
as a number goes into the expression as a number. Join an empty string to it
first: `contains({{app.code}} + "", "^\d+$", "regex")`.

There is no other regular expression anywhere in the language. There is no
capture group you can read, no replace, and no split. `contains` answers one
question: is it in there.

### The work a pattern may do

A regular expression is the one thing in this language that can do a great deal
of work on a small piece of text. So there is a bound, and the bound is a rate:
**a pattern may read 10000 characters for every character of the text it
searches**, and never more than 20 million characters in total. Over that, the
action fails with a message that names the position of the pattern.

It is a rate because the honest cost of a search is not flat. `contains`
searches from every position in the text, so an ordinary pattern such as `.*b`
costs about the square of the length: over 1800 characters that is 4.9 million
character reads, and there is nothing wrong with that pattern. A rate allows
work that grows with the square of the text, and refuses work that grows faster.
`.*.*b` over the same 1800 characters reads more than 400 million.

Two things to know if you meet the message:

- **A leading `.*` is never needed.** `contains` already searches the whole
  text. `.*Alice.*` and `Alice` find the same notifications, and the first one
  costs far more.
- **An anchor makes a search cheap.** `^a+b` over 1800 characters reads 3599
  characters, because it can only start in one place.

The bound counts characters read, not time. A bound in milliseconds would let a
rule work on a fast phone and fail on a slow one, which is the failure this
project works hardest to avoid.

### Numbers behave like a calculator, not like a float

Arithmetic and comparison use decimal arithmetic, the same choice "add to it"
makes, because a total built from repeated fractional additions drifts visibly
in binary floating point.

- `/` rounds to 20 significant digits, half up, because exact decimal division
  does not always end: `1 / 3`. A division that ends is unaffected by rounding
  it never needed.
- `/` and `%` by zero fail with "Division by zero."
- `%` is a remainder, and it takes the sign of the left side.
- A result is formatted with trailing zeros stripped, so `10 / 2` reads `5` and
  not `5.0`, and no result comes out in scientific notation.

### The limits

- 2000 characters of expression.
- 64 levels of nesting, counting a parenthesis, a function call, a ternary
  branch, or a chain of `-`, `+` or `not`.
- 10000 characters read per character of text searched by one regular
  expression, and 20 million in total. See "The work a pattern may do" above.

There is no timeout and no thread, and none is needed. The language has no
loops, no recursion you can write, and no call that reaches outside the text it
was given. The first two bounds protect the reader's own call stack. The third
exists because a regex engine is the one part of this that is not the
language's own work: it can do an unbounded amount of work on a bounded input,
so it got a bound of its own before it shipped.

**This holds only while the grammar stays this small.** The day it gains a
loop, a variable of its own, or anything that reads state, all three bounds
stop being the whole safety story.

### What an expression cannot do, deliberately

No variables of its own, no assignment, no loops, no functions you define, and
no recursion. Nothing that reads or writes anything outside the string it was
given: no clock, no random number, no file, no network, and no other variable
except through a `{{...}}` that step 1 already resolved. No string indexing or
slicing, no list, no date arithmetic, and no comments. A regular expression
only inside `contains`, and only to answer yes or no.

A rule is a file someone else can import onto their own phone. An embedded
script language would let a shared rule carry arbitrary code onto a stranger's
device. That is the whole reason this is a closed grammar.

## Worked examples

Every example below is pinned by `ExpressionExamplesTest` in `:core`, so a
change to the language breaks a test rather than only this page.

**A counter that starts by itself.** Mode "compute it", name `count`:

    {{app.count | 0}} + 1

**A cooldown that belongs to one rule.** The same expression with the scope
"this rule", so two rules can both have a `count` without agreeing on a name:

    {{mine.count | 0}} + 1

**Two texts joined.**

    "Charged to " + {{battery_level.level}} + "%"

**A label from a number.**

    {{app.level}} < 20 ? "low" : {{app.level}} < 60 ? "middle" : "high"

**A percentage, shortened.** Without `round`, a division can produce more
digits than anyone wants read aloud in a notification:

    round({{app.done}} / {{app.total}} * 100, 1) + "%"

**A value that depends on two "Turn a rule on or off" actions.** This is the
case per component namespaces exist for. "Flip it" is the only mode whose
result nothing else can know, so each flipping action reports where its rule
ended up as `enabled`, holding `on` or `off`. Two of them in one rule are
`set_rule_enabled` and `set_rule_enabled_2`, in the order they sit in the rule.
A third action, "Set a variable" with the mode "compute it", reads both:

    {{set_rule_enabled.enabled}} == "on" and {{set_rule_enabled_2.enabled}} == "on"
        ? "both on"
        : {{set_rule_enabled.enabled}} == {{set_rule_enabled_2.enabled}}
            ? "both off"
            : "one of each"

Three things make that work, and each is worth naming:

1. **The order.** An action reads only what the actions above it produced, so
   the computing action has to sit below both flipping actions. The "Insert
   variable" list in that field is the proof: it offers both names there, and
   offers neither in a field above them.
2. **The quotes.** `{{set_rule_enabled.enabled}}` becomes the literal `"on"` by
   itself, so you compare it against `"on"` and add no quotes of your own.
3. **The comparison in the middle.** Two references compare directly against
   each other, `{{a}} == {{b}}`, which is how "they agree" is written without
   naming either value.

The result is one text value. To act on it, write it with "Set a variable" and
read it back in the next action, such as a notification that says
`Both switches are {{action.value}}`.

**An "only if" that limits a rule to three runs.** In "Run another rule", with
a `count` that a "Set a variable" action in the target rule adds to:

    {{mine.count | 0}} < 3

**One pattern instead of three `contains` calls.** Three words, one search:

    contains(lower({{trigger.text | }}), "delivered|shipped|out for delivery", "regex")

**A pattern that checks the shape of a value.** Six digits and nothing else:

    contains({{trigger.title | }} + "", "^\d{6}$", "regex") ? "a code" : "not a code"

The `+ ""` is not decoration. A stored value that reads as a number goes into
the expression as a number, and `contains` needs text. The value that most
wants a digit pattern is exactly the value this happens to.

**A guard, with a fallback on every reference.** `and` short circuits, so the
call on the right never runs when the left answer is false. Both references
carry a fallback, because step 1 resolves both whatever `and` decides:

    {{app.device | }} != "" and contains(lower({{app.device | }}), "buds")

## Where the code is

| Part | File |
| --- | --- |
| The `{{...}}` grammar, the scopes, what a field offers | `core/.../Variables.kt` |
| The expression language | `core/.../Expression.kt` |
| The colours in the editor | `ui/.../ExpressionHighlight.kt` |
| Writing, and the four modes | `actions/.../SetVariableAction.kt` |
| The "only if" field, and what `true` means there | `actions/.../RunRuleAction.kt` |
| The condition and its seven comparisons | `triggers/.../VariableCheck.kt` |
| Names, and what a name may hold | `core/.../VariableStore.kt` |
