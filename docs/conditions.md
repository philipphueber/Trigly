# Conditions and gates

**Status: built, end to end.** `docs/actions.md` calls this the
largest single design decision left in the project and asks for it to have its own
document. This is that document. Decisions here were taken by Philipp on
2026-08-24; the reasoning and the constraints are written down so the
implementation does not have to rediscover them.

## What a rule is today, and what it becomes

Today: one trigger, a flat list of actions. There is no notion of a condition
anywhere in the model.

The decision: **a condition is a trigger, asked instead of watched.** A rule's
trigger side becomes a **gate**:

    Gate
      triggers     one or more edges — an OR at the first level. Required.
      conditions   an optional tree of checks that must hold when one fires

    ConditionNode
      Check(spec)        one trigger, asked for its current state
      All(children)      every child holds
      Any(children)      at least one child holds
      (a sub-gate is a nested All/Any — that is where grouping comes from)

**The first level is an OR of edges; everything below it holds levels.** A single
trigger is the common case and needs no wrapper, which is why `triggers` is a list
rather than a mandatory `Any` node — but a first-level OR gate can hold several
edge triggers, and then any one of them fires the rule. "When the charger goes in
*or* the headset goes in, if it is after 22:00" is one gate.

That split, rather than one tree of triggers, is the load-bearing idea: edges live
in `triggers`, levels live in `conditions`, and there is no position where the
wrong one would fit.

## The constraint that shapes everything: edges are not levels

A trigger is an **edge** — "the screen turned on", "a notification arrived". A
condition is a **level** — "the screen is on".

`screen_on AND bluetooth_connected` read as two edges is essentially never true,
because two events do not occur in the same instant. So a trigger used as a
condition needs a second, optional capability:

    interface Trigger {
        fun events(): Flow<TriggerEvent>

        /** Whether this holds *now*, or null when the question does not apply. */
        suspend fun currentlyHolds(): Boolean? = null
    }

Defaulted, so none of the existing triggers break and each opts in — the plugin
rule in `CLAUDE.md` stays intact.

**The gate's shape is what keeps edges and levels apart**, and this is the reason
to prefer it over a single tree of triggers. The first level takes edges; every
condition slot takes a level. It is not the editor's job to police the
distinction — the structure has no position where the wrong one would fit. An
earlier draft needed a list of tree shapes the editor had to refuse; that list is
gone, because those shapes are no longer expressible.

### Firing semantics

When **any** of the gate's triggers fires with event **E**, evaluate the condition
tree by asking each `Check` for its current state. If it holds, the rule fires
with **E** — the event carries whichever edge produced it. No conditions means it
always holds.

`ConditionNode.holds` is that evaluation and takes the state lookup as a
parameter, so it is testable with no device — which matters, because every mistake
in it produces a rule that either never fires or fires when it should not, and
both are silent.

**`null` must not read as true.** An unknown state is not a satisfied one. A check
that cannot answer, or that fails to read its state, does not hold; the
alternative is a rule that fires on a guess. `All` of nothing is true (there is
nothing to fail); `Any` of nothing is false (there is nothing to satisfy it).

## Which triggers can be conditions

| | Trigger slot | Condition slot |
|---|---|---|
| Sticky-broadcast and manager-backed triggers | yes | yes |
| Pure events | yes | only if a passive form exists — see below |
| Passive-only checks | no | yes |

The state-capable majority: Wi-Fi, battery level and temperature, charger type,
headset, screen, Bluetooth adapter and device, airplane mode, Do Not Disturb, GPS
provider, orientation, dark theme, app foreground, call state.

The pure events, which have no state to ask about as such: `notification_posted`,
`sms_received`, `ui_click`, `screen_content`, `interval`, `solar`,
`device_restart`, `package_change`, `clipboard`, `notification_watchdog`,
`location`, `shortcut`.

### A passive form is a different question, not the same one

Several of those twelve *do* have a meaningful passive form, because the state
version asks something related but distinct:

