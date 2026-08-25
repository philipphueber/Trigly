# Is Android Auto active?

**Decision.** Ship a narrow, honestly-named trigger/condition for the one case
the platform actually supports without new dependencies: **wired** Android
Auto, detected as a USB accessory attach whose manufacturer/model identify it
as an Android Auto head unit. Do **not** build anything that claims to detect
Android Auto in general — wired and wireless together — today. The one API
that could (`androidx.car.app`'s `CarConnection`) is not on this project's
classpath, could not be verified in this environment, and needs a real head
unit before it is worth a new dependency. `UiModeManager`/car-mode detection,
the classic answer people reach for, is a **dead end** and must not be used:
everything the platform evidence points to says phone-projected Android Auto
never changes the host phone's own UI mode, so that API would answer "not
active" while Auto is plainly running on the dash in front of the driver —
exactly the silent lie this project's triggers are built not to tell. Where a
Bluetooth connection to a paired car is already usable as a trigger, it must
stay named for what it is — "a Bluetooth device is connected" — and never be
relabelled "Android Auto," because it is a materially weaker claim.

Android Auto is inherently a Google product (the phone-side app, or a GMS
component on newer Android). Every mechanism below except the raw USB
accessory identification degrades to "unavailable" on a de-Googled device, and
that has to be stated up front rather than discovered later, given the
audience `docs/architecture.md` says the rest of this project bends over
backwards for.

---

## What was checked, and against what

Per this project's standing rule, everything below is checked against
`/home/philipp/Android/Sdk/platforms/android-35/android.jar` with `javap`,
not recalled. Two things are explicitly *not* in that jar and could not be
checked that way: `androidx.car.app` (an AndroidX library, not part of the
platform) and the exact AOAP manufacturer/model strings Google's Android Auto
head units advertise (a specification detail, not a class member). Both are
flagged below as open questions with the experiment that would close them.

### `android.car.*` — absent, and rightly so

    unzip -l android.jar | grep -i "android/car"   →  zero matches

Confirmed empty. `android.car` is the Android **Automotive OS** API surface —
the OS a car's own head unit runs, a different product from the phone app
most people mean by "Android Auto." Conflating the two is the mistake this
investigation was told to watch for by name, and the jar settles it outright:
there is nothing here to call even if the intent were to target the wrong
product.

### `UiModeManager` / `Configuration.UI_MODE_TYPE_CAR` — public, but answers the wrong question

Both are real, public API:

```
public class android.app.UiModeManager {
  public static java.lang.String ACTION_ENTER_CAR_MODE;
  public static java.lang.String ACTION_EXIT_CAR_MODE;
  public void enableCarMode(int);
  public void disableCarMode(int);
  public int getCurrentModeType();
  ...
}
```

`Configuration.UI_MODE_TYPE_CAR` is likewise a public `int` constant. So the
classic answer compiles, needs no permission, and looks exactly like the
sticky-broadcast/manager-backed pattern this project already uses for Wi-Fi,
Bluetooth adapter state, airplane mode, and the rest. That is precisely why it
is dangerous: it looks right.

**The crux, and why the answer is no.** `enableCarMode`/`UI_MODE_TYPE_CAR` is
the API behind the old "Car Dock" UI — a physical dock accessory switching the
launcher, dating to early Android. The jar itself carries the fossils of that
feature next to the modern classes: `res/layout-car-v8/`, `res/drawable-car-v8/`,
`res/color-car-v8/`, and `Notification$CarExtender` (the pre-2018 car
notification extension Google's own Car App Library later replaced). None of
that machinery has anything to do with phone-projected Android Auto, which
works by rendering to a **virtual display** that gets cast to the car's screen
— the host phone's own `Configuration` is not the thing being changed, the
picture being sent to another screen is. A phone running Android Auto to a
head unit keeps showing its own home screen, its own launcher, its own UI mode
if you look at the phone directly; nothing about that architecture calls
`enableCarMode()` on the host.

This document could not put a phone through a real projected Android Auto
session and call `getCurrentModeType()` mid-session — that is the one
genuinely open empirical question here (see "What could not be settled"
below) — but the architectural reasoning, plus the platform's own vestigial
car-dock resources sitting right next to the modern APIs, both point the same
way: **do not build on this.** If it is ever tempting to reach for it, the
test that would prove it wrong is one Android Auto session away, and cheap to
run.

`ACTION_ENTER_CAR_MODE`/`ACTION_EXIT_CAR_MODE` are public broadcast action
*strings*, but they are only ever sent when something calls
`enableCarMode()`/`disableCarMode()` — the same crux applies, and if
phone-projected Auto never calls those methods, the broadcasts are never sent
either. This is a "wrong event for this scenario" problem, not a delivery
problem, so the broadcast-to-background-apps question this project usually has
to ask (blocker 1, `docs/triggers.md`) does not even get to matter here.

### The Bluetooth angle — a real proxy, but for a different, weaker claim

`BluetoothConnectionTrigger` (`triggers/src/main/kotlin/.../BluetoothConnectionTrigger.kt`)
already answers "is a Bluetooth device connected," including, per its own
`currentlyHolds()`, a classic-profile car head unit on A2DP/HFP. That is a
genuine, already-shipped signal — but it is not the same claim as "Android
Auto is active":

- **Wireless Android Auto** starts with a Bluetooth handshake (the head unit
  wakes the phone and negotiates), but the actual session runs over Wi-Fi
  Direct once established. A live Bluetooth connection to the car can
  therefore predate, outlast, or coexist with an Android Auto session that
  has not started, has already ended, or never starts at all (some cars pair
  Bluetooth for calls/media only and never launch Auto).
- **Wired Android Auto** does not use the classic Bluetooth profile at all —
  see the USB path below.
- A car's Bluetooth being connected is evidence the driver is *probably in the
  car*, not evidence that Auto is *projecting*. Using it as an Android Auto
  proxy would be exactly the kind of "three different claims, one label"
  conflation this investigation was asked to avoid.

**Verdict: keep it as what it already is.** `bluetooth_connected` stays a
Bluetooth trigger/condition. It is a legitimate, cheap ingredient in a rule a
user builds themselves — "if my car's Bluetooth is connected" is an honest
condition — but Trigly must not rename or repurpose it as "Android Auto
active."

### The USB angle — the one case with a solid, verifiable public API: wired Android Auto

Confirmed public and permission-free to *read*:

```
public final class android.hardware.usb.UsbManager {
  public static final String ACTION_USB_ACCESSORY_ATTACHED;
  public static final String ACTION_USB_ACCESSORY_DETACHED;
  public static final String EXTRA_ACCESSORY;
  public UsbAccessory[] getAccessoryList();
  ...
}
public class android.hardware.usb.UsbAccessory {
  public String getManufacturer();
  public String getModel();
  public String getDescription();
  ...
}
```

Wired Android Auto is built on the Android Open Accessory Protocol (AOAP): the
car head unit's chip negotiates as a USB accessory the phone can identify by
manufacturer/model strings, the same mechanism that lets the Android Auto app
auto-launch on plug-in via a manifest accessory filter. Reading
`UsbAccessory.getManufacturer()`/`getModel()` off `EXTRA_ACCESSORY` or
`getAccessoryList()` needs **no runtime permission at all** — that check is
only required to *open* the accessory's data connection (`openAccessory`),
which this trigger has no reason to do. This fits the existing plugin shape
exactly:

- **Edge:** `ACTION_USB_ACCESSORY_ATTACHED`/`_DETACHED`, the same shape as
  `headset_plug` and `bluetooth_connected` — one broadcast per direction,
  already edge-shaped, nothing to deduplicate.
- **Level:** `getAccessoryList()` at any time, the same "manager, asked
  instead of watched" pattern `docs/conditions.md` already uses for Wi-Fi and
  Bluetooth adapter state. `currentlyHolds()` returns a real `false` when the
  list is empty or contains no matching accessory — that answer is
  trustworthy, not an unknown, the same way `power_connection`'s state read
  is. `null` is only appropriate if the read itself fails.

**What could not be settled here: the exact match strings.** Google's AOAP
head units are documented to identify themselves with fixed manufacturer/model
strings so the phone's Android Auto app can auto-launch — but the literal,
case-sensitive values are a specification detail living in Google's AOAP
documentation and in the Android Auto app's own manifest, neither of which is
inspectable from `android.jar` or reachable from this offline environment.
**Before shipping this trigger, confirm the exact strings** either by reading
Google's published AOAP/Android Auto accessory-filter documentation (needs
network access this session did not have) or, better, by plugging a real
phone into a real Android Auto head unit and printing
`getAccessoryList()[i].manufacturer`/`.model` — the same category of
device-only fact `docs/triggers.md` already accepts it cannot settle from a
JVM test.

Even confirmed, state the claim precisely: this proves the phone negotiated
AOAP with a device that identifies itself as an Android Auto head unit — which
is, in practice, the moment the phone's Android Auto app takes over, but it is
"the accessory handshake happened," not "the user is looking at the Auto UI
right now." Close enough to be useful; not the same sentence.

### `CarConnection` (`androidx.car.app`) — the honest general answer, unverified here

This is the API the task most suspected, and the suspicion is well-placed: the
Car App Library ships a `CarConnection` class specifically so a non-car app can
observe projection state — distinguishing "not connected," "connected to a
head unit via projection" (phone-projected Android Auto), and "running
natively" (Automotive OS) — without the app becoming a car app itself. That
would be the one mechanism answering the actual question asked, for both wired
and wireless Auto, in one place.

**It could not be verified in this environment**, and that has to be said
plainly rather than papered over with recollection:

- `grep`ing `gradle/libs.versions.toml` and every `build.gradle.kts` in the
  repo confirms `androidx.car.app` is **not currently a dependency anywhere**
  in this project.
- There is no cached artifact for it anywhere on this machine (`~/.gradle`,
  `~/.m2` both come up empty), and this session has no network access to fetch
  one, so `javap` — the tool this whole investigation is built to trust over
  memory — has nothing to point at.
- That means every specific claim about `CarConnection` (its exact
  package/class name, whether the query needs a permission, whether it
  requires Google's Android Auto app to be installed to answer meaningfully,
  whether wireless is reported identically to wired, and what it returns for
  "unknown" versus "confirmed not connected") is presently **recollection, not
  verified fact**, and must be treated as such until someone checks it against
  the real artifact.

**What adding it would cost, assuming it holds up:** `androidx.car.app` is a
plain AndroidX library fetched from Google's Maven repository, not a Play
Services *runtime* dependency like `play-services-location` — nothing like the
Play-services caveat `docs/triggers.md` already declined geofencing over. It
would not, by itself, break a de-Googled build the way bundling
`play-services-location` would. It belongs in `:triggers` per
`docs/architecture.md`'s module boundaries (`:triggers` depends on `:core`
only otherwise) — a small, additive dependency, not a new module.

