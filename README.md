# Trigly

Open source automation app for Android. Native Kotlin + Jetpack Compose.

Rules are "when this trigger fires, run these actions". Triggers and actions are
plugin-style: each type is its own class behind a common interface, so adding one
does not touch the existing ones.

## Modules

| Module      | Holds                                                                  |
|-------------|------------------------------------------------------------------------|
| `:core`     | `TriggerEngine`, domain model, rule storage. No UI, no Compose.        |
| `:triggers` | Trigger implementations, one per type.                                 |
| `:actions`  | Action implementations, one per type.                                   |
| `:ui`       | Compose screens, ViewModels, and the app assembly point. Applies the Android application plugin. |

Dependencies point one way: `:ui` → `:triggers`/`:actions` → `:core`. Nothing
depends on `:ui`, and `:core` depends on nothing in the project.

See `docs/architecture.md` for why it is built this way.

## Building

Requires JDK 17 and an Android SDK with API 35 and build-tools 35.0.0. Point
`JAVA_HOME` at the JDK and put the SDK path in `local.properties`
(`sdk.dir=/path/to/Android/Sdk`) — that file is machine-specific and gitignored.

The Gradle wrapper is checked in, so no Gradle install is needed:

    ./gradlew test                        # JVM unit tests, all modules
    ./gradlew assembleDebug               # build the debug APK
    ./gradlew lint                        # Android lint
    ./gradlew connectedDebugAndroidTest   # instrumented tests, needs a device or emulator

## Adding a trigger

1. Implement `Trigger` and its `TriggerFactory` in a new file under `:triggers`.
2. Add one line to `triggerFactories()` in that module.
3. Declare any permission it needs in `triggers/src/main/AndroidManifest.xml`,
   next to the code that needs it.

Nothing in `:core` or in a sibling trigger should change. If it must, the
interface is wrong — fix the interface. Actions follow the same three steps in
`:actions`.

## Status

Early. Implemented: the engine, the plugin seams, the requirement model and the
permission flow around it, **27 triggers**, a post-notification action, and a
rules list screen that explains why a rule cannot fire.

Triggers cover device state (battery, power, radios, screen, headset, theme,
orientation, location), apps and settings (install, foreground, work profile,
auto-sync), and the permission-gated ones (notifications, Do Not Disturb,
accessibility, calls, SMS, clipboard).

`docs/triggers.md` catalogues every trigger with its Android API, required
permission and known pitfalls — including the ones deliberately not built, and
why.

**Privacy.** Trigly's accessibility service can observe screen content, and its
notification listener sees every notification. Nothing is stored, logged or sent
anywhere: events are matched on the device and discarded. Both services are inert
until you enable them in system settings, and nothing needs them unless you build
a rule that does.

Not yet implemented, and each has a `TODO` at the relevant place in the code:

- **Persistence.** Rules live in memory and are lost on process death.
- **Background execution.** The engine runs in the application scope, so it
  stops with the process — which currently makes *every* trigger unreliable, not
  just future ones. It needs a foreground service. This is the next thing worth
  building.
- **Real scheduling.** The interval trigger uses a coroutine delay, which does
  not survive Doze. Time-of-day, sunrise/sunset and calendar triggers wait on it.
- **Geofencing and activity recognition.** Would require Google Play Services;
  left out on purpose so the app works on de-Googled devices. The `location`
  trigger uses the platform API instead.
