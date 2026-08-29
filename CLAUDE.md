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

## Open work

`docs/todo.md` holds the reliability and correctness work that is known to be
needed and is not built yet, in priority order, with a Rejected section that
says which review findings are wrong and why. Read it before you propose an
architectural change: the answer may already be in there, either as a numbered
item or as a rejected one. `docs/triggers.md` holds the separate backlog of
triggers that are not built yet.

## Testing

While building or changing something, run only the tests that cover it — the
relevant test labels, the one relevant unit-test file, the one or two
instrumented specs.

Run the complete set in **two cases only**: cutting a release, or a change that
touched everything. A change touches everything when it moves something every
module depends on, such as a `:core` interface every component implements, a
Gradle or version-catalog change, or a rename that swept the tree.

**Everything else runs a partial sweep**: the tests that cover what changed,
plus the tests of anything that reads it, on the two API levels. A whole-project
connected run costs about thirteen minutes and spends nearly all of it on tests
that could not have been affected by the diff. Time spent there is time not
spent on the failure that actually bites.

Report the counts either way, and **say which of the two you ran**. A partial
sweep reported as if it were the full set is worse than either, because the next
person cannot tell what was covered.

Choosing the sweep is a judgement, so write down what it covered and why in the
merge. If the honest answer is that you cannot name what the change could break,
that is the signal to run the full set rather than to guess.

    unit (JVM)     ./gradlew test
                   ./gradlew :core:testDebugUnitTest --tests "*TriggerEngine*"
    build          ./gradlew assembleDebug
    lint           ./gradlew lint
    instrumented   ./gradlew --no-parallel connectedDebugAndroidTest
                   ./gradlew :triggers:connectedDebugAndroidTest \
                     -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.TestClass>

Instrumented tests carry the weight here, not unit tests. The failure mode that
actually bites is "works on device X, breaks on device Y" — OEMs differ in how
aggressively they apply battery optimization to background execution, and no
JVM test can see that. **Two API levels is the part that does not shrink.** A
partial sweep narrows which tests run, never how many devices they run on: one
emulator cannot see the failure this whole posture exists for.

**`--no-parallel` is load-bearing for the whole-project connected run.**
`gradle.properties` sets `org.gradle.parallel=true`, so `:triggers` and `:ui`
otherwise run their instrumented tasks at the same time on the same device. Two
instrumentations then fight over `UiAutomation`, and one module's tests grant
permissions the other module's tests are measuring: a location grant from
`:triggers` makes `EngineService` claim the `location` foreground-service type,
which the platform refuses to a background app, and the process dies mid-run.
It surfaces as "Instrumentation run failed due to Process crashed" and a
different failing test each time, which reads exactly like flakiness or like
another session sharing the device. It is neither. Running one module's task at
a time costs nothing: the two devices still run in parallel with each other,
which is where the time goes.

A new instrumented test is run TWICE back to back before it is trusted.
On-device state leaks between runs — granted permissions, a registered
notification listener, a paired Bluetooth device — so a test that depends on
leftover state passes in isolation and on a first run, and fails on the second.

The suite runs on the **debug** build only, so it never sees R8. The release
build is checked by hand instead, and `docs/releasing.md` holds that smoke test.
It belongs to cutting a release, not to a merge, and it is a precondition for a
tag.

Report outcomes faithfully. If a test fails, say so and show the output; if a
step was skipped, say that.
