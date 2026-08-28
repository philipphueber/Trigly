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

T19, T20 and T21 were not in the review either. T19 is a consequence of
`run_rule`, and T20 and T21 are consequences of phase 5's writable scopes. All
three were found while writing the work down rather than by a failure.

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
- **The Bluetooth ingress.** A runtime receiver exists only while the engine's
  process does, so a connect that arrived after the process was gone reached
  nobody, and the fault log that would have said so died with it. A
  manifest-declared receiver for the two ACL actions now carries the event, the
  way `BootEvents` and `ShortcutEvents` already carry theirs. The stack puts the
  receiving app on a temporary allowlist for the foreground service start, per
  `Utils.getTempBroadcastOptions` in the Bluetooth module, so this is the
  intended path and not a loophole. See **T15** for a route that is not a
  broadcast at all.
- **The battery optimisation notice.** The app never asked to be excused and
  never checked. On a device whose manufacturer is aggressive about idle apps,
  that is the difference between an engine that survives the night and one that
  does not, and when it is killed every diagnostic dies with it.
- **T17 A durable wait.** `waitForDurable` and `waitUntilDurable` arm a
  `PendingIntent` alarm beside the listener one, and a manifest receiver records
  the wake and starts the engine. The listener form stays for a poll inside a
  live process. Two sourced findings came with it: only an *exact* alarm gets the
  platform's own temporary allowlist for a foreground service start, per
  `setImplLocked`, and an exact alarm needs an access Google reserves; but
  `isAllowlistedForFgsStartLOSP` passes any uid on the device-idle allowlist, so
  the battery exemption Trigly already asks for is what makes this work. On a
  device without it the wake is refused and the rule stays quiet, which is the
  same conditional the notice already states.
- **T13 Bound the whole retry.** One budget of twenty seconds now covers the
  reads and the waits together, chosen above the longest legitimate single read
  so it cannot cut off a position read that was going to answer. A read the
  budget cancels is reported as a component that could not answer, which needed
  `StateReader` to mark a leaf before asking it: a cancelled read never returns
  to report itself.
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

### T18 Wait for the audio route, do not guess at it

**Evidence.** `play_alert` can now play on the media route, which is what a rule
about a car wants. A Bluetooth rule fires on the ACL connect, and the audio
route arrives later: an ACL link, an A2DP link and an HFP link are three
separate events, established from AOSP while chasing the ingress bug. So the
sound can play a second before the car becomes the output, and it comes out of
the phone speaker instead. The rule looks like it worked and the person heard it
in the wrong place, which is the quiet kind of wrong this project keeps hunting.

**Do.** Let the action wait, briefly and with a bound, for the output it is
asking for. `AudioManager.AudioDeviceCallback.onAudioDevicesAdded` reports a
route appearing, and `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP` is the one that
matters for media. Neither needs a permission. A short wait is the whole fix: if
the route never appears, play anyway rather than swallow the sound, because a
sound in the wrong place still beats no sound from a rule the person built.

**Watch for.** The bound has to be small. This runs inside an action, and the
engine runs a rule's actions one at a time, so a long wait here delays
everything behind it. Also `TYPE_BLUETOOTH_SCO` is a different event from
`TYPE_BLUETOOTH_A2DP`: the first is call audio actually routed, which is later
again and not what a media sound waits for.

**Done when.** A rule that plays music on a Bluetooth connect is heard on the
device that connected, on two devices or API levels, and a rule with no such
device still plays without waiting the whole bound.

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

**Since this was written.** There is a sixth caller: the `delay` action waits on
`waitFor`, and it is the first one whose duration a person chooses rather than
this codebase. It already does the second half of "done when", because its
warning says the wait can be off by a few minutes. It has no claim on the first
half. Whether a wait a person set is worth the allow-while-idle family is
exactly the per-caller decision this item asks for, and `DelayAction`'s KDoc
says why it is not on the *durable* form, which is a different question from
this one.

