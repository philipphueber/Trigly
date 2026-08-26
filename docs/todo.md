# To do

Work that is known to be needed and is not built yet. Each item states the
evidence, the change, and the condition that makes it done.

This file exists because an external review of the repository raised twelve
points. Nine of them were correct. Three were not, and they are recorded in
**Rejected** at the end, with the reason, so that nobody opens them again.

The trigger backlog is a different list. `docs/triggers.md` holds the triggers
that are not built yet, each with its API and its known traps. This file holds
the reliability and correctness work. The two agree on the order: the scheduler
is first in both.

Priority 1 is work that makes a rule fire when it should. Priority 2 is work
that makes a rule explain itself. Priority 3 needs a decision before it needs
code.

---

## Priority 1

### T1 The scheduler

**Evidence.** No file references `AlarmManager`, `WorkManager` or
`JobScheduler`. Five places wait with a coroutine `delay`, and a coroutine
`delay` stops in Doze:

    IntervalTrigger.kt:33              delay(periodMillis)
    SolarTrigger.kt:56                 delay(fireAt - now())
    AppForegroundTrigger.kt:46         delay(pollMillis)
    NotificationWatchdogTrigger.kt:73  delay(pollMillis)
    ListenerBinding.kt:85              delay(retryMillis)

`docs/triggers.md` calls this open blocker 2. `IntervalTrigger.kt:15` carries a
`TODO(scheduling)`. `SolarTrigger.kt:23` calls it "the honest weakness".

**The part that is easy to miss.** The last line in that list is not a time
trigger. `keepListenerBound` is what recovers a notification listener that the
system did not give back. It waits with `delay`. So the repair path for a dead
listener is itself asleep in Doze. This is a reliability hole in the
event-driven half of the app, which is the half that looks safe.

**Do.** Add one scheduler port in `:core`, with an Android implementation over
`AlarmManager`. Prefer `setWindow`. Use `setExactAndAllowWhileIdle` only where
a user asked for an exact time. Exact alarms need `SCHEDULE_EXACT_ALARM` from
API 31, and Google keeps `USE_EXACT_ALARM` for alarm-clock apps, so design for
a few minutes of drift.

Then move all five waits onto it. `SolarTrigger.events()` is the one place that
trigger changes, as its own doc says.

**Done when.** An interval rule and a solar rule both fire after a Doze window
on two devices or API levels. A listener unbound by an app update rebinds after
a Doze window.

**What this does not fix.** See R1.

### T2 Pin the component type strings

**Evidence.** `ComponentFactory.type` says "Stable identifier, persisted in
rules. Renaming it breaks saved rules." Nothing enforces that. A rename passes
the whole suite and breaks every exported rule that names the old string.

`RuleJson` handles the file shape well. It has a `version` key, it refuses a
file from a newer format with a readable message, and it writes the version-1
shape when the rules fit it, so an export stays importable by an older build.
None of that helps if a type string moves under it.

**Do.** Add a unit test that holds the released set of type strings as literal
text and asserts that every released string is still registered. New strings
may appear. Old strings may not leave.

**Done when.** `./gradlew :core:testDebugUnitTest` fails if a type string is
renamed. Half a day of work. Do this before T1, because it costs almost
nothing and it guards everything else.

### T3 A bounded retry for a state nobody could read

**Evidence.** `TriggerEngine.StateReader.read` treats a state read that throws
as `null`. `TriggerNode.holds` treats `null` as "does not hold". The engine
then calls `onSuppressed` and does `return@collect`. There is no retry and no
queue. The event is gone.

The conservative half is deliberate and it is right. `docs/conditions.md:145`
states the rule: an unknown state is not a satisfied one. Firing unattended
actions on a state nobody could read is the worse of the two failures.

The missing half is the retry. A door opens, the position read fails for two
seconds, and the rule is dropped for good. The door event does not come back.

**Do.** Hold the event and ask again, on a bounded schedule, for a bounded
period. Give up after that and report a distinct outcome. Report the give-up
separately from the first failure to answer.

**Watch for.** The failure is not always short. `docs/conditions.md:108`
already describes a component that "answers unknown, and the group fails
forever with no message". A permanent unknown looks exactly like a temporary
one at the call site, so the give-up rule is the part that carries the weight.

**Done when.** `TriggerEngineTest` shows a rule that fires after a read that
failed once and then answered, and a rule that reports a give-up after a read
that never answered.

### T4 One reliability test on a device

