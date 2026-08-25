# Conditions and gates

**Status: the model in this document shipped once, was rejected by the person
who has to use the app, and was rebuilt.** The rebuild is what is in `:core`
today: `TriggerNode` in `core/src/main/kotlin/app/phueber/trigly/core/Gate.kt`.
This document describes that model, not the one it replaced, but it keeps the
earlier design and the reason it did not survive, because a design note that
hides its own reversal is worth less than one that records it. Decisions here
were taken by Philipp; the reasoning and the constraints are written down so
the implementation does not have to rediscover them.

## What a condition is

**A condition is a trigger, asked instead of watched.** That sentence is the
one thing that survived the rebuild untouched, and everything below follows
from it. A trigger has an event stream, `events()`, that a rule collects to
start running. The same component can also be asked, right now, whether it
currently holds: `currentlyHolds()` on `Trigger`, defaulted to `null` so
nothing existing breaks and each component opts in. Asking that question of a
trigger is what a "condition" is. It is not a second kind of thing a rule can
have; it is a second question you can ask a trigger you already have.

## The design that shipped first, and why it did not last

The first implementation modelled a rule's trigger side as a **gate**, and the
gate as two separate parts:

    Gate
      triggers     one or more edges. An OR at the first level. Required.
      conditions   an optional tree of checks that must hold when one fires

    ConditionNode
      Check(spec)        one trigger, asked for its current state
      All(children)      every child holds
      Any(children)      at least one child holds

The reasoning at the time was that the split *was* the safety mechanism: edges
live in `triggers`, levels live in `conditions`, and there is no position where
the wrong one would fit: a component's role would follow from which half of
the gate it sat in, so the editor would need no rules of its own about which
component may go where.

That reasoning held up as a type-safety argument and failed as a design for a
person building a rule. The editor built on top of `Gate`/`ConditionNode` grew
a second region, captioned "Must also be true" (an earlier build called it
"Only if"), with its own "add a check" affordance next to the ordinary trigger
picker: a second vocabulary for the same underlying idea, because "a condition
is a trigger asked instead of watched" was true of the code and not of the
screen the code produced. Philipp rejected it twice. The second time, in his
own words: *"WHY IS CONDITIONS STILL NOT A CUSTOM TRIGGER. I DON'T WANT IT IN
THE MAIN UI GARBLED UP."*

The mistake was structural, not cosmetic, which is why redesigning the editor's
layout would not have fixed it. `Gate(triggers, conditions)` made a *shape*
distinction (required-OR-of-edges in one slot, optional-AND/OR-of-levels in
another) that nobody asked for. A person building a rule thinks in one tree:
"this, or that, and, while we're at it, only if this other thing is also true"
is one nested structure, not a structure plus a second, different structure
bolted beside it. Two slots is two vocabularies, and a user who has to learn
that a "check" is a trigger wearing a different hat before the screen makes
sense has been handed the abstraction's seams instead of the idea it was meant
to express.

## The model that replaced it

One tree, and a group is a trigger:

```kotlin
sealed interface TriggerNode {
    data class One(val spec: ComponentSpec) : TriggerNode
    data class Group(val op: Op, val children: List<TriggerNode>) : TriggerNode
    enum class Op { ALL, ANY }
}
```

`Rule.trigger` is a single `TriggerNode`, however deeply it nests:

    One(bluetooth)                                    // when the car connects
    Group(ALL, [One(bluetooth), One(time_window)])     // …and it is night
    Group(ANY, [One(charger), One(headset)])           // when either happens
    Group(ALL, [Group(ANY, [a, b]), One(time)])        // a sub-group, nested

A group is chosen from the same picker as any other trigger: "add a sibling"
is what turns a lone component into a group of two, and removing back down to
one un-promotes it, so there is no separate "add a check" control and no
second region for it to live in. `Gate` and `ConditionNode` are gone from the
domain model; there is one node type, and grouping is just a node holding other
nodes.

### Edges and levels are a property of a component, not of a slot

