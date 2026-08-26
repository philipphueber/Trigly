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

The editor works on a `RuleDraft`, and `toNodeOrNull` is the boundary where a
draft becomes a rule. Two things it used to do there both lost a group without
saying so, and both are now refusals rather than repairs.

A group holding one child is kept. It used to be unwrapped, on the reasoning
that the editor never builds a singleton group. It does, on the way to every
group a person makes: a group is picked from the trigger picker and arrives
empty, so it holds one child for as long as it takes to add the second. Saving
in that state replaced the group with its child, so someone who built
`ALL(screen on, ANY(...))`, added the first branch of the OR and saved, reopened
the rule to find the OR gone. An `ANY` of one evaluates exactly like the child,
so keeping it costs nothing and keeps what the person built.

A group holding nothing refuses the save. It used to be pruned out of the tree
and the rule saved without it. The refusal message existed already but could
only fire when the root itself was the empty group, which was the one case where
pruning happened to produce a null tree.

A group that loses children to *removal* still collapses, and that is a
different question. Removing one of two OR branches leaves the other, and an OR
of one thing is that thing. The difference is intent: one child because a second
was removed is a finished edit, one child because a second is not added yet is a
rule in progress.

`transformTrigger` is the other half of that rule, and it was the other half of
the same bug. It walks to a node and replaces it, and it used to apply the
un-promotion to the result of *every* edit rather than only to a removal. So a
group holding one child lost the group the moment anything inside it was
touched: typing a value into the one trigger in a new OR group deleted the OR
group while the person was still filling it in. Fixing the save path alone was
not enough, because the draft had already lost the group before a save was
reached. Now a removal can collapse a group and no other edit changes the shape
at all. A group left with no children still disappears, because the last removal
from a group is the removal of the group, while an *empty* group someone made on
purpose is kept and refused at save.

The trigger picker asks a third question and needs a third answer.
`triggerOptionsFor` converts a candidate tree to test whether it could start, so
strictness there would empty the picker: with `ALL(screen on, ANY())` on screen,
every candidate for the root would convert to null and be filtered out,
including the components that would fill the empty group. It uses
`toNodeIgnoringEmptyGroups`, which prunes exactly the way `toNodeOrNull` used
to, and is named so the difference is visible at the call site.

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
column itself. Export exists because Android's Auto Backup needs a Google
account and does not run on de-Googled devices (the audience the rest of this
project bends over backwards for). An explicit file the user owns is the only
phone-switch mechanism that always works, and it doubles as a way to share one
rule with someone else. The format is versioned, and a file from a *newer*
version is refused rather than half-read: failing to import is better than
losing a rule silently.

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

Two things it is worth knowing it does *not* fix. It is not a
background-activity-start exemption (see `docs/actions.md`, where that mistake
is easy to make), and it is not a scheduler: a coroutine `delay` inside a
foreground service still stops in Doze.

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
new answer in every process. Any future trigger for an event that precedes the
engine belongs on this shape, not on a receiver.

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

**Material You dynamic colour is deliberately off.** It is the right default for
an app with no colour of its own; here the orange *is* the identity, and an
automation app whose screenshots and docs look different on every phone is not
friendlier. Dark mode follows the system, because there is no settings screen yet
in which a manual override would belong.

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

**A pattern can be tested, not just compiled.** `regexErrorOrNull` catches a
stray bracket and nothing else: a pattern can compile perfectly and match the
wrong thing, or nothing at all, and until there was a tester the only place that
surfaced was a rule that silently never fired. The Test button beside the mode
toggle opens a dialog with the pattern and a scratch sample, and reports the
verdict as you type.

Two decisions make it trustworthy rather than merely present. **The verdict comes
from `TextFilter.of(...).matches`**: the engine's own code path, so what the
dialog says is what will happen, including the case-insensitivity and the
`containsMatchIn` semantics that are both easy to assume the other way round.
`matchRangesIn` supplies only the highlight, and mirrors those two modes exactly;
a tester whose highlight disagreed with its own verdict would teach people to
trust neither, which is why one unit test checks the two against each other over
a spread of patterns rather than asserting them separately.

**And the states that are neither yes nor no are named.** An empty pattern reads
"matches anything", because an empty filter has no opinion and calling that a
mismatch would misdescribe the rule. A pattern that will not compile says so
rather than reporting a failed match. A zero-width match (`a*` against "bbb")
says it matched *and* that there is nothing to highlight, because both halves are
true and either alone misleads.

**The editor earns the mode's keep.** The mode toggle sits in the field's label
row, because it changes what the box below it means. In regex mode two things
switch on that a substring has no use for: the pattern is monospaced and coloured
by `RegexHighlight`, and it is checked on every keystroke by `regexErrorOrNull`:
the same `Regex(...)` the factory will run at save time, so the failure surfaces
while the cursor is still next to the mistake. The highlighter is a hand-rolled
scan rather than a regex over a regex, for the reason that matters most here: it
is asked to read half-typed, invalid input on every keystroke, and anything that
throws on bad input is useless in exactly the moments highlighting helps.

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
