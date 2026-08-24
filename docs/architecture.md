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
  have to be absorbed twice — once in the bridge, once in the shared layer.

Maintainability here comes from clean module separation *within* native
Android, not from the framework choice.

## Language and UI

- **Kotlin** — null-safety, coroutines for the async trigger/action pipeline,
  less boilerplate, and what contributors expect from a modern Android project.
- **Jetpack Compose** — declarative UI, and materially easier to test than XML
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
trigger, the abstraction is wrong — fix the interface rather than special-casing
the new type.

How that is actually enforced: a `Rule` stores a *type string*, not a class.
`Registry` resolves it against factory lists that are handed in at construction
by `AppContainer` in `:ui`. That indirection is the entire reason `:core` can
own the engine while knowing nothing about `:triggers` or `:actions` — and so
the reason adding a trigger touches exactly one existing line, its module's
`triggerFactories()` list, instead of a `when` branch in the engine. Two
factories claiming one type fail at assembly rather than resolving by list
order.

Files in `:triggers` are grouped by *source* rather than strictly one per type:
two triggers reading `ACTION_BATTERY_CHANGED` share a file because they share
the extras and the sticky-broadcast caveats. Adding one still does not touch the
other's logic, which is the property that matters.

### Requirements

Triggers and actions declare what they need — a runtime permission, a settings
screen the user must visit, a hardware feature, a minimum API, or a Play policy
restriction — as `ComponentRequirement` on the *factory*, so it can be read
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

`docs/triggers.md` catalogues every planned trigger against this model, plus
the cross-cutting blockers — no foreground service, no scheduler — that gate
whole groups of them.

### Config schema

Config is stored as `Map<String, String>`. The engine is happy with that; a form
cannot be drawn from it. So each factory also declares its fields as
`ConfigField` — the same pattern as `ComponentRequirement`: declared on the
*factory*, consumed by the UI, invisible to the engine.

Fourteen field kinds cover all 47 components: `Text`, `TextPattern`, `Choice`,
`Number`, `Decimal`, `Flag`, `AppPackage`, `SoundUri`, `BluetoothAddress`,
`Slider`, `Duration`, `Timestamp`, `TimeOfDay`, `Coordinates`. `Choice` carries the most weight, because the fourteen
two-word state fields (`enabled`/`disabled`, `plugged`/`unplugged`,
`entered`/`exited`) use a different word pair per component — which is precisely
why the words must be declared per factory instead of inferred from the key name.

**Eight of the fourteen exist purely so the editor can offer a better control for
something an existing kind could already store**, and that is the pattern to
follow rather than an accident to tidy up. Every one of them keeps the stored
value byte-for-byte and changes only the control, which is why adding them
migrated nothing: no saved rule, no exported file, no `RuleJson` version.

The second wave came out of a sweep of all 77 declared fields, prompted by the
calendar action asking for a time in *epoch milliseconds*. The pattern behind
that field turned out to be general — the stored value and the shown value were
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
the posting app. Both had exactly one working value — empty — so the honest
control was no control. `AppPackage`, `SoundUri` and
`BluetoothAddress` all store and validate exactly like `Text`; each is separate
because its value is one nobody can produce from memory — `com.google.android.dialer`,
`content://media/internal/audio/media/54`, `00:11:22:33:44:55`. `Slider` stores
like `Number` but renders as a track, and the line between them is not "has
bounds" — it is what the bounds *mean*. A `Number` bound is a guard rail on a
value you have decided (a 5000 ms poll interval), where a slider would be fiddly
to hit and illegible once set. A `Slider` value is a position — half volume —
where the digits are the least interesting part. Adding a kind for presentation is
cheap because the `when` in `ConfigFieldEditor` and in `ConfigSchemaContractTest`
are both exhaustive: the compiler names every place that has to handle it.

