<img src="docs/branding/trigly-mark.svg" alt="Trigly logo" width="88">

# Trigly

Open source automation app for Android. Native Kotlin + Jetpack Compose.

Rules are "when this trigger fires, run these actions". Triggers and actions are
plugin-style: each type is its own class behind a common interface, so adding one
does not touch the existing ones.

## Modules

| Module      | Holds                                                                  |
|-------------|------------------------------------------------------------------------|
| `:core`     | `TriggerEngine`, domain model, rule storage (Room), portable JSON.     |
| `:triggers` | Trigger implementations, one per type.                                 |
| `:actions`  | Action implementations, one per type.                                   |
| `:ui`       | Compose screens, ViewModels, and the app assembly point. Applies the Android application plugin. |

Dependencies point one way: `:ui` → `:triggers`/`:actions` → `:core`. Nothing
depends on `:ui`, and `:core` depends on nothing in the project.

See `docs/architecture.md` for why it is built this way.

**Theming.** Every colour in the app is in `ui/src/main/kotlin/app/phueber/trigly/ui/Palette.kt`
— tonal ramps, then the light and dark scheme assembled from them. Change the hue
there and the whole app follows. (The two window-background hexes in
`res/values*/colors.xml` must be kept in step; that file explains why they cannot
be read from Kotlin.)

## Building

Requires JDK 17 and an Android SDK with API 35 and build-tools 35.0.0. Point
`JAVA_HOME` at the JDK and put the SDK path in `local.properties`
(`sdk.dir=/path/to/Android/Sdk`) — that file is machine-specific and gitignored.

The Gradle wrapper is checked in, so no Gradle install is needed:

    ./gradlew test                        # JVM unit tests, all modules
    ./gradlew assembleDebug               # build the debug APK
    ./gradlew lint                        # Android lint
    ./gradlew connectedDebugAndroidTest   # instrumented tests, needs a device or emulator

A release build needs a signing key, which is not in the repository. Without
one `./gradlew :ui:assembleRelease` still runs and produces an unsigned APK,
so the release variant stays buildable from a plain clone. A maintainer makes
one with `./scripts/setup-signing.sh`, run in a real terminal — it asks for a
password once, keeps it in the system keyring rather than in a file, and leaves
nothing else to do. `docs/releasing.md` covers the key, the version numbers,
and how to verify the artifact.

## Adding a trigger

1. Implement `Trigger` and its `TriggerFactory` in a new file under `:triggers`.
2. Add one line to `triggerFactories()` in that module.
3. Declare any permission it needs in `triggers/src/main/AndroidManifest.xml`,
   next to the code that needs it.
4. On the factory, declare `displayName`, `category` and `configFields` — a
   `ConfigField` per setting your `create()` reads. That is what the rule editor
   renders a form from; without it your trigger exists but cannot be configured.
   `ConfigSchemaContractTest` fails if you skip it.

Nothing in `:core` or in a sibling trigger should change. If it must, the
interface is wrong — fix the interface. Actions follow the same four steps in
`:actions`.

## Status

Early. Implemented: the engine and the **foreground service** that keeps it
alive, the plugin seams, the requirement model and the
permission flow around it, **33 triggers**, **20 actions**, a rules list screen
that explains why a rule cannot fire, and a **rule editor** — pick a trigger and
actions from grouped pickers, fill in a form generated from each component's
declared config schema, and save. Rules persist in a local database and survive
process death.

A rule is no longer just one trigger and a flat list of actions. Its trigger
side is a **gate**: one or more edges that start it, and an optional tree of
**conditions** — AND/OR groups of the same triggers, asked instead of watched —
that must also hold for the actions to run. Most triggers can answer both ways;
a single component appears once in the picker and the slot decides which
question is being asked of it, so "when the doorbell rings, if it is dark and I
am at home" is one gate rather than a separate feature bolted beside triggers.
`docs/conditions.md` is the design note for this. Time-of-day is the one
genuinely passive-only check, because there is no time trigger yet for it to be
a form of; "in an area" briefly existed as a second, standalone component before
being folded into the `location` trigger as its passive form, which is what the
"appears once" rule above actually means.

