# Trigger catalogue

Every trigger Trigly intends to support, what it costs to build, and what it
needs from the user. Each entry names the concrete Android API so building it is
a mechanical change: a new file in `:triggers`, one line in `triggerFactories()`,
any permission declared in that module's manifest.

Requirements are declared in code as `ComponentRequirement` on the factory, so
the UI can explain a trigger before the user picks it and can explain a silent
rule afterwards. Keep the code and this document in step.

**Verification note.** Everything marked *implemented* compiles and its pure
logic is unit-tested. None of it has been exercised against real system
broadcasts yet. The emulator can fake some (`adb shell cmd`), but battery
temperature, NFC, and headset plug realistically need a device. Treat
"implemented" as "written and reviewed", not "proven on hardware".

---

## Cross-cutting blockers

Three gaps block whole groups below. They are worth fixing before picking off
individual triggers, because each one otherwise gets a private workaround.

**1. ~~No foreground service.~~** *Done.* `EngineService` in `:ui` hosts the
engine in a foreground service with an ongoing notification, started by
`TriglyApp` when any rule is enabled and by `BootReceiver` after a reboot or an
app update, and stopped by itself when no rule is enabled. Runtime-registered
receivers stay registered while it runs, which is what most Tier 1 broadcast
triggers depend on. It is not absolute: a force-stop or an aggressive OEM
battery manager still ends the process, and a runtime receiver ends with it.

That is why `bluetooth_connected` no longer uses one. Its ingress is a manifest
receiver, so the event arrives whether or not this service is alive, and the
event itself brings the service back. See "The Bluetooth ingress" below. Any
other trigger whose event can happen while the phone is idle wants the same
treatment, and can only have it if its broadcast allows a manifest receiver.
See `docs/architecture.md`.

**2. ~~No scheduler.~~** *Done.* `AlarmScheduler` in `:core` is the port; the
five places that used to wait with a coroutine `delay` now wait through it, so
none of them stops in Doze the way a plain `delay` did. See
`docs/architecture.md`'s scheduler section and `docs/todo.md`'s T1 for the
record and R1 for what it does not fix (a force-stop).

`AlarmManagerScheduler` in `:triggers` implements the port over `AlarmManager`,
using `setWindow` for every caller, since none of today's callers asks for an
exact time. `setExactAndAllowWhileIdle` needs `SCHEDULE_EXACT_ALARM` from API 31,
which Google restricts to alarm-clock-like apps at the `USE_EXACT_ALARM` tier,
so that path is deliberately not built until a caller actually needs it. Expect
drift of up to a few minutes: this blocker is resolved for the callers that
existed, not for wall-clock precision in general. Still *blocks*, because
nobody has built them yet: time of day, day of week, calendar, stopwatch.

The port has since grown a durable half, `waitForDurable`/`waitUntilDurable`,
because `docs/todo.md`'s T17 found that the plain form fixed the sleeping
phone and not the stopped app: `interval` and `solar` now use it, and a
future time-based trigger should ask whether its own wait needs the same
treatment. See `docs/architecture.md`'s scheduler section for the sourced
finding on what actually restarts the engine, and for why that finding is
about an ordinary alarm and not about `SCHEDULE_EXACT_ALARM`.

**3. ~~No permission-request flow.~~** *Done.* `RequirementChecker` evaluates
requirements against the device, the rules screen explains why an enabled rule
cannot fire, and a Grant button leads to the permission dialog or the right
settings screen. Requirements are re-checked on resume, since a grant made in
system settings reports nothing back to the app.

---

## Tier 1: implemented

| Trigger | Type string | Source | Requirement |
|---|---|---|---|
| Battery level threshold | `battery_level` | `ACTION_BATTERY_CHANGED` | None |
| Battery temperature | `battery_temperature` | `ACTION_BATTERY_CHANGED` | None |
| Power connected/disconnected | `power_connection` | `ACTION_POWER_CONNECTED`/`_DISCONNECTED` | None |
| Airplane mode | `airplane_mode` | `ACTION_AIRPLANE_MODE_CHANGED` | None |
| Wi-Fi radio on/off | `wifi_state` | `WIFI_STATE_CHANGED_ACTION` | None |
| Bluetooth radio on/off | `bluetooth_adapter_state` | `BluetoothAdapter.ACTION_STATE_CHANGED` | `BLUETOOTH_CONNECT` (API 31+) |
| Bluetooth device connects/disconnects | `bluetooth_connected` | `ACTION_ACL_CONNECTED`/`_DISCONNECTED`, via `BluetoothEvents` | `BLUETOOTH_CONNECT` (API 31+) to receive the broadcast at all |
| NFC on/off | `nfc_state` | `android.nfc.action.ADAPTER_STATE_CHANGED` | feature `android.hardware.nfc` |
| GPS on/off | `gps_state` | `PROVIDERS_CHANGED_ACTION` | None (only *reading* a location needs permission) |
| Screen on/off | `screen_state` | `ACTION_SCREEN_ON`/`_OFF` | None |
| Headset plugged | `headset_plug` | `ACTION_HEADSET_PLUG` (sticky) | None |
| Dark theme | `dark_theme` | `ACTION_CONFIGURATION_CHANGED` | API 29+ |
| Orientation | `screen_orientation` | `ACTION_CONFIGURATION_CHANGED` | None |
| Interval | `interval` | `AlarmScheduler` (was a coroutine delay, see blocker 2) | None |
| Charger type (USB/mains/wireless) | `charging_type` | `ACTION_BATTERY_CHANGED` → `EXTRA_PLUGGED` | None |
| Device restarted / app updated | `device_restart` | `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`, via `BootEvents` | `RECEIVE_BOOT_COMPLETED` |
| Sunrise / sunset | `solar` | calculated (NOAA), typed location | None |
| Time of day *(condition only)* | `time_window` | the clock | None |
| Home-screen shortcut tapped | `shortcut` | tap on a pinned launcher shortcut | None |