**Evidence.** The suite holds 452 unit test methods in 40 files, and 254
instrumented test methods in 23 files. Thirteen of the 23 instrumented files
are Compose or view-model tests.

`EngineServiceTest` asserts that the engine starts for an enabled rule, claims
the location type, stops when no rule is enabled, registers the boot receiver,
and declares the right foreground-service types. Those are manifest and
lifecycle facts. Not one of them survives a process kill, a Doze window or a
reboot.

`CLAUDE.md` already asks for connected tests on two devices before a merge.
The policy is not the gap. The gap is that almost nothing reliability-shaped
exists to run.

**Do.** Add one instrumented test with this shape: install, create a rule,
force-stop, reboot, wait, send the event, check the action ran. Then state in
the test what it proves and what it cannot.

**Watch for.** Run a new instrumented test twice back to back, per `CLAUDE.md`.
On-device state leaks between runs. Also see the memory note on why force-stop
breaks a reboot test: the two cannot go in one test without care.

**Done when.** The test runs on two devices or API levels and its limits are
written down.

### T5 Requirement liveness, as its own axis

**Evidence.** `ComponentRequirement` has five arms: `RuntimePermission`,
`SpecialAccess`, `SystemFeature`, `MinApiLevel` and `PolicyRestricted`. That
already covers a runtime grant, a settings grant, missing hardware, an API
level and Play policy. The model is good.

The gap is a sixth state that is not in it. `architecture.md:926` describes it:
after an app update the notification listener can be unbound while
`RequirementChecker` still reports notification access as granted, because the
secure setting it reads is still set. The ongoing notification says the app is
watching. Every notification rule is dead, and three separate things claim
otherwise.

`keepListenerBound` patches the symptom from outside the requirement system.
T1 says why that patch is not enough on its own.

**Do.** Make "granted" and "live" two answers, not one. A component whose
service is not bound must be able to say so through the same path that says a
permission is missing.

**Done when.** The rules screen tells the difference between "you never granted
this" and "you granted it and nothing is bound".

---

## Priority 2

### T6 A per-rule policy for a failed action

**Evidence.** `TriggerEngine.run` catches every throwable per action, turns it
into `ActionResult.Failure`, reports it, and carries on. `actions.forEach` is
sequential inside one collector, so two actions of one rule never overlap, and
two events of one rule never overlap either.

So the behaviour today is "continue, and report each failure". It was chosen
deliberately and the reason is in the KDoc. It is also the only behaviour there
is.

**Do.** Add a per-rule field with two values: continue, or stop at the first
failure. A rule whose second action must not run after the first failed cannot
be written today.

**Do not** call this transactional. Android cannot undo an action. A rule
cannot put a light back off because a later notification failed. The name would
promise a rollback that no platform API supports.

**Done when.** A rule set to stop at the first failure runs action 1, fails at
action 2, and does not run action 3. The outcome names the action that stopped
it.

### T7 Say when a Bluetooth identity is unstable

**Evidence.** Most of this is already built.
`BluetoothConnectionTrigger.kt:44` documents that a Bluetooth LE accessory
rotates a resolvable private address about every quarter of an hour.
`CONFIG_IDENTIFY_BY` lets a rule match by address or by name, and
`bluetoothNormalise` now resolves the absent case in one place through
`ComponentFactory.normalise`, so the editor and the engine can no longer read
one rule two ways. `BluetoothPicker` marks a device that is "not paired with
this phone".

So the model is settled and the pairing state is already on screen. What is
missing is what that state costs. Nothing says that an unpaired address rotates
and that the rule will quietly stop matching. The user sees a label, not a
consequence.

**Do.** Say the consequence where the rule is written, not only in the picker
list. Match on an address on an unpaired device, and the editor should say that
the address may change and that pairing is the fix.

**Done when.** The warning shows for an unpaired device and stays away for a
paired one.

### T8 Decide whether the diagnostic survives the process

**Evidence.** `RuleFaultLog` is in memory only, and the file says why: a fault
from a dead process describes a run whose conditions are gone.

It now records three kinds, per `RuleFault.Kind`: `ACTION_FAILED`, `UNDECIDED`
and `COULD_NOT_START`. The "not persisted" reasoning was written for the first
kind, and it fits that one well. It fits the other two less well, and the
difference is not a detail.

`UNDECIDED` is T3's case. A rule dropped just before an OEM battery manager
killed the process leaves nothing at all, and that is exactly what the user
reports as "it randomly stopped working".