The three pickers share their behaviour rather than their appearance.
`ValuePicker.kt` owns the searchable list, the row that restores blankness, the
manual-entry escape hatch and the value box; each field supplies only its list,
its labelling, and what "type it yourself" means there. That last one is where
they genuinely differ, and the differences are deliberate: a sound offers **no**
typed value, because a URI is not something a person composes and offering the
option would only invite one that cannot resolve — while a Bluetooth address must
offer one, because *paired* is not the same set as *can connect*. A device paired
to another phone still fires `ACTION_ACL_CONNECTED`. For the same reason an
address is displayed alongside its device name and a sound URI is not: the
address is short and distinguishes two identically-named devices, whereas the URI
is the thing the picker exists to hide.

Any of them can hold a value this device does not know — an imported rule, an
uninstalled app, a deleted sound, an unpaired device — and all three render it
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
an *absent* value as "match anything" — `bluetooth_connected` without an address,
a package filter left empty. An editor that helpfully supplied a default would
silently narrow the rule, so blankness is declared as a setting rather than left
to look like an unfilled field.

How it renders depends on the field kind, and the wording follows. A `Text`
field shows it as a hint under an empty box, so it reads as an instruction:
"Leave blank for any address". An `AppPackage` field is a picker with no blank
state to leave alone, so the same declaration is phrased as a *value* — "Any
app" — shown as what the field currently says and as the row that sets it back.

`TextPattern` is the one kind that owns **two** config keys — the pattern and
its match mode. See "Matching text, and matching it loosely" below for why they
are one field rather than a text box beside an unrelated dropdown.

Factories also declare `displayName`, `category` and an optional `warning`.
`category` is what makes a 28-item trigger picker usable; `warning` is where a
caveat that used to live in KDoc reaches the person building the rule.

The UI never touches a factory. `Registry` exposes a flattened
`ComponentDescriptor` instead, so the editor cannot call `create()` while someone
is still typing — construction is the validation step and it belongs at save
time.

Everything here is defaulted on `ComponentFactory`, so a factory that declares
nothing is ugly rather than broken. The drift guard in
`ConfigSchemaContractTest` is what keeps it from staying ugly: it walks every
registered factory, not a hand-maintained list.

### Rule storage and the portable format

Rules are Room-backed. Two tables: `rules`, and one `components` table holding
both triggers and actions — they are the same shape, a type string and a config
map, and `:core` deliberately knows nothing about which types exist. A
`components` row carries an `ordinal`, which is what makes action *order*
durable; a rule runs its actions in sequence.

There is deliberately **no** `fallbackToDestructiveMigration`. These are rules
somebody built by hand, and silently deleting them on a schema change is not an
acceptable failure mode — a missing migration should fail loudly in development
instead. Schemas are exported to `core/schemas/` for that reason.

`RuleJson` is the portable format, and it serves two jobs so there is one format
to get right rather than two that can disagree: export/import, and the `config`
column itself. Export exists because Android's Auto Backup needs a Google
account and does not run on de-Googled devices — the audience the rest of this
project bends over backwards for. An explicit file the user owns is the only
phone-switch mechanism that always works, and it doubles as a way to share one
rule with someone else. The format is versioned, and a file from a *newer*
version is refused rather than half-read: failing to import is better than
losing a rule silently.

Room stays an implementation detail of `:core`. Storage is handed out as a
`RuleRepository` from a factory function, and `room-runtime` is
`implementation`-scoped so it is not on `:ui`'s compile classpath at all. That
enforces the boundary rather than merely asking for it.

### Testing an action from the editor

Each action block has a Test button that runs that action immediately. It exists
because roughly half of what an action does is *sensory* — which sound, how loud,
how the spoken text reads — and without it the only way to find out is to save the
rule, wait for the real trigger, and infer what happened from the result.

Pressing it again stops the run, and that is a requirement rather than a
refinement: `play_alert` loops for up to a minute by design, and the action's own
docs note that disabling the rule was previously the only way to cut one short. A
test that could not be stopped would reproduce exactly the trap the duration cap
exists to avoid.

Two things it does not pretend. The event is synthetic and carries no payload, so
an action reading trigger payload sees nothing — harmless today, and the thing to
revisit when payload substitution lands. And a test necessarily runs with the app
on screen, which is the one condition under which the background-activity-start
restriction does not apply: an "open" action can pass here and still do nothing
when the rule fires for real. The result is therefore drawn in the neutral block
style rather than as a success, because a green tick would imply more than the
test can establish.