### Conditions: the same triggers, asked instead of watched

Most of these triggers can now also answer *"do you hold right now?"*, which is
what lets a condition be a trigger rather than a second component family. The
model, the capability matrix and the phasing are in `docs/conditions.md`; what
belongs here is what each one had to do to answer, because the answers were not
uniform.

Three ways a trigger reads its own level:

- **The sticky broadcast it already listens to.** `registerReceiver(null, filter)`
  returns the last broadcast immediately, which is how battery level,
  temperature, charger type, charger attached and headset answer. `power_connection`
  is the interesting one: its *own* broadcasts are edge-only and not sticky, so it
  reads `EXTRA_PLUGGED` off `ACTION_BATTERY_CHANGED` instead.
- **A manager.** Wi-Fi, Bluetooth adapter, NFC, GPS provider, airplane mode,
  auto-sync, dark theme, orientation, screen (`PowerManager.isInteractive`, since
  `ACTION_SCREEN_ON` has no sticky form).
- **A live read of something else's state.** The notification triggers ask the
  listener for what is posted now; `screen_content` walks the accessibility tree;
  `solar` computes both of today's bounds and answers "is it light"; `location`
  takes a single fresh position read instead of the continuous
  `requestLocationUpdates` its edge role holds open.

**Where the honest answer is null.** A trigger that cannot answer must say so
rather than return false. See `docs/conditions.md`. Four cases found while
implementing, each a place where false would have been a lie:

- **`bluetooth_connected` for classic audio.** `BluetoothManager.getConnectedDevices`
  covers GATT/LE only; a car head unit on A2DP/HFP never appears there even while
  connected, and bonded is not connected. Absence is therefore unknown, not
  disconnected. This was verified against `android.jar` rather than assumed.
- **`call_state` for anything but ringing.** `ANSWERED` and `OUTGOING` both read
  back as `CALL_STATE_OFFHOOK`, indistinguishable from any other mid-call moment;
  `ENDED` and `MISSED` describe something already over. Only `INCOMING` has a
  level, so the rest answer null.
- **`package_change` on API 30+.** Package visibility makes "genuinely absent" and
  "installed but filtered from us" raise the same `NameNotFoundException`. Below
  API 30 not-found is trustworthy and answers false; from 30 it answers null.
- **`app_foreground` beyond its lookback.** The usage-stats query walks a trailing
  window, so an app that has held the foreground longer than that, with nothing
  else in between, reads as "nothing foregrounded": the same staleness trade a
  cached location fix makes.

### Watching an area and checking one

`location` is the component whose two roles cost wildly different amounts.
Watching an area holds an open request for position updates for as long as the
rule is on, which is the most expensive thing this app can do to a battery.
Answering "am I in the area now" costs one fix, when something else asks.

A rule used to pay for the first whether it needed it or not. The engine collects
every leaf's `events()`, so a location leaf that a person only ever meant as a
condition in an `ALL` group still held the request open. The expensive behaviour
was the default and nothing on screen said so.

There are two picker rows now.

| Row | Type | Events | Cost |
|---|---|---|---|
| Enter or leave an area | `location` | yes | holds a position request while the rule is on |
| Is in an area | `location_check` | no | one position fix, when asked |

`Is in an area` declares `producesEvents = false`, and its `events()` returns an
empty flow before the location service is even looked up, so nothing on that path
can open a request. That pair has to agree: a type that declares no events and
produces some is a leaf the tree was told to ignore, and one that declares events
and produces none is a rule that waits forever.