`COULD_NOT_START` is a standing condition, not a run. A rule that could not be
built will not build on the next process either, because nothing about the
device changed. So the reason it did not start is worth keeping, and it is the
one kind that a restart does not re-derive on its own until the rule store is
collected again.

**Do.** Decide per kind rather than for the class. If any kind is persisted,
keep it small and bounded, and accept the schema migration.

**Done when.** The file's reasoning names all three kinds and says which of
them survives a restart, whichever way it goes.

### T9 An event inbox, for a short list of events

**Evidence.** `BootEvents` and `ShortcutEvents` already record an event so that
an engine which did not exist yet can consume it. Both are volatile fields in
an object, bounded by a freshness window. `architecture.md:1000` states that
nothing is persisted. `ShortcutEvents.kt:14` says why the record is needed: a
tap "is not reliably cold or warm".

That reasoning is general. Any event can arrive at a process that is about to
die.

**Do.** Add a persisted inbox with an id, a time, a type, a payload and a
processed flag. Then use it for a short list only: boot, shortcut tap, alarm
fire, Bluetooth connect and disconnect.

**Do not** put notification or accessibility events in it. A phone posts
hundreds of notifications a day, and the accessibility bus drops events under
load on purpose, per `architecture.md:918`, because losing a stale UI event
beats losing the service. Persisting those would spend a write per event to
protect something nobody misses.

**Done when.** An event recorded before a process kill is acted on after the
restart, once, and not twice.

---

## Priority 3, and each needs a decision first

### T10 Self-registering modules

A new trigger touches one existing file, and `TriggerFactories.kt:15` says so
in as many words. For one application that is not a real cost. It becomes one
if third-party extensions are ever a goal.

**Decide first.** Are third-party triggers wanted? Nothing in the repository
says they are. Do not build the plugin interface before the answer is yes.

**If yes, keep this.** `ConfigSchemaContractTest` walks every registered
factory and checks it against its own declared schema. That test is what makes
one central list safe. A self-registering model must keep an equal check, or it
trades one line of coupling for a class of editor breakage that nothing sees.

### T11 A minimum app version in the rule format

`RuleJson` protects the file shape. It does not protect meaning. A rule that
names a trigger this build does not have still imports without complaint, and
then fails at `startRule` with `UnknownComponentException`.

Two things have already taken most of the weight off this item.
`onStartFailure` now reaches the screen as `RuleFault.Kind.COULD_NOT_START`, so
such a rule says it never started instead of looking merely quiet. And
`ComponentFactory.normalise` gives a component one place to say what an absent
config key means, which is the config half of the same compatibility problem.

So `minimumAppVersion` would only move the message from first run to import
time. That is worth something and it is not worth much. T2 is the strong half
of this item and T2 is cheap. Do T2, then judge whether this field still earns
its place.

### T12 Nothing tests the release build

`README.md:25` says it: the tests cover the debug build, and the release build
shrinks and renames code. R8 renaming is unexercised. This is already recorded
as deferred work after 0.0.1.

---

## Rejected

### R1 The scheduler is not a cure for force-stop

The review proposed that the operating system should wake the app, and that the
foreground service should become an optional improvement rather than the base
of correctness. The first half is right and it is T1.

The claim that this answers force-stop is wrong. A user force-stop puts the
package in the stopped state. Pending alarms are cancelled. Broadcasts skip a
stopped package until something starts it again. So no ingress design recovers
from a force-stop, and the limit at `architecture.md:899` stays true whatever
wakes the app.

Doze and a system-initiated kill are one problem. Force-stop is a second
problem with a different cause and no fix inside the app. Do not let T1 be sold
as covering both.

### R2 Detect a randomised Bluetooth address

There is no public Android API that says an address is a resolvable private
address. Absence in `android.jar` is proof, so settle this with `javap` before
anybody builds on it. Pairing remains the answer, and T7 is how the user hears
about it.

### R3 Split classic Bluetooth from Bluetooth LE in the model

Weak. The trigger already reads the classic profile broadcasts and has a
profile-proxy path with its own timeout. Which broadcast arrived mostly implies
the transport. Adding a user-visible split would ask the user a question the
app can usually answer.

### R4 The watchdog is not fixable inside Trigly

The review's finding is correct and it is the file's own finding.
`NotificationWatchdogTrigger.kt:36` says that the trigger "is only as alive as
Trigly is, and a dead watchdog reports all fine by saying nothing".

Nothing to build. One thing to add to the honesty: the watchdog polls, so T1
also decides how late its detection is after a Doze window.