| Trigger | As an edge | As a condition |
|---|---|---|
| `notification_posted` | a notification arrived | one from that app is **currently showing** |
| `screen_content` | text appeared on screen | that text is **on screen now** |
| `solar` | at sunrise/sunset | it is **currently** after sunrise / after sunset |
| `package_change` | an app was installed | that app **is installed** |
| `notification_watchdog` | the notification vanished | the notification **is present** |
| `location` | entered or left an area | is **currently inside** the area |

`solar` is the striking one: the passive form is a pure calculation, so "is it
currently dark" costs nothing at all. `location` is close to the opposite
extreme — its passive form is not free, only far cheaper than the edge, because
asking for one position is a different operation from holding one open. The full
cost, and the correction that produced this row, are under "Passive-only checks"
below.

No passive form: `ui_click` (a click is inherently an instant), `sms_received` (we
keep no history), `interval`, `device_restart`, `shortcut` (a tap is inherently an
instant too).

### Grouped under one component, transparently

A component that supports both roles appears **once** in the picker. Which
question is being asked follows from the slot it is in: `solar` in the trigger
slot means "at sunset", the same `solar` in a condition slot means "it is after
sunset". Nobody has to learn which of two similarly named components to reach for,
and a rule reads the way it was meant: *when the doorbell rings, if it is dark and
I am at home.*

Negation is not a node. Most state-capable triggers already carry a two-word state
choice — `connected`/`disconnected`, `enabled`/`disabled` — so "if not charging"
is the check's own setting rather than a `Not` wrapper, which would need its own
editor affordance to express something already expressible.

## Passive-only checks

Some checks are impractical or impossible as triggers and trivial as conditions,
because a condition is *asked* rather than *watched*. **Passive-only is the
exception in this design, not the rule.** Almost every component that can answer
a condition is a trigger that also has an event stream, wearing its condition
role as a second, cheaper question about the same subject — that is what the
whole "passive form" table above is. Philipp's note names the one case where
there genuinely is no trigger to be the passive form of.

### Time of day

A time **trigger** needs `AlarmManager` — blocker 2 in `docs/triggers.md`, still
unbuilt, and the largest missing piece in the project. A time **condition** needs
nothing at all: read the clock when the gate is evaluated. `time_window` earns its
place in this section, alone, precisely because there is no time trigger for it to
be the passive form *of* — the scheduler that trigger would need does not exist.
Every other condition in this document is a slot on a component that also fires;
this is the one that is a component all by itself.

So "when the doorbell rings, if it is between 22:00 and 07:00, sound the alarm"
becomes expressible **without the scheduler**. This is the single largest thing
the gate model unlocks, and it arrives free.

Wraparound windows (22:00–07:00) are the obvious trap and belong in a pure
function with tests, alongside `solarTime`.

### Location is not one of these — it is a passive form, not a passive-only check

Location was, briefly, the mistake this whole document argues against. The build
had `location`, an edge-only trigger ("entered or left an area"), and
`location_check`, a condition-only component ("in an area") that could never fire
a rule — two names for one question, split exactly along the trigger/condition
line that a single component's slot is supposed to decide on its own. It is the
same failure mode "Grouped under one component, transparently" warns the *editor*
away from above, committed instead at the model level: `location_check` was never
an editor bug, it was a second component that should not have existed.

The fix is the one this document already applies to `solar` and
`notification_posted`: one `location` component, and the condition role is its
passive form, not a second component. The location **trigger**, used as an edge,
holds an active `requestLocationUpdates(GPS_PROVIDER, …)` for as long as the rule
is enabled, which is why its warning calls it expensive. The same component,
asked as a **condition**, holds nothing open — it reads a position once:

- `LocationManager.getCurrentLocation(...)` — one shot, API 30+.
- `getLastKnownLocation(provider)` — free, no hold, possibly stale. The fallback
  at `minSdk` 26, and the honest cost is staleness rather than battery.

