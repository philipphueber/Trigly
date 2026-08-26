# Variables

**Status: phases 1 and 2 are built. Phase 3 is not.** `docs/actions.md`
recorded variables as the largest design decision left after conditions. This
file is that decision, in the shape `docs/conditions.md` holds its own, and it
is kept as written so that what was weighed stays readable next to what was
chosen.

Four things came out differently from the plan below, each because building it
showed something the plan could not:

- **Encoding applies to an embedded reference only.** A field whose whole value
  is one reference gets the raw value. Section 8 assumed one encoding per field
  could serve every use, and it cannot: `{{app.endpoint}}` as an entire URL must
  not come back percent-encoded, while the same reference inside a query string
  must be. See `Template.isSingleReference`.
- **There is no regular-expression encoding.** Section 8 listed one. Nothing in
  phase 1 can reach it: the only pattern fields belong to triggers, a trigger's
  config is read when it is built rather than per event, and a trigger cannot
  read a variable out of its own event. It arrives with the field that needs it.
- **The two recipient fields take a variable**, which the audit in section 5 did
  not consider. "Text back whoever just texted me" is the most obvious rule the
  feature makes possible.
- **The firing time is offered twice**, as `{{event.time}}` for a person to read
  and `{{event.timestamp}}` for a server. That is what let phase 1 ship with no
  format language at all, which section 14 wanted and had no answer for.

`app` is reserved and deliberately unresolved until phase 2, so that adding the
store is not a breaking change for a rule saved in the meantime.

The scope of the plan:

1. A variable model with three scopes: the event, the rule, and the app.
2. Every trigger declares the contents it already emits, so a person can find
   them and the editor can offer them.
3. Several paths to read a variable in an action, with a recommendation for
   each.

---

## 1. What a variable is here

A variable is a **named string** that a rule can read while it runs. Nothing
more. There is no value type, no arithmetic, and no control flow. Config is
already `Map<String, String>` and a payload is already `Map<String, String>`, so
a string keeps the whole feature inside shapes that exist.

This is the single largest scope decision in the plan. Section 14 says what it
costs.

---

## 2. What already exists

Almost all of the runtime half is built.

`TriggerEvent.payload` is a `Map<String, String>` on every event. 32 of the 34
registered triggers put something in it. `TriggerEngine` hands the event to
every action, and two actions already read a key out of it: `dismiss_notification`
and `notification_button` both take the notification key from
`SharedPayloadKeys.NOTIFICATION_KEY`.

Three things are missing, and only one of them is runtime work.

- **Nothing declares what a trigger emits.** The keys are `const val PAYLOAD_*`
  in each trigger. The editor cannot list them, so a person cannot know they
  exist. This is the gap that makes the feature invisible today.
- **No action reads text from the payload.** An action gets its text from
  config, and config is fixed when the action is built.
- **No value survives a run.** A payload lives for one event. A counter, a
  "last seen" time, or a value shared between two rules has nowhere to live.

One constraint from the code decides most of the design below.
`TriggerEngine.startRule` builds each action **once** per rule start and reuses
the instance for every event. `HttpRequestAction` captures its URL in its
constructor. So substitution cannot happen in `create()`. Section 7 is about
where it happens instead.

---

## 3. The three scopes

| Scope | Lives for | Written by | Persisted |
|-------|-----------|------------|-----------|
| Event | One event, one rule run | The trigger that fired | No |
| Rule  | One rule run | The engine | No |
| App   | Until changed or removed | A `set_variable` action, or the user | Yes |

**Event scope** is the trigger payload, named and declared.

**Rule scope** is what the engine knows about the run and the trigger does not:
the type that fired, the time it fired, the rule name, and the rule id.

**App scope** is a small named store, shared by every rule. This is the only
scope that needs a table and a migration.

### Names

One syntax: `{{namespace.name}}`. Four reserved namespaces:

| Reference | Means |
|-----------|-------|
| `{{trigger.name}}` | A key from the leaf that fired, whichever leaf that was |
| `{{bluetooth_connected.name}}` | A key from that one trigger type in this rule |
| `{{event.type}}`, `{{event.time}}` | Engine facts about the run |
| `{{rule.name}}`, `{{rule.id}}` | The rule that is running |
| `{{app.trip_count}}` | An app-scope variable |

`trigger` is the form to recommend and to offer first. A rule usually has one
trigger, and `{{trigger.text}}` keeps working when the person changes which
trigger it is.

The type-qualified form exists because a rule's trigger is a tree. Only one leaf
fires. A leaf that did not fire has no payload, so
`{{bluetooth_connected.name}}` is empty when the screen turned on instead. The
editor has to say that plainly, next to the reference, not in a help page.

Two leaves of the same type share one namespace, and whichever of them fired
fills it. This is a deliberate limit. The alternative is to address a leaf by
its `NodePath`, and a path is not a name a person can read or keep. Rules with
two leaves of one type are rare, and the shared namespace is still correct for
the fired one.

`trigger`, `event`, `rule` and `app` therefore become reserved words. No
trigger type may be one of the four. A JVM test asserts that, in the same
place T2 pins the type strings.

### Grammar

The whole grammar is one production:

    {{ <namespace> . <name> [ | <fallback text> ] }}

- One optional fallback after a pipe. Literal text, no nesting.
- An unbalanced `{{` stays literal. Nothing needs an escape character.
- A well-formed reference to a name that no declaration provides is a **save-time
  error in the editor** and a run-time failure. It is never silently empty.

---

## 4. Declaring what a trigger emits

A new declaration on `ComponentFactory`, beside `configFields` and
`requirements`. Declared on the factory, consumed by the UI, and now also read
by the engine.

```kotlin
data class VariableSpec(
    /** The payload key. Use the same PAYLOAD_* constant the emit site uses. */
    val key: String,
    val label: String,
    val kind: VariableKind,
    /** A realistic value, for the picker preview and for the editor Test run. */
    val sample: String,
    val help: String? = null,
    /** False when the trigger can emit the event without this key. */
    val alwaysPresent: Boolean = true,
)

enum class VariableKind { TEXT, NUMBER, TIMESTAMP, PACKAGE, ADDRESS, STATE }
```

```kotlin
// on ComponentFactory
val variables: List<VariableSpec> get() = emptyList()
```

Defaulted, for the reason `supportsCondition` is defaulted: adding it must not
edit 34 triggers in one commit. Each trigger opts in on its own.

**Declare with the constant, not with a literal.** `VariableSpec(key = PAYLOAD_NAME, ...)`
ties the declaration to the emit site through the compiler. A rename then moves
both. This is the only part of the emit-and-declare pair a machine can check. A
key that is declared and never emitted stays possible, and a JVM test cannot
catch it, because catching it means firing a real trigger. Say so in the KDoc,
the way `supportsCondition` says what its own honest pair is.

**Declaring is not the same as emitting.** `notification_posted` puts four keys
in its payload and should declare three. `SharedPayloadKeys.NOTIFICATION_KEY` is
opaque, generated by the posting app, and useful only to the two actions that
target a notification with it. Putting it in a picker offers a person a value
they cannot read or use. So the declared list is a *chosen* list, and the type
carries the reason.

`alwaysPresent` is load-bearing. Four triggers build their payload with
`buildMap` and leave a key out when the platform did not give them one:
`bluetooth_connected` (name), `sms_received` (sender), `notification_posted`
(title, text) and the accessibility triggers (all three keys). A picker that
shows those beside a Bluetooth address, with no mark, promises a value the event
often does not carry.

---

## 5. What each trigger emits today, and what it should declare

Read from the emit sites. This is the audit the work starts from.

