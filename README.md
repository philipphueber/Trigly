<img src="docs/branding/trigly-mark.svg" alt="Trigly logo" width="88">

# Trigly

Trigly is an open source automation app for Android. It is written in native
Kotlin, and it uses Jetpack Compose for its UI.

A rule has this form: when a trigger starts, the rule runs a set of actions.
Triggers and actions use a plugin design. Each type is its own class, behind
a common interface. You can add a new type without a change to an existing
type.

## Modules

| Module      | Holds                                                                  |
|-------------|------------------------------------------------------------------------|
| `:core`     | `TriggerEngine`, domain model, rule storage (Room), portable JSON.     |
| `:triggers` | Trigger implementations, one per type.                                 |
| `:actions`  | Action implementations, one per type.                                   |
| `:ui`       | Compose screens, ViewModels, and the app assembly point. Applies the Android application plugin. |

Dependencies go in one direction only: `:ui` → `:triggers`/`:actions` →
`:core`. No module depends on `:ui`. `:core` depends on nothing else in the
project.

`docs/architecture.md` explains the reasons for this design.

**Theming.** All app colours are in one file:
`ui/src/main/kotlin/app/phueber/trigly/ui/Palette.kt`. This file defines
tonal ramps. It then builds the light and dark colour schemes from them. If
you change the hue value in this file, the whole app takes the new colour.

The file `res/values*/colors.xml` holds two window-background colours, as
hexadecimal values. You must keep these two colours the same as the colours
in `Palette.kt`. That file explains why the app cannot read these colours
from Kotlin code.

## Building

Trigly needs these tools to build:

- JDK 17.
- An Android SDK with API 35 and build-tools 35.0.0.

To set up the build:

1. Set `JAVA_HOME` to the JDK path.
2. Add the SDK path to `local.properties` as `sdk.dir=/path/to/Android/Sdk`.

`local.properties` is specific to your machine. Git does not track this
file.

The repository includes the Gradle wrapper, so you do not need to install
Gradle separately:

    ./gradlew test                        # runs the JVM unit tests, in all modules
    ./gradlew assembleDebug               # builds the debug APK
    ./gradlew lint                        # runs Android lint
    ./gradlew connectedDebugAndroidTest   # runs instrumented tests; needs a device or an emulator

A release build needs a signing key. The repository does not hold this key.

If there is no signing key, `./gradlew :ui:assembleRelease` still runs and
builds an unsigned APK. So a plain clone can still build the release
variant.

A maintainer creates the signing key with `./scripts/setup-signing.sh`. You
must run this script in a terminal window. The script asks for a
password one time. It stores the password in the system keyring, not in a
file. It leaves nothing else to do. `docs/releasing.md` describes the key,
the version numbers, and how to check the built file.

## Adding a trigger

1. Implement `Trigger` and its `TriggerFactory` in a new file, under
   `:triggers`.
2. Add one line to `triggerFactories()`, in that module.
3. Declare any permission the trigger needs, in
   `triggers/src/main/AndroidManifest.xml`, next to the code that needs it.
4. On the factory, declare `displayName`, `category`, and `configFields`.
   Add one `ConfigField` for each setting that your `create()` function
   reads.

The rule editor builds a form from these config fields. Without this
declaration, the trigger exists, but you cannot configure it. If you skip
this step, `ConfigSchemaContractTest` fails.

You must not change code in `:core` or in another trigger to add a new one.
If a change there seems necessary, the interface is wrong. Fix the
interface instead. Actions follow the same four steps, in `:actions`.

## Status

Trigly is at an early stage of development. The app has these parts:

- The trigger engine.
- A **foreground service** that keeps the engine running.
- The plugin design for triggers and actions.
- The requirement model, and the permission flow that goes with it.
- **33 triggers** and **20 actions**.
- A rules list screen. This screen explains why a rule cannot start.
- A **rule editor**. In the editor, you pick a trigger and actions from
  grouped lists, fill in a form, and save the rule. Trigly builds the form
  from each component's declared config schema.

Rules are stored in a local database. The database keeps a rule even if the
app's process stops.

A rule has one trigger. This trigger can be a single component, or it can
be a **group** of components. A group joins its members with AND or OR. A
group can hold another group inside it. This lets you build a rule like
this: "the doorbell rings, and it is dark, and I am at home."

When one member of a group starts the rule, Trigly counts that member as
true. Trigly then asks every other member for its current state. A
condition is one of these state questions: a trigger, asked instead of
watched. Trigly does not treat a condition as a separate kind of item.

Most triggers can answer a state question as well as start a rule. Each
component still appears once in the picker. The place you put it in the
group decides which question Trigly asks it. For this reason, "when the
doorbell rings, if it is dark and I am at home" is one group of triggers,
not a separate feature next to triggers. `docs/conditions.md` has the
design note for this.

