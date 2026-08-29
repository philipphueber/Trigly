<img src="docs/branding/trigly-mark.svg" alt="Trigly logo" width="88">

# Trigly

Trigly is an open source automation app for Android. It is written in native
Kotlin, and it uses Jetpack Compose for its UI.

> ### This is beta software
>
> The version number is `0.2.0`, and both halves of it are a statement. The
> leading zero says the surface is still not settled: a control can move, a
> trigger can change its name, and a setting can change its meaning between two
> releases. The move up from `0.0.x` says the shape has settled enough to ask
> people to use the app and to report what breaks.
>
> **Your rules survive an update.** That promise is separate from the one above,
> and it holds at every version. The rule format and the database each carry
> their own version number, and each release reads what the release before it
> wrote.
>
> Four limits to know before you install:
>
> - **A wait can be late, and it needs the background permission to survive a
>   stop.** A rule that waits for a time or for the next sunrise uses Android's
>   alarm service, so it survives a sleeping phone. It is not exact: Android sends
>   an inexact alarm in its next maintenance window, so the rule can be some
>   minutes late, and later still when the phone is in deep sleep. If Android
>   stops Trigly during the wait, the wait comes back only when Trigly is allowed
>   to run in the background. The rules screen asks for that. Without it, the rule
>   stays quiet until something else starts Trigly.
> - **No test suite covers the release build.** The tests run on the debug build,
>   and the build you install shrinks and renames code. So each release is smoke
>   tested by hand on a device instead: it starts, it starts itself again after a
>   restart of the phone, a rule runs, and the rules and the saved values written
>   by the release before it survive the update. `docs/releasing.md` holds the
>   steps and the result is named in the release notes. A suite would cover more
>   than a smoke test can, and there is not one yet.
> - **Some triggers need Trigly to be running.** Android can stop an app that
>   sits idle. Android starts Trigly again for a Bluetooth connect, for a
>   notification, for an accessibility event and after a restart of the phone, so
>   a rule on one of those works from a stopped app. Every other trigger needs
>   Trigly to be running at the moment of the event. The rules screen asks you to
>   let Trigly run when Android is free to stop it. Please allow this. Trigly
>   cannot tell you that the system stopped it, because the report stops with it.
> - **A force stop stops everything.** If you stop Trigly in Settings, no rule
>   runs until you open Trigly again. Android cancels the alarms and holds back
>   the messages of an app that a person stopped. No setting changes this.
>
> Report anything that surprises you. A rule that does nothing and says nothing
> is the failure this app is designed against, so it is the most useful thing
> you can report.

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

## License

Trigly is available under the Apache License 2.0. See `LICENSE` for the full
text. The app's own Attribution screen lists every open source project it
ships, with a copy of that same license.

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
- **33 triggers** and **26 actions**.
- A rules list screen. This screen explains why a rule cannot start. You can
  search it. The search examines the name of each rule, and also the names of
  the triggers and actions in it. Thus a search for "bluetooth" finds a rule
  that uses Bluetooth, even if the name of the rule does not say so.
- **Folders.** Give a rule a folder name in the editor. The list then shows one
  section for each folder, with a count, and you can close a section. Rules with
  no folder are in a section named "Other", which is always last. If you use no
  folders, the list looks the same as before.
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
- Playing a sound: a plain sound, or a loud alert that a silenced phone
  still plays.
- Opening something: a website or an app.
- Handing off to another app, for you to confirm: email, SMS, an alarm, or
  a calendar entry.
- Device state: volume, ringer mode, clipboard, Do Not Disturb.
- Other apps' notifications: dismiss one, press one of its buttons, or keep
  a button and press it later, after the notification has gone.
- Turning one of your own rules on or off, or running one now.
- Saving a value for later, and computing a new value from it. You choose
  where the value lives: in this run only, in this rule, or shared with
  every rule.
- Waiting, before the rest of the rule runs.
- HTTP requests, for webhooks and home automation.

`docs/triggers.md` and `docs/actions.md` list every trigger and action,
with its Android API, required permission, and known pitfalls.
`docs/expressions.md` is the complete reference for variables: every scope you
can read, every mode you can write with, every comparison, and every operator
and function the expression language has, with worked examples. These
documents also list the ones Trigly does not build on purpose, and the ones
current Android versions do not permit at all.

**Privacy.** Trigly's accessibility service can read screen content, and
its notification listener sees every notification. Trigly does not store,
log, or send this data anywhere: the app checks each event on the device,
then discards it. Both services do nothing until you turn them on in system
settings, and no function needs them unless you build a rule that does.

Trigly does store your rules, your saved values, and any token in a webhook
URL, in a database on the phone. Android's backup can copy that database to
the account signed in on the phone. Settings has a switch for this, on by
default. Turn it off to keep this database out of that backup. A phone with
no Google account and no backup service does not back up this data either
way.

The list below states what Trigly does not yet do, and known limits of what
it does do. Trigly states each limit here so you do not have to discover it
yourself. Each missing function has a `TODO` comment at the relevant place
in the code:

- **An exact time.** Trigly uses Android's alarm service for every wait now, so
  the interval trigger and the sunrise/sunset trigger survive Doze mode. Neither
  is exact. Trigly asks for an inexact alarm, which Android sends in its next
  maintenance window, and those windows grow further apart the longer the phone
  sleeps. An exact alarm needs a separate permission that Android keeps for
  alarm clock apps. A time-of-day trigger and a calendar trigger are now
  possible, and they are not built yet.
- **Android can stop Trigly.** Some manufacturers stop an app that sits idle,
  and a stopped app watches nothing. Trigly asks to be excused from battery
  optimisation, and the rules screen says when Android can still stop it. A
  Bluetooth connect starts Trigly again on its own, because Android delivers
  that event to an app that is not running. Most events do not work that way.
- **Geofencing and activity recognition.** These functions need Google Play
  Services. Trigly leaves them out on purpose, so the app works on a
  de-Googled device. The `location` trigger uses the plain Android platform
  API instead.
- **Loops.** Trigly now has conditions, and it has variables: a rule can
  save a value, read it the next time it starts, and compute a new value
  from it. A rule can also run another rule, and it can wait. What a rule
  still cannot do is repeat an action a computed number of times. Trigly
  also does not run a script that you write. The expression language
  computes one value. It has no loop and no function that you can define,
  because a rule is a file that you can send to another person, and that
  file must not carry program code onto their phone. `docs/expressions.md`
  lists everything the language does do, and `docs/variables.md` gives the
  reasons in full.
- **Location needs "Allow all the time".** Trigly reads your position while
  it runs in the background. Android permits this only with that setting.
  With any other setting, the `location` trigger and the same component's
  state form read no location data, and cannot fire or hold. Trigly now
  reports this: the rule shows the requirement, and the button opens the
  settings page that grants it.

  Two things about a position read changed with this. A large area needs only
  approximate location: from a radius of 3 km, Trigly asks for "Approximate" and
  not for "Precise", because an approximate fix answers that question correctly.
  And Trigly asks the cheapest source that can answer, then the next one, and
  GPS last. GPS is the most exact source and it cannot answer inside a building,
  which is where the question is usually asked. The cost is a coarser position,
  and a fix too coarse for the area gives no answer at all rather than a guess.