The distinction that mattered before (a trigger is an **edge** ("the screen
turned on"), a condition is a **level** ("the screen is on")) still matters,
because `screen_on AND bluetooth_connected` read as two edges is essentially
never true: two events do not occur in the same instant. What changed is
*where* that distinction lives. It used to be enforced by shape: the first slot
of a gate could only hold edges, so an edge in the wrong place was simply not
expressible. Now every leaf in the tree is a `ComponentSpec`, indistinguishable
by position, and the constraint is enforced by computing over the tree instead
of by the tree's shape:

- `TriggerNode.canStart(hasEvents, hasState)`: can this tree ever start a
  rule? A `One` can if its component produces events. An `ANY` group can if any
  child can. An `ALL` group can if *one* child can start and *every other*
  child can be asked for a state: one edge and any number of levels is the
  useful rule; a second edge under the same `ALL` is the mistake, because
  whichever edge fires, the other is asked for a state it does not have,
  answers unknown, and the group fails forever with no message.
- `TriggerNode.canHold(hasState)`: can this tree be asked for a state at all?
  A group can if every child can, whatever its operator.

The editor uses `canStart`/`canHold` to decide which components a slot may
offer, which is the one job the old gate shape used to do by construction. It
is a computed answer now rather than a structural guarantee, and that is a
fair trade: nothing at runtime needs the edge/level distinction at all.
`TriggerNode.holds` takes the path of the leaf that fired and reads every other
leaf as a level, so a component that cannot produce events simply never starts
a rule, and a component that cannot answer a state simply never satisfies one:
neither needs a special case, and neither needs the tree shaped a particular
way to be safe.

### Firing semantics

When any leaf's `events()` fires with event **E**, evaluate the whole tree:
the leaf that fired counts as true without being asked (it just happened, and
asking a component whether it *is* connected right after it reported
connecting would fail for anything momentary), and every other leaf is asked
for its current state through `currentlyHolds()`. If the tree holds, the rule
fires with **E**. Groups combine children the way the words say: `ALL` holds
if every child does, `ANY` if at least one does; `ALL` of nothing holds because
nothing failed, `ANY` of nothing does not because nothing satisfied it. The
editor cannot build either empty case; an imported file can.

`TriggerNode.holds` is that evaluation, and it takes the state lookup as a
parameter rather than a dependency, so it is testable with no device: every
mistake in it produces a rule that either never fires or fires when it should
not, and both are silent.

**`null` must not read as true.** An unknown state is not a satisfied one. A
component that cannot answer, or that fails while trying, does not hold; the
alternative is unattended actions running on a guess, which for this app is the
worse failure by a distance.

Leaves are identified by position in the tree (`NodePath`, the child indices
from the root), not by value, because two leaves can hold the same component
with the same settings: two `wifi_connected` leaves with different network
names, say. Comparing specs would mark both as fired at once, and in an `ALL`
group that is the difference between a rule that runs and one that cannot.

## Which triggers can be conditions

| | Can produce events (an edge) | Can be asked for a state (a level) |
|---|---|---|
| Sticky-broadcast and manager-backed triggers | yes | yes |
| Pure events | yes | only if a passive form exists: see below |
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

| Trigger | As an edge | As a level |
|---|---|---|
| `notification_posted` | a notification arrived | one from that app is **currently showing** |
| `screen_content` | text appeared on screen | that text is **on screen now** |
| `solar` | at sunrise/sunset | it is **currently** after sunrise / after sunset |
| `package_change` | an app was installed | that app **is installed** |
| `notification_watchdog` | the notification vanished | the notification **is present** |
| `location` | entered or left an area | is **currently inside** the area |

`solar` is the striking one: the passive form is a pure calculation, so "is it
currently dark" costs nothing at all. `location` is close to the opposite
extreme: its passive form is not free, only far cheaper than the edge, because
asking for one position is a different operation from holding one open. The full
cost, and the correction that produced this row, are under "Passive-only checks"
below.

No passive form: `ui_click` (a click is inherently an instant), `sms_received` (we
keep no history), `interval`, `device_restart`, `shortcut` (a tap is inherently an
instant too).

### Grouped under one component, transparently

A component that supports both roles appears **once** in the picker. Which
question is being asked follows from what the tree needs of it where it sits
(whether the leaf just fired, or is being asked for its state while another leaf
fired instead), not from a slot with its own label. `solar` fired as an edge
means "at sunset"; the same `solar` asked as a level, because some sibling leaf
fired instead, means "it is after sunset." Nobody has to learn which of two
similarly named components to reach for, and a rule reads the way it was meant:
one tree, *when the doorbell rings, if it is dark and I am at home.*

Negation is not a node. Most state-capable triggers already carry a two-word state
choice (`connected`/`disconnected`, `enabled`/`disabled`), so "if not charging"
is the check's own setting rather than a `Not` wrapper, which would need its own
editor affordance to express something already expressible.

## Passive-only checks

Some checks are impractical or impossible as triggers and trivial as
conditions, because asking a question is cheaper than watching for it forever.
**Passive-only is the exception in this design, not the rule.** Almost every
component that can answer a state also has an event stream of its own, wearing
the state question as a second, cheaper question about the same subject:
that is what the whole "passive form" table above is. One case genuinely has
no trigger to be the passive form *of*.

### Time of day

A time **trigger** needs `AlarmManager`: blocker 2 in `docs/triggers.md`, still
unbuilt, and the largest missing piece in the project. Asking "is it currently
between 22:00 and 07:00", by contrast, needs nothing at all: read the clock
when the tree is evaluated. `time_window` earns its place in this section,
alone, precisely because there is no time trigger for it to be the passive
form *of*: the scheduler that trigger would need does not exist. On the
factory this is `producesEvents = false`: `time_window` is the one component
that says so, which is what tells `canStart` it can never occupy the one edge
a group needs and tells the engine its `events()` is `emptyFlow()`: it
contributes nothing to a rule's merged event stream and is only ever read as a
level when some sibling leaf fires.

So "when the doorbell rings, if it is between 22:00 and 07:00, sound the alarm"
is expressible **without the scheduler**, as `Group(ALL, [doorbell, time_window])`
with no separate concept for the second half. This is the single largest thing
this model unlocks, and it arrives free.

Wraparound windows (22:00-07:00) are the obvious trap and belong in a pure
function with tests, alongside `solarTime`.

### Location is not one of these: it is a passive form, not a passive-only check

Location was, briefly, the mistake this whole document argues against. The
build had `location`, an edge-only trigger ("entered or left an area"), and
`location_check`, a state-only component ("in an area") that could never fire
a rule: two names for one question, split exactly along the edge/level line
that a single component is supposed to decide for itself depending on how it
is asked. It is the same failure mode "Grouped under one component,
transparently" warns against above, committed instead at the model level:
`location_check` was never an editor bug, it was a second component that
should not have existed.

The fix is the one this document already applies to `solar` and
`notification_posted`: one `location` component, and the state role is its
passive form, not a second component. The location **trigger**, used as an edge,
holds an active `requestLocationUpdates(GPS_PROVIDER, …)` for as long as the rule
is enabled, which is why its warning calls it expensive. The same component,
asked for its **state**, holds nothing open. It reads a position once:

- `LocationManager.getCurrentLocation(...)`: one shot, API 30+.
- `getLastKnownLocation(provider)`: free, no hold, possibly stale. The fallback
  at `minSdk` 26, and the honest cost is staleness rather than battery.

Neither touches the Geofencing API, and neither keeps GPS awake. State the
staleness in the field's help: a cached fix can be minutes old, which is fine for
"am I at home" and wrong for "am I in the driveway".

**The reboot restriction still applies to the state role, same as the edge.**
Per the boot-started-foreground-service note, a service started at boot is denied
location access for its whole life, so reading the passive form in a rule that
runs after a reboot reads nothing, and must therefore not hold. It is the same
silent failure the edge already has, which is exactly the point of folding them
into one component: one disclosure instead of two components each carrying half
of it, with no guarantee the user ever reads both.

## Storage

`Rule.trigger` is a single `TriggerNode`, stored as its own JSON column,
`RuleEntity.triggerJson`, via `RuleJson.encodeNode`/`decodeNode`. That is
database version 3.

The portable file format (`RuleJson`) has carried three shapes, and reads all
three so nothing exported by an earlier build is stranded:

- **v1** (0.0.1-0.0.3): `"trigger"` held a single component spec, the
  original one-trigger-only rule.
- **v2** (0.0.4, alongside v1): `"triggers"` (a list, an implicit OR) or
  `"trigger"` (one spec), plus an optional `"conditions"` tree of
  `check`/`all`/`any` nodes, the shipped `Gate`/`ConditionNode` shape this
  document used to prescribe.
- **v3** (this build): `"trigger"` holds a `TriggerNode` directly: a leaf is
  `{"type", "config"}`, a group adds an `"op"` key plus `"children"`.

Reading a v1 or v2 file folds the old two-part shape into the `TriggerNode` it
meant: the edges become `One` if there was exactly one, else
`Group(ANY, edges)`: the old model ran the rule when any edge fired; the
condition tree becomes the equivalent `Group(ALL, …)`/`Group(ANY, …)` of
`One`s; and if both were present, the result is `Group(ALL, [edgesNode,
conditionsNode])`: exactly ALL of the two halves, which is what the old
evaluator required before a rule could fire. The database side has the same
fold: a pre-version-3 row's `TRIGGER` component rows (in `ordinal` order) plus
its `conditionsJson` column are read back the same way, and the row heals
itself (starts writing `triggerJson` instead) the next time that rule is
saved. Neither legacy column is ever written again; both are read-only,
forever, because a device can still hold a row nothing has re-saved since
before version 3.

## Engine

`TriggerEngine.startRule` collects every leaf's `events()`, merged into a
single flow and collected once: one job per rule, however many leaves the
tree has, so two leaves firing together still run the rule's actions once
rather than concurrently, and cancellation stays the one stop button the
editor's Test button and `stopRule` rely on. A leaf that never produces
events (`time_window`'s `events()` is `emptyFlow()`) contributes nothing to
the merge and needs no special case; that is the payoff of
`TriggerFactory.producesEvents` existing as a declared fact rather than
something the engine has to infer.

Triggers are built once per distinct leaf `ComponentSpec`, not per event,
because any leaf may be asked for its state on any other leaf's fire, and
constructing a fresh instance each time would pay a factory's cost repeatedly:
for anything holding a resource, needlessly. When a leaf fires, `TriggerNode.holds`
is evaluated with a state lookup that treats a throw the same as a `null`
answer: unknown, not a definite no, because firing unattended actions on a
guess is the worse failure. Cancellation is rethrown rather than swallowed,
because a cancelled rule is not a rule whose trigger failed to hold.

## What implementing the underlying capability taught

Four triggers turned out to have no honest level for at least one of their
settings, and each is a place where returning false would have been a lie the
evaluator could not see through. They are listed in `docs/triggers.md` under
"Where the honest answer is null": Bluetooth classic audio, call state beyond
ringing, package visibility on API 30+, and the foreground app beyond its lookback
window. That four of them exist is the argument for `null` being a distinct answer
rather than a tidy-looking `Boolean`.

One trigger had to grow a config field to have a passive form at all:
`package_change` fired on install or removal of *any* app, so "is that app
installed" had no subject. It gained an optional package filter, blank meaning the
old any-app behaviour, so existing rules are untouched.

## Where this stands

The pieces below `:core` are built and tested against the model in this
document: `TriggerNode` itself, `canStart`/`canHold`, `holds`, the v1/v2/v3
storage fold, and the engine's per-leaf merge. The editor is being migrated to
match (one picker, no second region, adding a sibling promotes a leaf into a
group and removing back down to one un-promotes it), and that migration is
in progress rather than finished as of this note. Do not take this document as
proof the editor screen already matches it; if you are checking, read the
editor's own code rather than this paragraph.