Neither touches the Geofencing API, and neither keeps GPS awake. State the
staleness in the field's help: a cached fix can be minutes old, which is fine for
"am I at home" and wrong for "am I in the driveway".

**The reboot restriction still applies to the condition role, same as the edge.**
Per the boot-started-foreground-service note, a service started at boot is denied
location access for its whole life — so asking the passive form in a rule that
runs after a reboot reads nothing, and must therefore not hold. It is the same
silent failure the edge already has, which is exactly the point of folding them
into one component: one disclosure instead of two components each carrying half
of it, with no guarantee the user ever reads both.

## Storage

`Rule.trigger` becomes a `Gate`, which is a `RuleJson` version bump. Backward
compatibility is cheap and non-negotiable — saved rules must survive every update:

- An old rule's single trigger reads as a gate with no conditions.
- A gate with no conditions may be written back in the old shape, so a rule that
  never used conditions exports as it always did.

## Engine

Two changes, and the first is the only structural one in this design.
`TriggerEngine` currently collects exactly one flow per rule; with a first-level
OR it collects one per edge and merges them, still as a single job per rule so
that cancellation stays the stop button it is today. And the evaluation goes
between the event arriving and the actions running.

An earlier draft of this document claimed the engine was unchanged, on the
strength of one trigger per gate. That was wrong once the first level could hold
several edges — recorded here because "no engine change needed" is exactly the
kind of reassurance that gets planned around.

## Phasing

1. **Built.** The gate and condition model — including the first-level OR of
   edges — the capability seam, and the pure evaluation, with tests.
2. **Built.** Storage: database version 2, one nullable `conditionsJson` column,
   several edges as extra `TRIGGER` rows (which needed no migration).
3. **Built.** Engine: the first level's edges merged into one flow per rule, and
   the conditions evaluated before the actions run.
4. **Built.** `currentlyHolds()` across the state-capable triggers — twenty-four
   components now answer, and `docs/triggers.md` records how each reads its level
   and where the honest answer is null.
5. **Built, then corrected.** The passive forms (`notification_posted`,
   `notification_watchdog`, `dnd_mode`, `screen_content`, `solar`,
   `package_change`, and later `location`) and the one genuine passive-only
   check, `time_window`. This phase originally shipped a second passive-only
   check, `location_check` — a mistake caught and fixed after the fact: it was
   folded into `location` as that trigger's passive form rather than left
   standing as a component of its own. See "Passive-only checks" below.
6. **Built.** The editor speaks of **triggers**, not of a second thing called a
   "check." A rule's gate is one tree of **trigger slots** with AND/OR groups
   over them: the very first slot is the edge and accepts any trigger; every
   slot after it is a condition slot, filtered by `supportsCondition` so a
   component that cannot answer is never offered rather than offered and
   refused. An earlier build grew a separate "Only if" section with its own
   "add a check" affordance — a second vocabulary for the same underlying thing,
   which meant a user had to learn that a "check" is a trigger wearing a
   different hat before the screen made sense. That section is gone; grouping
   now happens under the same trigger tree, transparently, which is Philipp's
   own phrasing for the fix. Adding a sibling to a lone slot promotes it into a
   group and removing back to one un-promotes it, so a single trigger — and
   likewise a single condition — carries no AND/OR chrome.

Phase 1 was invariant to every later decision, which is why it went first — and it
held: nothing in phases 2–5 changed the model.

## What implementing it taught

Four triggers turned out to have no honest level for at least one of their
settings, and each is a place where returning false would have been a lie the
evaluator could not see through. They are listed in `docs/triggers.md` under
"Where the honest answer is null" — Bluetooth classic audio, call state beyond
ringing, package visibility on API 30+, and the foreground app beyond its lookback
window. That four of them exist is the argument for `null` being a distinct answer
rather than a tidy-looking `Boolean`.

One trigger had to grow a config field to have a passive form at all:
`package_change` fired on install or removal of *any* app, so "is that app
installed" had no subject. It gained an optional package filter, blank meaning the
old any-app behaviour, so existing rules are untouched.