| Trigger | Emits today | Can be absent | Worth adding |
|---|---|---|---|
| `airplane_mode` | state | | |
| `app_foreground` | package | | app label (derived) |
| `app_install_state` | package, state | | app label (derived) |
| `auto_sync` | state | | |
| `battery_level` | level | | |
| `battery_temperature` | temperatureC | | |
| `bluetooth_adapter_state` | state | | |
| `bluetooth_connected` | address, name, state | address, name | |
| `call_state` | event | | the number, and see below |
| `charging_type` | source | | |
| `clipboard_changed` | text | | |
| `dark_theme` | state | | |
| `device_restart` | reason | | |
| `dnd_mode` | state | | |
| `gps_state` | state | | |
| `headset_plug` | state | | |
| `interval` | nothing | | nothing. `{{event.time}}` covers it |
| `keyboard_visibility` | state | | |
| `location` | state | | latitude, longitude, accuracy |
| `location_check` | no events | | |
| `nfc_state` | state | | |
| `notification_posted` | key, package, title, text | title, text | app label, posted time |
| `notification_watchdog` | package, reason | | |
| `power_connection` | state | | |
| `screen_content` | package, text, class | all three | |
| `screen_orientation` | state | | |
| `screen_state` | state | | |
| `shortcut` | shortcutId | | nothing. The id is Trigly's, not the person's |
| `sms_received` | sender, body | sender | |
| `solar` | event | | the solar instant |
| `time_window` | no events | | |
| `ui_click` | package, text, class | all three | |
| `wifi_state` | state | | the network name, and see below |
| `work_profile` | state | | |

Two entries in that last column are not free, and they show a wrinkle worth
naming.

**A variable can cost a permission, and the trigger cannot see who reads it.**
The Wi-Fi network name needs a location grant. `call_state` says in its own
KDoc that the number is not offered, because the read permission is a cost the
whole trigger would pay. `requirementsFor(config)` is the model for "only ask
for what this rule uses", and it reads the *trigger's* config. A variable is
used in an *action*, so the trigger never learns that anybody wants it.

So a costly variable must be opted into on the trigger itself: a
`ConfigField.Flag` such as "include the network name", which `requirementsFor`
can then see. That keeps the requirement honest. It also means a costly variable
is absent unless the flag is on, which `alwaysPresent = false` already
expresses.

The `location` coordinates are the same question with privacy in place of a
permission. The trigger already holds the position it just tested. Putting it in
a payload sends it to whatever the rule's actions do next, and section 13 is
about that.

---

## 6. Reading a variable in an action: seven paths

Each path is judged on its own. Several of them belong in the product together.

### P1. A template inside a text field

`{{...}}` in a field value, resolved by the engine before the action runs.

- Covers the cases people ask for: "Battery at {{trigger.level}}%", a webhook
  body with the notification title in it, a spoken message that names the caller.
- Costs a parser, a resolver, and the seam in section 7.
- **Recommended. This is the core of the feature.**

### P2. A picker that inserts the token, with a live preview

The editor lists what is available for the rule as written, inserts the token at
the cursor, and shows the field rendered with each declaration's `sample`.

- Without this, P1 is a syntax nobody discovers. The declaration in section 4
  exists to feed exactly this.
- It also fixes the honest gap the Test button records today.
  `RuleEditorViewModel` says the test event "carries no payload, so an action
  that reads trigger payload sees nothing", and names payload substitution as
  the thing to revisit. Samples close it: a test run substitutes realistic
  values and the screen says they are samples.
- **Recommended, and it ships with P1. Neither is worth much alone.**

### P3. An implicit default: a blank field means the matching variable

`post_notification` with no title uses the triggering notification's title.

- Zero configuration, and it reads well in a demo.
- It collides with something load-bearing. `ConfigField.Text.blankMeaning`
  exists because several components treat blank as "match anything" or "use the
  device tone", and the KDoc warns that a helpful default would "silently narrow
  the rule". This path adds a second meaning to blank, decided per action, and
  invisible on screen.
- **Rejected.** The magic is not worth a third meaning for an empty box.

### P4. The action reads the payload itself

What `dismiss_notification` and `notification_button` do today.

- Right for a value that is opaque and must not be shown: a notification key is
  generated by the posting app and cannot be typed in advance.