**Two rows rather than one row with a switch, and the difference is not
cosmetic.** The first version of this was a "Only check, never watch" flag on the
watching component, which meant `canStart` had to ask about a whole
`ComponentSpec` rather than a type, since the answer depended on config. Worse,
the flag could be turned on *after* the leaf existed: a rule whose only trigger
was a location leaf became unstartable in place, which needed a save-time refusal
to catch. As a picker row none of that applies. `RuleEditorViewModel.triggerOptionsFor`
already derives what a slot may offer from `canStart`, so `Is in an area` is
simply absent from a slot where it would be the only trigger, exactly as
`time_window` is. The state cannot be reached rather than being caught late.

The save-time refusal stays, because an imported file can still describe a rule
that nothing can start.

The two share their config keys, so the ordinary "change this block's type"
carries the coordinates and the radius across when someone changes their mind.
The checking row drops one field, the interval between checks, because nothing
there is watching.

That swap also found a bug in `migrateConfig`, which decided what to carry from
`newFields.map { it.key }`. A field can own more than one key when the values are
one answer, and a `Coordinates` field owns the longitude as a companion. Swapping
the two rows kept the latitude and silently dropped the longitude. Migration now
asks `companionKeys()`, which the editor already used for drawing the same
fields.

### The one condition that is not a trigger at all

`time_window` has **no event stream**: its `events()` is empty and it can never
start a rule. It exists because asking is cheap where watching is not, and
because there is, today, no time *trigger* for it to be a passive form *of*.
Blocker 2 is resolved and a time-of-day trigger could now be built on
`AlarmScheduler`, but nobody has built it yet, so the gap this paragraph
describes stands until that trigger exists. Every other condition in this
document rides on a component that also fires; `time_window` is the one
component that is a condition and nothing else.

It is the one that changes what is possible today. A time *trigger* needs
`AlarmManager`, while a time *condition* needs only the clock, so "when the
doorbell rings, if it is between 22:00 and 07:00" works now. Boundaries are
inclusive start, exclusive end, so adjacent windows abut exactly; a window whose
start equals its end reads as "no restriction" rather than "never", which is a
judgement call recorded on the function itself. The wraparound case is a pure
function with eighteen tests, because 22:00-07:00 is where this kind of code
goes wrong.

A condition for "in an area" used to sit here too, as `location_check`, a
separate component that had an answer but no event stream, exactly like
`time_window`. That was wrong for a reason `time_window` does not share: unlike
time, an area already has a trigger (`location`), so the check did not need to
be a standalone component at all. It needed to be that trigger's passive form.
It has been folded in on that basis. What it costs to ask, rather than watch, is
recorded once, in `docs/conditions.md`'s "Passive-only checks" section, rather
than duplicated here next to a component it no longer is.

### Bluetooth disconnects can be debounced

A car head unit can flicker (disconnect, reconnect within seconds) and firing on
every raw edge makes rules thrash. `bluetooth_connected` gained an optional settle
time on the **disconnect** direction only (default 0, so existing rules are
untouched); connect stays immediate, because a connection that appears is real.

The mechanism worth knowing: during the settle window the trigger also listens for
`ACTION_ACL_CONNECTED` and cancels the pending emission when the device comes back.
That edge-based cancellation is what gives the debounce teeth, because for a
classic-profile device the state re-check *cannot* confirm a reconnect (see the
null case above). The re-check is a secondary safety net and fails **open**: if it
cannot verify, the disconnect is emitted, since suppressing a real disconnect
would make the rule silently never fire.

### The type string outlives the description

`bluetooth_connected` fires on connection *or* disconnection, chosen by a state
field, in the same shape as `power_connection`: two already-edge-shaped
broadcasts, only the chosen one registered, no state to deduplicate.

The type string still says `connected`, and stays that way. It is persisted in
every saved rule and every exported file, so it is an identifier rather than a
description; renaming it to match the behaviour would break exactly the thing it
names.

The migration is the part worth knowing about. `parseTarget` **errors on an
absent key**, so simply adding a `stateChoice` to a trigger that never had one
would make every rule saved beforehand throw at `create()`: the engine would
report a start failure and those rules would silently stop firing on update, which
is the loudest failure this app has no way to announce. `parseTargetOrDefault`
exists for that: absent means what the rule meant when it was written (here,
connect), while an unknown word is still an error, because a typo is a wrong
answer rather than an old one. Note that it defaults *stored data* and not the
form: the schema declares no default, so a new rule still prompts.

### A MAC address is not always an identity

`bluetooth_connected` can be narrowed by address *or* by device name, and the
name is not a convenience. A Bluetooth LE accessory rotates a resolvable private
address roughly every fifteen minutes, so a rule pinned to an address it once
advertised will quietly stop matching: the worst shape of failure this app has,
because the rule still looks correct.

Bonding is the platform's answer: the two ends exchange an identity-resolving key,
and the stack resolves a rotating address back to the device it paired with. Which
is why the editor's picker lists **paired** devices, and why pairing is the advice
for anything address-based. Classic Bluetooth (headsets, car audio, speakers)
uses a fixed public address and was never affected.