### T15 A second ingress that is not a broadcast

**Evidence.** A user's Bluetooth connect rule never fired because no process was
alive to hold a runtime receiver. The manifest receiver fixes that, and it is
still a broadcast, so it still depends on the broadcast pipe reaching this app.
A survey of AOSP found nothing in the platform that would withhold the ACL
broadcast from a live process holding the grant, which leaves a manufacturer's
own battery layer as the remaining explanation, and that layer is not in AOSP
and cannot be reasoned about from source.

`CompanionDeviceManager.startObservingDevicePresence` with
`CompanionDeviceService.onDevicePresenceEvent` is a different pipe entirely.
`DevicePresenceEvent.EVENT_BT_CONNECTED` is documented in
`CompanionDeviceManager.java` as firing when a classic device connects, it
arrives through a **bound service** rather than a broadcast, and the permission
it needs, `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`, is
`protectionLevel="normal"`. The system binds the service, which is what revives
the process, exactly as the notification listener already does.

It also answers a question this project has open elsewhere. A companion
association is a durable, system-tracked identity for one device, so a rule
built on it does not care that an unpaired Bluetooth LE address rotates. That is
**T7**'s subject and **R2**'s dead end approached from a different direction.

**The cost is real.** An association needs the user to pick the device from a
system dialog, once per device, and the device needs
`FEATURE_COMPANION_DEVICE_SETUP`. So this is a second way to build a Bluetooth
rule, not a replacement for the first.

**Decide first.** Whether a trigger may ask for a one-time system dialog before
it works at all. Nothing in this app does that today, and it is a different deal
with the user than a permission row is.

**Done when.** Either the route is built for a device the user associates, and
the editor says plainly which of the two kinds of Bluetooth rule they are
making, or the decision against it is written down here with the reason.

### T16 Claim the connectedDevice service type when a rule needs it

**Evidence.** The engine claims `specialUse`, and adds `location` when the grant
allows it. Android 14 added `connectedDevice` for the case where a foreground
service exists to talk to an external device, which is exactly what the engine
is doing while it serves a Bluetooth rule. An app whose whole purpose is
reacting to Bluetooth connects, and which was studied for the ingress work,
declares its handoff service with that type.

**Do.** Decide whether the type follows the rules that exist, the way `location`
already does through `hasLocationGrant`, or whether `specialUse` is the honest
answer for an engine that does many unrelated things at once. The type is
claimed at runtime for a reason worth re-reading before touching this: from API
34 `startForeground` throws for a type the app holds no permission for, and that
is why `location` is conditional rather than declared.

**Watch for.** `FOREGROUND_SERVICE_CONNECTED_DEVICE` is another Play review
item, and the whole point of a typed service is that the type describes the
work. Claiming every type that might apply would make the declaration
meaningless.

**Done when.** The choice is recorded, and any type the engine claims is one it
can justify per rule rather than in general.

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

### T19 A rule picker that says which rule cannot be picked

**Evidence.** `run_rule` refuses a rule that runs itself, and it has to: see
`TriggerEngine.runNow` and `docs/variables.md` section 11. But the field that
chooses the target is `ConfigField.RuleRef`, the same field `set_rule_enabled`
uses, and it offers the open rule like any other. It even marks it, with "the
rule you're editing", because for `set_rule_enabled` picking yourself is a real
rule: a one-shot that fires and turns itself off.

So `run_rule` pointed at its own rule saves cleanly and then fails on every
firing. This is not silent, and that is why it is Priority 2 rather than
Priority 1. The fault log names the rule and says the run would never stop. It
is still a save the editor could have refused while the person was looking at
the field, which is what this project does everywhere else a reference cannot
resolve.