- Wrong as the general mechanism. It puts the same code in every action, and the
  plugin rule in `CLAUDE.md` calls that the abstraction being wrong. An action
  that forgets it is a rule that quietly ignores its own variables.
- **Keep for opaque values. Do not extend.**

### P5. A `set_variable` action and an app-scope store

- This is what makes a counter, a "last seen at", or a cooldown possible. It is
  also the only path that lets one rule tell another rule anything, other than
  the existing `set_rule_enabled`.
- Costs a table, a migration, and a port. Section 10.
- **Recommended, as phase 2.**

### P6. A `variable_check` trigger

A component with `producesEvents = false` and `supportsCondition = true`, whose
`currentlyHolds()` reads the store.

- It needs **no engine change at all.** `TriggerNode` already composes levels
  with edges through `ALL` and `ANY`, and `canStart` already knows that a
  check-only component cannot start a rule. A variable becomes a condition for
  free, in the vocabulary the editor already has.
- **Recommended, with P5.** It is most of the value of app scope.

### P7. Bind a whole field to one variable

A mode switch on a field: either a typed value, or "take this field from
`{{app.volume}}`".

- A template cannot work in a `Number`, a `Duration`, a `Slider` or a `Choice`.
  Those fields are not text, the editor draws a control rather than a box, and
  `"{{app.x}}"` is not a number the factory will accept.
- Whole-field binding needs no parser and cannot corrupt a value. It is the
  right mechanism for exactly the fields P1 cannot reach.
- **Recommended as phase 3**, for typed fields only. Two mechanisms, each where
  it fits, and each visible on screen as itself.

### P8. Expressions and formats

Arithmetic, comparison, string functions, date formats.

- **Deferred, on purpose.** `docs/actions.md` already weighed a scripting model
  and recommended against it. The one place a format is genuinely needed is a
  timestamp, and that is handled in phase 3 as a *derived* variable, not as a
  language. See section 15.

---

## 7. Where substitution happens

Not in `create()`. An action is built once per rule start and its config is
captured in its constructor.

Not inside each action either, for the reason P4 gives.

**At the engine's action seam.** For each event, before running an action:

1. Ask the registry for the action's declared fields.
2. For each config key whose field declares a substitution other than `NONE`,
   resolve the template against the event, the rule, and the store.
3. If the resolved config equals what the live instance was built from, reuse
   the instance. Otherwise build a new one from the resolved config.

The reuse rule is what keeps this from being a behaviour change. Compute once at
`startRule` whether any action config value contains `{{`. If none does,
resolution is the identity function, the instance is built once, and the engine
takes exactly the path it takes today. Only an action that actually uses a
variable pays for a rebuild, and that rebuild is once per event.

Rebuilding per event is safe for the actions that hold something. `play_alert`
loops and can be stopped, and the stop already works by cancelling the job
rather than by holding the instance.

Which keys are eligible is **declared per field**, not guessed:

```kotlin
enum class Substitution { NONE, TEXT, URL, JSON_STRING, REGEX_QUOTE }

// on ConfigField, defaulted like shownWhen
val substitution: Substitution get() = Substitution.NONE
```

Only a field's primary `key` is eligible. A companion key from
`companionKeys()` is never substituted, because it holds a mode or a package and
not prose.

Resolution is suspending: an `{{app.*}}` read goes to the store. It runs inside
the action loop, which already runs one action at a time.

---

## 8. Encoding: the quiet failure this must not have

A variable carries whatever the platform gave it. A notification title can
contain a quotation mark. An SMS body can contain an ampersand.

Put that in an `http_request` body declared as `application/json` and the JSON
breaks. The server answers 400, the rule log says HTTP 400, and nothing says
why. Put it in a URL and a `&` silently adds a query parameter. Put it in a
`TextPattern` used as a regex and a `(` makes the pattern invalid.

This is the reason the encoding lives on the field rather than in the resolver:
the same value needs different treatment depending on where it lands.