### Navigation

Three destinations — the list, the editor, and the notification inspector — do
not justify a navigation library and the dependency it brings. A sealed `Screen`
plus **one** `BackHandler` is the whole feature.

One handler, not one per destination, and the difference is the whole reason this
section exists. Per-destination handlers are added and removed as the screen
changes, which means the editor's handler is disposed *by the navigation it just
performed* — a back press then arrives while the callback that should answer it
is being torn down. The rule list, meanwhile, registered no handler at all and
leaned on the framework default to finish the activity, so "back on the list
closes the app" was never something the app actually said. Now `backTarget` says
it: a `Screen?` where null means the list is the bottom of the stack and back
leaves, and every other destination — the editor, the inspector — maps back to
the list. It is a pure function precisely so that the one decision here is
checkable without a device.

`Screen` is saved through `ScreenSaver`, so a rotation inside the editor does not
dump the user back on the list. That is load-bearing rather than a courtesy — see
below.

**The editor's draft is discarded when the editor is left.** The editor gets a
ViewModel keyed by rule id, which handles *different* rules — but an unsaved rule
has no id, so its key can only be the constant `editor-new`, and these ViewModels
live in the activity's store. One instance therefore served every new rule for
the life of the activity, carrying the last draft with it: tapping "New rule"
reopened the rule you thought you had closed. `RuleEditorViewModel.reset()` is
the answer, called from a `DisposableEffect` in `EditorHost` because dispose is
the one place that catches every way of leaving — back, the header arrow, a save,
a delete — and cannot be forgotten when a fifth is added. It is guarded on
`isChangingConfigurations`, since a rotation disposes the composition too and
losing a draft to a turn of the phone would be worse than the bug being fixed.
That guard is why the destination has to survive rotation.

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
joined haystack comes from `notificationHaystack` in `:core` — the same function
`matchesNotification` calls, which is why that formatting was lifted out of the
matcher rather than reproduced in the screen. A screen that reconstructed an
approximation would be worse than no screen, because its whole value is being
believed: someone comparing a pattern against what it is shown has to be
comparing it against the real thing. Values are quoted and monospaced for the
same reason — a missing title still contributes its separating space, and an
anchored pattern failing on an invisible leading space is exactly the puzzle this
screen is for.

Its two empty states are different problems and are reported as such: notification
access not granted, versus granted with nothing currently posted. Only what is
posted *now* can be inspected — there is no history, because the listener keeps
none — so the screen says so instead of looking broken while it waits.

### Where the engine runs

`EngineService` in `:ui` is a foreground service, and it owns the engine's
lifetime: the engine is constructed against the service's own `CoroutineScope`,
so there is one answer to "is Trigly running?" rather than two that can
disagree. Nothing outside holds a reference to it — `AppContainer` deliberately
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
foreground-service type catalogue describes what a service is *doing* — playing
media, syncing data, following a location — and general-purpose automation is
none of them. `dataSync` is the tempting mislabel and is also the one Android 15
caps at six hours a day, which would stop the engine every evening. `specialUse`
carries no timeout; its price is a subtype string that Google reviews before a
Play release, which is a fair price for saying what the service actually is.

**Starting is the app's job; stopping is the service's.** `TriglyApp` collects
the rule store and starts the service whenever any rule is enabled;
`EngineService` stops itself when none is. Splitting it that way means neither
side has to know what the other is doing, and re-asking on every rule change
makes a service that went missing come back on the next edit rather than at the
next reboot — starting one that is already running costs a single
`onStartCommand`. `BootReceiver` covers the two events that end a process with
no user involved, a reboot and an app update; `ACTION_MY_PACKAGE_REPLACED`
matters as much as `BOOT_COMPLETED`, because otherwise every update would
silently stop every rule until someone next opened the app.