Two things stated plainly because they set the limits of the above. Whether
`ACTION_ACL_CONNECTED` always carries the *resolved* identity address for a bonded
LE device is stack- and OEM-dependent and **has not been measured here**: the
emulators have no Bluetooth radio, so this needs a real phone and a real
accessory, connected twice more than fifteen minutes apart. And
`BluetoothDevice.getAddressType()` (which would let the app *detect* a random
address and say so) is API 35 only. The `ADDRESS_TYPE_*` constants are API 31,
which makes it look older than it is; the method on `BluetoothDevice` is not.

The two filters are separate optional fields rather than a "match on…" choice.
They narrow independently, an absent one means "no opinion" exactly as everywhere
else in the schema, and no rule saved before the name filter existed needs
migrating.

Two behaviours are easy to get wrong and are handled centrally in
`BroadcastTrigger`/`StateTracker`: sticky broadcasts replay on registration (a
rule would fire just for being enabled), and `ACTION_BATTERY_CHANGED` /
`ACTION_CONFIGURATION_CHANGED` fire constantly for unrelated reasons (a rule
would fire hundreds of times). New broadcast triggers get both for free by
returning a `stateKey`.

### A connect that can arrive before the engine exists

`bluetooth_connected` used to register its own receiver, live, on collection,
the same way most broadcast triggers still do. That was wrong for this one
broadcast. `ACTION_ACL_CONNECTED` and `ACTION_ACL_DISCONNECTED` are sent by
the system whenever they happen, not only while Trigly is running, and the
system routinely kills Trigly's process for sitting idle. A phone reconnecting
to a paired device after a few idle hours is exactly the case where the
process is gone, so a receiver that only exists during collection hears
nothing, and nothing is ever reported. There is no crash and no log, because
the process that would have written one does not exist.

The fix is `BluetoothEvents`, and it takes the shape of `ShortcutEvents`
rather than `BootEvents`. A boot broadcast is always cold: `BOOT_COMPLETED`
starts the engine, so no trigger is ever collecting yet when it lands. A
Bluetooth connect is not reliably cold or warm. The engine is commonly
already running, in which case the trigger reading that device is already
collecting and wants the sighting delivered live. The system can just as
easily have killed the process, in which case the connect is what restarts
it, and the sighting lands before any trigger exists to read it.
`BluetoothConnectionReceiver`, a manifest receiver in `:ui`, records every
sighting and starts the engine, in that order, without checking whether any
rule cares first. `bluetooth_connected` then reads a pending sighting at
collection start for the cold case, and a live bus for the warm one, guarded
by a timestamp so the same sighting is never turned into two emissions.

One thing this case needs that neither `BootEvents` nor `ShortcutEvents` do:
some real Bluetooth accessories send the same edge for the same device twice,
several seconds apart, as a platform quirk rather than a second event.
`BluetoothEvents.record` drops a repeat like that before it reaches any
trigger, once, rather than asking every trigger to notice it on its own.

Starting the engine's foreground service from a manifest receiver is banned
from Android 12 unless the app is exempt. This case is exempt, and not by
accident: AOSP's Bluetooth stack puts the receiving app on a temporary
allowlist for exactly this broadcast before sending it (`RemoteDevices`
passes `TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED` with reason
`REASON_BLUETOOTH_BROADCAST` to the options on both ACL intents). The
platform names this as an intended use, not a gap Trigly happens to fit
through.

Only the two ACL actions are worth a manifest receiver. They are
`protected-broadcast` and marked to include background receivers, which is
why the system still delivers them to a killed app; the profile-level
broadcasts (`BluetoothA2dp`/`BluetoothHeadset` `ACTION_CONNECTION_STATE_CHANGED`)
have neither property, so a manifest receiver for those would simply never
fire. `CompanionDeviceManager.startObservingDevicePresence` is a second,
unbuilt ingress for device presence rather than the raw link, delivered to a
bound service instead of a broadcast.

What this does not fix: a user's force-stop puts the package in the stopped
state, and broadcasts skip a stopped package until something starts it again.
`BluetoothConnectionReceiver` is no exception. `docs/todo.md`'s R1 records the
same limit for every other wake-up path in this app; nothing here changes it.

### A tap that can arrive before the engine exists

`shortcut` is a home-screen launcher button: a custom label, an emoji icon chosen
from a picker, and a tap fires the rule. The icon is deliberately an emoji rather
than a URI or an image file: the same instinct behind the Test button and the
paired-device picker elsewhere in the editor, that a setting a user cannot type
correctly should be chosen, not typed. An emoji needs no storage permission,
renders identically regardless of density or theme, and cannot go stale the way a
path to a file the user later moves or deletes would.

Two things about it are not obvious from "a button that fires a rule."