| Field | Substitution |
|---|---|
| `toast.text`, `speak.text`, notification title and body, `set_clipboard.text` | `TEXT` |
| `http_request.url` | `URL` (percent-encode each substituted value) |
| `http_request.body` | `JSON_STRING` when the content type is JSON, else `TEXT` |
| a `TextPattern` in regex mode | `REGEX_QUOTE` |

`http_request.body` is the one case where the encoding depends on a sibling
field. `FieldCondition` already models "this field depends on a sibling", so the
shape exists. If that turns out to read badly, the honest fallback is to declare
the body as `TEXT` and say in its help text that a variable is inserted raw.

**Do not ship P1 for `http_request` without this.** A silently malformed webhook
is precisely the failure mode the rest of this project is built to avoid.

---

## 9. A value that is not there

`TriggerNode.holds` sets the precedent: an unknown state does not satisfy, and
null is not false. The same honesty applies here, and the failure has to be
loud.

- **A name that no declaration provides** is a save-time error in the editor.
  The person is told which name, while they are looking at the field.
- **A declared name with no value at run time** fails the action. The failure
  reason names the variable and says why it was empty: the leaf did not fire, or
  the platform did not supply the key, or the app variable is not set. That
  reaches the screen through `RuleFault.Kind.ACTION_FAILED`, which already
  exists.
- **A fallback makes it succeed.** `{{trigger.name | Unknown device}}` is how a
  person says that empty is acceptable, and it is visible in the field.

Failing by default is the right way round. An empty string in a notification
body is cosmetic. An empty string in a webhook URL or an SMS is a wrong action
taken quietly, and this project treats a wrong action as worse than no action.

---

## 10. App scope: the store, the action, the check

### Storage

A new table, and a `:core` port so `:actions` can reach it without seeing Room.

```kotlin
@Entity(tableName = "variables")
data class VariableEntity(
    @PrimaryKey val name: String,
    val value: String,
    val updatedAtMillis: Long,
)

interface VariableStore {
    suspend fun get(name: String): String?
    suspend fun set(name: String, value: String)
    suspend fun remove(name: String)
    fun all(): Flow<Map<String, String>>
}
```

Database version 4 becomes 5, with `MIGRATION_4_5` creating the table and
touching nothing else. `MigrationTest` covers it.

The store lives in `:core`, which already owns Room. `set_variable` lives in
`:actions` and gets the store injected by `:ui`.

**The precedent is `RuleRepository`, not `NotificationController`.** This file
said the latter before phase 1 was built, and that was wrong in a way that would
have produced a worse store. `NotificationController` is a *port*: it exists
because its implementation has to live in `:triggers`, next to the listener
service, while its caller lives in `:actions`, and neither module can see the
other. Its `Unavailable` default is a real and always-correct answer, because
"notification access is off" is a true state of the device.

A variable store has no such problem. Its Room implementation lives in `:core`,
in the same module as the interface, exactly as `RoomRuleRepository` does. So it
follows that shape: `VariableStore` beside `RuleRepository`, a working
`InMemoryVariableStore` beside `InMemoryRuleRepository`, and `RoomVariableStore`
beside `RoomRuleRepository`. The in-memory one is a *working* store and not a
stub that refuses, because "this device has no variables" is not a real state,
and a default that reported it would make every test that did not wire a store
silently test nothing.

**One landmine to clear first: `triglyDatabase(context)` is not memoized.** It
builds a fresh `Room.databaseBuilder(...)` on every call, which is invisible
today because `ruleRepository(context)` is its only caller and `AppContainer`
calls that once. A second top-level factory beside it opens a second
`TriglyDatabase` on the same file. Memoize it before adding the second caller.
Nothing in that file currently shows a cached singleton to copy, so this is a
gap rather than a pattern to follow.

### `set_variable`

Fields: the name, the value (`Substitution.TEXT`, so a variable can be built
from another one), and a mode.

Modes: **set**, **clear**, and **add**. Add is what a counter needs. Add fails
with a clear reason when the current value is not a number, rather than
guessing zero.

### `variable_check`

