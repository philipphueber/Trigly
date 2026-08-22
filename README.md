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

The Gradle wrapper JAR is **not** checked in yet. Generate it once, with Gradle
8.9 or newer installed:

    gradle wrapper

After that, `./gradlew` works and the usual commands apply:

    ./gradlew test                    # JVM unit tests, all modules
    ./gradlew assembleDebug           # build the debug APK
    ./gradlew lint                    # Android lint
    ./gradlew connectedDebugAndroidTest   # instrumented tests, needs a device

Requires JDK 17 and an Android SDK with API 35 installed.

## Adding a trigger

1. Implement `Trigger` and its `TriggerFactory` in a new file under `:triggers`.
2. Add one line to `triggerFactories()` in that module.
3. Declare any permission it needs in `triggers/src/main/AndroidManifest.xml`,
   next to the code that needs it.

Nothing in `:core` or in a sibling trigger should change. If it must, the
interface is wrong — fix the interface. Actions follow the same three steps in
`:actions`.

## Status

Early scaffold. Implemented: the engine, the plugin seams, an interval trigger,
a Bluetooth-connected trigger, a post-notification action, and a rules list
screen.

Not yet implemented, and each has a `TODO` at the relevant place in the code:

- **Persistence.** Rules live in memory and are lost on process death.
- **Background execution.** The engine runs in the application scope, so it
  stops with the process. It needs a foreground service.
- **Notification trigger.** Needs a `NotificationListenerService` the user
  enables in system settings.
- **Real scheduling.** The interval trigger uses a coroutine delay, which does
  not survive Doze.