**A trigger does not know which rule it belongs to.** Nothing in the `Trigger`
interface exposes a rule id, so the shortcut cannot address itself by one. It
carries a generated id in its own config instead (created once, when the trigger
is added to a rule, and baked into the launcher shortcut), so a tap can be routed
back to the trigger that owns it. The rule's *name* was the obvious alternative
and was rejected on sight: a name is editable text, and a shortcut addressed by
name would silently misfire, or stop firing, the moment someone renamed the rule
it belongs to.

**Tapping the shortcut can start the process the engine runs in**, which means the
tap can arrive before any trigger exists to hear it: the same problem
`device_restart` has with `BOOT_COMPLETED`, above. The fix is the same shape: the
activity the tap lands on records that it happened, rather than trying to listen
for it, and the trigger reads that record when it starts collecting a few
milliseconds later in the same process. This is the second trigger built on that
shape, which makes it worth naming as a pattern rather than a one-off: any future
trigger for an event that can precede the engine's own existence belongs on a
record read at collection time, not on a listener that assumes the engine is
already running to hear it.

It has **no passive form, and cannot grow one**: for the same reason `ui_click`
cannot. A tap, like a click, is inherently an instant: there is nothing to ask
"is it currently tapped" between taps. `docs/conditions.md`'s capability matrix
lists `shortcut` alongside `ui_click`, `sms_received`, `interval` and
`device_restart` as having none. There are no longer edge slots and condition
slots to place it in (a rule has one trigger, which may be a group), so the
constraint is now expressed by what the editor offers: `shortcut` can be the
thing that *starts* a rule, and an `all of` group may hold at most one such
component, since two instants never coincide. `TriggerNode.canStart` is what
computes that, and the picker follows it.

---

## Tier 1: remaining

### ~~USB vs AC charging~~: done

Built as `charging_type`, in `BatteryTriggers.kt` beside the two triggers that
read the same sticky broadcast. `EXTRA_PLUGGED` is matched as a **bitmask**, not
by equality: it is documented as flags, and equality would fall through to
"unplugged" on any device that ever set two at once: a rule that silently never
fires. The tracked state is the plug kind itself, so swapping USB for mains
without an unplug in between is still a change the trigger sees, and unplugging
re-arms it.

### Wi-Fi SSID
Not the same as the radio toggle. From API 31 read `WifiInfo` off
`NetworkCapabilities.getTransportInfo()` via a `ConnectivityManager` network
callback; below that, `WifiManager.getConnectionInfo()`. Either way the SSID is
location-derived data: **`ACCESS_FINE_LOCATION` is required from API 27**, and
without it the framework returns `<unknown ssid>` rather than failing: a silent
wrong answer, so check the permission and surface it.

### Mobile data changed
`CONNECTIVITY_ACTION` is deprecated (API 28) and not delivered to background
apps at all from API 26. Use `ConnectivityManager.registerNetworkCallback` with a
`NetworkRequest` for `TRANSPORT_CELLULAR`. No permission. Callback-based, so it
fits `callbackFlow` the same way `BroadcastTrigger` does: likely wants a sibling
base class, `NetworkCallbackTrigger`.

### ~~Device restart~~: done

Built as `device_restart`, and it is the one trigger that cannot wait for its own
broadcast. `BOOT_COMPLETED` is what *starts* the engine, so by the time any
trigger is collecting it has been delivered and gone. Registering a receiver for
it would be waiting for something that already happened.

So `BootReceiver` records the reason in `BootEvents` **before** it starts the
service, and the trigger reads that record when it is collected a few
milliseconds later in the same process. Nothing is persisted: the only question is
"did *this* process start because of a boot", and a new process is a new answer.
Two details carry the correctness. The record is **not consumed** by reading, so
two rules on the same trigger both fire. And it is bounded by a one-minute
freshness window, because the record outlives the moment: without the bound, a
rule toggled off and on at lunchtime would announce the morning's reboot.

The trigger's flow is single-shot: it emits at most once and completes, rather
than idling forever pretending it might fire again this process.

App update is the same shape of event (the process dies, nothing the user did),
so it is the same trigger with a second setting rather than a near-duplicate type.

Still out of scope, and still for the recorded reason:
`ACTION_LOCKED_BOOT_COMPLETED`. The rule database is credential-encrypted and
unreadable before first unlock, so supporting direct boot means moving storage,
not adding an action string.

### SIM change / roaming
There is no public SIM-state broadcast. Use
`SubscriptionManager.OnSubscriptionsChangedListener` for SIM changes and
`TelephonyCallback.ServiceStateListener` (API 31+; `PhoneStateListener` before)
for roaming. Both need `READ_PHONE_STATE`; reading `ServiceState` also needs
location permission on recent versions. Verify the exact combination on a device
before promising the trigger.