A **notification inspector** screen shows what a notification actually looks
like from inside the app — package, title versus text, flags, and each button's
real label — because a notification rule is otherwise written against values
nobody can see, and a typo there fails silently. The same principle produced the
button-capture picker: a notification action button is chosen by tapping it on
a live notification rather than counted by position, since a position shifts
under a rule that keeps working right up until it doesn't.

The pickers list only what the phone can actually run: a trigger needing an API
this Android version does not have, or hardware the device lacks, is not offered
at all. Each component's caveats are stated in full once it is chosen.

Settings you could not type are chosen from a list — an app, a sound, a paired
Bluetooth device — and each action has a **Test** button that runs it there and
then, so a sound is picked by ear rather than by URI. Pressing Test again stops a
running one.

Rules run **in the background**, in a foreground service that starts at boot and
after an app update, and stops itself when no rule is enabled. Android shows an
ongoing notification while it runs, which is the deal: the app cannot watch the
device without the device saying so. Almost every trigger depends on this —
since Android 8 the system delivers most broadcasts only to a live process, so
without the service a rule would fire only while the app was open.

Actions that **open** something — a website, an app, a pre-filled email — work
from the background too, which on modern Android takes more than calling
`startActivity`: the system silently drops such a start unless the app holds
"Display over other apps". Trigly asks for that permission and draws nothing
with it; it is there purely so a rule can put something on screen when you are
not already looking at the phone. Grant it from the rule that needs it. Without
it those actions still work while you are using the device, and the editor says
so rather than pretending.

Rules can be **exported and imported** as versioned JSON, one rule or all of
them. That is the phone-switch story: Android's Auto Backup needs a Google
account and does not run on de-Googled devices, so an explicit file you own is
the only mechanism that always works — and it doubles as a way to share a rule
with someone else.

Triggers cover device state (battery, power, radios, screen, headset, theme,
orientation, location), apps and settings (install, foreground, work profile,
auto-sync), a home-screen shortcut you tap yourself, and the permission-gated
ones (notifications, Do Not Disturb, accessibility, calls, SMS, clipboard).

Actions cover notifying (notification, toast, speech, vibration), opening
(website, app), handing off to another app for the user to confirm (email, SMS,
alarm, calendar), device state (volume, ringer, clipboard, Do Not Disturb),
other apps' notifications (dismiss, press a button), turning one of your own
rules on or off, and HTTP requests for webhooks and home automation.

`docs/triggers.md` and `docs/actions.md` catalogue every trigger and action with
its Android API, required permission and known pitfalls — including the ones
deliberately not built, and the ones Android no longer permits at all.

**Privacy.** Trigly's accessibility service can observe screen content, and its
notification listener sees every notification. Nothing is stored, logged or sent
anywhere: events are matched on the device and discarded. Both services are inert
until you enable them in system settings, and nothing needs them unless you build
a rule that does.

Not yet implemented, and known limits stated plainly rather than left for a user
to discover — each of the former has a `TODO` at the relevant place in the code:

- **Real scheduling.** There is no scheduler. The interval trigger uses a
  coroutine delay, which does not survive Doze, and the sunrise/sunset trigger
  inherits the same weakness while it waits on this. Time-of-day and calendar
  triggers cannot exist at all without it. Now the largest gap, and the next
  thing worth building.
- **Geofencing and activity recognition.** Would require Google Play Services;
  left out on purpose so the app works on de-Googled devices. The `location`
  trigger uses the platform API instead.
- **Variables and loops.** Conditions shipped — see above — but a rule still
  cannot carry state between firings or repeat an action a computed number of
  times. That remains an open execution-model question; see the design note in
  `docs/actions.md`.
- **Location goes silent after a reboot.** A service started at boot is denied
  location access for the rest of its life on current Android, which is a
  platform restriction Trigly cannot route around. The `location` trigger, and
  the same component's passive form when it is used as a condition, both read
  nothing in a rule that has not run since the device last restarted — and there
  is no warning for it, because the app has no way to notice a denial it never
  gets a chance to check.
