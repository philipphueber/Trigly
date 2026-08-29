# Architecture

## Platform: native Kotlin + Jetpack Compose

No cross-platform framework. The decision is driven by what Trigly actually
does, not by preference:

- **The core APIs are Android-only.** Accessibility Service,
  `NotificationListenerService`, and Bluetooth broadcasts have no
  cross-platform bridge. React Native, Flutter, and KMP would each need a
  native module for the parts that matter.
- **A cross-platform layer means two codebases**, not one: the shared layer
  plus the native bridges under it. That is worse for maintainability, not
  better.
- **Android's background-execution rules change often**, and every change would
  have to be absorbed twice: once in the bridge, once in the shared layer.

Maintainability here comes from clean module separation *within* native
Android, not from the framework choice.

## Language and UI

- **Kotlin**: null-safety, coroutines for the async trigger/action pipeline,
  less boilerplate, and what contributors expect from a modern Android project.
- **Jetpack Compose**: declarative UI, and materially easier to test than XML
  layouts.

## Structure

MVVM, with the `TriggerEngine` fully decoupled from the UI. The engine must be
exercisable without a UI attached; nothing in `:core` may depend on `:ui`.

Gradle multi-module, so a contributor can work on one piece without
understanding the whole:

| Module      | Holds                                                        |
|-------------|--------------------------------------------------------------|
| `:core`     | `TriggerEngine`, domain model, rule storage (Room), the portable JSON format. No UI, no Compose. |
| `:triggers` | Trigger implementations.                                      |
| `:actions`  | Action implementations.                                       |
| `:ui`       | Compose screens, ViewModels, and the app assembly point. Applies the Android application plugin. |

Dependencies point one way: `:ui` → `:triggers`/`:actions` → `:core`. Nothing
depends on `:ui`, and `:core` depends on nothing in the project.

`:ui` doubles as the application module rather than adding a fifth `:app` that
would only hold a manifest and a wiring class.

### Plugin-style triggers and actions

Each trigger type and each action type is its own swappable implementation
behind a common interface. **Adding a trigger must not require editing an
existing one.** If a new trigger type forces a change to `:core` or to a sibling
trigger, the abstraction is wrong: fix the interface rather than special-casing
the new type.

How that is actually enforced: a `Rule` stores a *type string*, not a class.
`Registry` resolves it against factory lists that are handed in at construction
by `AppContainer` in `:ui`. That indirection is the entire reason `:core` can
own the engine while knowing nothing about `:triggers` or `:actions`, and so
the reason adding a trigger touches exactly one existing line, its module's
`triggerFactories()` list, instead of a `when` branch in the engine. Two
factories claiming one type fail at assembly rather than resolving by list
order.

Files in `:triggers` are grouped by *source* rather than strictly one per type:
two triggers reading `ACTION_BATTERY_CHANGED` share a file because they share
the extras and the sticky-broadcast caveats. Adding one still does not touch the
other's logic, which is the property that matters.

### A trigger can also be a condition

`Trigger` carries a second, defaulted capability (`currentlyHolds()`) so the
same component can be *watched* as an edge or *asked* as a level. That is what
lets a condition be a trigger rather than a second component family, and
`TriggerFactory.supportsCondition` (alongside `producesEvents`, its declared
counterpart, false only for `time_window`) is how the editor knows what a
component can be used for without instantiating one.

`Rule.trigger` is a single `TriggerNode` (`core/Gate.kt`), either one
component (`One`) or a group of them combined with `ALL`/`ANY` (`Group`), and a
group can hold groups. This is the second design: the first modelled a rule's
trigger side as `Gate(triggers, conditions)`, a required first-level OR of
edges plus a separate optional tree of checks, rendered by the editor as a
second region captioned "Must also be true." That was rejected, twice, for
making a structural distinction between "trigger" and "condition" that nobody
building a rule had asked for; a condition is not a different kind of node,
it is the same node asked a different question depending on where it sits.
`Gate` and `ConditionNode` are gone from the model. `TriggerNode`, storage, and
the engine are built and wired; the editor is being migrated to match. See
`docs/conditions.md`, which also carries the capability matrix for all
thirty-one triggers and why a *passive* time check sidesteps the missing
scheduler entirely.

Two invariants from there that constrain anything touching this: an unknown
state never holds (a check that cannot answer must not be read as denial *or*
as satisfaction), and edges-versus-levels is now a property of a leaf,
computed by `TriggerNode.canStart`/`canHold`, rather than a property of a slot
a two-part shape used to guarantee by construction.

### What a save may not quietly change

The editor works on a `RuleDraft`, and `toNode` is the boundary where a draft
becomes a `TriggerNode`. It is total: nothing about the *shape* of a trigger
tree can make it refuse, which is new. It replaced `toNodeOrNull`, which
refused two shapes outright, and both refusals are gone for the reason the next
section gives: a rule that is not finished yet saves anyway, disabled, rather
than not saving at all.

A group holding one child is still kept, unconditionally, the same as before
this change. It used to be unwrapped, on the reasoning that the editor never
builds a singleton group. It does, on the way to every group a person makes: a
group is picked from the trigger picker and arrives empty, so it holds one
child for as long as it takes to add the second. Unwrapping it mid-build turned
`ALL(screen on, ANY(...))` into `ALL(screen on, ...)` the moment the first OR
branch was added, silently, while the person was still filling in the second.
An `ANY` of one evaluates exactly like the child, so keeping it costs nothing
and keeps what the person built.

A group holding nothing is also kept, as exactly that: an empty `TriggerNode.Group`,
not pruned and not refused. It used to refuse the save outright. The refusal
message could only fire when the root itself was the empty group, which was
the one case where the old pruning happened to produce a null tree. But an
empty group is now exactly how a rule saved before its trigger is finished
spells "nothing here yet", the same as an entirely absent trigger; see
"A rule can save before it is finished" below.

A group that loses children to *removal* still collapses, and that is a
different question. Removing one of two OR branches leaves the other, and an OR
of one thing is that thing. The difference is intent: one child because a second
was removed is a finished edit, one child because a second is not added yet is a
rule in progress.

`transformTrigger` is the other half of that rule, unchanged by this: it walks
to a node and replaces it, and it used to apply the un-promotion to the result
of *every* edit rather than only to a removal. So a group holding one child
lost the group the moment anything inside it was touched: typing a value into
the one trigger in a new OR group deleted the OR group while the person was
still filling it in. Now a removal can collapse a group and no other edit
changes the shape at all. A group left with no children still disappears,
because the last removal from a group is the removal of the group, while an
*empty* group someone made on purpose is kept, saves, and is refused only at
the *enable* gate, not the save.

The trigger picker asks a third question and needs a third answer.
`triggerOptionsFor` converts a candidate tree to test whether it could start, so
strictness there would empty the picker: with `ALL(screen on, ANY())` on screen,
every candidate for the root would convert to a tree still holding an empty
group and be filtered out, including the components that would fill it. It
uses `toNodeIgnoringEmptyGroups`, which prunes, unlike `toNode`, which
deliberately does not, and is named so the difference is visible at the call
site.

### A rule can save before it is finished

`RuleEditorViewModel.save` used to conflate two different reasons a rule might
not be ready: a component that will not build or a `{{...}}` reference to a
variable the rule does not offer (wrong, and still refused at save time, by
`validate`), against no trigger, no actions, or an empty group somewhere in the
tree (not wrong, only unfinished). Only a blank name refuses a save now. Every
other kind of incompleteness saves, because refusing it is how people lose
work, and this app has no draft anywhere else.

`Rule.trigger` is a `TriggerNode`, not nullable, so a rule saved with no
trigger chosen still needs one to hold. `NO_TRIGGER` (`core/Gate.kt`) is that
value: an `ALL` group with nothing in it, which `TriggerNode.canStart` already
reads as unable to start a rule, the same as any other unstartable tree, so
nothing downstream needed a new state to recognise it. `RuleJson.decodeNode`'s
v3 reader used to refuse an empty `children` array, on the reasoning that the
editor could never build one on purpose; that guard is gone, because the
database column and an exported file both now have to carry this value for a
rule saved unfinished.