That said, the *feature* behind it is still Google-app-shaped regardless of
which Gradle repository serves the jar: Android Auto itself does not exist on
a phone with no Google apps, so on a de-Googled device this condition would
have nothing to observe and should read `null` (or a confirmed
`CONNECTION_TYPE_NOT_CONNECTED`, once verified which) rather than silently
appearing broken. That degrade-gracefully shape is exactly what
`ComponentRequirement` already exists to describe on the factory.

**The experiment that would close this:** before writing a line of code
against it, (1) confirm the artifact coordinates and inspect the decompiled
class to settle the questions above, and (2) run it on a real phone connected
to a real head unit — both wired and wireless — and again with Wi-Fi/Bluetooth
off entirely, to see what it reports for "definitely not connected" versus
"cannot tell." Only then does it earn a place next to the twenty-four
already-verified `currentlyHolds()` implementations in `docs/triggers.md`.

---

## Edge, level, or both

Android Auto is fundamentally ambient state — "is it on right now" — which
`docs/conditions.md` says means the *condition* form is what matters, and that
holds here too. Whichever mechanism eventually ships:

- **USB-accessory wired detection** is naturally both, in the same shape as
  every other sticky-broadcast/manager-backed component: `ATTACHED`/`DETACHED`
  as the edge, `getAccessoryList()` as the level. No config field needed
  beyond the match itself; no null case beyond a failed read.