Those are also the moments the platform *allows* a foreground service to start.
From API 31 an app may only start one while it is exempt — visible on screen,
answering one of those two broadcasts, or excused from battery optimisation —
and there is no API that answers "am I exempt right now?" well enough to branch
on. So `EngineService.start` catches the refusal rather than predicting it: a
refusal means the process woke for some other reason, and `START_STICKY` will
bring the service back anyway. Crashing over it would turn a missed start into a
dead app.

**`sync`, not `start`.** The service calls `TriggerEngine.sync` on every
emission from the rule store, and `sync` deliberately leaves an unchanged rule
running. Rebuilding a trigger re-registers its receiver, and a sticky broadcast
replays on registration — so restarting rule A because rule B was edited would
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
background-activity-start exemption — see `docs/actions.md`, where that mistake
is easy to make — and it is not a scheduler: a coroutine `delay` inside a
foreground service still stops in Doze.

### Services the system owns

`NotificationListenerService` and `AccessibilityService` are constructed by the
framework, so there is no instance to hand a trigger and nowhere to inject a
dependency. Each service publishes to a process-wide `ServiceEventBus`, and the
triggers subscribe; neither knows the other.

The services stay deliberately thin — flatten the callback argument, publish,
return. The system unbinds a service whose callbacks are slow, so no rule
evaluation or I/O happens on those threads. The bus drops the oldest event under
load rather than blocking, because accessibility events arrive in bursts of
hundreds and losing stale UI events is better than losing the service.

Each bus also exposes whether its service is connected. A trigger whose service
is not bound is not quiet, it is broken, and that difference has to be
expressible.

Actions reach those services the other way round, through a port in `:core` that
`:triggers` implements over the live service — `NotificationController` for the
notification listener. That is what keeps `:actions` from depending on
`:triggers`: the dismiss and button actions call *into* it, and `play_alert`'s
"stop when the notification goes away" *reads* through it. Reading is a poll, not
a subscription, for the reason the watchdog trigger polls: the bus carries edges,
and something that starts after the edge has passed would wait for it forever. An
action that needs to observe the system, rather than act on it, belongs on this
port too — never on a bus in a sibling module.

## Look and feel

### Colours live in one file

`Palette.kt` holds every colour in the app, in three sections: the raw tonal
ramps, the light and dark scheme assembly, and the handful of roles Material 3
has no slot for. Nothing else declares a colour. Re-branding is rotating the hue
of one ramp; moving a colour to a different *use* is editing one line of the
scheme.

Two colours unavoidably live outside it. `res/values/colors.xml` and
`res/values-night/colors.xml` carry the window background, because the framework
paints the window before any Compose code runs — without them a dark-mode launch
flashes white. They must mirror `Tone.Paper` and `Tone.Ink`, and the file says
so.

Three rules decide which tone plays which role, and they are what make the
screens read as loud rather than as tinted:

**The fill is the logo.** `primary` is `Tone.Orange60`, `#EC6206` — not a
neighbouring step of the brand ramp but the literal background of the app mark,
and the same value in both schemes. The header slab and the launcher icon are one
colour, so `primary` is the only role here that does not invert with the theme.

**The grid is ink, and so is the text.** `outline` and `onSurface` are the same
value: near-black on light, near-white on dark. Borders used to be orange, which
is what cost the fills their punch — a saturated fill has nowhere to land against
a saturated edge. On/selected states change their *fill* and keep the ink frame,
so a control does not appear to gain weight when it turns on.

**Ink on the orange, not white.** `onPrimary` is `Tone.Ink`. White on `#EC6206`
is 3.32:1 and fails AA; ink on it is 5.66:1 and passes. This is the constraint
that used to force a burnt `#9F3D00` into `primary`, and resolving it the other
way is what let the vivid orange onto a slab at all.

The cost of rule 1 is that one Material role cannot cover both jobs: `#EC6206` as
text on the page is 3.23:1. So the brand orange as *ink* — an outlined button's
label and border, a value readout, a regex escape — is
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
of what the theme says — which is why the blocks *were* square for a long time
without anyone passing a shape, and why editing the theme alone would have
rounded the dialogs and left every card behind. `BlockShape` closes that gap by
passing `shapes.medium` explicitly at each block.