**Do.** A flag on `ConfigField.RuleRef`, declared by the factory that needs it
and defaulted off, saying that this field may not name the rule it is in. Then
the picker leaves the open rule out and save-time validation refuses it. Both
halves read the flag off the schema, so neither `:ui` nor `:core` learns that
`run_rule` is the action that wants it. Naming the action in either place is the
coupling the plugin rule forbids, and it is the reason this is not simply a
special case in the editor.

**Watch for.** A rule saved before this exists can already hold a
self-reference, so validation has to refuse it on the next save rather than
assume the picker prevented it. Also the *indirect* cycle stays a run-time
matter whatever this does: the editor cannot see that rule A runs B which runs
A, and the chain depth cap is what covers that.

**Done when.** The open rule cannot be picked in a `run_rule` action, a stored
self-reference is refused with a message naming the field, and
`set_rule_enabled` still offers the open rule.

### T20 Let an action declare the variable it writes

**Evidence.** `variableProblems` checks that every `{{...}}` reference names
something the rule offers, and it exempts all three writable scopes. Two of
those exemptions are principled: an app value or a rule value is very often read
by a field written before the field that sets it, and refusing that save would
make a pair of actions impossible to write in either order.

The third is not principled, it is a gap. A `{{local.*}}` name exists only
because some action earlier in the same run writes it, which is knowable in
principle and not knowable here: finding it would mean knowing that
`set_variable` is the action that writes, and that its `name` key holds the
name, and that its `scope` key decides which namespace. That is one component's
identity in a shared file, which the plugin rule forbids for the reason
`Rule.appVariablesRead` gives about the same temptation.

So a typo in a run-scope name saves cleanly and fails on the firing, and the
picker cannot offer run-scope names at all, which is the one place
`docs/variables.md` section 12's "exactly what is available" is not honoured.

**Do.** A declaration on `ComponentFactory`, beside `variables`: what this
component *writes*, as the config key holding the name and the config key
holding the scope. Then `availableVariables` can offer the run-scope names an
earlier action will write, and validation can be exact about them.

**Watch for.** The declaration has to describe a key, not a value. An action
whose name field holds `{{trigger.title}}` writes a name nobody can know before
the rule fires, and the honest answer for that case is to keep accepting it on
sight rather than to refuse a legitimate rule.

**Done when.** The picker offers a run-scope name written by an earlier action,
a misspelt one is refused at save time, and neither `:ui` nor `:core` names
`set_variable` to do it.

### T21 Hand-editing a rule-scope value

**Evidence.** The saved values screen lists rule-scope values and can delete
one, which covers the recovery path: a rule that wrote nonsense into its own
scope is fixable without deleting the rule. It cannot add or edit one, unlike
the shared scope.

**Decide first.** Whether this is wanted at all. Adding one needs a rule chosen
as well as a name, so it is a picker and a dialog, and the case for it is
thinner than for the shared scope: a rule-scope value exists because a rule
wrote it, and a person seeding one by hand is setting up a state that rule was
going to reach anyway.

**Done when.** Either it is built, or this item is moved to Rejected with the
reason.

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

### T12 No suite tests the release build

The tests run on the debug build, and the build people install shrinks and
renames code. R8 renaming is unexercised by anything automated. This was
recorded as deferred work after 0.0.1 and it is still open.

What has changed is that the gap is no longer unattended. `docs/releasing.md`
now holds a smoke test of the artifact, and it is a precondition for a tag: four
static checks of what R8 kept, launch, a reboot, one rule run through the UI,
and an in place upgrade from the previous published APK. That is what caught the
boot time loss of location, so it is not a formality.

So judge this item on what a suite would add over that, which is real but
narrower than it first looks:

- **Every** trigger and action, instead of the one or two a person exercises by
  hand. This is the strong half. `ConfigSchemaContractTest` already walks every
  registered factory, and it walks it in the debug build only.
- Repeatability, and a result that does not depend on who cut the release.

