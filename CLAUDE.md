# Trigly

Open source automation app for Android. Native Kotlin + Jetpack Compose.

## Architecture

Full reasoning and module boundaries: `docs/architecture.md`. The parts that
constrain day-to-day work:

- MVVM. The `TriggerEngine` is fully decoupled from the UI — nothing in
  `:core` may depend on `:ui`.
- Gradle multi-module: `:core` (engine, domain, persistence), `:triggers`,
  `:actions`, `:ui` (Compose screens and ViewModels).
- Triggers and actions are plugin-style: each type is its own swappable module
  behind a common interface. **Adding a trigger must not require editing an
  existing one.** If it does, the abstraction is wrong — fix the interface
  instead of special-casing the new type.

## Testing

While building or changing something, run only the tests that cover it — the
relevant test labels, the one relevant unit-test file, the one or two
instrumented specs.

Run the complete set **only as the gate immediately before merging**. That check
is not optional; only its frequency is. Report the counts.

    unit (JVM)     ./gradlew test
                   ./gradlew :core:test --tests "*TriggerEngine*"
    build          ./gradlew assembleDebug
    lint           ./gradlew lint
    instrumented   ./gradlew connectedDebugAndroidTest
                   ./gradlew :triggers:connectedDebugAndroidTest \
                     -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.TestClass>

Instrumented tests carry the weight here, not unit tests. The failure mode that
actually bites is "works on device X, breaks on device Y" — OEMs differ in how
aggressively they apply battery optimization to background execution, and no
JVM test can see that. The pre-merge gate means connected tests on at least two
devices or API levels, not one emulator.

A new instrumented test is run TWICE back to back before it is trusted.
On-device state leaks between runs — granted permissions, a registered
notification listener, a paired Bluetooth device — so a test that depends on
leftover state passes in isolation and on a first run, and fails on the second.

Report outcomes faithfully. If a test fails, say so and show the output; if a
step was skipped, say that.
