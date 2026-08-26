# To do

Work that is known to be needed and is not built yet. Each item states the
evidence, the change, and the condition that makes it done.

This file exists because an external review of the repository raised twelve
points. Nine of them were correct. Three were not, and they are recorded in
**Rejected** at the end, with the reason, so that nobody opens them again.

The trigger backlog is a different list. `docs/triggers.md` holds the triggers
that are not built yet, each with its API and its known traps. This file holds
the reliability and correctness work. The scheduler was first in both, and it
has landed, so the trigger list is no longer blocked on it.

**Landed** at the top holds what is done, as one line each. Two items in
Priority 1, T13 and T14, were not in the review at all: they are consequences of
what landed, and neither was visible from inside the item that caused it.

Priority 1 is work that makes a rule fire when it should. Priority 2 is work
that makes a rule explain itself. Priority 3 needs a decision before it needs
code.

---

## Landed

Kept as a list rather than as bodies, because this file holds what is left. The
identifiers stay because T4, T8, T11 and R1 point at them.

- **T1 The scheduler.** `AlarmScheduler` in `:core` is two suspend functions, a
  wait for a duration and a wait until an instant, with cancellation as the only
  cancel. `AlarmManagerScheduler` in `:triggers` implements it with `setWindow`
  and an `OnAlarmListener`, so it needs no `PendingIntent`, no receiver and no
  exact-alarm permission. All five waits moved onto it, the listener rebind
  included. See **T14**: `setWindow` is still deferred by Doze, so this bounds
  the lateness instead of removing it.
- **T2 Pin the component type strings.** 34 trigger strings and 20 action
  strings are held as literal text in JVM tests, and an instrumented test
  asserts each is still registered. A rename fails the suite. An addition does
  not, on purpose: forcing every new trigger to edit an existing test file is
  the coupling the project forbids.
- **T3 A bounded retry for a state nobody could read.** Up to three extra tries,
  two seconds apart. A leaf that answers on any of them fires the rule late and
  reports nothing. The give-up keeps the existing `UNDECIDED` outcome, because
  the first miss is no longer reported at all. See **T13**.
- **T5 Requirement liveness, as its own axis.** `Liveness` has three states, so
  "nobody has asked yet" cannot collapse into "dead", and the probe is an
  injected port that keeps `:core` away from `:triggers`. The rules list shows
  granted-but-not-bound as its own row, with "Check settings" rather than a
  "Grant" button for a setting that is already on.

---

## Priority 1

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

### T13 Bound the whole retry, not the gaps in it

**Evidence.** T3's `resolveHolds` bounds the waits between tries at two seconds
each and says in its own KDoc that the trade favours giving up past a few
seconds. It does not bound the reads. `location_check` has a 15 second read
budget of its own, so an area check that never answers costs four reads plus six
seconds of waiting, which is about a minute, and the rule's collector is blocked
for all of it. The code and its stated intent disagree.

**Do.** Bound the total time `resolveHolds` may spend, reads included, and
report the give-up when that budget is spent whichever way it ran out. A read
cancelled by the budget is a read that did not answer, which is the outcome the
retry already has a name for.

**Done when.** A leaf whose read takes longer than the budget reports the
give-up inside the budget, and a `TriggerEngineTest` case pins the total rather
than the number of tries.

### T14 Decide whether a wait must survive deep Doze

**Evidence.** T1 used `setWindow`, as T1 itself asked for, and
`AlarmManagerScheduler`'s KDoc is honest about the cost: only the
allow-while-idle family is exempt from Doze deferral, so a `setWindow` alarm
waits for the platform's next maintenance window. In deep Doze those are hours
apart, so an interval rule set to fifteen minutes fires every few hours.

`setAndAllowWhileIdle` is the inexact member of that family and needs no
permission at all. `SCHEDULE_EXACT_ALARM` is only for the exact one. The catch
is a rate limit of about one firing every nine minutes per app, and no
`OnAlarmListener` overload, so it needs a `PendingIntent` and a receiver.

**Decide first.** Which rules are worth that. A poll every minute cannot beat
the rate limit whatever API it uses, so the answer is probably per caller and
not global: a solar rule and an interval rule of ten minutes or more want it,
and a poll does not.

**Done when.** The choice is written down per caller, and any caller that keeps
`setWindow` says in its own warning text how late it can be.

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
of this item, and T2 has landed. So this field now buys only the move of the
message from first run to import time. Judge it on that alone.

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