The cost is where it gets awkward. A connected run against the release variant
needs `testBuildType = "release"`, and then the test APK needs signing material
too, which a contributor without a key cannot supply. That is the same
constraint the signing section describes, and it argues for a separate opt in
variant rather than a change to what `connectedDebugAndroidTest` means.

Cheaper and worth more than it costs: assert the static half in Kotlin instead
of in shell. A JVM test can read `classes.dex` from the release APK and check
that every registered `type` string is still present, which is check 2 of the
smoke test and the one failure that would break every saved rule at once.


### T22 `round`'s second argument is not bounded

`round(number, places)` reads `places` with `intValueExact`, and nothing checks
the result. Two consequences, found while writing `docs/expressions.md` and
neither of them is what the language promises:

- `round(1.234, 1.5)` throws `ArithmeticException("Rounding necessary")`, which
  is not an `ExpressionError`, so it escapes `evaluateExpression`. The engine's
  per-action `catch (t: Throwable)` turns it into "This action threw
  ArithmeticException. Rounding necessary". Nothing breaks, and the message
  names Java rather than naming the field, which every other mistake in this
  language avoids.
- `round(1.5, 20000000)` succeeds. It builds a `BigDecimal` with twenty million
  digits, which took 9 seconds on a desktop with a 256 MB heap. A larger number
  is worse. `Expression.kt`'s own safety section claims "every expression does a
  bounded amount of work and returns", and that claim is what this breaks: the
  bound exists, but it is set by heap size rather than by the language.

The fix is small and belongs with the other bounds: read `places` as a whole
number and refuse anything outside a sane range, with an
`ExpressionOutcome.Failed` naming the argument. A range like -6 to 12 covers
every use a person reading a value has. It is deliberately not urgent: the
damage is a slow action and an ugly message, not a wrong value or a crash, and
only a hand-written expression can reach it.

One more reason to fix it, added when `contains` gained a regex mode. That
mode's bound is a rate on the length of the text searched, and the argument for
why a whole *evaluation* stays bounded is that all the text every search can
look at comes out of the same 2000 characters of source. `round` with a huge
`places` is the one thing that breaks that argument, because it returns more
text than it was given. The regex bound has an absolute ceiling as well as a
rate, so a single search is bounded whatever feeds it, and nothing here is
exploitable today. It is the composition argument that is untidy while this
stands.

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

### R5 A generic timeout on `TriggerEngine.run`'s call to `action.execute`

The review asked for one timeout wrapped around every action, on the grounds
that `TriggerEngine.run` calls `action.execute(event)` with no bound at all.

Rejected for two reasons.

`withTimeout` cannot interrupt a blocking platform call. A thread stuck inside
one keeps running after the coroutine around it is marked cancelled, because
the thread never checks. The timeout would report a failure to the engine
while the thread stayed stuck, which is a false sense of safety, not a fix.

No single number fits every action. `delay` waits on purpose, for as long as
the person who built the rule asked for, up to an hour. Everything else wants
a bound of seconds. A number big enough for `delay` is not a bound on
anything else, and a number small enough for everything else would cut
`delay` short. `delay` would then need an exemption from the engine's own
timeout, and carving out one component's behaviour inside the engine is the
same mistake `CLAUDE.md` already names: adding a component must not mean
editing an existing one.

What was done instead: each action bounds its own waiting, stated as the
contract on `Action.execute` in `:core`. `PlaySoundAction` and
`PlayAlertAction` were the two actions this review found that did not follow
it, both for the same call: `MediaPlayer.prepare()` is synchronous blocking
I/O, so cancelling either action's rule could not free the thread until
`prepare()` returned on its own. Both now use `prepareAsync()` with a
suspension a fifteen-second `withTimeout` can actually cancel, and a real
cancellation now reaches both. The wait is shared between them as
`awaitPrepared` in `:actions`, since the bridge from the platform's callback
to a suspension is identical; only the timeout's own reasoning differs, since
`PlayAlertAction`'s default tone is a local resource and its custom sound is
the part actually at risk.
