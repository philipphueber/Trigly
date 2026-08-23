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
so the release variant stays buildable from a plain clone; `docs/releasing.md`
covers the key, the version numbers, and how to verify the artifact.

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
permission flow around it, **28 triggers**, **18 actions**, a rules list screen
that explains why a rule cannot fire, and a **rule editor** — pick a trigger and
actions from grouped pickers, fill in a form generated from each component's
declared config schema, and save. Rules persist in a local database and survive
process death.

The pickers list only what the phone can actually run: a trigger needing an API
this Android version does not have, or hardware the device lacks, is not offered
at all. Each component's caveats are stated in full once it is chosen.

Rules run **in the background**, in a foreground service that starts at boot and
after an app update, and stops itself when no rule is enabled. Android shows an
ongoing notification while it runs, which is the deal: the app cannot watch the
device without the device saying so. Almost every trigger depends on this —
since Android 8 the system delivers most broadcasts only to a live process, so
without the service a rule would fire only while the app was open.

Rules can be **exported and imported** as versioned JSON, one rule or all of
them. That is the phone-switch story: Android's Auto Backup needs a Google
account and does not run on de-Googled devices, so an explicit file you own is
the only mechanism that always works — and it doubles as a way to share a rule
with someone else.

Triggers cover device state (battery, power, radios, screen, headset, theme,
orientation, location), apps and settings (install, foreground, work profile,
auto-sync), and the permission-gated ones (notifications, Do Not Disturb,
accessibility, calls, SMS, clipboard).

Actions cover notifying (notification, toast, speech, vibration), opening
(website, app), handing off to another app for the user to confirm (email, SMS,
alarm, calendar), device state (volume, ringer, clipboard, Do Not Disturb),
other apps' notifications (dismiss, press a button), and HTTP requests for
webhooks and home automation.

`docs/triggers.md` and `docs/actions.md` catalogue every trigger and action with
its Android API, required permission and known pitfalls — including the ones
deliberately not built, and the ones Android no longer permits at all.

**Privacy.** Trigly's accessibility service can observe screen content, and its
notification listener sees every notification. Nothing is stored, logged or sent
anywhere: events are matched on the device and discarded. Both services are inert
until you enable them in system settings, and nothing needs them unless you build
a rule that does.

Not yet implemented, and each has a `TODO` at the relevant place in the code:

- **Real scheduling.** The interval trigger uses a coroutine delay, which does
  not survive Doze. Time-of-day, sunrise/sunset and calendar triggers wait on it.
  Now the largest gap, and the next thing worth building.
- **Geofencing and activity recognition.** Would require Google Play Services;
  left out on purpose so the app works on de-Googled devices. The `location`
  trigger uses the platform API instead.
- **Background activity starts.** Since Android 10 an app in the background
  cannot open an app or a website — silently, with no error. The engine's
  foreground service does *not* lift this; running one is not on the platform's
  exemption list. The overlay permission is, so actions that open something stay
  unreliable until that is offered.
- **Conditions, variables and loops.** A rule is a trigger plus a flat list of
  actions. Anything more is an execution model, and the largest design decision
  still open; see the design note in `docs/actions.md`.
