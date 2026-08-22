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

Six field kinds cover all 46 components: `Text`, `Choice`, `Number`, `Decimal`,
`Flag`, `AppPackage`. `Choice` carries the most weight, because the fourteen
two-word state fields (`enabled`/`disabled`, `plugged`/`unplugged`,
`entered`/`exited`) use a different word pair per component — which is precisely
why the words must be declared per factory instead of inferred from the key name.

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

### Navigation

Two destinations — the list and the editor — do not justify a navigation library
and the dependency it brings. A sealed `Screen` plus `BackHandler` is the whole
feature. The editor gets a ViewModel keyed by rule id, so opening a different
rule cannot inherit the previous draft.

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

**Material You dynamic colour is deliberately off.** It is the right default for
an app with no colour of its own; here the orange *is* the identity, and an
automation app whose screenshots and docs look different on every phone is not
friendlier. Dark mode follows the system, because there is no settings screen yet
in which a manual override would belong.

### Blocks, not cards

The design is flat rectangles with hard 2dp borders: no rounding, no elevation,
no gradients. Three decisions carry it.

**Nothing is rounded, declared once.** Every Material shape role is square in
`Theme.kt`, so dialogs, menus, text fields and buttons follow without a `shape =`
argument at any call site.

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

Caveats are also shown at a different *time* than they used to be. The picker
printed each component's full warning under its name, on the reasoning that a
caveat matters most before the choice is made — but the list became a wall of
prose in which no single item could be read. The picker now marks that a caveat
exists with one badge, and the editor states the sentence in full once the
component is chosen, which is the moment it is actionable and swapping it out is
still one tap away.

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