- **`CarConnection`**, if it holds up, would give the level natively (its
  `LiveData<Integer>`/equivalent) and an edge by observing that value change —
  again both, the same "grouped under one component, transparently" shape
  `docs/conditions.md` prefers over a second, parallel component.
- **`bluetooth_connected`** already has both roles today and keeps them,
  unrelated to Android Auto.
- **`UiModeManager`/car-mode** is not being built at all, so the question does
  not arise.

Whatever `currentlyHolds()` a new component grows here must return `null`, not
`false`, whenever the answer is genuinely unavailable — no `CarConnection`
dependency present, the query unanswered, no Android Auto app installed to ask
in the first place. `docs/conditions.md`'s invariant is explicit that an
unknown state must never read as satisfied *or* as denied, and "Android Auto
is not installed" is exactly the kind of absence `bluetooth_connected`'s own
GATT/A2DP/HEADSET union already had to get right: two different situations
(genuinely not running, versus this method cannot see it) must not collapse
into the same observable answer.

## What this project should actually do next

1. **Do not build `UiModeManager`/car-mode detection.** The evidence available
   without a device says it will not fire during phone-projected Auto, and a
   trigger that silently never fires is worse than no trigger at all — the
   same principle `docs/architecture.md`'s `RequirementChecker.isPossible`
   is built around.
