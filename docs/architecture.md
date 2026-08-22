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
| `:core`     | `TriggerEngine`, domain model, rule storage. No UI, no Compose. |
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

`docs/triggers.md` catalogues every planned trigger against this model, plus
the cross-cutting blockers — no foreground service, no scheduler, no permission
flow — that gate whole groups of them.

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

See the Testing section of `CLAUDE.md` for the commands.