### Sensors: shake, flip, light, proximity
`SensorManager` with `TYPE_ACCELEROMETER` (shake and flip), `TYPE_LIGHT`,
`TYPE_PROXIMITY`. No permission. Shake is a magnitude threshold with debounce;
flip is better served by `TYPE_GRAVITY` than raw acceleration.

The real cost is power: a registered sensor listener keeps the CPU busy and does
not survive Doze. Use the slowest delay that works, and consider
`SensorManager.registerListener` with a batch latency so the SoC can sleep
between deliveries. These should probably be off by default and carry a warning
in the UI.

### ~~Time of day / day of week~~: done, type `time_of_day`

Built as `time_of_day`. It fires once at a chosen hour and minute, on
whichever days of the week are selected, so "every weekday at 8am" is one
rule. The day selection lives on the trigger itself, not on the standalone
day-of-week condition elsewhere in this file: a condition can only be asked
once something else has already woken the rule, so nothing wakes this one at
8am on a Tuesday if the day check lives outside it.

It copies `SolarTrigger`'s pattern: `AlarmScheduler.waitUntilDurable` for the
T17 reason, and `AlarmWakeEvents` read on a fresh collection so an occurrence
already due is found rather than skipped for next week's. The next
occurrence is always built from the hour and minute on a calendar date, never
from "now plus a day", which is what keeps a late firing from drifting later
on every fire, and what keeps a daylight-saving change correct: the same
local time resolves to a different UTC offset on either side of the
transition, instead of shifting by an hour.

Its zone is read fresh on every wait rather than fixed once, unlike
`SolarTrigger`'s typed place: this trigger has no place of its own, so "8am"
means "8am wherever this phone currently is". A live
`ACTION_TIMEZONE_CHANGED` receiver interrupts an in-flight wait, so a zone
change mid-wait is corrected at once instead of on the next firing. Boot
needs no extra code: every trigger is rebuilt fresh from its stored config
each time the engine starts, so the first pass through `events()` already
computes from a fresh clock.

### ~~Sunrise / sunset~~: done, scheduler caveat resolved

Built as `solar`, and the manual path is the one offered: the location is
**typed**, so the trigger needs no permission at all: no location access, no
network. Auto-detection (which would need `ACCESS_COARSE_LOCATION`) is still not
offered, deliberately.

`solarTime()` in `SolarTime.kt` is the NOAA calculation, pure and tested against
published times for Berlin, Sydney and Svalbard to within two minutes. Two things
it does rather than approximate: it returns a *reason* (polar day or polar night)
instead of a time on days when the sun genuinely does not rise or set, and it
takes the zone explicitly so a rule about a place you are not standing in is
still right.

**It used to share `interval`'s scheduling weakness; it no longer does.** The
wait is now `AlarmScheduler.waitUntilDurable`, not a plain coroutine `delay`
and not the plain listener form either: `docs/todo.md`'s T17 found that the
listener form fixed Doze but not a kill, since the alarm behind it dies with
the process holding it. `events()` was the single place that changed for T1,
as this section used to predict, and it is also the one place T17 changed a
second time, to search from a little before "now" once a durable wait's own
alarm has fired recently rather than searching from "now" and risking a
missed occurrence. Its warning still says drift of a few minutes is normal,
because the fix is not exact-alarm precision.

### Calendar event
Query `CalendarContract.Instances` with `READ_CALENDAR`. There is no "event
starting" broadcast: register a `ContentObserver` on the calendar URI to notice
edits, and schedule an alarm for each upcoming event's start. Re-query after
every observer callback, since events move.

### Stopwatch
No Android API: engine state plus a notification. Depends on the scheduler for
the elapsed-time callback.

---

## Tier 2: implemented

| Trigger | Type string | Requirement |
|---|---|---|
| Notification posted | `notification_posted` | Notification access |
| DND mode changed | `dnd_mode` | Notification access |
| An app's notification went missing | `notification_watchdog` | Notification access |
| UI element clicked | `ui_click` | Accessibility access |
| Screen content changed | `screen_content` | Accessibility access |
| Keyboard opened/closed | `keyboard_visibility` | Accessibility access (best effort) |
| App installed/uninstalled | `app_install_state` | None |
| App came to foreground | `app_foreground` | Usage access |
| Call incoming/outgoing/answered/ended/missed | `call_state` | `READ_PHONE_STATE`, API 31+ |
| SMS received | `sms_received` | `RECEIVE_SMS`, Play-restricted |
| Entered/left an area *(and, as a condition, currently in it)* | `location` | `ACCESS_FINE_LOCATION`, plus `ACCESS_BACKGROUND_LOCATION` from API 29 |
| In an area, checked and never watched | `location_check` | `ACCESS_FINE_LOCATION`, plus `ACCESS_BACKGROUND_LOCATION` from API 29 |
| Work profile available/unavailable | `work_profile` | None |
| Auto-sync changed | `auto_sync` | None |
| Clipboard changed | `clipboard_changed` | Platform-restricted |