`enableRefusal` (`ui/RuleDraft.kt`) is the other half: why a rule *cannot be
switched on*, checked wherever a rule can be switched on. The toggle on the
rules list (`RulesViewModel.setEnabled`) and the editor's own switch
(`RuleEditorViewModel.setEnabled`) both refuse through it and post the same
kind of message either way, a toast on the list and the editor's own inline
error surface. No trigger and no actions get a message naming what to add. A
trigger that is present but cannot start (two edge-only components sharing
one `ALL`, or a leaf whose "only check" setting was flipped on after it was
already the rule's one trigger) keeps the older, more specific message that
`save` used to give, since a person can only fix that by changing what is
already there, not by adding something. `RuleEditorViewModel.save` asks the
same question again right before it persists, and forces `enabled` off if the
answer is still no, whatever the draft's own switch happened to show. That is
the safety net for a rule that was validly enabled and then had its trigger or
actions edited away without anyone touching the switch. Without it, a freshly
opened editor's switch defaulting on would turn every half-written rule into a
`RuleFault.Kind.COULD_NOT_START` the moment it saved, which is the opposite of
the point of letting it save at all.

`RulesScreen`'s `UnfinishedRuleCell` shows the same `enableRefusal` message on
the rules list itself, for a disabled rule, without anyone tapping its switch.
`LastFaultCell` never runs for a disabled rule, so without this the only way to
learn a rule is unfinished was to open it or read the toast. Caution-coloured,
not error-coloured: nothing here is switched on and failing right now, which is
what the error colour means elsewhere on this screen; this is the ordinary,
expected shape of a rule nobody has finished yet.

### Absent versus wrong

The first pass at the line above missed one case: an action or trigger a
person picked and left partly filled in, a required field with nothing typed
into it yet. `validate` used to call that broken, the same as an unknown
component or a malformed value, because it decided by calling `create()` and
reading whatever came back. That conflated two different problems again, one
level down from the first split. Caught in the connected suite, on two
devices, by a test that had simply forgotten to configure a trigger.

**Absent** is a required field with no value at all: not filled in yet,
which is unfinished, not wrong. **Wrong** is everything else `create()` can
still refuse: a value that is present but malformed, or a combination of
present values that breaks a cross-field rule such as
`notification_watchdog`'s "poll must not exceed absence". The two are told
apart by the schema, not by the factory: `ConfigField.unfilled`
(`core/ConfigField.kt`) reports the required fields, among the ones a
sibling's own value currently shows, that still have no value and no
declared default. `validate` skips a component `unfilled` names entirely
rather than calling `create()` on it, since building it would only refuse
for the exact reason already known. `enableRefusal` names the same
component in its own message, "Finish setting up X", rather than folding it
into "add a trigger" or "add an action": a person who already added an
action and is then told to add one would reasonably think the app is
broken.

A field's declared default matters here for the same reason it matters to
`shownWith`: a required field the editor is already showing a real value
for, such as `play_alert`'s "Tone", is not something a person left blank,
even when nothing has been typed. Only the stored value being absent *and*
no default existing counts as unfilled. Getting this wrong the other way,
by reading `required` alone, would have called a fresh, fully-answered
`play_alert` unfinished over a field nobody had touched.

### Getting a rule out of the app: share and export

Two controls, two jobs, and they used to be one job under two names. "Share" on
a rule and "Export all" in the header both opened the document picker, so the
control that promised the share sheet was the one that did not open it.

**Export all** still writes a file through `ACTION_CREATE_DOCUMENT`. No storage
permission, and the file lands where the person put it and can find it again,
which is what an export is for.

**Share** sends one rule through `ACTION_SEND`, wrapped in a chooser.

It sends a *file*, not text in `EXTRA_TEXT`. Text reads fine in a chat and is
useless on arrival, because importing reads a file through the document picker:
a rule pasted into a message has to be saved out by hand before the app on the
other end can take it. Sending the file makes the round trip work.

The file goes through a `FileProvider`, since a content URI is the only way to
hand a file to another app without either side holding a storage permission. The
declaration pairs `exported="false"` with `grantUriPermissions="true"`: no app
may query the provider on its own, and the one app the person picked may read the
one file it was granted. `FLAG_GRANT_READ_URI_PERMISSION` is set on the inner
send *and* on the chooser, because the chooser is the intent the system actually
starts.

It writes into a `shared/` subdirectory of the cache, cleared on every share. The
cache is the right lifetime for a copy made for one hand-off, and clearing it
means a second share cannot hand the receiving app the previous rule as well, and
a rule since deleted from the app does not survive in it. The provider's paths
file grants that one subdirectory rather than the whole cache.

The provider's authority is the part worth a test: it lives in the manifest, is
matched against a string built at runtime from the package name, and a mismatch
is neither a compile error nor something any other code path touches. It would
surface as a crash the first time somebody pressed Share. `ShareRuleTest` asks
the real provider for a URI and reads it back through the resolver.

A failed `startActivity` is reported. Nothing guarantees an installed app accepts
an `application/json` send, and a Share button that does nothing is the failure
mode this project keeps designing against.

### Duplicating a rule

The rules list offers `Duplicate` per rule. Most of a copy is the rule, and the
parts that are not are the point.

A generated id is minted again rather than copied. Some config values identify
the rule to something outside it, and a home screen shortcut is the one that
exists today: the shortcut trigger fires on any tap whose id matches its own, so
a copied `shortcutId` means one shortcut starts two rules. `ConfigField.GeneratedId`
is already the declared marker for "the editor mints this, a person never types
it", so the copy walks every component at every depth of the trigger tree and
mints each one again. Declared rather than a list of known keys, for the reason
the field type exists: a new component with an identity of its own must not have
to edit the duplicate code. A component this build does not know keeps its
config untouched, because with no schema there is no way to tell which key is an
identity, and inventing one would corrupt a rule that a build with the component
installed could still run.

The copy arrives switched off. Duplicating an enabled rule that acts on the
world would otherwise mean two rules doing the same thing from the moment of the
tap, before anyone changed the part they duplicated it to change. Off is a state
a person corrects with one tap in the direction they choose.

The copy lands at the end of the list, like every other new rule: the repository
gives a rule it has not seen the next free position. Placing it beside the
original would mean shifting the position of every rule below it, and a list that
reorders itself around a copy is a bigger surprise than a copy at the bottom.

The walk that mints fresh generated ids, `Rule.withFreshGeneratedIds`, lives in
`:core` rather than beside `duplicated()` in `:ui`. It moved there once
importing a rule file needed the exact same walk, and `Registry` already lives
in `:core`.

### Importing a rule

Importing shares the fresh-id walk with duplicating, and is stricter than it in
every way duplicating is not, because a rule file did not necessarily come from
this device's owner. Anyone who can hand someone a file, or who can get a copy
of a rule someone else exported and shared, can hand them a working program:
`INTERNET` is an install-time permission this app already holds, and
notification access is a grant a Trigly user has usually already given for some
other rule. An imported rule that arrived enabled could act the moment it lands,
before anyone has looked at it, with no new prompt to notice.

So `Rule.imported` disables the rule regardless of what the file's `enabled` key
said, and mints a fresh id for the rule itself on top of the fresh generated ids
the shared walk already mints. A shortcut's `shortcutId` is not a secret inside
the file. Anyone holding a copy of that file, or of a rule shared by any other
means, could otherwise start the exported shortcut activity with the id read
straight out of it, whether or not they ever imported the rule themselves. The
name is the one thing import leaves untouched: this is not a copy, and a name
somebody else chose is not this app's to edit. `RulesViewModel.import`'s message
says the rules arrived switched off, so turning them on again reads as a choice
rather than as the app not working.

### A control that does not fit

A button label given no line limit wraps to whatever width it is handed, and a
button squeezed narrow enough wraps *per character*. A real phone showed a
block's footer whose last control read as a vertical column of single letters,
one under the next. It was still tappable and still reported the right text to
the accessibility tree, so nothing failed and nothing said anything: the row ran
out of room and the text obeyed.

Two halves, and both are needed.

**Every button label in `Blocks.kt` is `maxLines = 1, softWrap = false`.** A
label that does not fit is clipped, which reads as wrong, rather than rearranging
itself into something that looks deliberate. This is the property a button label
should have had from the start.

**A row whose contents can grow wraps.** The block footer is a `FlowRow`,
because how many controls land in it is not fixed: a component declares its own
tools, so a shortcut trigger contributes "Add to home screen" beside "Add
trigger" and "Remove", and an action block in the middle of a list can hold its
tools, both reorder arrows and Remove. Capping the count is not an option, since
each of those controls is the only route to something. "Add to home screen" is
the one way a shortcut trigger ever fires, so a footer that dropped its fourth
control to save space would save a rule that can never run.

Rows with a *fixed* set of controls stay `Row`s. The pattern to keep for those is
the one the requirement rows already use: give the text `weight(1f)` so it
absorbs the squeeze, and let the button keep its intrinsic width.

The tests assert *height*, not presence. `assertIsDisplayed` passes on a crushed
button, and so does anything that looks for the text. A six-letter label stacked
one letter per line is six line heights tall, and that is what the assertion
looks at: measured against the pre-fix code, `REMOVE` came back 112dp tall.

### Nesting depth costs a rail, not a thumb

A level of nesting costs 6dp: a 2dp accent rail and a 4dp gap. It used to cost
about 30dp, and three of those left a trigger's own fields with nowhere to go.
Depth reads from the stacked rails and from each group's AND/OR heading.

Three separate things were charging for a level, and only the last is left.

- **The indent.** Each group padded its children by 16dp. Gone: the rail marks
  them instead.
- **The nested card.** Each group drew its own bordered card with 14dp a side.
  Only the outermost group draws one now. A nested group is already inside its
  parent's rail, so a second border adds an outline the eye does not need and
  width the triggers do.
- **The card's own padding, charged per level.** The horizontal padding a card
  wants around its heading is not padding the triggers inside it should pay. The
  heading and the AND/OR row keep it; the children region does not.

Measured on a 411dp screen, a rule three groups deep put its innermost trigger
card at 106dp from the edge before this, and at 39dp after.

`IntrinsicSize.Min` on the children row is load-bearing: `fillMaxHeight` inside
a row that wraps its own height has no bounded constraint to fill, so the rail
would measure zero and draw nothing.

### Saying that a run failed

Two things can make a rule do nothing, and until now they looked identical from
the device: the trigger never fired, or the trigger fired and an action failed.
The engine wrote the second one to logcat and stopped there, so answering "why
did my rule do nothing" needed a cable. For an app whose argument is that a
silent rule must explain itself, that was the hole in the middle of it.

`RuleFaultLog` closes it. The engine's `onOutcome` hook already existed for
this ("for logging and for the UI's run history") and now carries the action's
type as well, because a rule with three actions produces three outcomes and a
reader that cannot tell them apart cannot say which one failed.

Three decisions in it.

**The log lives in `AppContainer`, not in the engine.** `EngineService` owns the
engine's lifetime and deliberately hands out no reference to it, so the list
screen cannot ask the engine anything. A plain sink both sides can see is how
they meet: the service writes, the list reads, neither knows the other.

**It is not persisted, and that is the honest lifetime.** A failure describes a
run under conditions that existed at the time. If the process died and came
back, those conditions are gone and showing the record would be a claim the log
cannot support. A rule that still fails will fail again and say so again.
Persisting it would also mean a schema migration for something no rule depends
on.

#### The third case: a rule that never reached an action

`onOutcome` covers a rule whose action failed. It cannot cover a rule that was
dropped *before* any action ran, and that is a real case with its own cause: an
`ALL` group asks every leaf it did not fire on for a state, and a leaf that
cannot answer does not satisfy the group. Deliberately so, since running
unattended actions on a guess is the worse failure. But the rule was then
dropped in silence, and on screen that is identical to the condition answering a
plain no. "I am at home and it did not run" had no cause the app could name.

`TriggerEngine.onSuppressed` is that third hook. `StateReader` reads the leaf
states for one evaluation and remembers which of them answered null, so the
engine can tell a rule held back by a "no" from one held back by a component
that could not look. Only the second is reported; reporting the first would
accuse every rule with a condition in it each time the condition was simply
false. `EngineService` names the components through `Registry.displayNameOf` and
writes the sentence to the same `RuleFaultLog`, where the `UNDECIDED` kind
renders as "Last run stopped" rather than "Last run failed": nothing ran, so
there is no action to blame. Any action later succeeding clears it,
whichever action that was, because a rule that ran proves the record stale.

The case that produced it is the area check reading no position in the
background, which is fixed above. The hook stays because the shape is general:
any condition that cannot answer now says so.

**A success clears the record only for the same action.** Two actions, the first
failing and the second working, is a rule doing half its job. An unguarded clear
would erase the failure a moment after recording it and report the rule as
healthy.

The cell is amber, not the error colour. `RequirementCell` is red because it
states a fault standing in the way right now, with the grant that fixes it. This
is a report about a run that already happened and a condition that may be gone.
A disabled rule shows nothing, guarded in both the ViewModel and the cell,
because accusing a rule nobody asked to run is worse than saying nothing.

**`onSuppressed` is now the last resort, not the first response.** A read that
comes back unknown was, for a while, reported the moment it happened, which
meant a position read that missed for a second or two dropped its event for
good and reported a fault for a state that had already resolved itself by the
time anyone read the screen. `TriggerEngine.resolveHolds` asks again first, a
bounded number of times over a few seconds, and only calls `onSuppressed` once
that budget is spent. A leaf that answers on a later try never reaches
`onSuppressed` at all: the rule fires, a little late, and nothing is reported,
because a component that missed once and then answered is the rule working.
`docs/conditions.md` carries the schedule and the reasons for its bound; the
short version is that a rule's actions are unattended, and a late fire can be
worse than no fire, so the retry is deliberately short rather than open-ended.

#### The fourth case: a rule that never started

`onStartFailure` was there from the beginning and went to logcat alone. It fires
when resolving a rule's components throws: an unknown type from a file a newer
build exported, or config a factory refuses. Nothing was ever registered, so
there is no run to report and neither of the two sentences above fits.

It is the worst of the four to leave silent, because the rule reads as healthy.
It is stored, the switch says on, the summary line looks right, and no receiver
exists anywhere. `RuleFaultLog.couldNotStart` records it and the list says "Rule
not started" with what the failure said.

This one is in the error colour, in the same cell. The colour follows the tense
rather than the cell: a rule that was never built is a fault standing in the way
right now, as present as a missing permission, and it stays true until the rule
is edited. It is not in `RequirementCell` because there is no button to offer.
What it needs is the rule fixed, and only its own message can say how.

`RuleFaultLog.started` clears it, called for every rule the engine has running on
every sync, so an edit that fixes the config clears the report at once instead of
leaving it up until a trigger fires. Narrow on purpose: a failed action from an
earlier run is still true, and a rule starting says nothing about it.

The class was `ActionFailureLog` until this arrived. Two of its three kinds are
now about something other than an action, so it is `RuleFaultLog` and holds
`RuleFault`, which carries the kind explicitly rather than encoding it in a null
action type.

#### A trace of the last evaluation, for the case that is not a fault at all

Every case above is a fault: an action failed, a component could not answer, a
rule never started. None of them cover the plainest way a rule does nothing.
An `ALL` group whose fourth condition is simply false, right now, is a rule
working exactly as written, and until this existed it produced no fault, no
log line and nothing on screen. "My rule does nothing and I do not know which
part of it is holding it back" had no answer the app could give.

`StateReader` inside `TriggerEngine.resolveHolds` already computed every
consulted leaf's answer, true, false or unreadable, before this arrived. It
was thrown away the moment `resolveHolds` collapsed it to the single `Boolean`
`TriggerNode.holds` returns. `TriggerTrace`, in `core/Gate.kt`, is that same
answer kept as a tree instead: a `Group` for every `TriggerNode.Group` and a
`Leaf` for every `TriggerNode.One`, each leaf marked `FIRED`, `YES`, `NO`,
`UNREADABLE` or `NOT_CONSULTED`. This is exposing what the engine already
computes, not computing something new.

**`TriggerNode.holds`'s signature is unchanged.** `holds` still takes a
`firedPath` and a `stateOf` lookup and returns a plain `Boolean`, and every
existing caller, `GateTest` and `TriggerEngineTest` included, calls it exactly
as before. Underneath, `holds` now delegates to the same `holdsAt` that builds
a full `TriggerTrace`, reading `TriggerTrace.held` off the root and discarding
the rest. The alternative was a second, hand-written traversal that only
computes a `Boolean`, kept beside the first. Short-circuiting a group's
children is a promise this codebase makes deliberately, not an optimisation,
so two independent copies of that promise would be two places for it to
quietly drift apart. One traversal, built once, is what `GateTest`'s existing
short-circuit and empty-case tests now exercise directly, since `holds` calls
into it.

**A leaf the evaluation never reached is not the same claim as a leaf that
said no.** An `ALL` group short-circuits on its first failing child, so a
sibling further along is never asked at all, not even for the fact of whether
it is the leaf that fired. `notConsulted()` walks the unreached subtree and
marks every leaf under it `NOT_CONSULTED`, without calling `stateOf` on any of
them: asking anyway, only to throw the answer away, would still pay the read
the short-circuit exists to avoid. This is the single most important honesty
property of the trace. Without it, a rule whose second condition sank an
`ALL` would show its third and fourth conditions as though they had also
failed, when the truth is that the tree already had its answer and never
looked.

**The leaf that fired is its own outcome, not a `YES`.** It is true by
construction and was never read, the same reason `TriggerNode.holds` never
calls `stateOf` on it either. And, in exactly the case above, the leaf that
fired can itself end up `NOT_CONSULTED`: traversal order follows the tree's
own shape, left to right, not which leaf produced the event, so an earlier
sibling that already decides an `ANY` skips every leaf behind it, the one
that fired included. Reporting it as `FIRED` regardless would be true of the
event and false about this evaluation.

**A firing that held is recorded too, not only one that did not.** Someone
debugging why an `ANY` fires *this* often, and not on some other condition
they expected, needs to see which leaf carried it on a run that succeeded;
limiting the trace to failures would answer only half of "why did my rule do
what it did". `TriggerEngine.onEvaluated` is called once per event, whichever
way the tree came out, alongside `onOutcome`, `onStartFailure` and
`onSuppressed`.

**The trace kept across a retry is the last completed one, not necessarily
the last attempted one.** `resolveHolds` updates a `lastTrace` after every
finished call to `evaluateTrace`, so a retry that itself times out mid-read
still leaves the previous, fully-formed trace in place. That is the common
case, since it is usually the waiting between tries that spends the budget,
not one single read. The one gap that leaves is the very first try hanging
past the whole twenty-second budget, with no completed try at all to fall
back on. `approximateTrace` covers it, rebuilding a trace from the same
`answers` map `StateReader` already kept for `unreadable`. It is not a second
traversal engine: it does not re-run the short-circuit logic, only labels
each leaf from what was already recorded. That is safe specifically because
there is at most one incomplete attempt behind the map in this call path;
reusing it across several completed retries would not carry the same
guarantee, since a later round's different short-circuit path could leave an
earlier round's stale answer for a leaf this round never touched.

**`RuleTraceLog`, in `:ui`, holds the result the same shape `RuleFaultLog`
holds a fault: one slot per rule id, overwritten, and not persisted, for the
same reason.** A trace describes a run under conditions that existed at the
time; a trace from a dead process describes a run whose conditions are gone.
It is a separate sink from `RuleFaultLog` rather than a fourth `RuleFault.Kind`,
because a fault is a claim that something is wrong and a held evaluation is
not one; folding it in would mean either inventing a "kind" that names no
fault, or dropping the held case to keep the class honest about its own name,
which is exactly the case this exists to stop dropping. One overwritten `Map`
entry is also the answer to the volume question `docs/todo.md`'s trace entry
raises: `screen_content` can drive ten evaluations a second, and overwriting
one field that often costs nothing, where a history or a ring buffer would
not.

**The screen is a dialog, not a destination**, the same shape the notification
inspector chose and for the same reason: a tree several levels deep wants the
width a full-bleed `Dialog` gives it, and `RulesScreen`'s `RuleBlock` already
has somewhere to open it from. `LastFaultCell` renders one sentence; a tree is
not a sentence, so "Last check" opens `TriggerTraceScreen` instead of trying
to fit the tree into that cell. It follows the inspector's own convention of
no string resources for its labels, the same developer-facing register that
screen already uses for "Title" and "Text".

### A hidden field must not decide the answer

The Bluetooth trigger identifies a device by address or by name, and the choice
is stored as `identifyBy`. That key used to be editor-only: it decided which of
the two fields was *drawn*, while the engine read both and ANDed them.

The reasoning was real. A rule saved before the key existed might match on a
name, and the editor would show it as "Any device", so keeping the hidden value
live meant such a rule kept working. What it also meant: a rule showing a device
picked from the paired list, with a name filter left over from an earlier
attempt, matched nothing at all, because the name had to match too and the
device's advertised name did not contain the leftover text. The editor showed one
filter and the engine applied two.

`identifyBy` is read at runtime now, by `bluetoothWantedAddress` and
`bluetoothNameFilter`: `address` ignores any stored name, `name` ignores any
stored address. Absent used to keep ANDing both, exactly as before the key
existed, and that half-fix is what a real rule then died of.

The key is seeded when a component is *chosen*, so a rule written before it
existed has no value here and editing that rule never adds one. Every such rule
kept the ANDed reading, invisibly: `bluetoothIdentifyBy` now resolves absence
from what the rule stores instead. A stored address wins, because an address
identifies one device on its own and a name beside it can only subtract; failing
that a stored name wins, which is the legacy promise the AND was really
protecting; failing both, "any device". Never two filters at once, which is the
invariant a test pins directly.

The other half is that the editor has to agree. A `shownWhen` condition can only
read a stored value, so the form drew the schema default and hid the filter that
was deciding every match. `ComponentFactory.normalise` is the hook for that: the
editor asks the registry to fill in what an older build left out before it draws
a rule, and saving writes the answer down for good. `:core` stays ignorant of
which key any component grew.

The general rule this is an instance of: a field the editor hides must not
change what a rule does. Either it is shown, or it is inert. And a key added
after rules were already being saved needs one place that decides what its
absence means, or the editor and the engine will each decide differently.

### Requirements

Triggers and actions declare what they need (a runtime permission, a settings
screen the user must visit, a hardware feature, a minimum API, or a Play policy
restriction) as `ComponentRequirement` on the *factory*, so it can be read
without instantiating anything.

This exists because Android gives the app no way to distinguish "the condition
has not occurred yet" from "this can never fire". A rule whose notification
access was never granted looks identical to one that is simply waiting.
Declaring the precondition per type lets the rule editor state it up front and
lets a diagnostics screen explain a silent rule afterwards.

`RequirementChecker` evaluates them against the device, and the rules screen
shows an enabled-but-unfirable rule what is missing, with a button to the
permission dialog or settings screen. Requirements are re-read when the app
resumes, because a grant made in system settings reports nothing back.

**A requirement that is met is not shown at all.** The text exists so nobody
saves a rule that cannot fire; once the access is granted it has nothing left to
say, and a "Grant" button next to something already granted invites pressing it
again. So the editor draws only the *unmet* ones, and a component whose
requirements are all satisfied has no requirements section. The check is passed
into the screen rather than performed there (it reads live device state, which a
stateless screen has no business reaching for), and the activity bumps a counter
on resume so returning from a settings screen re-evaluates it. Without that
bump the row would linger after the grant and read as the grant having failed.

**And a requirement withheld needs a proven claim, not a plausible one.**
`requirementsFor` lets a component drop a requirement its configuration does not
use, which is right for `play_alert`, whose early stop is the only thing that
needs notification access. `bluetooth_connected` used it to drop
`BLUETOOTH_CONNECT` from an "any device" rule, reasoning that such a rule reads
neither the address nor the name and so matches the raw broadcast. There is no
raw broadcast: the Bluetooth stack sends `ACTION_ACL_CONNECTED` with that
permission named as the *receiver* permission, so a receiver without the grant
is sent nothing. The rule could not fire for anybody and the list said nothing
was missing, which is worse than the over-eager row the override exists to
avoid. A permission that gates delivery belongs in `requirements`, unconditional
except for the Android version that introduced it.

`docs/triggers.md` catalogues every planned trigger against this model, plus
the cross-cutting blockers (no foreground service, no scheduler) that gate
whole groups of them.

#### Granted is not the same question as live

`RequirementChecker.isSatisfied` answers one question: did the user grant
this. For the notification listener and the accessibility service, granted and
working are not the same fact. Both are backed by a service the framework
constructs and can drop without telling anyone who would notice, and the
setting `isSatisfied` reads stays on regardless. See "Getting the notification
listener back" for the failure this is about.

`RequirementChecker.liveness` is the second axis, kept apart from `isSatisfied`
rather than folded into it. It answers `LIVE`, `NOT_LIVE`, or `UNKNOWN`, and the
third state is there on purpose: a service nobody has asked about yet must
never be reported as dead, because a requirement model that accuses on silence
instead of on evidence is one nobody can trust. `NOT_LIVE` only ever applies to
a requirement that is already granted, so it can never double up with `unmet`
and say the same rule is broken for two contradictory reasons.

The fact this reads is `NotificationController.isConnected` and
`UiController.isConnected`, the same two ports actions already use to reach
these services from `:actions`. `ControllerLivenessProbe`, in `:core`, adapts
them into the small `LivenessProbe` port `RequirementChecker.liveness` takes,
so no new path into `:triggers` had to be opened for this: `:core` still does
not depend on it, and `:ui` wires `ControllerLivenessProbe` in next to every
other seam it already assembles. Every existing `RequirementChecker` caller
that does not pass a probe gets `LivenessProbe.Unknown`, which answers nothing
for every kind, so nothing about this is a behaviour change until something
asks.

The rules screen shows a granted-but-not-live requirement in its own row, with
its own wording and its own button to the same settings screen: a "Grant"
button next to something already on would read as broken UI, not as help.

### Config schema

Config is stored as `Map<String, String>`. The engine is happy with that; a form
cannot be drawn from it. So each factory also declares its fields as
`ConfigField`, the same pattern as `ComponentRequirement`: declared on the
*factory*, consumed by the UI, invisible to the engine.

**A field can declare when it applies.** `shownWhen` on a `ConfigField` names a
sibling key and the values that make this field relevant; the editor draws only
the fields whose condition holds. `play_alert`'s "keep sounding for" is the case
it was built for: a tone set to play once lasts exactly as long as the tone does,
which is the one length a duration field cannot express, so the field is not
drawn rather than drawn with a sentence explaining that it does nothing.

Two details decide whether it behaves: the sibling's *effective* value is what is
stored **or failing that its own default**, because an untouched rule has nothing
stored for the gating key while the editor is plainly showing its default. The
naive version hides the duration on every newly added alert. And a condition
naming a key no sibling declares leaves the field visible, so a typo looks like a
condition that does nothing rather than a field that vanished. Both are pinned by
tests, since neither is visible by reading the schema.

Deliberately just equality against a set of strings. An expression language here
would be a second, worse validator competing with the `create()` that already
owns cross-field rules.

**The same move, spent on one sentence of `help` instead of a whole field.**
`ConfigField.Text.helpWhen` names a sibling and the value that makes one extra
sentence apply, appended after the field's own `help` when it does.
`set_variable`'s value field is the case it was built for: the same box is
explained three different ways depending on the sibling `mode` field, and
printing all three regardless of `mode` is what grew that field's help to four
topics and 300 characters. `ConfigFieldEditor` reads it through
`ConfigField.effectiveHelp(companions)` rather than `field.help` directly, and
`companions` is the same map `NotificationButton` and friends already fill in
from `companionKeys()` — so a mode-specific sentence reaches the screen through
a channel the editor cannot tell apart from a two-key field's own companion,
and the editor itself never learns `mode`, `set_variable`, or any other
factory's vocabulary. It only ever asks "does this sibling's value match",
the same question `shownWith` already asks.

It resolves a missing sibling the opposite way `shownWith` does, on purpose.
`shownWith` falls back to a sibling's own default, because guessing "hidden"
wrongly is the worse mistake — a field vanishing from a form nobody has
touched. Guessing wrong here prints an extra sentence nobody asked for on a
form nobody has touched, which is the smaller mistake, so a sibling with
nothing stored contributes no extra sentence rather than one for its default.

Field kinds cover every component: `Text`, `TextPattern`, `Choice`, `Number`,
`Decimal`, `Flag`, `AppPackage`, `SoundUri`, `BluetoothAddress`,
`NotificationButton`, `RuleRef`, `Slider`, `Duration`, `Timestamp`, `TimeOfDay`,
`Coordinates`. `Choice` carries the most weight, because the fourteen two-word
state fields (`enabled`/`disabled`, `plugged`/`unplugged`, `entered`/`exited`)
use a different word pair per component. That is precisely why the words must
be declared per factory instead of inferred from the key name.

**Most of them exist purely so the editor can offer a better control for
something an existing kind could already store**, and that is the pattern to
follow rather than an accident to tidy up. `RuleRef` is the newest: a rule id is
a UUID, so `Text` could hold it and nobody could ever fill it in. Every one of them keeps the stored
value byte-for-byte and changes only the control, which is why adding them
migrated nothing: no saved rule, no exported file, no `RuleJson` version.

The second wave came out of a sweep of all 77 declared fields, prompted by the
calendar action asking for a time in *epoch milliseconds*. The pattern behind
that field turned out to be general: the stored value and the shown value were
the same thing, because config is a `Map<String, String>` and the map's view was
winning. `Duration` reads and writes milliseconds but is entered in seconds,
minutes or hours, choosing the unit on load by exact divisibility so 1800000
comes back as the "30 min" someone typed rather than 1800 seconds. `Timestamp`
puts a date control and a time control over one epoch value. `TimeOfDay` and
`Coordinates` each own **two** keys, following `TextPattern`, because an hour
without a minute and a latitude without a longitude are not half an answer.

Three kinds' worth of control was not the whole fix. Two fields were deleted
outright, which is the other half of the same lesson: `cancel_notification`'s
"Notification id" could never be filled in correctly, since ids are minted from
`firedAtMillis`, and `dismiss_notification`'s "Notification key" is generated by
the posting app. Both had exactly one working value (empty), so the honest
control was no control. `AppPackage`, `SoundUri` and
`BluetoothAddress` all store and validate exactly like `Text`; each is separate
because its value is one nobody can produce from memory: `com.google.android.dialer`,
`content://media/internal/audio/media/54`, `00:11:22:33:44:55`. `Slider` stores
like `Number` but renders as a track, and the line between them is not "has
bounds". It is what the bounds *mean*. A `Number` bound is a guard rail on a
value you have decided (a 5000 ms poll interval), where a slider would be fiddly
to hit and illegible once set. A `Slider` value is a position (half volume)
where the digits are the least interesting part. Adding a kind for presentation is
cheap because the `when` in `ConfigFieldEditor` and in `ConfigSchemaContractTest`
are both exhaustive: the compiler names every place that has to handle it.

The three pickers share their behaviour rather than their appearance.
`ValuePicker.kt` owns the searchable list, the row that restores blankness, the
manual-entry escape hatch and the value box; each field supplies only its list,
its labelling, and what "type it yourself" means there. That last one is where
they genuinely differ, and the differences are deliberate: a sound offers **no**
typed value, because a URI is not something a person composes and offering the
option would only invite one that cannot resolve, while a Bluetooth address must
offer one, because *paired* is not the same set as *can connect*. A device paired
to another phone still fires `ACTION_ACL_CONNECTED`. For the same reason an
address is displayed alongside its device name and a sound URI is not: the
address is short and distinguishes two identically-named devices, whereas the URI
is the thing the picker exists to hide.

Any of them can hold a value this device does not know (an imported rule, an
uninstalled app, a deleted sound, an unpaired device), and all three render it
raw in that case. Blank would make the rule look empty when it is merely
unresolvable here.

**The schema renders; the factory still validates.** Nothing in `ConfigField`
duplicates the `require()` checks inside `create()`. Bounds like `Number.min`
exist to pick a keyboard and write a hint, not to guarantee anything. The editor
validates by calling `create()` and surfacing what it throws, because the real
rules are not expressible declaratively: `notification_watchdog` needs "poll must
not exceed absence", and `IntervalTrigger`'s positive-period check lives in its
constructor rather than its factory. One validation path, and it is the one the
engine will actually use.

`blankMeaning` is load-bearing rather than decoration. Several components treat
an *absent* value as "match anything": `bluetooth_connected` without an address,
a package filter left empty. An editor that helpfully supplied a default would
silently narrow the rule, so blankness is declared as a setting rather than left
to look like an unfilled field.

How it renders depends on the field kind, and the wording follows. A `Text`
field shows it as a hint under an empty box, so it reads as an instruction:
"Leave blank for any address". An `AppPackage` field is a picker with no blank
state to leave alone, so the same declaration is phrased as a *value* ("Any
app"), shown as what the field currently says and as the row that sets it back.

`TextPattern` is the one kind that owns **two** config keys: the pattern and
its match mode. See "Matching text, and matching it loosely" below for why they
are one field rather than a text box beside an unrelated dropdown.

Factories also declare `displayName`, `category` and an optional `warning`.
`category` is what makes a 28-item trigger picker usable; `warning` is where a
caveat that used to live in KDoc reaches the person building the rule.

The UI never touches a factory. `Registry` exposes a flattened
`ComponentDescriptor` instead, so the editor cannot call `create()` while someone
is still typing: construction is the validation step and it belongs at save
time.

Everything here is defaulted on `ComponentFactory`, so a factory that declares
nothing is ugly rather than broken. The drift guard in
`ConfigSchemaContractTest` is what keeps it from staying ugly: it walks every
registered factory, not a hand-maintained list.

### Variables: what a trigger emits, and how an action reads it

`docs/variables.md` holds the whole plan and the paths that were weighed and
rejected. This is the shape of what phase 1 built. The subsections below carry
what came after it: app scope, the screen that makes it findable, a value that
computes, an output an action hands to the next one, one rule running another,
and a wait inside a rule.

A variable is a **named string**, and only a string. There is no value type.
Arithmetic arrived later, in a closed expression language, and what it computes
is parsed out of a string and formatted back into one: see "A value that
computes" below. There is still no control flow.
`TriggerEvent.payload` was already
`Map<String, String>` and config was already `Map<String, String>`, so the
feature fits inside shapes that existed.

Almost all of the runtime half existed too. 32 of the 34 registered triggers
already filled a payload, and two actions already read a key out of one. What
was missing was a *declaration*, so nobody could find the values, and a *seam*,
so an action could read one.

**The declaration.** `ComponentFactory.variables` is a list of `VariableSpec`,
defaulted to empty, and filled in by the triggers. Same pattern as
`configFields`: declared on the factory, consumed by the UI. Two properties of
it carry more weight than they look like they do.

A spec's `key` is written as the trigger's own `PAYLOAD_*` constant, never as a
literal. That ties the declaration to the emit site through the compiler, so a
rename moves both. It is the only half of the emit-and-declare pair a machine
can check: a key declared and never emitted is a variable that is always
absent, and catching that needs a real trigger to fire.

`alwaysPresent` is false for a key the platform does not always supply. A
Bluetooth device with no name, an SMS with no originating address, a
notification with no title: each is a `buildMap` that leaves the key out. A
picker that listed those beside a key that is always there would promise a
value the event often does not carry.

Declaring is deliberately not the same as emitting.
`SharedPayloadKeys.NOTIFICATION_KEY` is emitted and not declared, because it is
opaque, generated by the posting app, and useful only to the two actions that
target a notification with it.

**Names.** One syntax, `{{scope.name}}`, with an optional fallback after a pipe.
`{{trigger.text}}` reads the leaf that fired, whichever leaf that was, and it is
the form the picker offers first: a rule usually has one trigger, and this name
keeps working when the person changes which trigger it is. The type-qualified
form, `{{bluetooth_connected.name}}`, exists because a rule's trigger is a tree
and only one leaf fires; it resolves when that type is what fired and says so
plainly when it is not. `availableVariables` offers it only for a tree with more
than one leaf, since for one leaf it says nothing the short form does not, and
it recomputes `alwaysPresent` while it is there: a key is always present under
`trigger` only if every leaf declares it.

`trigger`, `event`, `rule` and `app` are reserved words, so no trigger type may
be one. `app` is reserved and not resolved: the store behind it is phase 2, and
taking the word now keeps that from being a breaking change for saved rules.

**The seam is in the engine, and this is the part that had a wrong answer
available.** An action is built once per rule start and reused for every event,
so `HttpRequestAction` captures its URL in its constructor: substitution cannot
happen in `create()`. The other wrong answer is for each action to read the
payload itself, which is what the two notification actions do. That puts the
same code in twenty actions, and an action that forgets it is a rule that
quietly ignores its own variables.

So `TriggerEngine.ActionSlot` resolves the declared fields per event and rebuilds
the action only when the resolved config differs from what the live instance was
built from. Two consequences worth stating:

- **A rule with no variables behaves exactly as it did before.** Its instance is
  built once and every event reuses it. That is a compatibility promise rather
  than an optimisation: rebuilding a component needlessly is how this project
  has caused phantom firings before.
- An instance is still built from the *raw* config at start time, even for an
  action that will be rebuilt on its first event. That is what keeps an unknown
  type, or config a factory refuses, failing inside `startRule` where
  `onStartFailure` reports it, instead of at the first event where it would read
  as a failed run.

**Escaping is declared per field, not chosen by the resolver.** The same value
needs different treatment depending on where it lands: a notification title with
a quotation mark in it is fine in a notification, breaks a JSON body, and
silently adds a query parameter in a URL if it contains an ampersand. So
`ConfigField.substitution` says whether a field accepts variables and how a
value is escaped, and `ComponentFactory.substitutionsFor(config)` is the
override for a field whose escaping depends on a sibling. `http_request` is the
case that needed it: the body is a JSON string when the content type is JSON and
plain text otherwise. The relationship between the two is exactly the one
`requirements` has to `requirementsFor(config)`.

Encoding applies to an **embedded** reference only. A field whose whole value is
one reference gets the raw value, because that field *is* the value:
`{{app.endpoint}}` as an entire URL must not come out percent-encoded, while
`https://x/?t={{trigger.title}}` must.

**An absent value fails the field.** `TriggerNode.holds` set the precedent that
unknown is not yes, and it applies with more force here. An empty string in a
notification is cosmetic; an empty string in a webhook URL or an SMS is a wrong
action taken quietly. The failure names the reference and says why it was empty,
and it reaches the screen through the outcome hook that already exists, as
`RuleFault.Kind.ACTION_FAILED`. A person who is happy with empty writes a
fallback in the field, where it can be read.

The editor validates every reference at save time, so a name nobody offers is
reported while the field is on screen rather than by a rule that fails every
time it fires. And `VariableSpec.sample` is what finally gives the Test button
something to work with: the test event used to carry no payload at all, so an
action that read one saw nothing.

#### App scope: a value that outlives the run

Phase 2 of `docs/variables.md`. Event scope answers "what just happened".
App scope answers "what happened before", which is what a counter, a "last seen
at", or a cooldown needs, and it is the only way one rule can tell another rule
anything beyond the existing `set_rule_enabled`.

`VariableStore` is shaped after `RuleRepository` and deliberately **not** after
`NotificationController`. That distinction was written down wrong in the plan
first, so it is worth stating: `NotificationController` is a port, and it exists
because its implementation has to live in `:triggers` beside the listener service
while its caller lives in `:actions`. Its "unavailable" default is a true state
of the device. A variable store has no such problem, because its Room
implementation lives in `:core` beside the interface. So the default everywhere
is a *working* `InMemoryVariableStore`, not one that refuses: "this device has no
variables" is not a state a device can be in, and a refusing default would make
every test that did not wire a store silently test nothing.

`triglyDatabase(context)` had to be memoized before any of this. It built a fresh
`Room.databaseBuilder` on every call, which was invisible while
`ruleRepository(context)` was its only caller. A second factory beside it would
have opened two databases on one file.

**The read happens once per action, not once per event.** This is the part where
the obvious implementation is wrong. A rule's actions run in sequence, and one of
them can be a `set_variable` that a later one reads. A snapshot taken before the
first action would show the later action a value the earlier action had already
replaced, and "actions run in order" is how anyone reads a list of actions. So
`ActionSlot` collects the app names its own templates reference, once, at start
time, and reads exactly those immediately before that action runs. An action that
references none reads nothing at all, which keeps the cost on the rules that use
the feature. `VariableLookup` stays pure and non-suspending throughout, which is
what that snapshot is for.

`TriggerEngine` takes the store as a **required** constructor parameter. Around
twenty-four test call sites had to be updated to pass one, and that was the
cheaper half of the trade: a defaulted store would mean that a production
assembly point which forgot to wire the real one would read every app variable as
empty, silently, forever.

**Writing: `set_variable`.** Three modes, set, clear and add. Add is what a
counter needs, so it starts from zero for a name nothing has written yet, and it
fails loudly on a stored value that is not a number rather than treating it as
zero and discarding what was there. It works in `BigDecimal` rather than
`Double`, so a total built from repeated fractional additions does not drift, and
it strips trailing zeros so a whole-number counter reads as `5` and not `5.0`.

**Reading as a condition: `variable_check`.** A component with no events, built
like `TimeWindowCheck`, which needed **no engine change at all**: `TriggerNode`
already combines a level with an edge, and `canStart` already knows that a
check-only component cannot start a rule. That is the whole payoff of a condition
being a trigger rather than a second component family.

Three decisions in it are worth keeping:

- Its value field does **not** accept a variable. A condition is asked without an
  event, because `currentlyHolds()` is called about a leaf that did not fire, so
  a `{{trigger.*}}` reference there would be empty exactly when it is used.
- A name the store does not hold is a **definite** answer, not unknown. The store
  knows the name is absent. `null` is reserved for a read that actually failed,
  which is the only path to it, because `null` costs a retry and then a
  suppressed rule.
- An unrecognised comparison **refuses the build**. Absence reads as the declared
  default, which is ordinary: a `Choice` declares one, the editor draws it, and
  `normalise` writes it down. But a value this build does not know can only come
  from a hand-edited file or an export from a newer build, and degrading it to
  some other comparison would gate unattended actions on a question its author
  never wrote. `HttpRequestAction` already refuses a method it does not know, for
  the same reason.

**A save may not require an app variable to exist.** `variableProblems` accepts
an app reference on sight, and that has to stay true. App variables are written
by rules, so the rule that reads `{{app.trip_count}}` is very often saved before
the rule that first sets it, and refusing that would make the pair impossible to
write in either order. There is no name left to check either: a name that arrived
inside a parsed reference has already proved it can be read back, which is the
only question `variableNameProblem` asks.

That function is worth one more line. It validates a name by building the
reference the name would need, parsing it, and checking that what comes back is
the reference that was meant. A regular expression here would be a second
spelling of the grammar, and it would drift the day the grammar gained a
character: a name a person was allowed to store would become a name no field
could read.

**The store has one wholesale read, not two.** `history()` carries every value
with the moment it was written, and `all()` is an extension derived from it. The
first shape of this had both as interface methods, with `history()` defaulted to
derive from `all()` and report every timestamp as zero. That is two spellings of
one fact, and the default was a plausible lie: an implementation that overrode
`all()` and not `history()` would have rendered every saved value as last changed
in 1970. One abstract read means an implementation cannot answer half the
question.

#### Seeing and setting a saved value

The screen that makes app scope findable. Before it, nothing showed which saved
values existed or let a person set one by hand, so the editor's variable picker
had nothing to offer until some rule had already written something. A working
feature that cannot be found is the failure this project keeps designing against,
and this was an instance of it.

Reached from the overflow beside "New rule". That is its third home, and each
move was caused by the last: a third header action broke `BlockHeader`, which
lays actions after a title that takes the remaining width; a full-width row
fixed that and charged the rule list a row of height on every visit, for a
screen a person opens rarely. An overflow was rejected at the row stage because
a menu *in the header* would have had to swallow Import and Export all to make
room. In the bottom bar it displaces nothing. The count came along into the menu
entry, because a rule having written a value is how a person finds the screen at
all.

It was previously reached from the rules list header, beside "Export all", and
the reasoning for putting it near neither rule nor list still holds: a saved value
belongs to no rule: any rule can read it and any rule can write it. It is offered
even when there are no rules at all, unlike export, because somebody arriving
before their first rule is exactly who needs to learn what a saved value is. The
empty state therefore has to teach rather than apologise: it names the action that
writes one.

**Deleting names what it will break.** A value that rules read cannot vanish
quietly, because those rules would start failing on a reference that no longer
resolves and nothing afterwards would say why. So a delete on a value two rules
read names both rules first. A delete on a value nothing reads gets no dialog at
all: ceremony only where there is something to lose.

`Rule.appVariablesRead` answers that question, and it finds **reads only**.
Finding what *writes* a variable would mean knowing that `set_variable` is the
action that does it and which config key holds the name, which is one component's
identity in a shared file, and the plugin rule forbids exactly that. A read is
spelled `{{app.name}}` in a field that declared it accepts a reference, so it is a
property of the grammar rather than of any component, and a component added later
that learns to read a variable is found by this without it changing.

"Read by two rules" is drawn as information rather than as a warning, per
"Warnings are not errors" below. A value being used is the ordinary case, and it
is the reason the value exists.

#### A value that computes

`set_variable`'s evaluate mode and `run_rule`'s "only if" condition both run the
same small expression language, in `core/Expression.kt`. Arithmetic, comparison,
a ternary, `and`/`or`/`not`, and six string or number functions, one of which
takes a match mode and can search with a regular expression.

**It runs after substitution, never instead of it.** A field declared
`Substitution.EXPRESSION` is substituted first, which turns every `{{...}}`
reference into a literal the grammar can parse, and only then evaluated. So the
evaluator never sees a variable and needs no idea that variables exist. It also
means the expression encoding is the one that ignores the single-reference
exemption the other encodings share: an expression field is always source text
to run, so a field holding nothing but `{{app.count}}` still has to arrive as
`42`, and a device name still has to arrive quoted.

**A closed grammar rather than an embedded script, because a rule is a file
somebody else can import.** JavaScript or Python in a rule would be a way to
carry arbitrary code onto a stranger's phone. This language has no variables of
its own, no loops, no function a person can define, and no call that reads or
writes anything outside the string it is given. Every function is fixed and
reviewed. That is what makes the safety story short enough to state: with no
loops and no recursion a person can write, every expression does a bounded
amount of work and returns, so the only thing left to protect is the parser's
own call stack. The parser bounds the input length and the nesting depth, and
nothing else stands between an expression and the evaluator. The bound is on the
grammar staying this small, and `Expression.kt` says so where somebody would add
the feature that ends it.

**A regular expression is the one part that is not the language's own work, so
it has a bound of its own.** `contains` takes a match mode, `"contains"` or
`"regex"`, the same pair `TextFilter` uses. A backtracking engine can do an
unbounded amount of work on a bounded input, which is exactly the property the
paragraph above depends on not existing, so the feature could not ship without
the replacement bound the file had already promised.

That bound now lives in `core/RegexBudget.kt`, not in `Expression.kt`, because
`TextFilter`'s `regex` mode turned out to need the identical one: see "Matching
text, and matching it loosely" below for why a trigger's text filter can run
into the same unbounded engine on a hotter path than an expression ever does.
The number, the shared thread and the type that reports a refusal are shared
code, not two copies that happened to agree.

**The first version of that bound was a rate, counted in characters read, and
it never worked on the platform this app ships to.** The reasoning for a rate
over a flat number was sound and is worth keeping: the honest cost of a search
is not flat. `contains` searches from every position, so an ordinary `.*b` over
1800 characters reads 4.9 million characters, while a genuinely bad `.*.*.*b`
over sixty characters reads only 1.9 million. No single number can separate
those two, because the good pattern over long text costs more than the bad
pattern over short text. Reads rather than milliseconds, for the reason that
decides most things here: a timeout would make a rule work on a fast phone and
fail on a slow one. The count was taken by handing the engine a `CharSequence`
that counted what it read.

Android's `Matcher` converts its input to a `String` when it is handed anything
else, so that counting `CharSequence` was never read on a phone and nothing was
ever refused there. Worse, before the wrapper overrode `toString`, the search
ran against `Object.toString()`: a pattern matched the hex digits of a hash
code, and a device test reported a match at index 37 of a six-character
sample. The correctness half was fixed by overriding `toString`. The bound
itself needed a different mechanism entirely, because there is no way to make
Android's engine read a custom `CharSequence` one character at a time: it
copies to a `String` first, always.

**What replaced it bounds the wall clock instead, on one shared thread.**
`RegexGuard` in `core/RegexBudget.kt` runs every bounded search on a single
background thread and waits up to five seconds for an answer. A regular
expression search cannot be interrupted, so the thread that runs a pathological
pattern keeps burning CPU for as long as that pattern's own backtracking takes,
whatever the waiting caller decides. A second search asked for while the first
has not yet timed out is refused at once, not queued behind it, because
`screen_content` can ask for a new one every hundred milliseconds and a queue
in front of a stuck search would grow without end.

**A search that does not finish in time is abandoned, not left holding the
guard.** The first version of this design kept the same thread forever and
cleared its one "busy" flag only when the search running on it truly finished,
which a runaway pattern never does. Found by the connected gate before this
ever reached `main`: an ordinary six-character search failed with "took too
long", because an earlier test's pattern had already poisoned the one shared
thread for good, and every later search of any pattern was refused from then
on. One bad pattern in one rule would have permanently turned off regular
expressions for every rule on the device. `RegexGuard` now abandons the thread
a search timed out on and starts a fresh one for the next call, and remembers
the pattern's identity, its text and whether it matched case-insensitively, so
that specific pattern is refused on sight rather than abandoning a second
thread for it on its next event. `MAX_ABANDONED_THREADS` caps how many
distinct bad patterns may be abandoned at once, so several different bad
patterns arriving close together still cannot pin more than a handful of
cores. A refusal now says which of four reasons it was, and every caller that
shows or reports one to a person says the right one rather than "took too
long" for a search that was never even tried.

Five seconds is measured, not guessed, on an emulator whose CPU is the host
machine's and is likely faster than a mid-range phone. A notification-sized
pattern answers in under a tenth of a millisecond. The most expensive honest
pattern measured, an unanchored search missing over 1800 characters, answers in
18 to 46 milliseconds, so five seconds is more than a hundred times that. A
pattern built to be refused, three or four of `.*` chained together, does not
finish in ten to fifteen seconds on the same devices, so refusing at five costs
nothing an honest pattern needed.

**The honest limit of a wall-clock bound is that it does not grow with the
text, and a character-count bound did.** `screen_content`'s haystack is
`visibleScreenText`, which has no length cap, and the same two honest patterns
measured above take 2.3 to 2.8 seconds over 20000 characters on that same
emulator, only about twice under the five-second bound rather than a hundred
times. A haystack large enough, on a device slow enough, could still see an
honest pattern refused. `docs/todo.md` T24 has the full account of that
trade-off, including the rejected alternative that does not have it.

Note which pattern is *not* the threat, whichever mechanism bounds it. The
textbook `(a+)+b` reads 1741 characters over thirty `a`s on the JDK these
numbers first came from, and finishes in under a millisecond on the JVM these
numbers were remeasured on, because that engine optimizes that shape away.
That is one engine's optimization of one shape, the pattern arrives inside a
rule somebody else wrote, and ART is not that engine. The bound is what makes
the claim, not the engine's good behaviour on the famous example.

Two lessons worth keeping, because they cost a full day between them and a
second design besides. The paragraph above was right that one engine's
behaviour is not a safety argument, and it was written while depending on
another engine's behaviour without checking it. And 1852 green JVM tests said
the first bound worked. The instrumented tests are what said otherwise, which
is the whole reason this project weighs them the way the testing section says
it does: a mechanism that only an instrumented test can disprove is exactly the
kind of bug this project's testing posture exists to catch, and it did.

Arithmetic is `BigDecimal`, the same choice `set_variable`'s add mode made and
for the same reason: a running total built from repeated fractional additions
drifts visibly in binary floating point, and a rule that computes a total is
what this exists for. Division is the exception that needs a rule of its own,
because exact decimal division does not always terminate, so it rounds.

#### What an action hands to the next one

An action can produce a value for the actions after it in the same run, read as
`{{action.<key>}}` or `{{<action type>.<key>}}`. `ActionOutputs` in `:core`
carries it: fresh at every event, grown as each action returns, and never saved.

**Only a value the action computed.** `set_rule_enabled`'s toggle mode is the
case that asked for the feature, because "flip it" leaves the action that
flipped it as the only place that ever learns which way it went. What is
deliberately not here is an arbitrary captured result: `HttpRequestAction` still
never reads the response body, and its own KDoc calls draining an arbitrary
response into memory a liability rather than a feature. An output is declared
like any other variable, with a label, a sample and help text, so the editor can
offer it and a person can read what it means.

**The editor asks per action, not per rule, and that is the whole feature from
where the person stands.** The engine grows the outputs as it goes, so an action
naming a *later* action's output resolves absent on every firing.
`availableActionOutputs` therefore takes the action's position and offers only
what is above it. This half arrived late, and its absence is worth recording:
the engine resolved these references correctly and every producing action
declared its output, while `availableVariables` walked only the trigger tree, so
save-time validation refused every field that read one. A declared output the
picker never offers and validation refuses is not a feature with a missing
screen. It is a feature that does not exist. `docs/variables.md` section 15 has
the rest.

An action output is never marked always-present, whatever it declares. That is
the opposite of the rule for a trigger, which can promise a key every leaf
declares, and the reason is that an earlier action *running* is not the same as
it producing: it can fail first, and `set_variable`'s clear mode succeeds while
storing nothing.

#### One rule runs another

`run_rule` runs a named rule's actions immediately, bypassing that rule's own
trigger and its on/off switch. A rule kept switched off and reached only this way
is a legitimate design, closer to a callable routine than to a watched rule.

**The seam exists because of *when* an engine exists, not because of module
boundaries.** `:actions` already depends on `:core`, where `TriggerEngine` lives,
so naming the class would compile. The problem is that `actionFactories()` is
called from `AppContainer`'s constructor and no engine exists at that point:
`EngineService` builds one later, against the very registry being assembled, and
only once a rule is enabled. So `run_rule` holds a `RuleRunner` interface, and
`AppContainer` hands it a `RuleRunnerHandle` that starts with nothing to
delegate to. `EngineService` attaches the real engine when it creates one and
detaches on destroy. A call arriving outside that window is refused with a
reportable reason rather than silently doing nothing, the same choice
`NotificationController.Unavailable` makes for "notification access is off".
This is `RuleFaultLog`'s shape in the other direction, and unlike that sink it
cannot live in `:ui`, because `:actions` has to name the interface it calls.

**The loop guard was designed before the action shipped.**
`docs/variables.md` section 11 refused a `variable_changed` trigger for exactly
this shape and wrote down that a guard had to exist first. Two parts: a rule
cannot run itself, directly or by appearing again further down its own chain,
refused outright because no depth makes a cycle safe; and a chain of distinct
rules is capped, because the first check cannot see a cycle in which no single
rule repeats. The chain travels as a coroutine context element rather than as a
field on the engine, so two rules can each be part-way through their own chain
at once without mixing them together.

#### Waiting inside a rule

`delay` holds the rest of the rule for a set time, on the scheduler port's
`waitFor` and never on a plain coroutine `delay`, which Doze can sleep straight
through. The port's *durable* form is deliberately not used, and the reason is
worth stating because it looks like the safer choice. A durable wait works for
`interval` and `solar` because a fresh collection after a killed process is a
correct resumption: "the next occurrence" means the same thing either way. This
action holds a position part-way through one firing of one rule, plus whatever
earlier actions produced, and none of that is saved anywhere. A restarted engine
listens for the *next* qualifying event and has no way back into the firing that
was interrupted, so a durable alarm would only ever wake a process with nothing
left to resume, at the cost of a needless wake and, without the battery
exemption, a refused foreground-service start.

What that costs is stated in the action's own warning rather than left to be
discovered: a kill during the wait loses the rest of the rule with nothing to
retry it, and a second event reaching the same rule while it waits never runs
beside the wait, because a rule runs its actions in one coroutine and
`TriggerEngine.startRule` collects that rule's whole trigger tree as one merged
flow that nothing reads again until the wait, and every action after it,
returns.

That is a promise about ordering, not about delivery. Nothing collects the
merged flow while `delay` waits, so a later event sits in whichever trigger
produced it, and every trigger's own hold on it is bounded: a bus-backed
trigger's `ServiceEventBus` keeps 64 events and drops the oldest past that (see
"Services the system owns" below), and a broadcast-backed trigger keeps its own
bounded buffer and drops the new arrival instead once that fills. A wait long
enough, or a trigger bursty enough, empties either one, and the events past
that point are lost, not merely delayed.

#### One namespace per component

Two leaves of one trigger type used to share a namespace, filled by whichever
fired, and two actions of one type shared one too, won by whoever wrote last. So
a rule watching two chats could not say which chat it read. Each component
instance now has its own namespace, numbered by position among the components of
its own type: the first is the bare type string, the second is `<type>_2`.

`componentInstanceNames` in `:core` is the **single** definition of that
numbering, and that matters more than it looks. Three separate places have to
agree on it exactly: the picker that offers a name, the save-time validation
that accepts it, and the engine that resolves it. Any two of them counting
independently would produce a rule that saves cleanly and then reads the wrong
component, which is the class of bug this codebase spends the most effort
avoiding.

**The engine already knew which leaf fired.** `startRule` carries the fired
leaf's path through the merged flow, because `resolveHolds` needs it to evaluate
the tree. Numbering the leaves turns that path into the one namespace allowed to
read the payload; every other leaf namespace is refused with a reason that names
the leaf that did fire. `TriggerEvent` still cannot say which leaf produced it,
and did not need to change.

**Positional numbering is only safe because the editor repairs references.**
Deleting the first of three leaves of one type makes the old third the second,
so a saved `_2` silently starts reading a different trigger. Nothing downstream
can catch it: the reference still resolves, so the grammar is satisfied and
validation sees a name it offers. `docs/variables.md` section 12 has the four
decisions that make the repair correct, of which the two easiest to get wrong
are matching old components to new **by object identity** rather than by
position or value, and applying the renames in **one pass** so a shifting
rename cannot chain two references onto one component.

The short form `{{trigger.x}}` is offered only for a one-leaf rule, since with
two leaves it cannot say which payload arrives. The engine still resolves it, so
an imported rule keeps running, and adding a second leaf rewrites it into the
first leaf's own name rather than leaving a save to be refused.

#### Three places a value can live

`set_variable` chooses between one firing, one rule, and every rule.

**One firing** is `{{local.*}}`, and it cannot live in a store.
`Action.execute` takes only a `TriggerEvent`, so a run-scoped write has no store
to reach and no parameter to arrive by. It lives on the coroutine running the
firing, exactly as the `run_rule` chain does and for the same reason. That also
answered the question a chain raises: run values are shared down a chain,
because a chain is one firing started by one event, while `{{mine.*}}` follows
whichever rule is running now.

**One rule** is `{{mine.*}}`, a table keyed by rule id and name, added as
database version 6. The obvious cheaper design, a prefixed name in the shared
`variables` table, was rejected for three specific failures rather than on
taste: the saved values screen would bury a person's own names under
bookkeeping they never wrote; a deleted rule would leave its values behind for
ever, because nothing joins a name to a rule; and two rules could collide on the
same prefix, which is the one thing the scope exists to prevent. A keyed table
with a foreign key gets all three right by construction.

**Every rule** is `{{app.*}}`, unchanged, and it is the default for a config
with no `scope` key. It has to be: that is where every `set_variable` saved
before the field existed put its value.

An unrecognised scope also falls back there, which is the opposite call from an
unrecognised *mode*. A mode this build cannot perform refuses, because guessing
would do the wrong thing to a stored value. A scope from a newer build is most
likely one this build never had, and the honest answer to "where does this go"
is where it has always gone.

Rule-scope values are listed on the saved values screen under their own heading.
State this app persists with no way to see it and no way to clear it would be
the exact failure that screen was built to fix. Delete is offered and
hand-editing is not: deleting is the recovery path for a rule that wrote
nonsense into its own scope, while adding one by hand needs a rule as well as a
name.

#### A notification button kept for later

`notification_button` resolves its target in the live notification list, so it
can only press a button still on screen. `capture_notification_button` keeps the
button's `PendingIntent` under a name, and `press_captured_button` sends it
later, with no notification access and no notification required.

**The platform fact this rests on** is that a `PendingIntent` is a token the
system holds on behalf of the app that created it, and its life is not tied to
the notification that carried it.
`CapturedButtonOutlivesDismissalTest` established that against the real
framework before any of this was built, and also pinned the boundary: an app that
rebuilds its intent with `FLAG_CANCEL_CURRENT` invalidates every copy anyone
holds, and `send` throws rather than doing nothing, which is what lets a rule
report "the app withdrew that button" instead of claiming success.

**The token cannot be persisted, and that shapes everything.** A `PendingIntent`
is not a URI or an id. There is no form of it to write into a variable, a Room
row or an exported rule, and nothing can reconstruct one after the process ends.
So `CapturedButtons` is an in-memory map, and the limit belongs in what a person
reads before building the rule rather than in a comment: a kept button dies with
Trigly's process. The engine's foreground service is what makes that uncommon,
since a rule that keeps anything is a rule that is enabled; `docs/todo.md`'s R1
covers the one cause nothing here can fix.

It follows that "keep a button into a variable" is not possible in the sense it
sounds. What a variable can carry is the *name*, which is why the keeping action
reports it as `{{action.captured}}`: the name is a string, the token is not.

**The editor reads the captures, and that is why
`NotificationController.capturedNames` exists.** It is the only method on that
interface which no rule calls. A rule never needs it: `pressCaptured` already
answers "nothing is kept under that name" in the one place where the answer
matters. The editor does need it, because the name it is asking a person to type
was typed into a different action, and `:ui` merges it with the names the rules
declare, from `declaredKeptButtons`. That function is in `:actions` rather than
in `:ui` because the action type and the config key are declared there, and a
copy of either in the UI would be a second spelling that drifts. Empty is an
ordinary answer from both halves, not a fault, so neither is a failure type.

**Why an object rather than state on the controller.**
`ListenerNotificationController` deliberately holds nothing of its own and is
constructed wherever it is needed, so it stays correct across the unbind and
rebind cycles the system puts a listener through. A kept button has to outlive
that, and outlive the controller instance that took it, so it lives in an object
for the same reason `NotificationEvents` and `ShortcutEvents` do.

**The shared resolution, and the one place it must not be shared.**
`resolveButtonTarget` is used by pressing and by keeping, so both agree on the
package selection, on `chooseButton`'s semantic-then-label-then-index order, and
on refusing a reply box. Its outcomes are separate types rather than one failure
string, because the two actions need different answers to the
custom-RemoteViews case: the screen can press a button the system does not
expose, and it cannot hand over a token to keep. Collapsing the outcomes would
force a shared policy where there is none.

### Rule storage and the portable format

Rules are Room-backed. Two tables: `rules`, and one `components` table holding
both triggers and actions: they are the same shape, a type string and a config
map, and `:core` deliberately knows nothing about which types exist. A
`components` row carries an `ordinal`, which is what makes action *order*
durable; a rule runs its actions in sequence.

There is deliberately **no** `fallbackToDestructiveMigration`. These are rules
somebody built by hand, and silently deleting them on a schema change is not an
acceptable failure mode. A missing migration should fail loudly in development
instead. Schemas are exported to `core/schemas/` for that reason.

`RuleJson` is the portable format, and it serves two jobs so there is one format
to get right rather than two that can disagree: export/import, and the `config`
column itself. Export exists because Auto Backup needs a Google account and a
backup transport, and neither exists on a de-Googled device. That is exactly
the audience the rest of this project bends over backwards for. An explicit
file the user owns is the only phone-switch mechanism that always works
there, and it doubles as a way to share one rule with someone else. The
format is versioned, and a file from a *newer* version is refused rather than
half-read: failing to import is better than losing a rule silently.

That was the whole story until backup became a setting rather than a fixed
"never". `TriglyBackupAgent` is what makes `android:allowBackup="true"` a
question the user actually answers instead of a build-time constant: the
manifest attribute cannot become a runtime setting on its own, so a custom
`BackupAgent` is the hook that can still say no when the stored preference
says to. See that class and `BackupSettings` for the mechanism, and
`SettingsScreen` for where a person sees and changes it. Export is still the
*only* mechanism on a de-Googled phone, and the only one that survives a
choice to keep the database off Auto Backup entirely; it is no longer the
only mechanism, period.

A rule can optionally carry a `folder`: a user-typed name the rule list
groups by, with ungrouped rules collecting under "Other". It is a single
nullable `String` throughout: a nullable column on the `rules` row, and an
additive `"folder"` key in the portable format that does not bump
`RuleJson.VERSION`. An older build simply ignores the key it does not
understand and keeps the rule, losing only the grouping. `null` and `""` are
never allowed to both mean "no folder" at once: every boundary that accepts a
folder name from outside `:core` normalizes blank to `null` before it is
stored, and comparison after that is exact, case-sensitive `String` equality.
See `Rule.folder`'s kdoc.

Room stays an implementation detail of `:core`. Storage is handed out as a
`RuleRepository` from a factory function, and `room-runtime` is
`implementation`-scoped so it is not on `:ui`'s compile classpath at all. That
enforces the boundary rather than merely asking for it.

A `components` row's type string is where this format meets a limit nothing
enforced before. `ComponentFactory.type` is read back out of both the
database and an imported file. Renaming it breaks every rule that already
names the old string, and it breaks the rule silently. Renaming the Kotlin
constant that holds it still compiles and still passes the whole suite,
because every place that reads the constant agrees with every other place
that reads it. Nothing in the running app ever compares today's value
against yesterday's.

Two tests close that gap, one per half of the problem. `TriggerTypeStringsTest`
in `:triggers` and `ActionTypeStringsTest` in `:actions` each hold the
released type strings as literal text. A JVM test can then catch the string
itself changing under its constant, with no Android context needed. Beside
`ConfigSchemaContractTest` in `:ui`, `PinnedTypeStringsTest` holds the same
released strings and checks that each one is still returned by
`triggerFactories` or `actionFactories`. That catches the other failure: a
factory dropped from the registry while its constant sits unused. Neither
test stops a new type from being added. Both fail loudly if a released one
changes value or disappears.

### Finding a rule: search and folders

The list screen gained two things at once, and they are deliberately different
kinds of thing.

**Search** filters on a rule's name *and* on the display names of every component
it uses: every leaf of the trigger tree, and every action. That second half is
the point: typing "bluetooth" has to find a rule called "Driving mode" that nobody
named after Bluetooth, because the name is what someone forgot and the trigger is
what they remember. A query that matches nothing says so in its own words; an
empty list with no explanation reads as "you have no rules", which is a lie the
moment a filter is active.

**Folders** are a name the user types on the rule itself. See `Rule.folder`
above. The list groups by it, each section collapsible with a count, and rules
without one collect under "Other", pinned last however the alphabet would sort
the letter O, because it is the leftovers rather than a peer.

Three decisions in there worth keeping:

- **With no folder in use anywhere, the list renders exactly as it did before the
  feature existed**: the same `LazyColumn` over the same blocks, no headings, no
  "Other" wrapped around everything. Someone who never types a folder name does
  not pay for folders. That is asserted by a test rather than left to inspection,
  and the "are any folders in use" question is asked of *all* the rules rather
  than the filtered ones, so a search matching only unfoldered rules cannot make
  the chrome flicker away.
- **Headings survive a search**, showing only matching rules and only non-empty
  sections. The heading is how someone tells which "Driving mode" they just
  found.
- **Collapsing a section does not compose its rules at all**, rather than drawing
  them and hiding them. Which sections are shut is `rememberSaveable` view state:
  it survives a rotation, and it is deliberately not stored on the rule, because
  it describes this screen right now rather than anything about the automation.
  That state does not always start empty, though. With more than three rules in
  the database and at least one of them already filed into a folder, every
  folder starts closed instead, so opening an established list does not throw
  every rule at the reader at once. Below that count, or with no folder in use
  at all, every folder still starts open, exactly as before this default
  existed. The decision is made once, against the first non-empty list
  `RulesScreen` sees. (The list arrives from a flow, so the very first
  composition is always empty, and deciding against that frame would freeze
  the default on "open" forever.) It is then locked for the life of that
  screen entry, alongside the collapsed set itself. So a rule added later
  cannot re-close a folder someone just opened by hand, and a rotation cannot
  repeat the decision and undo a manual toggle either. Leaving the screen and
  coming back starts a fresh decision, which is wanted: it is a fact about the
  database right now, not a promise kept forever.

Filing a rule from the editor offers the folders that already exist, through the
same pick-or-type dialog the app, sound and Bluetooth fields use. That list is
read in `MainActivity` from the rule list's own ViewModel rather than the editor's,
which holds one rule and has no business reading the table. Since folder names
compare case-sensitively, offering the existing ones is what stops a second "car"
appearing beside "Car".

### What a block offers: component tools

A block in the editor can carry buttons of its own: run this action now, add a
home-screen button for this shortcut, look at what notifications actually contain.
The screen does not decide which: a factory declares them through
`ComponentFactory.toolsFor(config)`, returning values of the closed
`ComponentTool` set, and the editor renders whatever comes back without knowing
which component it is looking at.

The alternative, which this replaced, was the editor recognising components by
name. Test was written into the action section for every action. Pinning was
keyed off a config key that happened to be unique to one trigger, with a comment
admitting it was a special case. Adding "Inspect" to the notification components
would have been the third, and the point at which the editor's knowledge of
particular components stopped being an accident and started being a design. The
project rule that adding a trigger must not require editing an existing one has a
quieter corollary: adding one must not require editing the editor either.

Config-aware, for the same reason `requirementsFor` is: the shortcut trigger has
nothing to pin until it has an id, and a button that pins nothing is worse than no
button. `ActionFactory` defaults to offering `Test`, since every action can be
run on demand; `TriggerFactory` defaults to offering nothing, because a trigger
cannot be.

Two things stay with the screen. The Test button doubles as Stop while an action
is running, and only the screen knows which action that is, so the tool declares
*that there is a test*, and the label and handler are passed in. And a
component nested anywhere in the trigger tree (asked as a condition rather
than watched as the edge) renders its tools the same way, through a
composable lambda passed down to whatever draws that level of the tree, so a
notification component gets the same Inspect button wherever in the tree it
sits, without the tree-rendering code learning what a tool is. (The trigger
tree's own editor code is under active revision as this is written, so this
paragraph is deliberately not naming a composable. Verify against the
current code rather than against a name here.)

An instrumented contract test holds the notification half of this: any component
declaring `SpecialAccessKind.NOTIFICATION_LISTENER` must offer
`InspectNotifications`. Keyed on the requirement rather than on a list of names,
because the failure being prevented is the *next* notification component, added
by someone who never learns the inspector exists.

### Testing an action from the editor

Each action block has a Test button that runs that action immediately. It exists
because roughly half of what an action does is *sensory* (which sound, how loud,
how the spoken text reads), and without it the only way to find out is to save the
rule, wait for the real trigger, and infer what happened from the result.

Pressing it again stops the run, and that is a requirement rather than a
refinement: `play_alert` loops for up to a minute by design, and the action's own
docs note that disabling the rule was previously the only way to cut one short. A
test that could not be stopped would reproduce exactly the trap the duration cap
exists to avoid.

Two things it does not pretend. The event is synthetic and carries no payload, so
an action reading trigger payload sees nothing (harmless today, and the thing to
revisit when payload substitution lands). And a test necessarily runs with the app
on screen, which is the one condition under which the background-activity-start
restriction does not apply: an "open" action can pass here and still do nothing
when the rule fires for real. The result is therefore drawn in the neutral block
style rather than as a success, because a green tick would imply more than the
test can establish.

### Navigation

Two destinations (the list and the editor) do not justify a navigation library
and the dependency it brings. A sealed `Screen` plus **one** `BackHandler` is the
whole feature.

It was three. The notification inspector was reachable from a button in the rule
list's bottom bar, and that button is gone: the inspector now opens only as a
dialog from the block of whichever component reads notifications. Removing it
took the `Screen` case, the `backTarget` branch, the `ScreenSaver` tag and the
string with it, which is the argument for a sealed type over a route table:
deleting a destination is a change the compiler checks.

One handler, not one per destination, and the difference is the whole reason this
section exists. Per-destination handlers are added and removed as the screen
changes, which means the editor's handler is disposed *by the navigation it just
performed*. A back press then arrives while the callback that should answer it
is being torn down. The rule list, meanwhile, registered no handler at all and
leaned on the framework default to finish the activity, so "back on the list
closes the app" was never something the app actually said. Now `backTarget` says
it: a `Screen?` where null means the list is the bottom of the stack and back
leaves, and the editor maps back to the list. It is a pure function precisely so that the one decision here is
checkable without a device.

`Screen` is saved through `ScreenSaver`, so a rotation inside the editor does not
dump the user back on the list. That is load-bearing rather than a courtesy. See
below.

**A new rule is emptied when the editor is entered, not when it is left.** The
editor gets a ViewModel keyed by rule id, which handles *different* rules, but an
unsaved rule has no id, so its key can only be the constant `editor-new`, and
these ViewModels live in the activity's store. One instance therefore serves
every new rule for the life of the activity, carrying the last draft with it:
left alone, tapping "New rule" reopens the rule you thought you had closed.

The first fix reset the draft on *exit*, from a `DisposableEffect`'s `onDispose`,
reasoning that dispose catches every way of leaving. It does not catch them
reliably. The disposal that coincides with a configuration change has to be
guarded out with `isChangingConfigurations` (otherwise a rotation, which also
disposes the composition, would wipe the draft), and any exit that is guarded out
leaves the retained ViewModel dirty, so the *next* entry inherits the stale
draft. That was the "new rule is sometimes prefilled" report: an exit that
happened to coincide with a configuration change skipped the reset.

Entry has no such gap. `OnFreshEntry` calls `RuleEditorViewModel.reset()` when the
new-rule editor is genuinely opened, and stays quiet when the same entry is
rebuilt by a configuration change: the distinction is `rememberSaveable`, which a
real entry starts fresh and a rotation restores. `Screen` is still saved through
`ScreenSaver` so the destination survives rotation, and the draft rides along with
the retained ViewModel; what does *not* survive is walking back to the list and
opening a new rule, which is exactly right. Exit keeps one job of its own:
`stopTest()`, so a looping `play_alert` is silenced when the screen goes away.

### The notification inspector

A diagnostic destination, and the only screen in the app that exists to explain
the app rather than to configure it. Every notification rule is written against
values nobody can see from outside the process: which package posted a
notification, what the platform considers its *title* versus its *text*, whether
it carries `FLAG_ONGOING_EVENT`, and what its buttons are called underneath their
icons. Guessing those and finding out from a rule that silently never fires is a
loop with no feedback in it at all, and it is the most likely reason a working
build looks broken.

**It renders the strings the matchers use, not a tidied version of them.** The
joined haystack comes from `notificationHaystack` in `:core` (the same function
`matchesNotification` calls), which is why that formatting was lifted out of the
matcher rather than reproduced in the screen. A screen that reconstructed an
approximation would be worse than no screen, because its whole value is being
believed: someone comparing a pattern against what it is shown has to be
comparing it against the real thing. Values are quoted and monospaced for the
same reason: a missing title still contributes its separating space, and an
anchored pattern failing on an invisible leading space is exactly the puzzle this
screen is for.

Its two empty states are different problems and are reported as such: notification
access not granted, versus granted with nothing currently posted. Only what is
posted *now* can be inspected (there is no history, because the listener keeps
none), so the screen says so instead of looking broken while it waits.

**Reached one way, and it is not a destination.** It opens as a full-bleed dialog
*over* the editor, from the `Inspect` button on the block of whichever component
reads notifications. That is not a styling choice: leaving the editor's
composition fires the fresh-entry reset that keeps a new rule empty, so
navigating away to check a package name would discard the half-written rule you
were checking it for. A reference you consult while filling in a field has no
business costing you the field.

It used to *also* be a destination, reachable from a button in the rule list. That
button is gone. Two entry points to one screen is not itself a problem; two with
different quality was: the list's route navigated away, could cost you a draft,
and had to give vaguer advice about granting access, because from there the Grant
control genuinely is elsewhere. With one host the screen states the specific
thing: the Grant button is on the block directly behind it. That sentence lives
in the screen rather than arriving as a parameter, because there is now exactly
one caller and a parameter would be a sentence written for a host nobody can
see.

### Where the engine runs

`EngineService` in `:ui` is a foreground service, and it owns the engine's
lifetime: the engine is constructed against the service's own `CoroutineScope`,
so there is one answer to "is Trigly running?" rather than two that can
disagree. Nothing outside holds a reference to it. `AppContainer` deliberately
no longer builds one.

**A live process is the mechanism, not an optimisation.** Since API 26 most
implicit broadcasts may not be declared in a manifest, so every broadcast
trigger registers its receiver at runtime. An engine in the application scope
therefore has exactly the lifetime the system feels like giving the process,
which on many OEM builds is minutes. A foreground service is the only
arrangement Android offers where "keep running" is a promise rather than a hope,
and the ongoing notification is a feature of that bargain rather than a tax: an
automation app that watches the device invisibly is exactly what a user should
not have to take on trust.

**The type is `specialUse`, and there was no honest alternative.** The
foreground-service type catalogue describes what a service is *doing* (playing
media, syncing data, following a location), and general-purpose automation is
none of them. `dataSync` is the tempting mislabel and is also the one Android 15
caps at six hours a day, which would stop the engine every evening. `specialUse`
carries no timeout; its price is a subtype string that Google reviews before a
Play release, which is a fair price for saying what the service actually is.

**`location` is the second type, and it is a capability rather than a
description.** The manifest declares `specialUse|location`, but the service
claims the types at runtime through `ServiceCompat.startForeground` and adds
`location` only when a location permission is held. That is not tidiness: from
API 34 `startForeground` throws for a declared type the app has no permission
for, so an engine that always claimed `location` would die at startup on any
device where the user never granted location, stopping every rule to serve a
location rule that person does not have.

What claiming it buys is a position read that answers while the app is off
screen. The fine-location grant alone is "while in use", which means a read
answers while an activity is visible and returns nothing otherwise, and the
engine is off screen almost always. Without the type, `location_check` inside an
`ALL` group answered "I cannot look" every time, the group did not hold, and the
rule was dropped with nothing recorded anywhere. The types are re-claimed in
`onStartCommand` as well, because a grant usually arrives long after `onCreate`
and `MainActivity` pokes the service after every grant.

It is half the fix. Since Android 12 a foreground service started while the app
was in the background loses while-in-use access for the whole life of that
instance whatever type it claims, and `BOOT_COMPLETED` is such a start.
`ACCESS_BACKGROUND_LOCATION` is the other half and is what survives a reboot.

**Starting is the app's job; stopping is the service's.** `TriglyApp` collects
the rule store and starts the service whenever any rule is enabled;
`EngineService` stops itself when none is. Splitting it that way means neither
side has to know what the other is doing, and re-asking on every rule change
makes a service that went missing come back on the next edit rather than at the
next reboot: starting one that is already running costs a single
`onStartCommand`. `BootReceiver` covers the two events that end a process with
no user involved, a reboot and an app update; `ACTION_MY_PACKAGE_REPLACED`
matters as much as `BOOT_COMPLETED`, because otherwise every update would
silently stop every rule until someone next opened the app.

Those are also the moments the platform *allows* a foreground service to start.
From API 31 an app may only start one while it is exempt (visible on screen,
answering one of those two broadcasts, or excused from battery optimisation),
and there is no API that answers "am I exempt right now?" well enough to branch
on. So `EngineService.start` catches the refusal rather than predicting it: a
refusal means the process woke for some other reason, and `START_STICKY` will
bring the service back anyway. Crashing over it would turn a missed start into a
dead app.

**`sync`, not `start`.** The service calls `TriggerEngine.sync` on every
emission from the rule store, and `sync` deliberately leaves an unchanged rule
running. Rebuilding a trigger re-registers its receiver, and a sticky broadcast
replays on registration, so restarting rule A because rule B was edited would
fire A for no reason, which is the phantom firing `StateTracker` exists to
prevent. `sync` also reports a rule it cannot build through `onStartFailure`
instead of throwing, for the same reason a throwing action does not take down
its rule: one rule left invalid by an import from a newer build must not stop
the others.

**What this still does not survive**, stated because the watchdog trigger's
honesty depends on it: a force-stop from app settings, and an OEM battery
manager that disregards the promise. The service raises the odds a long way; it
does not make them one.

**The odds are also something the app can now ask about, and raise.** The
foreground service is the mechanism; the exemption from battery optimisation
is a separate switch Android keeps per app, and until now Trigly never asked
for it, checked it, or said anything about it on screen. That silence is
exactly wrong on the phone where it matters: an app killed for sitting idle
loses every rule and every diagnostic it holds, since both live in a process
that no longer exists, and the user sees nothing running and nothing that
explains why. `RulesScreen` now reads `PowerManager.isIgnoringBatteryOptimizations`
and shows a notice, `BatteryOptimizationNotice`, until it answers true.

It sits once above the whole rule list rather than inside `ComponentRequirement`,
the model the "Requirements" section above this one builds: a requirement in
that model is scoped to the trigger or action that declared it, and an unmet
one explains why *that* component cannot fire. Battery optimisation is not
scoped that way. It stops the process every enabled rule runs in, so a rule
holding every permission it asks for is exactly as exposed as one holding
none, and repeating the same sentence on each rule's card would not describe
any one of them correctly.

Two intents do the asking. `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
given a `package:` URI and the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
permission, opens a one-tap system dialog. Google Play restricts that intent to
an app whose core function needs to keep running in the background, which is a
fair description of this engine and the reason `MainActivity` declares it
deliberately rather than as a shortcut. `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
opens the settings list instead, needs no such permission, and is the fallback
when the dialog does not resolve, the same defence `openSettings` already
applies elsewhere: not every manufacturer ships every settings screen.

Granting it does not touch the line above this one. A force-stop still empties
Trigly's process whatever this setting says; the notice lowers the odds Android
stops Trigly on its own and says nothing more than that, because promising more
would be the same failure this section exists to prevent, stated by the app
itself this time. `docs/todo.md` **R1** is the record of why no design inside
this app changes that half of the picture.

Two things it is worth knowing it does *not* fix. It is not a
background-activity-start exemption (see `docs/actions.md`, where that mistake
is easy to make), and on its own it is not a scheduler: a coroutine `delay`
inside a foreground service still stops in Doze. The next section is that
scheduler.

### The scheduler port

Five places in this codebase waited with a coroutine `delay`: `IntervalTrigger`,
`SolarTrigger`, `AppForegroundTrigger`, `NotificationWatchdogTrigger`, and
`keepListenerBound`, the repair path that asks the system for the notification
listener back. A `delay` is counted by the process's own clock rather than
asked of the system, so it can sleep through the whole wait once the device
enters Doze. The last of the five is the case that is easy to miss: it is the
repair for a dead notification listener, so a `delay` there meant the repair
for a dead listener was itself asleep in Doze. `docs/todo.md` names this T1 and
puts it first in the backlog for that reason.

`AlarmScheduler`, in `:core`, is the fix. `:core` may not depend on any Android
type, so the port is kept to the shapes every one of the five callers needs:
`waitFor(durationMillis)`, a repeating wait counted from now, `waitUntil(atMillis)`,
a wait until one wall-clock instant, and, since T17 below, a durable version of
each. None of the four takes a separate cancel parameter. Every caller reaches
the port from inside its own coroutine, and cancelling that coroutine is the
cancel; the implementation's job is to release whatever it asked the system for
when that happens, not to offer a second way to stop. The shape is deliberately
small, for the same reason `NotificationController` and `UiController` are: a
small port is one a fake in a JVM test can implement in a few lines, which is
how all five callers are tested without a device.

`AlarmManagerScheduler`, the implementation, lives in `:triggers` rather than
`:ui`. The port exists for triggers to call, and `:triggers` is already the
module that turns a system callback into a suspend function for them.
`BroadcastTrigger` registers and unregisters a receiver on collection and
cancellation, and this class registers and cancels an alarm the same way.
`:ui` is the module that assembles the app: `TriglyApp`'s container builds one
instance and hands it to `triggerFactories`, the same way it hands
`notifications` and `ui` to the modules that need them, without needing to
know how the wake-up itself works. `keepNotificationListenerBound` builds its
own instance instead of taking one from the container, for the same reason it
builds its own `RequirementChecker`: its only caller, `EngineService`, already
takes just a `Context`, and that shape did not need to change for this.

Every wait used to go through `AlarmManager.setWindow` with an
`OnAlarmListener`, never a `PendingIntent`. The listener form delivers
straight into the calling process while it is alive, on a plain `Handler`,
with no manifest entry and no exact-alarm permission, and that is still
exactly the shape `AppForegroundTrigger`, `NotificationWatchdogTrigger` and
`keepListenerBound` need: each polls inside a process the engine is already
keeping alive, and none needs to be woken in a process the system has already
killed. `setExactAndAllowWhileIdle` is the API for a wait that must survive
Doze *and* land on the minute, and it needs `SCHEDULE_EXACT_ALARM` from API
31, a permission Google reserves for alarm-clock-like apps at its
`USE_EXACT_ALARM` tier. No caller in this codebase asks for that precision
today, so that path is still not built; the honest trade for these three is
drift of up to a few minutes, sized by how long the wait itself is, and each
says so in its own warning text.

**T17: the listener form fixed the sleeping phone and not the stopped app.**
`IntervalTrigger` and `SolarTrigger` do not merely poll inside a live process;
their whole wait is worthless if it does not outlive one. AOSP deletes a
listener alarm the instant the process holding it dies:
`AlarmManagerService.setImplLocked` links the listener's death to
`removeLocked(..., REMOVE_REASON_LISTENER_BINDER_DIED)`. So a rule on either
trigger, killed mid-wait, was left with no pending alarm at all and nothing
that would ever set a new one. `docs/todo.md`'s T17 is the record of that
gap, and `waitForDurable`/`waitUntilDurable` are the fix: a second,
independent `PendingIntent` alarm for the same instant, aimed at
`AlarmWakeReceiver` in `:ui`, the same shape `BootReceiver` and
`BluetoothConnectionReceiver` already use to bring the engine back for their
own events. `AlarmWakeEvents` is the record those two already have a
counterpart for (`BootEvents`, `BluetoothEvents`): the receiver writes it
before starting the engine, so a fresh collection can tell that a durable
wait just fired somewhere and search from a little before "now" instead of
from "now" itself, which is what stops `SolarTrigger` from skipping an
occurrence that was already due in favour of tomorrow's. `IntervalTrigger`
needs no such check: it always counts a fresh period from whenever it
restarts, kill or no kill.

**Whether the durable alarm can actually restart the foreground engine is a
sourced answer, not a hope, and it depends on which alarm fired.**
`ActiveServices.shouldAllowFgsStartForegroundNoBindingCheckLocked` (AOSP tag
`android-15.0.0_r1`) is what `EngineService.start`'s
`startForegroundService` call is checked against, and it allows a background
start only for a caller already on one of a short list of allowances, read
out of `ActivityManagerService.isAllowlistedForFgsStartLOSP`.
`AlarmManagerService.setImplLocked` puts a caller on that list, with
`TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED`, only for an *exact*
alarm: `setExactAndAllowWhileIdle` or `setAlarmClock`. An ordinary
`setWindow` alarm, which is all this codebase uses, gets nothing from that
allowlist at all. So this fix deliberately stops short of `SCHEDULE_EXACT_ALARM`:
that permission is a user-granted special access, `USE_EXACT_ALARM` is
Play-restricted to alarm-clock apps, and reaching for either is a product
decision for the maintainer, not a byproduct of closing T17.

There is a second, unrelated door onto that same allowlist.
`ActivityManagerService.isAllowlistedForFgsStartLOSP` also passes any uid
already on the device-idle "except idle" allowlist, which is exactly the list
a user joins by granting the battery-optimisation exemption described above.
So on a device where the user has granted what this app already asks for and
nags about, an ordinary, inexact durable wake *does* bring the foreground
engine back; on a device where they have not, `EngineService.start` is
refused, exactly as its own KDoc already expects for a call site that is not
one of the platform's own exemptions, and the durable alarm still recorded
that it fired, for whichever restart happens to come next to read. The README
limit on this trigger pair should say it survives a kill on a device that has
granted the exemption Trigly already asks for, and otherwise survives sleep
but not a stop, which is the honest version of the claim until the maintainer
decides an exact alarm is worth its cost.

**What none of this fixes.** A user's force-stop puts the app in the stopped
state, and the system cancels every alarm this class has pending, both the
listener form and the durable `PendingIntent` form alike. Nothing in this
port, or in any implementation of it, gets that back. The limit described
above for the foreground service stays true whatever wakes the app.
`docs/todo.md`'s R1 records this explicitly, because the review that first
asked for a scheduler also proposed it as a fix for force-stop, and it is not
one.

### Services the system owns

`NotificationListenerService` and `AccessibilityService` are constructed by the
framework, so there is no instance to hand a trigger and nowhere to inject a
dependency. Each service publishes to a process-wide `ServiceEventBus`, and the
triggers subscribe; neither knows the other.

The services stay deliberately thin: flatten the callback argument, publish,
return. The system unbinds a service whose callbacks are slow, so no rule
evaluation or I/O happens on those threads. The bus drops the oldest event under
load rather than blocking, because accessibility events arrive in bursts of
hundreds and losing stale UI events is better than losing the service.

Each bus also exposes whether its service is connected. A trigger whose service
is not bound is not quiet, it is broken, and that difference has to be
expressible.

#### Getting the notification listener back

That connected flag has a second job, and it is the one that made the difference
matter. The system owns the listener's lifetime and does not always give it
back: a process killed by an OEM battery manager, and an app update most
reliably of all, can leave the listener unbound while everything else recovers.
`START_STICKY` returns `EngineService`, the engine starts every rule, the
ongoing notification says it is watching, and `RequirementChecker` still reports
notification access as granted, because the secure setting it reads is still
set. Nothing is bound, so `NotificationEvents.posted` never emits and every
notification rule is dead with three separate things claiming otherwise.

There is no callback for this. `onListenerDisconnected` reaches the process that
was told, and the process that would have been told is the one that died.
`NotificationListenerService.requestRebind` is static for exactly that reason: a
process holding no binding at all can still ask for one.

`keepListenerBound` is the fallback, running for as long as the engine does. It
watches the connected flag through `collectLatest`, waits out a grace period
before asking, and asks again on a long interval while nothing binds. Three
decisions in it:

- **The grace period is not politeness.** A fresh process starts with nothing
  bound and the system binds it moments later, so asking on sight would mean
  asking on every app start, and `requestRebind` unbinds before it rebinds. The
  normal path would pay a gap it did not need.
- **`collectLatest`, so a binding cancels the retry loop** rather than leaving it
  to notice on its next tick. The healthy case costs one cancelled `delay`.
- **It watches the flag rather than calling from `onListenerDisconnected`.** That
  callback covers only the case that was already recoverable. One mechanism with
  one grace period covers both, and cannot make a disconnect-request-disconnect
  loop.

What it cannot see: a service destroyed without `onListenerDisconnected` being
delivered leaves the flag reading true. Nothing observable separates that from a
healthy binding without a binder call on a timer, which would spend every user's
battery on a case the platform is not documented to produce.

Actions reach those services the other way round, through a port in `:core` that
`:triggers` implements over the live service, `NotificationController` for the
notification listener. That is what keeps `:actions` from depending on
`:triggers`: the dismiss and button actions call *into* it, and `play_alert`'s
"stop when the notification goes away" *reads* through it. Reading is a poll, not
a subscription, for the reason the watchdog trigger polls: the bus carries edges,
and something that starts after the edge has passed would wait for it forever. An
action that needs to observe the system, rather than act on it, belongs on this
port too, never on a bus in a sibling module.

**An action that needs the screen goes through a second port, not the bus.**
`UiController` is that port, implemented in `:triggers` over the accessibility
service, and it exists for exactly one job: pressing a notification button that an
app drew itself with `RemoteViews`, for which Android exposes no `PendingIntent`
at all. The shape mirrors `NotificationController`: an interface in `:core`, an
`Unavailable` no-op default so nothing requires the grant, and the live adapter
reading the current service from the bus object on every call.

What is deliberately *not* in the port is tree reading for triggers: those get
flattened events from the bus, and giving actions a window onto the whole
accessibility tree would widen the most invasive permission the app has for no
gain. The port takes one intent-shaped request ("press this label in the shade")
rather than exposing nodes, and the decision inside it that is easy to get wrong
(which node a press should land on) is a pure function in `:core` with tests, not
something only observable by watching a phone.

**An event that arrives before the engine exists needs a record, not a listener.**
`BootEvents` is the one case: `BOOT_COMPLETED` is what starts the engine, so no
trigger can be registered in time to hear it, and `device_restart` would be a
trigger waiting forever for a broadcast already delivered. The manifest receiver
in `:ui` writes the reason there *before* starting the service, and the trigger
reads it on collection: the write always precedes the engine, so the ordering is
a fact of the sequence rather than a race to win. Two properties make it correct
rather than merely working: reading does not consume, so several rules on one
trigger all fire; and the record is bounded by a freshness window, because it
outlives its moment and a rule enabled hours later must not announce the morning's
reboot. Nothing is persisted: "did *this* process start because of a boot" has a
new answer in every process. Any future trigger for an event that always
precedes the engine belongs on this shape, not on a receiver.

**An event that might or might not precede the engine needs both a record and
a bus.** `BootEvents` covers the case where the answer is always "before":
`BOOT_COMPLETED` is what starts the engine, so a trigger is never collecting
yet when it lands. `ShortcutEvents` and `BluetoothEvents` cover the case where
the answer is "it depends." A shortcut tap and a Bluetooth connect can each
land while the engine is already running as a foreground service, in which
case the trigger asking about it is already collecting and needs the event
delivered live; or the system can have killed the engine's process for
sitting idle, in which case the very same tap or connect is what restarts it,
and the event lands before any trigger exists to read it. Nothing available
at the point of receiving either event says in advance which case applies.
Both objects answer this the same way: a live `ServiceEventBus` for the
already-collecting case, and a freshness-windowed pending record for the
cold-start case, fed from the one call site (a trampoline activity for a tap,
a manifest receiver for a connect) that does not have to work out which
applies. The risk unique to this shape is that a pending record read at
collection start and a live publish a moment later can describe the same
event twice; `ShortcutTrigger` and `BluetoothConnectionTrigger` both guard
against that by remembering the timestamp of the event each has already
turned into an emission and skipping an exact repeat of it. `BluetoothEvents`
adds a second, unrelated repeat to guard against: some Bluetooth accessories
resend the same connect or disconnect for the same device several seconds
later, which is a platform quirk rather than a second event, and that
duplicate is dropped once, at the point the sighting is recorded, rather than
detected separately by every trigger reading it.

## Look and feel

### Colours live in one file

`Palette.kt` holds every colour in the app, in three sections: the raw tonal
ramps, the light and dark scheme assembly, and the handful of roles Material 3
has no slot for. Nothing else declares a colour. Re-branding is rotating the hue
of one ramp; moving a colour to a different *use* is editing one line of the
scheme.

Two colours unavoidably live outside it. `res/values/colors.xml` and
`res/values-night/colors.xml` carry the window background, because the framework
paints the window before any Compose code runs. Without them a dark-mode launch
flashes white. They must mirror `Tone.Paper` and `Tone.Ink`, and the file says
so.

Three rules decide which tone plays which role, and they are what make the
screens read as loud rather than as tinted:

**The fill is the logo.** `primary` is `Tone.Orange60`, `#EC6206`: not a
neighbouring step of the brand ramp but the literal background of the app mark,
and the same value in both schemes. The header slab and the launcher icon are one
colour, so `primary` is the only role here that does not invert with the theme.

**The grid is ink, and so is the text.** `outline` and `onSurface` are the same
value: near-black on light, near-white on dark. Borders used to be orange, which
is what cost the fills their punch: a saturated fill has nowhere to land against
a saturated edge. On/selected states change their *fill* and keep the ink frame,
so a control does not appear to gain weight when it turns on.

**Ink on the orange, not white.** `onPrimary` is `Tone.Ink`. White on `#EC6206`
is 3.32:1 and fails AA; ink on it is 5.66:1 and passes. This is the constraint
that used to force a burnt `#9F3D00` into `primary`, and resolving it the other
way is what let the vivid orange onto a slab at all.

The cost of rule 1 is that one Material role cannot cover both jobs: `#EC6206` as
text on the page is 3.23:1. So the brand orange as *ink* (an outlined button's
label and border, a value readout, a regex escape) is
`TriglyExtraColors.accent`, a darker step of the same hue. `primary` belongs in
`Surface(color = …)` and `containerColor`; never in a `Text` or an `Icon`.

The knock-on is in `MainActivity`: with `primary` no longer inverting, the status
bar band behind the clock is the same orange in both themes, so its icon polarity
is fixed (`SystemBarStyle.light`) rather than derived from the system theme.

**Material You dynamic colour is off by default, not absent.** It is the right
default for an app with no colour of its own; here the orange *is* the
identity, and an automation app whose screenshots and docs look different on
every phone out of the box is not friendlier. Settings now offers it as an
explicit choice anyway - "Follow the system" - beside five other fixed brand
hues a person can pick instead, all resolved through `PresetSchemes.kt`. Dark
mode still follows the system rather than its own setting: it is a platform
convention nobody expects an app to override, which is a different question
from which *hue* renders in either mode.

### Blocks, not cards

The design is flat rectangles with hard 2dp borders, a 3dp corner, and a solid
offset shadow instead of elevation. No gradients, no blur. Four decisions carry
it.

**One corner radius, declared once.** All five Material shape roles carry the
same value in `Theme.kt`, so a chip cannot end up rounder than a dialog. Two
steps are needed to make that stick, and only the first is obvious. Material's
own components (dialogs, menus, text fields, snackbars) resolve their shape from
the theme by themselves. Everything in `Blocks.kt` does not: Compose's `Surface`
defaults to `RectangleShape`, so a block that omits `shape` is square regardless
of what the theme says. That is why the blocks *were* square for a long time
without anyone passing a shape, and why editing the theme alone would have
rounded the dialogs and left every card behind. `BlockShape` closes that gap by
passing `shapes.medium` explicitly at each block.

Two kinds of surface stay square whatever the radius, and the test is whether
they have an edge of their own to round. Full-bleed chrome (`BlockHeader`,
`BlockBottomBar`) runs to the screen edges under the system bars. Cells inside a
block (an unmet requirement under a rule, a caveat under a chosen component)
fill their parent card to its inner edges, and rounding them would show the
card's own fill through four notches.

The radius is 3dp because that is small enough that nobody would call the app
rounded and large enough that a corner reads as chosen rather than unfinished. It
is also bounded from above by two things: `BlockDivider` runs a 2dp line the full
width of a card and starts leaving a notch at each end past roughly 4dp, and a
2dp border around a generous curve looks neither brutalist nor modern. Wanting
more radius than this means thinning the border too, which is a different design
rather than a shape tweak.

**Weight without elevation.** `Modifier.hardShadow` draws a solid offset copy of
a block's own silhouette in the ink of its border: 4dp for cards and actions,
3dp for the on-state of a toggle or chip. Material's `shadowElevation` is
deliberately unused: a blur that fades with distance is a claim about a light
source, and this design has none. A soft shadow under a 2dp border is also the
exact combination that reads as a Material card in costume.

Two consequences worth knowing. The modifier **reserves its own space**: the
padding is applied before the draw, so the block is laid out 4dp smaller and the
shadow lands in the strip that padding freed. That is what keeps it to one call
site per component: no screen knows a shadow exists and nothing can be clipped
by a parent sized before it was added. And the shadow colour is
`colorScheme.outline`, not a fixed ink, which makes it a different idea per
theme: in light mode it is a shadow, and in dark mode (where ink on ink would
vanish) it inverts to the near-white border colour and becomes a second outline,
brighter than the thing casting it. For a control the space is reserved whether
the shadow is drawn or not, so a toggle does not change size under the finger
that just tapped it.

**One vocabulary, in `Blocks.kt`.** `BlockHeader`, `BlockCard`, `BlockButton`,
`BlockToggle`, `BlockDivider` and friends are what the screens are assembled
from, which is what stops a border width or a padding drifting between them.
They wrap Material's own components rather than drawing from scratch, so focus,
ripple and accessibility roles survive the reskin: `BlockToggle` is
`Modifier.toggleable` with `Role.Switch`, so a screen reader still calls it a
switch even though it renders as an ON/OFF cell.

**Chrome is uppercase; prose is not.** Titles, buttons, category bars, field
labels and rule names are labels in this design and are uppercased in one place
each (`BlockButton` uppercases its own text, `ConfigFieldEditor` uppercases
field labels), so a new call site cannot arrive in the wrong case. Warnings,
help text and requirement explanations stay in sentence case: they are sentences,
and capitals make sentences unreadable. This is visible in the tests, which
assert `"NEW RULE"` and `"Needs usage access, granted in system settings"`: the
accessibility tree contains exactly what is drawn.

The rule summary is monospaced. A screen of rules then lines up into a column
that can be scanned rather than read.

### Warnings are not errors

A component's caveat ("this polls, so it costs battery"; "Android 12 suppresses
these in the background") is not a failure. The rule is valid and will save. So
caveats get their own amber (`TriglyExtraColors.caution`, the one role Material
3 has no slot for), and `colorScheme.error` is kept for things that actually went
wrong: a refused save, a permission that is missing, a rule that cannot fire.
Once two thirds of the triggers carry a caveat, drawing them all in red teaches
people to ignore red.

Caveats are also shown at a different *time* than they used to be, and the rule
is now the same everywhere: **the prose is hidden by default, and the one thing
that reveals it is its badge.** The picker once printed each component's full
warning under its name, on the reasoning that a caveat matters most before the
choice is made, but the list became a wall of prose in which no single item
could be read. Two thirds of the triggers carry a caveat, so the same wall grows
in the editor the moment a rule has a few components.

So `CaveatBadge` is both the marker and the control. It carries the `!` glyph, it
reads to the accessibility tree as a toggle, and tapping it is what brings the
sentence: in the picker it opens in place under the component's name, in the
editor it opens under the block's heading. In the editor the badge lives in the
header rather than inside the fold, so it stays with a block that has been folded
shut for reordering: a caveat is worth reading before moving an action, not only
while filling one in. Nothing shows the sentence on its own (not opening a block,
not choosing the component), which is what keeps a long rule and a 28-item picker
both readable while still admitting, at a glance, that there is something to know.

A field's own `help` follows the same rule once it runs long, with one
deliberate difference. Nine of the app's 92 declared help strings cover more
than one topic and run past 200 characters — `set_variable`'s value field was
one before `helpWhen` split it up, and `play_alert`'s alarm-versus-music
explanation is another that genuinely has no sibling to split on. `Hint` shows
such a field's first sentence and a small chevron that reveals the rest, the
same default-to-less shape `CaveatBadge` already established, but starting
from a full first sentence rather than from nothing: a caveat is worth a glyph
precisely because most components carry none, so zero characters is the honest
starting point there, while a `Hint` is prose every field under the threshold
already shows in full, and collapsing it to nothing would read as the field
having lost its help rather than having more of it. The 200-character cut sits
in a real gap in the data, between 198 and 279, so it folds exactly those nine
paragraphs and none of the ordinary one- or two-sentence hints around them.

### A component block folds

The editor puts everything on one scroll, which is right for building a rule and
wrong for finding your way around one that already has six actions. So each
trigger and action block folds.

What folds is what you *read and fill in*: the settings and the requirements.
What stays is the heading, the caveat badge, any fault, and the footer: Test, Up,
Down, Remove. Reordering a long rule is the main thing folding is for, so hiding
the controls along with the settings would remove the reason to fold at all. A
fault stays because "this component is not available in this version" is not
something the user should be able to tuck away. The caveat *badge* stays for the
same reason the fault does (that there is a catch is not tuckable), while the
caveat *prose* is governed by the badge, not the fold, and can be read from a
folded block (see "Warnings are not errors").

Three smaller decisions. Everything starts **open**, so a one-action rule looks
exactly as it did before this existed and folding is something the user does
rather than something they have to undo. The fold is **not offered** when there
is nothing behind it (an unchosen component, or one with no settings and no
requirements), because a button that visibly does nothing is worse than no
button; a lone caveat does not bring it back, because the caveat has its own
control. And the state is keyed by *position*, `trigger` and `action-0`, because
a `ComponentDraft` has no identity of its own: Up and Down therefore move an
action out from under its own fold. The revealed-caveat state is kept the same
way and for the same reasons: a separate positional set, saved across rotation.
That is the right way round for the job, which is getting a long rule down to a
list of headings you can reorder.

### Insets are the screen's job

Since Android 15 an app targeting API 35 draws behind the status and navigation
bars whether it opts in or not, so `MainActivity` calls `enableEdgeToEdge()` to
make every supported version behave alike.

Neither screen uses `Scaffold`. The design wants the orange header band painted
*behind* the status bar, and keeping content out of exactly that area is
`Scaffold`'s job. Instead the two components that touch the system bars own
them: `BlockHeader` paints full-bleed and insets its own content, and
`BlockBottomBar` takes the navigation-bar padding. The editor takes `imePadding`
at the root so the keyboard pushes Save up rather than covering it. That is
also why Save and Delete are in that bar and not at the end of the scroll: a rule
with six actions is taller than a screen.

### The mark, and an icon with no bitmaps

The app mark is a T whose stem runs into an arrowhead: something fires,
something follows, which is the product in one shape. It is drawn out of
rectangles and one triangle, the same brutalism as the blocks. Source of truth
is `docs/branding/trigly-mark.svg`.

The launcher icon is **vector at every layer and has no PNG mipmaps at all**.
That is a consequence of `minSdk = 26`: adaptive icons arrived in exactly that
release, so there is no older device to keep a `mipmap-hdpi`-through-`xxxhdpi`
ladder around for. For the same reason the adaptive XML sits in a bare
`mipmap/` rather than the `mipmap-anydpi-v26` the templates emit: that
qualifier exists to hide adaptive icons from API 25 and below, and lint flags it
as obsolete here. Its suggested fix, `mipmap-anydpi`, does *not* work: AAPT2
ignores that folder and the build fails to link. No qualifier is the spelling
that both builds and lints clean.

One number worth keeping if the mark is ever redrawn. The 108-unit board is the
adaptive-icon canvas, but only the centre 66dp circle survives every launcher
mask, and the mark's widest points sit 33.3dp from centre against a 33dp budget.
The foreground is therefore scaled to 0.94. Without it, a circular mask shaves
the ends off the T's bar and it reads as a lowercase r.

### Only what the device can run

The editor's pickers list components this phone can actually execute.
`RequirementChecker.isPossible` draws the line that `isSatisfied` cannot: a
missing permission is a prompt away, while an API that arrived after this phone's
Android version and a radio it does not have are permanent. Offering a trigger
that can never fire is worse than omitting it: the user builds a rule around it,
nothing happens, and the app looks broken rather than honest.

Two deliberate exclusions from that filter. `PolicyRestricted` does **not** hide
a component: it says Google will not publish it on Play, which has nothing to do
with whether it works on the device in front of you, and Trigly is meant to be
sideloadable. And the filter applies to the *pickers only*: `Registry` stays
device-agnostic and `descriptorFor` looks up unfiltered, so a rule imported from
a newer phone still renders its trigger instead of going blank.

### Picking an app, not typing one

`AppPackage` was a distinct field kind from the start for one reason, and this is
it: nobody knows that the dialer is `com.google.android.dialer`. It stores and
validates exactly like `Text`, so the only thing that justifies the extra kind is
the editor rendering it as a picker.

The list is **launcher apps only**, and that is the whole design decision.
Enumerating every installed package needs `QUERY_ALL_PACKAGES`, which Google
treats as a restricted permission requiring a declared exception: a heavy price
for a convenience, and a publishing obstacle for an app meant to be easy to
distribute. Declaring the launcher intent in `<queries>` instead returns every
app with an icon, which is what a person means by "an app".

The cost is real and is paid explicitly: a service with no launcher icon (a
plausible target for `notification_watchdog`) is not in the list. So the search
box doubles as manual entry. Type something that looks like a package name and
the picker offers it as a row; `looksLikeAPackageName` gates that offer and is
deliberately loose, because the factory validates for real at save time and
refusing a valid-but-unusual package is worse than offering one that turns out
not to be installed. The same looseness is why it must reject anything a person
would type to *search*. One field serves both purposes.

Two smaller things the picker has to get right. A field whose blankness is a
setting gets a row that restores it, or opening the picker would be a one-way
door. And a stored package always shows its label with the raw package beneath,
including when the app is not installed: a rule imported from another phone
renders as its package name rather than as nothing.

The app list is read once per process, off the main thread, and handed down
through `LocalInstalledApps`. A `staticCompositionLocal` rather than a parameter
because exactly one branch of `ConfigFieldEditor` wants it, and threading a list
from the activity through two screens and a component block would add it to four
signatures with no other use for it. Its empty default is safe (the picker still
offers manual entry), and it is what lets the instrumented tests supply their own
app list instead of asserting against whatever the emulator image ships.

### Matching text, and matching it loosely

Six fields across five triggers ask the same question ("does this text match
what the user asked for"), and each of them used to answer it with its own
`contains(x, ignoreCase = true)`. `TextFilter` in `:core` is that question asked
once, which is what made regular expressions a change in one file instead of six,
and what makes the *next* text filter regex-capable without anyone remembering to
make it so. The schema side matches: `textFilter()` in `ConfigSchema.kt` is the
only way these fields are declared, so a pattern key and its mode key cannot
drift apart.

**Compiled when the rule is built, not when an event arrives.** `TextFilter.of`
compiles the regex in the constructor and closes over it. That buys two things.
`screen_content` can be asked about every visual change on the screen, so
per-event compilation would be the wrong cost in the wrong place. And a pattern
that does not compile throws from `create()`, which is the path every other
invalid config already takes, so the editor shows it at Save instead of the
engine throwing from a coroutine while the phone is in a pocket.

**A blank pattern matches everything, and an unknown mode reads as `contains`.**
The first is just the existing meaning of an empty filter, moved somewhere the
callers can stop restating it: the private constructor and `of()` exist so
"nothing entered" becomes "no opinion" exactly once. The second is the one
deliberately lenient parse in the project: every rule saved before the mode key
existed has no mode at all, and an import from a newer build may carry a mode
this one has never heard of. In both cases the pattern still means something as a
substring, so falling back loads a rule the user can see is reasonable instead of
refusing it.

A regex is searched with `containsMatchIn`, not `matches`: the field reads like
grep, and `^…$` is there for anyone who wants the whole string. For
`notification_posted` the haystack is the title and body joined by a space, which
is what makes `^` anchor to the start of the *title*. That is worth knowing, because it
is the one place where the text being matched is not a thing the user can see as
a single string.

**The search is bounded, the same bound `contains(a, b, "regex")` uses.**
`screen_content` can run its `regex` mode against `visibleScreenText`'s
uncapped, flattened accessibility tree on every content-change event, and the
service config asks Android for one as often as every hundred milliseconds, on
the engine's own collector thread. A pattern that backtracks without end there
does not answer slowly, it occupies that thread forever. `core/RegexBudget.kt`
holds `RegexGuard`, the single shared background thread every bounded search in
this app runs on with a five-second wait, and the "A value that computes"
section above has the measurements behind that number. `TextFilter.of` and
`matchRangesIn` both run the search through it. `TextFilter.matches` cannot
throw, so a refused search, whichever of `RegexGuard`'s four reasons it was,
reads as `Outcome.REFUSED(reason)` folded into "no match": `TextFilter`'s own
KDoc names that decision and its cost, which is that a rule built around a
runaway pattern then never fires, silently, with no channel back to the
person who wrote it. `docs/todo.md`'s T23 has that as an open item rather
than something built here.

**A pattern can be tested, not just compiled.** `regexErrorOrNull` catches a
stray bracket and nothing else: a pattern can compile perfectly and match the
wrong thing, or nothing at all, and until there was a tester the only place that
surfaced was a rule that silently never fired. The Test button beside the mode
toggle opens a dialog with the pattern and a scratch sample, and reports the
verdict as you type.

Two decisions make it trustworthy rather than merely present. **The verdict comes
from `TextFilter.of(...)`**: the engine's own code path, through `outcome`
rather than `matches` so the dialog can also see the one answer `matches` folds
into "no". What the dialog says is what will happen, including the
case-insensitivity and the `containsMatchIn` semantics that are both easy to
assume the other way round. `matchRangesIn` supplies only the highlight, and
mirrors those two modes exactly, including running through the same
`RegexGuard`; a tester whose highlight disagreed with its own verdict would
teach people to trust neither, which is why one unit test checks the two
against each other over a spread of patterns rather than asserting them
separately.

**And the states that are neither yes nor no are named.** An empty pattern reads
"matches anything", because an empty filter has no opinion and calling that a
mismatch would misdescribe the rule. A pattern that will not compile says so
rather than reporting a failed match. A zero-width match (`a*` against "bbb")
says it matched *and* that there is nothing to highlight, because both halves are
true and either alone misleads. A refused search says so too, rather than
reporting a match that never ran: that is the fourth state, and it is not one
label but four, since `RegexGuard.runBounded`'s `RegexRefusal` names which of
its reasons this was and only one of them, a search timing out on this exact
sample, is actually about this sample taking too long. The other three, a
pattern already known to run away, another search busy right now, or too many
patterns already stuck, are refused without this sample costing anything, and
the dialog's label says which one happened rather than "took too long" for a
search that was never even tried. The verdict runs off the main thread, on
`Dispatchers.Default`, behind a `LaunchedEffect` keyed on the pattern, the mode
and the sample. `RegexGuard` bounds how long the dialog waits for an answer,
not how long the search itself runs, so a bounded search is still not owed to
the thread the dialog is drawn on: cancelling the coroutine cannot reach the
`RegexGuard` background thread, the same reason a coroutine timeout could not
either.

**The editor earns the mode's keep.** The mode toggle sits in the field's label
row, because it changes what the box below it means. In regex mode two things
switch on that a substring has no use for: the pattern is monospaced and coloured
by `RegexHighlight`, and it is checked on every keystroke by `regexErrorOrNull`:
the same `Regex(...)` the factory will run at save time, so the failure surfaces
while the cursor is still next to the mistake. The highlighter is a hand-rolled
scan rather than a regex over a regex, for the reason that matters most here: it
is asked to read half-typed, invalid input on every keystroke, and anything that
throws on bad input is useless in exactly the moments highlighting helps.

**An expression field is coloured the same way, by `ExpressionHighlight`, and
the switch is the configuration rather than the declaration.** `set_variable`
declares its value field as plain text, and it becomes an expression only when
the mode says "evaluate", so the editor keys the colouring off
`substitutionsFor`'s answer through `ConfigFieldEditor`'s `previewEncoding`.
That is not a shortcut: the colour arriving the moment the mode changes is the
clearest available way to tell somebody that the box stopped holding text and
started holding code, and the same field has to keep drawing as prose in the
mode where it is prose.

**The box's shape follows the same signal, because a real phone showed that
colour alone was not enough.** A `set_variable` expression clipped mid-line —
`{{set_rule_enabled.enabled}} == "c`, the rest scrolled off sideways with no
scrollbar and no ellipsis to say more was there — and everything below it, the
sample, "Insert variable", and the help, was pushed under the keyboard. Colour
says *what* the box holds; it says nothing about *how much room* holding an
expression needs, and an expression is source someone reads and edits line by
line where a line of prose is not. So `isExpression` gates `minLines` and
`maxLines` the same way it gates the highlighter: three lines to start, eight
before it stops growing and scrolls internally instead — bounded, because
Compose does not clip or ellipsise a text field's own content, so a field left
to grow without limit would not reopen the clipping bug, but a long enough
expression could still push everything under it off screen the same way the
one-line box used to. Every other field kind, including a `multiline` one,
is unaffected: the bound applies only to the one field kind that is about to be
*run*.

Two choices in that highlighter are load-bearing rather than cosmetic. A number
and a piece of text get **different** colours, because the language has no
casts and `5` never equals `"5"`, so which one a substituted value turned into
decides the answer, and the colour is the only place it is visible before
saving. And a `{{...}}` reference keeps its colour **inside** quotes, because
substitution does not respect quotes: `"{{app.state}}"` resolves anyway and
produces `""on""`, a syntax error nobody typed. A reference that lost its
colour in there would read as if the quotes had made it safe.

## Testing posture

Instrumented tests on real devices matter more here than unit tests. The real
risk is not a wrong pure function; it is "works on device X, breaks on device
Y" because OEMs differ in how aggressively they apply battery optimization to
background execution. Unit tests cannot see that class of failure.

That does not make unit tests pointless. It decides what belongs in them. The
pure parts are extracted so they can be tested on the JVM: threshold
arithmetic, the state machine that collapses sticky and repeated broadcasts,
the engine's dispatch and failure isolation. `Intent` parsing is deliberately
kept thin and left to instrumented tests against real broadcasts, because a
mocked `Intent` would only prove the mock behaves as written.

One instrumented test is not about a device at all: the config-schema drift
guard enumerates every registered factory and asserts that each has a human
name, a category, no duplicate keys, valid `Choice` defaults, and that a
component built from its own declared schema is accepted by its own factory. It
lives in `:ui` because that is the only module that can see both `:triggers` and
`:actions`, and it needs a `Context` to build the factories at all. The failure
it exists to catch is drift: a config key added or renamed without the schema,
which makes a working component look broken in the editor.

See the Testing section of `CLAUDE.md` for the commands.

## Releasing

The application module is also the release module: `:ui` declares
`versionCode`/`versionName` and the release signing config, because it is the
only module that produces an installable artifact. Library modules have no
version of their own. They are not published anywhere.

Signing material is described by a gitignored `keystore.properties` rather than
being configured in the build file, and its absence leaves the release build
unsigned instead of failing. That is what keeps a release-variant build
runnable by a contributor who has no key. Full procedure, and the reasons
behind the choices, in `docs/releasing.md`.

## Attribution

`Screen.Attribution` is the app's first two-level destination: it is reached
from a row on `Screen.Settings`, not from the rule list's overflow the other
three destinations share, so it is also the first whose own `backTarget` is
not `RuleList`. `backTarget`'s KDoc states the exception; `ScreenSaver` and
`MainActivity.Destination` each got a real branch for it rather than falling
back to a default, the same way every other `Screen` case has to.

The screen and the settings row that opens it are both called "Used
components", not "Open source licenses": every row on it is a link to a
project's own page, not only a licence notice, and the old name described
only the licence half of what it does.

`AttributionScreen` is stateless for its own data, the same reasoning
`SettingsScreen` gives for itself: nothing about the project list, the
licence link or the repository link changes while the screen is open. It
takes its project list and two fixed URLs as parameters rather than reading
`shippedDependencies` and a hardcoded string itself, so its own instrumented
test can run against fake values that a real dependency bump cannot break.
`AttributionHost`, beside `SettingsHost` in `MainActivity.kt`, supplies the
real ones: the version from `packageManager.getPackageInfo`, since
`buildFeatures.buildConfig` is off and turning it on for one string is not
worth it, and two constants, the Apache 2.0 licence's own canonical URL and
Trigly's own repository URL (see the License section of `README.md`).

**Every link opens through one callback.** A project's row, the licence row
and the repository row all call the same `onOpenUrl: (String) -> Unit`; the
screen does not need to tell them apart, only the host does. `MainActivity
.openUrl` is that host: `startActivity(Intent(Intent.ACTION_VIEW, ...))`,
wrapped in the same `runCatching`-and-`Toast` shape `shareSingle` already
uses for its own external launch, since nothing guarantees a browser is
installed and a link that silently does nothing is the failure this project
keeps designing against.

**The licence text is a link now, not a bundled file.**
`res/raw/license_apache_2_0.txt` is gone, and `res/raw` is a resource type
this app no longer ships at all; the licence row opens
`https://www.apache.org/licenses/LICENSE-2.0` instead. One row covers every
project the screen lists, not one per project: the shipped set is all Apache
2.0, `licensee { allow("Apache-2.0") }` in `ui/build.gradle.kts` enforces
that, so a copy each would be several identical links. This reverses an
earlier decision, on purpose: the licence used to ship in the APK because a
link would be useless on an offline phone, which is still true of an idle
scroll through the screen. It is not true of a tap: a project's own page and
Trigly's own repository were always going to be external links the moment
"link to the project" was the point of this screen, so a licence link costs
nothing a phone without a browser or without data was not already going to
find unreachable. `MainActivity.openUrl` reports that honestly rather than
crashing or staying silent, the same as every other link here.

`shippedDependencies`, generated rather than hand-written by `app.cash.licensee`,
applied to `:ui` only. Because `:ui` depends on every other module as a project
dependency, its release runtime classpath already holds everything the APK
ships, including `androidx.compose.material:material-icons-core`, which
arrives transitively through `material3` and has no entry of its own in
`gradle/libs.versions.toml`. Licensee checks each artifact's declared licence
against an allow list and fails the build on anything else, which is the same
shape as the checks `docs/releasing.md` already keeps for a release. The
`generateAttributionList` task in `ui/build.gradle.kts` turns licensee's own
report into a `GeneratedAttribution.kt` under `build/generated/`, compiled
into `:ui`'s `main` source set; neither that file nor the report is checked
in, since a committed snapshot would recreate exactly the staleness this
design exists to prevent. `Attribution.kt` keeps the `Attribution` data class
the generated file builds, one instance per Maven artifact.

**An artifact is a build output, not a project a reader recognises.** The
release classpath holds 88 Maven artifacts, and reading `androidx.compose.ui:
ui-graphics-android` beside 30 more lines that all mean "Jetpack Compose" tells
a reader nothing. `groupIntoProjects`, also in `Attribution.kt`, folds the
per-artifact list down into five: AndroidX, Kotlin, Kotlin Coroutines, Guava,
and JetBrains Java Annotations, each carrying how many artifacts of it this
build actually ships and the URL a tap on its row opens, so the screen does
not read as though Trigly ships one file of AndroidX. `AttributionHost` calls
it once, on the real `shippedDependencies`; `AttributionScreenTest` still
passes `AttributionScreen` fake `AttributionProject` values directly, so
grouping logic is not part of what that test can break or be broken by.

The fold is keyed on `groupId`, never on `scm.url` even though every artifact
in licensee's report carries one: four AndroidX artifacts
(`androidx.autofill:autofill`, `androidx.concurrent:concurrent-futures`,
`androidx.interpolator:interpolator`, `androidx.versionedparcelable:versionedparcelable`)
carry a stale `http://source.android.com` URL left from before they moved out
of AOSP into Jetpack. Grouping by `scm.url` would split AndroidX in two and
label part of it as AOSP; `groupId` does not lie for these four.

**Which URL a project's row opens is chosen, not taken from whichever
artifact `groupBy` happens to list first.** `mostCommonUrl`, in
`Attribution.kt`, picks the most common non-null `scm.url` among a project's
own artifacts, a tie broken by the URL's own text so the choice never depends
on map or list iteration order. "First" is exactly the stale-URL trap above:
84 AndroidX artifacts agree on the real project page and four do not, and
picking whichever one a fold lists first would sometimes send AndroidX's row
to `http://source.android.com` instead. `Attribution.scmUrl`, generated
alongside `groupId`, `artifactId` and `license`, is what `mostCommonUrl` has
to choose from; the choice itself stays in `Attribution.kt`, not in
`generateAttributionList`, the same reasoning that already keeps the grouping
decision out of that Gradle task.

An artifact whose `groupId` matches none of the five shows up under its own
`groupId` as the project name, in `groupIntoProjects`, rather than vanishing
from the page or being folded into the wrong project. Deliberately a soft
degrade rather than a throw: `AttributionHost` calls `groupIntoProjects` to
render the screen, so a throw there would crash the app the moment somebody
opened Used components, taking away the one thing that screen exists to
show them, in exchange for catching a problem that a row reading a raw
group id already reports honestly. Nothing vanishes and nothing is
mislabelled either way; the only difference is whether the app still works.
Such a row can still have a URL, since `mostCommonUrl` runs over whatever
`scm.url` its own artifacts carry regardless of whether the group is a known
project; `AttributionScreen` disables the row's click instead of omitting the
handler only when that URL turns out to be null too.

The strictness that would otherwise be lost lives in the test instead.
`GeneratedAttributionTest`'s `nothing is unmapped` calls `groupIntoProjects`
over the real generated `shippedDependencies` and asserts every resulting name
is one of the five known projects, and that test is part of the merge gate,
the same drift-guard shape `ConfigSchemaContractTest` already uses for the
config schema above. A `groupId` the table does not know is caught there,
before a release, without the production code ever needing to crash to prove
it.

`SettingsRow`, in `Blocks.kt`, is the shared shape behind every row on
`SettingsScreen`, and behind the repository and licence rows on
`AttributionScreen` too: a title, and whatever the row shows or does on its
trailing edge. A project's own row is not built on it, since it also needs an
artifact count and a licence name on the trailing edge that `SettingsRow` has
no slot for, so it is a plain `Surface` instead, styled to match. The backup
switch, the attribution row on `SettingsScreen`, and these two rows are all
built on `SettingsRow`, and its signature is deliberately wide enough for a
fourth shape none of them use yet — a row that shows a current value and
opens a picker to change it — so that caller does not have to reshape the row
again.

## Update check

One button on `AttributionScreen`, below the version: "Check for updates".
Pressing it is the only thing that ever calls `checkForUpdate`, in
`UpdateCheck.kt` — there is no scheduler, no `WorkManager` job and nothing
else in this codebase that calls it. A person presses a control, Trigly looks
once, and nothing about this app phones home any other way; see that file's
own KDoc, which says so directly for the next person who goes looking for
where else it might run.

`android.permission.INTERNET` did not need declaring for this: `:actions`
already declares it in `actions/src/main/AndroidManifest.xml`, for the HTTP
action, and the manifest merger folds it into the app manifest already.
Nothing changed there.

`checkForUpdate` reads GitHub's own "latest release" API
(`api.github.com/repos/philipphueber/Trigly/releases/latest`), the same
information anyone visiting the releases page already sees, over
`HttpURLConnection` — the same client `HttpRequestAction` uses in `:actions`,
for the same reason: one caller does not justify adding OkHttp.
`parseLatestReleaseTag` reads the response's `tag_name` field with
`org.json`, the same library `RuleJson` uses in `:core` for the same reason:
android.jar's own copy is a stub that throws at runtime, so `ui/build.gradle
.kts` adds a `testImplementation` of the real one, for `UpdateCheckTest`.
`isNewerVersion` compares each dot-separated part of a version as a number,
not as text — `"0.10.0" < "0.9.0"` by plain string comparison, backwards for
a version number — and treats a missing or non-numeric part as zero rather
than failing, since a malformed tag must not turn a button press into a
crash.

A result is one of three, `UpdateCheckResult` in `UpdateCheck.kt`: up to
date, a newer version is available (carrying its number), or the check
failed (carrying why). The third exists on purpose: a check that silently
fails offline and says nothing is worse than no check at all.

The button's own "checking…" flag and its last result live in
`AttributionScreen`'s local `remember`ed state, the same shape
`TextPatternField`'s own `testing` flag uses for its "Test" button, not a
ViewModel: nothing here is data worth surviving a configuration change for,
and a stale "checking…" after a rotation is one more press away from
correct.