`producesEvents = false`, `supportsCondition = true`. Fields: the name, a
comparison (is set, is empty, equals, does not equal, contains, is above, is
below), and a value. Built like `TimeWindowCheck`, which is the same shape: its
own file, `events()` returning an empty flow, and the answer in
`currentlyHolds()`.

**That comparison vocabulary does not exist anywhere in the project yet.**
`Threshold` models above and below with hysteresis, for a trigger that has to
re-arm, and `TextFilter` models contains and regex. Neither is the set this
needs, and no user-facing string for "is set" or "is empty" exists to match. So
this is new vocabulary, and the two files are style precedent only: a small enum,
a pure top-level function beside it, and a lenient `parse` that falls back rather
than throwing on a value an older or newer build wrote.

What is directly reusable is `ConfigField.Choice` for the comparison itself and
`FieldCondition` for hiding the value field when the comparison is "is set" or
"is empty". `play_alert` already hides "keep sounding for" that way, and the
reasoning in `ConfigField.shownWhen` is about exactly this case: a field a
sibling has made irrelevant should not be left on screen with a sentence
explaining why it does nothing.

The unknown rule from `docs/conditions.md` applies exactly. A name that is not
in the store is a definite **false** for every comparison, and a definite
**true** for "is empty". It is knowable, so it is not unknown. Unknown is only a
store that could not be read, and that returns null, which does not satisfy.

---

## 11. The loop, and why `variable_changed` waits

A `variable_changed` trigger plus a `set_variable` action is a loop. Rule A sets
X, the change fires rule A, which sets X. The engine has no depth limit and no
rate limit, and every hop runs real actions.

So `variable_changed` is **not in the plan**. `variable_check` gives most of the
value with none of the risk, because a check cannot start anything.

If it is ever wanted, the guard has to be designed first, not added after. Two
parts, both needed: a write from a rule does not wake that same rule, and a
chain of variable-caused runs is capped at one hop. Write the decision down
before the code.

---

## 12. The editor

- The variable picker on every field that declares a substitution. It lists what
  this rule can actually offer: the declared variables of every leaf in the
  trigger tree, the rule-scope names, and the app-scope names that exist.
- Each entry shows its label, its sample, and a mark when it can be absent.
- An entry from a leaf that is one of several says that it is empty unless that
  leaf is the one that fired.
- A preview under the field, rendered from the samples.
- Save-time validation of every reference, per section 9.
- The Test button substitutes samples and says on screen that they are samples.

---

## 13. Privacy, stated where the rule is written

Variables are the first feature that moves content out of one part of the phone
and into another. `{{trigger.body}}` from `sms_received` inside an
`http_request` body sends a text message to a server. `{{trigger.text}}` from
`screen_content` sends whatever the accessibility service read.

Nothing about that is wrong. The person built the rule. But the editor has to
say it at the moment they build it, not in a document. The pattern already
exists: `warning` on a factory is shown prominently, and
`BACKGROUND_START_WARNING` is the precedent for one sentence stated once and
reused by every action that needs it.

So: an action that sends or stores text, and whose config references a variable
from a content-bearing trigger, shows that sentence. The `location`
coordinates in section 5 are the same question, and they are a good reason to
put them behind a flag on the trigger rather than in every event.

---

## 14. Not built, on purpose

- **No value type.** Strings only. A number comparison in `variable_check`
  parses at the point of comparison and fails clearly when it cannot.
- **No expressions, no branching, no loops.** `docs/actions.md` weighed this and
  recommended against it. Nothing here reopens that.
- **No `variable_changed`.** Section 11.
- **No action outputs in phase 1.** An action producing a variable is a real
  path, and the obvious candidate is an HTTP response body. `HttpRequestAction`
  deliberately never reads the response, and its KDoc calls draining an
  arbitrary response into memory "a liability, not a feature". So this needs its
  own decision, not a phase.
- **No addressing a leaf by path.** Section 3.

---

## 15. Phases