**Time of day** is the only component that can answer a state question but
cannot start a rule on its own, because there is no time trigger for it to
attach to. An earlier, separate component called "in an area" existed for a
short time. Trigly then merged it into the `location` trigger, as that
trigger's state form. This merge is why each component now appears only
once in the picker.

A **notification inspector** screen shows what a notification looks like
from inside the app: the package name, the title, the text, the flags, and
the real label of each button. Without this screen, you would write a
notification rule against values that you cannot see, and a typo in one of
these values would stop the rule without any message.

The same idea produced the button-capture picker. You choose a notification
action button by tapping it on a live notification. Trigly does not count
buttons by position, because a button's position can change; a rule that
counts by position can keep working, then stop without warning, once the
position changes.

The pickers list only the components that the phone can run. If a trigger
needs an Android API that the phone's version does not have, Trigly does
not offer that trigger. If a trigger needs hardware that the phone does not
have, Trigly does not offer it either. Once you choose a component, Trigly
states all of its caveats in full.

You choose some settings from a list instead of typing them: an app, a
sound, or a paired Bluetooth device. Each action has a **Test** button. This
button runs the action right away, so you can choose a sound by listening
to it, not by its URI. If you press Test again while the action is running,
Trigly stops it.

Rules run **in the background**, in a foreground service. This service
starts when the phone boots, and after an app update. It stops itself when
no rule is enabled. Android shows an ongoing notification while the service
runs; this is a trade-off, because the app cannot watch the device unless
Android tells the user that it is doing so. Almost every trigger depends on
this service: since Android 8, the system sends most broadcasts only to a
process that is already running, so without the service a rule would start
only while the app was open.

Actions that **open** something also work from the background: for example,
a website, an app, or a pre-filled email. On modern Android, opening
something from the background needs more than a call to `startActivity`.
Without the "Display over other apps" permission, Android silently blocks
the attempt and draws nothing.

Trigly asks for this permission, but draws nothing with it. The permission
exists only so a rule can put something on the screen while you are not
already looking at the phone. Grant the permission from the rule that needs
it. Without it, these actions still work while you are using the phone, and
the editor states this limit rather than hiding it.

You can **export and import** rules as versioned JSON, one rule or all of
them. This solves the problem of moving to a new phone: Android's Auto
Backup needs a Google account, and it does not run on a de-Googled device,
so an explicit file that you own is the only method that always works. You
can also use this file to share a rule with another person.

Trigly's triggers cover:

- Device state: battery, power, radios, screen, headset, theme,
  orientation, location.
- Apps and settings: install, foreground app, work profile, auto-sync.
- A home-screen shortcut that you tap yourself.
- Functions gated behind a permission: notifications, Do Not Disturb,
  accessibility, calls, SMS, clipboard.

Trigly's actions cover:

- Sending a notice: a notification, a toast, speech, or vibration.
- Opening something: a website or an app.
- Handing off to another app, for you to confirm: email, SMS, an alarm, or
  a calendar entry.
- Device state: volume, ringer mode, clipboard, Do Not Disturb.
- Other apps' notifications: dismiss one, or press one of its buttons.
- Turning one of your own rules on or off.
- HTTP requests, for webhooks and home automation.

`docs/triggers.md` and `docs/actions.md` list every trigger and action,
with its Android API, required permission, and known pitfalls. These
documents also list the ones Trigly does not build on purpose, and the ones
current Android versions do not permit at all.

**Privacy.** Trigly's accessibility service can read screen content, and
its notification listener sees every notification. Trigly does not store,
log, or send this data anywhere: the app checks each event on the device,
then discards it. Both services do nothing until you turn them on in system
settings, and no function needs them unless you build a rule that does.

The list below states what Trigly does not yet do, and known limits of what
it does do. Trigly states each limit here so you do not have to discover it
yourself. Each missing function has a `TODO` comment at the relevant place
in the code:

- **Real scheduling.** Trigly has no scheduler. The interval trigger uses a
  coroutine delay, and this delay does not survive Doze mode. The
  sunrise/sunset trigger uses the same method, so it has the same weakness,
  until Trigly builds a real scheduler. Without a real scheduler, Trigly
  cannot build a time-of-day trigger or a calendar trigger at all. This is
  currently the largest gap, and the next feature worth building.
- **Geofencing and activity recognition.** These functions need Google Play
  Services. Trigly leaves them out on purpose, so the app works on a
  de-Googled device. The `location` trigger uses the plain Android platform
  API instead.
- **Variables and loops.** Trigly now has conditions; see above. But a
  rule still cannot carry state between the times it starts, and a rule
  still cannot repeat an action a computed number of times. How to add this
  remains an open question about the execution model; see the design note
  in `docs/actions.md`.
- **Location goes silent after a reboot.** On current Android, a service
  that starts at boot loses location access for the rest of its run. This
  is a platform restriction that Trigly cannot work around. This affects
  the `location` trigger, and the same component's state form: if a
  rule has not run since the device last restarted, both forms read no
  location data, and Trigly gives no warning for this, because the app has
  no way to check for a denial that it never gets the chance to test.
