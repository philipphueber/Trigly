# Conditions and trigger groups

**Status: design, not built.** `docs/actions.md` calls this the largest single
design decision left in the project and asks for it to have its own document.
This is that document. Decisions here were taken by Philipp on 2026-08-24; the
reasoning and the constraints are written down so the implementation does not
have to rediscover them.

## What a rule is today, and what it becomes

Today: one trigger, a flat list of actions. There is no notion of a condition
anywhere in the model.

The decision: **a condition is a trigger**, and triggers are composed into a
tree with AND/OR grouping. A rule's trigger side becomes:

    Any(children)   fires when any child fires
    All(children)   fires when one child fires and every other child holds
    Leaf(spec)      one trigger

`Any` at the root is "several triggers, OR'd" — the other decision taken at the
same time. `All` is what people mean by a condition: *when this happens, if those
are true*.

## The constraint that shapes everything: edges are not levels

A trigger is an **edge** — "the screen turned on", "a notification arrived". A
condition is a **level** — "the screen is on".

`screen_on AND bluetooth_connected` read as two edges is essentially never true,
because two events do not occur in the same instant. It only means something if
`All` evaluates the *state* the other leaf implies. So "a condition is a trigger"
requires a second, optional capability on a trigger: **can you tell me whether
you hold right now?**

    interface Trigger {
        fun events(): Flow<TriggerEvent>

        /** Whether this holds *now*, or null when the question does not apply. */
        suspend fun currentlyHolds(): Boolean? = null
    }

Defaulted, so none of the existing triggers break and each opts in — the plugin
rule in `CLAUDE.md` stays intact.

### Firing semantics

When leaf **L** fires with event **E**, evaluate the tree with `L = true` and
every other leaf as its `currentlyHolds()`. If the tree is true, the rule fires
with **E**.

That is one pure function over a tree and a state lookup, so it is testable with
no device — which matters, because every mistake in it produces a rule that
either never fires or fires when it should not, and both are silent.

**`null` must not read as true.** An unknown state is not a satisfied one. A
trigger that cannot answer, or that fails to read its state, makes an `All`
false; the alternative is a rule that fires on a guess.

## Leaf capabilities

Two independent capabilities, and a leaf may have either or both:

| Capability | Means | Without it |
|---|---|---|
| **fires** | has an event stream | cannot start a rule |
| **holds** | can answer "am I true now" | cannot sit in an `All` beside others |

Three kinds of leaf follow:

- **Both** — the useful majority. Backed by a sticky broadcast or a queryable
  manager: Wi-Fi, battery level and temperature, charger type, headset, screen,
  Bluetooth adapter and device, airplane mode, Do Not Disturb, GPS provider,
  orientation, dark theme, app foreground, location-in-area, call state.
- **Fires only** — pure events, with no state to ask about. "Is a notification
  currently being posted" has no answer. `notification_posted`, `sms_received`,
  `ui_click`, `screen_content`, `interval`, `solar`, `device_restart`,
  `package_change`, `clipboard`, `notification_watchdog`.
- **Holds only** — see below. These are new, and they are the reason this design
  is worth more than reusing triggers.

## Passive conditions: holds-only leaves

Some checks are impractical or impossible as triggers and trivial as conditions,
because a condition is *asked* rather than *watched*. Philipp's note names the
two that matter:

### Time of day

A time **trigger** needs `AlarmManager` — blocker 2 in `docs/triggers.md`, still
unbuilt, and the largest missing piece in the project. A time **condition** needs
nothing at all: read the clock when the gate is evaluated.

So "when the doorbell rings, if it is between 22:00 and 07:00, sound the alarm"
becomes expressible **without the scheduler**. This is the single largest thing
the condition model unlocks, and it arrives free.

Wraparound windows (22:00–07:00) are the obvious trap and belong in a pure
function with tests, alongside `solarTime`.

### Location — checked, not watched

The location **trigger** holds an active `requestLocationUpdates(GPS_PROVIDER, …)`
for as long as the rule is enabled, which is why its warning calls it expensive.
A location **condition** does not: it reads a position once, when asked.

- `LocationManager.getCurrentLocation(...)` — one shot, API 30+.
- `getLastKnownLocation(provider)` — free, no hold, possibly stale. The fallback
  at `minSdk` 26, and the honest cost is staleness rather than battery.

Neither touches the Geofencing API, and neither keeps GPS awake. State the
staleness in the field's help: a cached fix can be minutes old, which is fine for
"am I at home" and wrong for "am I in the driveway".

**The reboot restriction still applies.** Per the boot-started-foreground-service
note, a service started at boot is denied location access for its whole life — so
a passive location condition in a rule that runs after a reboot reads nothing and
must therefore make its `All` false, not true. Same silent failure as the
location trigger, and it needs the same disclosure.

### Where passive leaves may go

**`All` positions only.** A holds-only leaf in an `Any` can never start anything:
if nothing fires, there is no edge, and if something else fires then the `Any` is
already true. Offering it there would produce a leaf that looks meaningful and
does nothing, so the editor must not offer it.

## What the editor has to refuse

Every one of these produces a rule that can never fire — silently, permanently,
and indistinguishable from "it hasn't happened yet", which is the failure mode
this project keeps designing against.

1. **No fire-capable leaf anywhere.** Nothing can start the rule.
2. **A fires-only leaf in an `All` beside others.** `notification_posted AND
   sms_received` is never true.
3. **A holds-only leaf in an `Any`.** Not fatal, but useless — refuse it rather
   than draw it.

A `TriggerFactory` therefore declares its capabilities, so the editor knows
before instantiating anything — the same shape as `requirements` and
`configFields`.

## Storage

`Rule.trigger` becomes a tree, which is a `RuleJson` version bump. Backward
compatibility is cheap and non-negotiable — saved rules must survive every
update:

- An old rule's single trigger reads as a one-leaf tree.
- A one-leaf tree may be written back in the old shape, so a rule that never
  used grouping exports as it always did.

## Engine

`TriggerEngine` currently collects exactly one flow per rule. It will collect one
per leaf and merge them, tagging each event with the leaf that produced it, then
evaluate the tree before running the actions. The rest — one job per rule,
cancellation as the stop button, one action's failure not taking down the rule —
is unchanged.

## Phasing

1. The tree, the capability seam, and the pure evaluation, with tests. No UI, no
   storage change: nothing user-visible, and the part where the edge/level
   problem is actually solved.
2. Storage version and backward compatibility.
3. Engine: collect many leaves, evaluate on fire.
4. `currentlyHolds()` for the state-capable triggers — mechanical, one at a time,
   each independently testable.
5. The two passive leaves: time window and location check.
6. The nested AND/OR editor, with the three refusals above enforced.

Phase 1 is invariant to every later decision, which is why it goes first.