**Phase 1: event scope, read-only. Built.** `VariableSpec`, the declaration on
30 trigger factories, the grammar and resolver in `:core`, the engine seam with
the reuse rule, the `Substitution` declaration with three encodings rather than
four, the picker, the preview, save-time validation, and sample values in the
Test button.

Was done when a rule posts a notification whose text contains the title of the
notification that triggered it, on a device, and a rule with no variables in it
builds its actions exactly once as it does today. Both are now pinned by tests:
`VariableSubstitutionOnDeviceTest` for the first, on two API levels, and
`TriggerEngineTest` for the second, with a counting factory.

**Phase 2: app scope. Built.** The table, the migration to database version 5,
the store, `set_variable`, and `variable_check`.

Was done when a counter survives a process restart, and a rule with a
`variable_check` in an `ALL` group does not run while the check is false.

Four things came out differently from what this section assumed:

- **The store is not a port.** It is shaped after `RuleRepository`, because its
  implementation lives in `:core` beside the interface. Section 10 above says the
  rest.
- **The engine reads once per action, not once per event.** A rule can write a
  variable in one action and read it in the next, and a per-event snapshot would
  show the second action a value the first had already replaced.
- **An unrecognised comparison refuses the rule** rather than falling back to a
  lenient default. Section 10 says why that is the opposite call from
  `TextMatchMode.parse`.
- **A save never requires an app variable to exist.** The reader is usually
  written before the writer.

**The screen that was missing is built.** "Saved values", reached from the rules
list header. It lists what exists with each value's last-changed time, lets a
person add or edit one by hand, and names the rules that read a value before
letting it be deleted. Until it existed the picker had nothing to offer before
some rule had written something, so a working feature was nearly impossible to
find. See `docs/architecture.md`'s "Seeing and setting a saved value".

**Phase 3: the fields a template cannot reach, and derived variables.**
Whole-field binding per P7. Derived variables computed at resolution rather than
at emit time, which is how an app label and a formatted timestamp arrive without
paying for a lookup on every event that nobody reads.

Done when a `Duration` field takes its value from an app variable, and
`{{trigger.appLabel}}` resolves without the trigger emitting it.

---

## 16. Tests

JVM, because all of this is pure:

- The parser: a well-formed reference, an unbalanced `{{`, a fallback, an empty
  fallback, a pipe inside the fallback text, an unknown namespace.
- Each encoding: a quotation mark into `JSON_STRING`, an ampersand into `URL`, a
  bracket into `REGEX_QUOTE`.
- Resolution against a tree with two leaves of the same type, asserting that the
  fired leaf fills the namespace.
- The reuse rule, with a counting fake factory: no template means one build for
  many events, one template means one build per event.
- Reserved namespaces are not trigger types. Declared keys pinned as literals,
  in the same place T2 pins the type strings.
- Every declared `VariableSpec` has a non-blank label and sample, added to
  `ConfigSchemaContractTest`, which already walks every registered factory.

Instrumented:

- `MIGRATION_4_5`.
- `set_variable` then `variable_check` across a process restart.
- One end-to-end rule that carries a real notification title into a posted
  notification.

Per `CLAUDE.md`: two devices or API levels before a merge, and every new
instrumented test run twice back to back.

---

## 17. Decisions this plan does not take

1. **Do app variables travel with a shared rule?** A template is inside config,
   so it exports for free. The value does not. The recommendation is that a rule
   exports the *names* it reads, and the import screen lists the ones that do not
   exist on this device, in the same shape `TriggerNode.unknown` already reports
   a missing component.
2. **Do the `location` coordinates become variables at all?** Section 5 and
   section 13. The recommendation is yes, behind a flag on the trigger.
3. **Is the Wi-Fi network name worth a location grant?** The recommendation is
   the same flag, so `requirementsFor` can see the cost.
4. **Does `http_request` need an extra confirmation** when its config references
   a content-bearing trigger, or is the warning in section 13 enough? The
   recommendation is the warning, because a confirmation a person sees on every
   save is a confirmation they stop reading.