Two kinds of surface stay square whatever the radius, and the test is whether
they have an edge of their own to round. Full-bleed chrome (`BlockHeader`,
`BlockBottomBar`) runs to the screen edges under the system bars. Cells inside a
block — an unmet requirement under a rule, a caveat under a chosen component —
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
a block's own silhouette in the ink of its border — 4dp for cards and actions,
3dp for the on-state of a toggle or chip. Material's `shadowElevation` is
deliberately unused: a blur that fades with distance is a claim about a light
source, and this design has none. A soft shadow under a 2dp border is also the
exact combination that reads as a Material card in costume.

Two consequences worth knowing. The modifier **reserves its own space** — the
padding is applied before the draw, so the block is laid out 4dp smaller and the
shadow lands in the strip that padding freed. That is what keeps it to one call
site per component: no screen knows a shadow exists and nothing can be clipped
by a parent sized before it was added. And the shadow colour is
`colorScheme.outline`, not a fixed ink, which makes it a different idea per
theme: in light mode it is a shadow, and in dark mode — where ink on ink would
vanish — it inverts to the near-white border colour and becomes a second outline,
brighter than the thing casting it. For a control the space is reserved whether
the shadow is drawn or not, so a toggle does not change size under the finger
that just tapped it.

**One vocabulary, in `Blocks.kt`.** `BlockHeader`, `BlockCard`, `BlockButton`,
`BlockToggle`, `BlockDivider` and friends are what the screens are assembled
from, which is what stops a border width or a padding drifting between them.
They wrap Material's own components rather than drawing from scratch, so focus,
ripple and accessibility roles survive the reskin — `BlockToggle` is
`Modifier.toggleable` with `Role.Switch`, so a screen reader still calls it a
switch even though it renders as an ON/OFF cell.

**Chrome is uppercase; prose is not.** Titles, buttons, category bars, field
labels and rule names are labels in this design and are uppercased in one place
each — `BlockButton` uppercases its own text, `ConfigFieldEditor` uppercases
field labels — so a new call site cannot arrive in the wrong case. Warnings,
help text and requirement explanations stay in sentence case: they are sentences,
and capitals make sentences unreadable. This is visible in the tests, which
assert `"NEW RULE"` and `"Needs usage access, granted in system settings"` — the
accessibility tree contains exactly what is drawn.

The rule summary is monospaced. A screen of rules then lines up into a column
that can be scanned rather than read.

### Warnings are not errors

A component's caveat ("this polls, so it costs battery"; "Android 12 suppresses
these in the background") is not a failure. The rule is valid and will save. So
caveats get their own amber — `TriglyExtraColors.caution`, the one role Material
3 has no slot for — and `colorScheme.error` is kept for things that actually went
wrong: a refused save, a permission that is missing, a rule that cannot fire.
Once two thirds of the triggers carry a caveat, drawing them all in red teaches
people to ignore red.

Caveats are also shown at a different *time* than they used to be, and the rule
is now the same everywhere: **the prose is hidden by default, and the one thing
that reveals it is its badge.** The picker once printed each component's full
warning under its name, on the reasoning that a caveat matters most before the
choice is made — but the list became a wall of prose in which no single item
could be read. Two thirds of the triggers carry a caveat, so the same wall grows
in the editor the moment a rule has a few components.

So `CaveatBadge` is both the marker and the control. It carries the `!` glyph, it
reads to the accessibility tree as a toggle, and tapping it is what brings the
sentence — in the picker it opens in place under the component's name, in the
editor it opens under the block's heading. In the editor the badge lives in the
header rather than inside the fold, so it stays with a block that has been folded
shut for reordering: a caveat is worth reading before moving an action, not only
while filling one in. Nothing shows the sentence on its own — not opening a block,
not choosing the component — which is what keeps a long rule and a 28-item picker
both readable while still admitting, at a glance, that there is something to know.

### A component block folds

The editor puts everything on one scroll, which is right for building a rule and
wrong for finding your way around one that already has six actions. So each
trigger and action block folds.