2. **Leave `bluetooth_connected` alone.** It already exists, already has a
   condition form, and should keep being described as what it detects — a
   Bluetooth connection — not relabelled as Android Auto.
3. **Wired Android Auto is buildable now**, cheaply, with a real public API
   and no new dependency and no runtime permission — gated on confirming the
   exact accessory manufacturer/model strings on a real head unit first.
4. **General (wired + wireless) Android Auto detection is a "not yet,"** not
   a "no." `CarConnection` is the right-looking answer and does not carry the
   Play-services distribution cost this project has already declined
   elsewhere — but nobody has yet looked at the actual class, and this
   investigation, run with no network access and no cached artifact, could not
   either. That is the one piece of homework standing between "promising" and
   "verified."

## What could not be determined here, and how to close it

- **Whether phone-projected Android Auto ever calls `enableCarMode()` on the
  host phone.** Reasoned against, from the projection architecture and the
  platform's own vestigial car-dock resources, but never observed directly.
  *Close it:* connect a phone to a real head unit (wired and wireless
  separately) and poll `UiModeManager.getCurrentModeType()` and register for
  `ACTION_ENTER_CAR_MODE`/`_EXIT_CAR_MODE` throughout the session.
- **The exact AOAP manufacturer/model strings for an Android Auto head unit.**
  Needed before the wired trigger can be trusted to match the right accessory
  and not a random USB peripheral. *Close it:* read Google's published AOAP
  documentation (network access this session lacked), or print
  `UsbAccessory.getManufacturer()`/`getModel()` from a real wired session.
- **Everything about `CarConnection`** — package, permission, behavior with no
  Android Auto app installed, and whether wireless reports identically to
  wired. *Close it:* fetch `androidx.car.app`, decompile or read its source,
  and test both connection types on a real head unit, exactly as this
  project's testing policy already asks for any trigger touching a real
  external device rather than an emulator.