### Watching another app stay alive

`notification_watchdog` exists for a specific and common need: making sure an
always-on app (an alarm, a pager, a monitor) has not been silently killed by
the system. It fires when that app's persistent notification has been missing
for longer than a configured window.

It is a *proxy*, and the reasoning behind it is worth keeping, because the
obvious approaches do not work:

- **You cannot ask whether another app's service is running.**
  `getRunningServices` has been own-app-only since Android 8,
  `getRunningAppProcesses` since Android 5, and `/proc` scraping has been
  blocked since Android 7. `getHistoricalProcessExitReasons` needs the `DUMP`
  permission for any package but your own. None of this is coming back.
- **An app running a foreground service must post an ongoing notification**:
  Android requires it. So the presence of that notification is the closest
  observable proxy for "the service is alive".
- **Presence must be polled, not listened for.** An ongoing notification is
  posted once and can sit there for days without another callback. Treating
  "no post event lately" as absence would fire constantly on a healthy app, so
  each tick asks what is currently active instead.

Three limits to state plainly:

1. **A blocked channel is invisible.** If the user set that notification's
   channel to importance "none", Android drops it before any listener sees it,
   and the watchdog cannot tell that from a dead service. It reports
   `never_seen` rather than a false alarm, and the fix is to set the channel to
   *silent* instead of blocked: still invisible to the user, still visible to
   the listener.
2. **Android 13+ lets users dismiss foreground-service notifications** while the
   service keeps running. That produces a false alarm, not a false all-clear,
   which is the right direction for a watchdog to fail.
3. **The watchdog is only as alive as Trigly.** A dead watchdog produces
   silence, which reads as "all fine": the one failure mode a watchdog must not
   have. The foreground service (blocker 1, now done) is what makes that
   unlikely rather than routine, and it was a prerequisite for trusting this at
   all. It is still not a guarantee: a force-stop, or an OEM battery manager
   that disregards the foreground-service promise, ends both apps at once.

For an app that posts *nothing* while healthy, there is no proxy at all and no
honest watchdog can be built. The remaining options there are the app's own
"run in background" setting, if it has one, and
`PowerManager.isIgnoringBatteryOptimizations` as a preventive check.

Three shipped with caveats that are load-bearing rather than cosmetic:

- **`call_state` is Android 12+.** The pre-31 `PhoneStateListener` must be built
  on a thread with a `Looper`, which does not fit a `callbackFlow` without a
  main-thread hop. Declared as `MinApiLevel(31)` so the UI says so rather than
  the trigger silently doing nothing. A pre-31 path is a fair follow-up.
- **`call_state` carries no caller number.** That needs `READ_CALL_LOG`, which
  Play restricts to default dialers. Rather than make the trigger unshippable
  for one payload field, it is simply not offered.
- **`clipboard_changed` mostly will not fire.** Since Android 10 only a
  foreground app, the default keyboard, or an accessibility service may read the
  clipboard. It is shipped for the accessibility-enabled case and declares the
  restriction so the UI can warn.

---

## Tier 2: remaining

### Geofencing and activity recognition
Both need `play-services-location`, which would make an open-source automation
app depend on Google Play Services and stop it working on de-Googled devices.
**Deliberately not added**: that is a product decision, not a technical one.

The `location` trigger above uses the platform `LocationManager` instead, so
area-based rules work today without Google. The trade is real: the Play
Geofencing API is batched and system-managed, so it costs far less battery than
an active location request, and activity recognition has no platform equivalent
at all. If Play Services is acceptable, the clean shape is a separate
`:triggers-gms` module so a de-Googled build can exclude it.

### Notification action buttons
Firing a notification's action `PendingIntent` is an *action*, not a trigger, and
belongs in `:actions` alongside `PostNotificationAction`. The listener service
already receives everything needed.

### SMS sent
There is no broadcast for outgoing SMS. It needs a `ContentObserver` on
`content://sms/sent`, and carries the same Play restriction as receiving.

---

## Tier 2: reference

### Notification listener
`NotificationListenerService`, bound via `BIND_NOTIFICATION_LISTENER_SERVICE`,
enabled by the user at `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`, a
settings screen, not a permission dialog, which is exactly the case
`ComponentRequirement.SpecialAccess` exists for.

- **Notification posted**: `onNotificationPosted`. Filter by package and by
  text extras.
- **Notification action button**: the posted `Notification.Action` carries a
  `PendingIntent`; firing it is an *action*, not a trigger, and belongs in
  `:actions`.
- **DND mode changed**: `onInterruptionFilterChanged`, available through the
  same service. Changing DND additionally needs `ACCESS_NOTIFICATION_POLICY`.

The service is long-lived and rebound by the system, so it is a natural place to
host the engine, worth considering as part of blocker 1.