What folds is what you *read and fill in* — the settings and the requirements.
What stays is the heading, the caveat badge, any fault, and the footer: Test, Up,
Down, Remove. Reordering a long rule is the main thing folding is for, so hiding
the controls along with the settings would remove the reason to fold at all. A
fault stays because "this component is not available in this version" is not
something the user should be able to tuck away. The caveat *badge* stays for the
same reason the fault does — that there is a catch is not tuckable — while the
caveat *prose* is governed by the badge, not the fold, and can be read from a
folded block (see "Warnings are not errors").

Three smaller decisions. Everything starts **open**, so a one-action rule looks
exactly as it did before this existed and folding is something the user does
rather than something they have to undo. The fold is **not offered** when there
is nothing behind it — an unchosen component, or one with no settings and no
requirements — because a button that visibly does nothing is worse than no
button; a lone caveat does not bring it back, because the caveat has its own
control. And the state is keyed by *position*, `trigger` and `action-0`, because
a `ComponentDraft` has no identity of its own: Up and Down therefore move an
action out from under its own fold. The revealed-caveat state is kept the same
way and for the same reasons — a separate positional set, saved across rotation.
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
at the root so the keyboard pushes Save up rather than covering it — which is
also why Save and Delete are in that bar and not at the end of the scroll: a rule
with six actions is taller than a screen.

### The mark, and an icon with no bitmaps

The app mark is a T whose stem runs into an arrowhead — something fires,
something follows, which is the product in one shape. It is drawn out of
rectangles and one triangle, the same brutalism as the blocks. Source of truth
is `docs/branding/trigly-mark.svg`; the alternatives it was chosen from are
still in `docs/branding/_explore/`.

The launcher icon is **vector at every layer and has no PNG mipmaps at all**.
That is a consequence of `minSdk = 26`: adaptive icons arrived in exactly that
release, so there is no older device to keep a `mipmap-hdpi`-through-`xxxhdpi`
ladder around for. For the same reason the adaptive XML sits in a bare
`mipmap/` rather than the `mipmap-anydpi-v26` the templates emit — that
qualifier exists to hide adaptive icons from API 25 and below, and lint flags it
as obsolete here. Its suggested fix, `mipmap-anydpi`, does *not* work: AAPT2
ignores that folder and the build fails to link. No qualifier is the spelling
that both builds and lints clean.

One number worth keeping if the mark is ever redrawn. The 108-unit board is the
adaptive-icon canvas, but only the centre 66dp circle survives every launcher
mask, and the mark's widest points sit 33.3dp from centre against a 33dp budget.
The foreground is therefore scaled to 0.94 — without it, a circular mask shaves
the ends off the T's bar and it reads as a lowercase r.

### Only what the device can run

The editor's pickers list components this phone can actually execute.
`RequirementChecker.isPossible` draws the line that `isSatisfied` cannot: a
missing permission is a prompt away, while an API that arrived after this phone's
Android version and a radio it does not have are permanent. Offering a trigger
that can never fire is worse than omitting it — the user builds a rule around it,
nothing happens, and the app looks broken rather than honest.

Two deliberate exclusions from that filter. `PolicyRestricted` does **not** hide
a component: it says Google will not publish it on Play, which has nothing to do
with whether it works on the device in front of you, and Trigly is meant to be
sideloadable. And the filter applies to the *pickers only* — `Registry` stays
device-agnostic and `descriptorFor` looks up unfiltered, so a rule imported from
a newer phone still renders its trigger instead of going blank.

### Picking an app, not typing one

`AppPackage` was a distinct field kind from the start for one reason, and this is
it: nobody knows that the dialer is `com.google.android.dialer`. It stores and
validates exactly like `Text`, so the only thing that justifies the extra kind is
the editor rendering it as a picker.

The list is **launcher apps only**, and that is the whole design decision.
Enumerating every installed package needs `QUERY_ALL_PACKAGES`, which Google
treats as a restricted permission requiring a declared exception — a heavy price
for a convenience, and a publishing obstacle for an app meant to be easy to
distribute. Declaring the launcher intent in `<queries>` instead returns every
app with an icon, which is what a person means by "an app".