### Accessibility service
`AccessibilityService` with `BIND_ACCESSIBILITY_SERVICE`, enabled at
`Settings.ACTION_ACCESSIBILITY_SETTINGS`. Gives screen content
(`TYPE_WINDOW_CONTENT_CHANGED`), UI clicks (`TYPE_VIEW_CLICKED`), and window
changes.

**Read this before building it.** Google requires accessibility-API use to be for
accessibility, prominently disclosed, and justified in the Play listing;
automation apps have been removed for this. It is also the most invasive
permission on the platform. For an open-source app distributed outside Play this
is a product decision, not a technical one: decide the distribution story first.

*Keyboard opened/closed* has no real API. It is inferred from window insets or
`TYPE_WINDOW_STATE_CHANGED`, and is unreliable across keyboards and OEMs. Treat
as best-effort or drop it.

### App installed / uninstalled
`ACTION_PACKAGE_ADDED` / `ACTION_PACKAGE_REMOVED`, runtime-registered. On API 30+
package *visibility* rules mean a `<queries>` element (or `QUERY_ALL_PACKAGES`,
which Play restricts) is needed to resolve details about other apps. The
broadcast itself still arrives.

### App started / stopped
`UsageStatsManager.queryEvents`, gated by the `PACKAGE_USAGE_STATS` special
access (`Settings.ACTION_USAGE_ACCESS_SETTINGS`). Poll-based (there is no push),
so expect seconds of latency and a battery cost proportional to poll frequency.
The accessibility service gives this in real time, at the cost above.

### Calls
`TelephonyCallback.CallStateListener` (API 31+; `PhoneStateListener` before) with
`READ_PHONE_STATE` covers ringing / off-hook / idle, from which
incoming/answered/ended follow. The **caller's number** needs `READ_CALL_LOG`,
which Play restricts to default dialer/assistant apps. Missed calls likewise come
from the call log. Design so the number is optional and the trigger degrades to
"a call came in".

### SMS sent / received
`RECEIVE_SMS` / `READ_SMS`. Play restricts these to the user's default SMS
handler; an automation app is very unlikely to be approved, and the restriction
survives user consent. Modelled as
`ComponentRequirement.PolicyRestricted` so a Play build can hide it while an
F-Droid or sideloaded build keeps it. **Decide the distribution story before
building this.**

### Location and geofence
`FusedLocationProviderClient` and the Geofencing API from
`play-services-location`, a Google Play services dependency, which matters if
Trigly targets de-Googled devices. Needs `ACCESS_FINE_LOCATION` plus
`ACCESS_BACKGROUND_LOCATION`, which the app already holds for the platform
location component. Note that from Android 11 the background grant has no
dialog: a request returns denied at once, and only the app's settings page can
grant it. It is also a Play review form.
Geofences cap at 100 per app and are dropped on reboot, so re-register on boot.

### Clipboard changed
`ClipboardManager.OnPrimaryClipChangedListener` exists, but from Android 10 an
app may only read the clipboard while it holds focus, is the default IME, or is
an accessibility service. A background automation app is none of these, so this
is **effectively not implementable** without the accessibility route. Recommend
dropping it rather than shipping something that works only while the app is open.

### Work profile
`ACTION_MANAGED_PROFILE_ADDED` / `_REMOVED` / `_AVAILABLE` / `_UNAVAILABLE`,
delivered to the primary user. No permission. Untested territory: verify on a
device with a work profile before claiming support.

### Auto-sync changed
`ContentResolver.addStatusChangeListener(SYNC_OBSERVER_TYPE_SETTINGS)`. No
permission, no service. **The cheapest Tier 2 entry**: arguably Tier 1.

### Activity recognition
`ActivityRecognitionClient` from `play-services-location`, with the
`ACTIVITY_RECOGNITION` runtime permission (API 29+). Same Play-services caveat as
geofencing.

---

## Suggested order

1. ~~Foreground service (blocker 1).~~ Done: `EngineService`.
2. ~~Scheduler (blocker 2).~~ Done: `AlarmScheduler` in `:core`, over
   `AlarmManager` in `:triggers`. `interval` and `solar` both wait through it
   now, and so does the notification-listener rebind repair and the two other
   poll loops; see `docs/todo.md`'s T1. `interval` and `solar` wait through
   its durable half specifically, added for T1's own gap that T17 found and
   closed: a wait bound only to a live process. It still unlocks the
   remaining time-based triggers below: time of day, day of week, calendar,
   stopwatch.
3. ~~Device restart.~~ Done: `device_restart`, via `BootEvents`.
4. ~~USB vs AC.~~ Done: `charging_type`.
5. Network callbacks (mobile data, Wi-Fi SSID), sensors, calendar.
6. Decide distribution, which gates whether the accessibility and SMS triggers
   that now exist can ship in a Play build, and now also the `specialUse`
   foreground-service subtype, which Google reviews.