The cost is real and is paid explicitly: a service with no launcher icon — a
plausible target for `notification_watchdog` — is not in the list. So the search
box doubles as manual entry. Type something that looks like a package name and
the picker offers it as a row; `looksLikeAPackageName` gates that offer and is
deliberately loose, because the factory validates for real at save time and
refusing a valid-but-unusual package is worse than offering one that turns out
not to be installed. The same looseness is why it must reject anything a person
would type to *search* — one field serves both purposes.

Two smaller things the picker has to get right. A field whose blankness is a
setting gets a row that restores it, or opening the picker would be a one-way
door. And a stored package always shows its label with the raw package beneath,
including when the app is not installed: a rule imported from another phone
renders as its package name rather than as nothing.

The app list is read once per process, off the main thread, and handed down
through `LocalInstalledApps`. A `staticCompositionLocal` rather than a parameter
because exactly one branch of `ConfigFieldEditor` wants it, and threading a list
from the activity through two screens and a component block would add it to four
signatures with no other use for it. Its empty default is safe — the picker still
offers manual entry — and it is what lets the instrumented tests supply their own
app list instead of asserting against whatever the emulator image ships.

### Matching text, and matching it loosely

Six fields across five triggers ask the same question — "does this text match
what the user asked for" — and each of them used to answer it with its own
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
that does not compile throws from `create()` — which is the path every other
invalid config already takes, so the editor shows it at Save instead of the
engine throwing from a coroutine while the phone is in a pocket.

**A blank pattern matches everything, and an unknown mode reads as `contains`.**
The first is just the existing meaning of an empty filter, moved somewhere the
callers can stop restating it — the private constructor and `of()` exist so
"nothing entered" becomes "no opinion" exactly once. The second is the one
deliberately lenient parse in the project: every rule saved before the mode key
existed has no mode at all, and an import from a newer build may carry a mode
this one has never heard of. In both cases the pattern still means something as a
substring, so falling back loads a rule the user can see is reasonable instead of
refusing it.

A regex is searched with `containsMatchIn`, not `matches`: the field reads like
grep, and `^…$` is there for anyone who wants the whole string. For
`notification_posted` the haystack is the title and body joined by a space, which
is what makes `^` anchor to the start of the *title* — worth knowing, because it
is the one place where the text being matched is not a thing the user can see as
a single string.

**A pattern can be tested, not just compiled.** `regexErrorOrNull` catches a
stray bracket and nothing else: a pattern can compile perfectly and match the
wrong thing, or nothing at all, and until there was a tester the only place that
surfaced was a rule that silently never fired. The Test button beside the mode
toggle opens a dialog with the pattern and a scratch sample, and reports the
verdict as you type.

Two decisions make it trustworthy rather than merely present. **The verdict comes
from `TextFilter.of(...).matches`** — the engine's own code path, so what the
dialog says is what will happen, including the case-insensitivity and the
`containsMatchIn` semantics that are both easy to assume the other way round.
`matchRangesIn` supplies only the highlight, and mirrors those two modes exactly;
a tester whose highlight disagreed with its own verdict would teach people to
trust neither, which is why one unit test checks the two against each other over
a spread of patterns rather than asserting them separately.

**And the states that are neither yes nor no are named.** An empty pattern reads
"matches anything", because an empty filter has no opinion and calling that a
mismatch would misdescribe the rule. A pattern that will not compile says so
rather than reporting a failed match. A zero-width match — `a*` against "bbb" —
says it matched *and* that there is nothing to highlight, because both halves are
true and either alone misleads.

**The editor earns the mode's keep.** The mode toggle sits in the field's label
row, because it changes what the box below it means. In regex mode two things
switch on that a substring has no use for: the pattern is monospaced and coloured
by `RegexHighlight`, and it is checked on every keystroke by `regexErrorOrNull` —
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

That does not make unit tests pointless — it decides what belongs in them. The
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
version of their own — they are not published anywhere.

Signing material is described by a gitignored `keystore.properties` rather than
being configured in the build file, and its absence leaves the release build
unsigned instead of failing. That is what keeps a release-variant build
runnable by a contributor who has no key. Full procedure, and the reasons
behind the choices, in `docs/releasing.md`.
