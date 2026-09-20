<img src="docs/branding/trigly-mark.svg" alt="Trigly logo" width="88">

# Trigly

Trigly is an open source automation app for Android. It is written in native
Kotlin, and it uses Jetpack Compose for its UI.

A rule has this form: when a trigger starts, the rule runs a set of actions.
Trigly has **39 triggers** and **29 actions**, and the full list is below.

> ### This is beta software
>
> The version number is `0.3.3`, and both halves of it are a statement. The
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
> - **A long wait can be late, and it needs the background permission to survive
>   a stop.** A wait of 30 seconds or less is held to the second, because Trigly
>   keeps the processor awake for it and asks Android for nothing. Every longer
>   wait, and every rule that waits for a time or for the next sunrise, uses
>   Android's alarm service instead. That service survives a sleeping phone and
>   is not exact: Android sends an inexact alarm in its next maintenance window,
>   so the rule can be some minutes late, and later still when the phone is in
>   deep sleep. If Android stops Trigly during the wait, the wait comes back only
>   when Trigly is allowed to run in the background. The rules screen asks for
>   that. Without it, the rule stays quiet until something else starts Trigly.
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

## What Trigly does

A rule has one trigger and any number of actions. The trigger can be a single
component, or a **group** of components joined with AND or OR, and a group can
hold another group. This lets you build a rule such as "the doorbell rings, and
it is dark, and I am at home".

When one member of a group starts the rule, Trigly counts that member as true
and asks every other member for its current state. A **condition** is one of
those state questions: a trigger, asked instead of watched. Conditions are not
a separate kind of item, and most triggers answer both ways. Where you put a
component in the group decides which question Trigly asks it.
`docs/conditions.md` has the design note.

### Building and keeping rules

- **A rule editor** that builds each form from the component's own declared
  settings. Pick a trigger and actions from grouped lists, fill in the form,
  and save.
- **A Test button on every action.** It runs that action now, so you choose a
  sound by hearing it rather than by its address. Press Test again to stop an
  action that is still running.
- **Pickers instead of typing**, for an app, a sound, a paired Bluetooth
  device, an emoji, and a notification's button.
- **Only what your phone can run.** A component that needs an Android version
  or hardware your phone does not have is not offered at all. Once you pick a
  component, Trigly states its caveats in full and names any permission it
  needs, with a button that opens the right settings page.
- **Folders.** Give a rule a folder name and the list groups itself, with a
  count per folder and a section you can close. Rules with no folder sit in
  "Other", which is always last.
- **Search** across rule names and also across the names of the triggers and
  actions inside each rule, so a search for "bluetooth" finds a rule that uses
  Bluetooth even when its name does not say so.
- **Duplicate** a rule, and turn any rule on or off from the list.
- **Export and import** every rule as versioned JSON. This is how you move to a
  new phone, because Android's own backup needs a Google account and does not
  run on a de-Googled device.
- **Share** one rule, or every rule under one folder heading, as a file. The
  phone at the other end imports it the same way it imports any other file of
  rules.
- **A home screen shortcut** for a rule you want to start yourself, with an
  emoji of your choice as its icon.

### Seeing what happened, and why

- **Last check** shows the rule's trigger tree as it was last evaluated, with
  the answer at each branch, so a rule that did not fire can say which part
  said no.
- **A notification inspector** shows what a notification looks like from
  inside the app: the package, the title, the text, the flags, and the real
  label of each button. Without it you would write a notification rule against
  values you cannot see.
- **A button capture picker.** Choose a notification's button by tapping it on
  a live notification rather than by counting positions, because a position
  changes without warning.
- **A pattern tester** for a text match, so a regular expression is checked
  against a sample before the rule depends on it.
- **A saved values screen** listing every variable Trigly holds, with its
  scope and its current value.
- **Fault reports on the rules list.** A rule that cannot start says why.

### Variables and expressions

A rule can save a value, read it the next time it starts, and compute a new
value from it. You choose where the value lives: in this run only, in this
rule, or shared with every rule. A rule also reads what fired it, through
`{{trigger.*}}`, `{{event.*}}` and `{{rule.*}}`.

The expression language computes one value. It has no loop and no function you
can define, on purpose: a rule is a file you can send to another person, and
that file must not carry program code onto their phone. `docs/expressions.md`
is the full reference and `docs/variables.md` gives the reasons.

### Running in the background

Rules run in a foreground service. It starts when the phone boots and after an
app update, and it stops itself when no rule is enabled. Android shows an
ongoing notification while it runs. That is the trade: the app cannot watch the
device unless Android tells you that it is doing so. Almost every trigger
depends on this service, because since Android 8 the system sends most
broadcasts only to a process that is already running.

### The look of it

Nine colour schemes, and on Android 12 and later a tenth choice that takes its
colours from your wallpaper. The app, its icon on your home screen and the
colour of its notification all follow the scheme you pick. Light and dark
follow the system setting in every scheme.

Settings also holds the backup switch, the app version with a check for a newer
one, and the list of open source components the app ships.

## Triggers

**Time**
Every so often · Time of day · Day of the week · Month · Date range · Sunrise
or sunset · Time of day, as a condition between two times

**Power**
Battery level · Battery temperature · Charger · Charger type

**Connectivity**
Wi-Fi radio · Bluetooth radio · Bluetooth device · Airplane mode · NFC ·
Location services

**Device state**
Screen on or off · Screen orientation · Dark theme · Wired headset · Clipboard
changes · Auto-sync setting · Device restarted · Home screen shortcut

**Apps**
App comes to the foreground · App installed or removed · Work profile

**Notifications**
Notification appears · App's notification goes missing · Do Not Disturb

**Screen content**
Text appears on screen · Something is tapped · Keyboard opens or closes

**Calls and messages**
Phone call · SMS received

**Location**
Enter or leave an area · Is in an area

**Variables**
Variable

## Actions

**Tell me something**
Show a notification · Show a brief message · Speak out loud · Vibrate · Play a
sound · Play an alert sound · Blink the flashlight · Clear Trigly's
notifications · Keep a notification button · Press a kept button

**Open something**
Open a website · Open an app

**Hand off to an app**
Compose an email · Compose a text message · Set an alarm · Add a calendar event

**Device settings**
Set the volume · Set ringer mode · Set Do Not Disturb · Copy text ·
Flashlight

**Other apps' notifications**
Dismiss a notification · Press a notification's button

**Trigly's own rules**
Turn a rule on or off · Run another rule · Set an app variable

**Network**
Send an HTTP request

**Timing**
Wait

**Advanced**
Fire an intent

`docs/triggers.md` and `docs/actions.md` describe every one of these, with its
Android API, the permission it needs, and its known pitfalls. Both documents
also list what Trigly leaves out on purpose, and what current Android versions
do not permit at all.

## Privacy

Trigly's accessibility service can read screen content, and its notification
listener sees every notification. Trigly does not store, log, or send this data
anywhere: the app checks each event on the device, then discards it. Both
services do nothing until you turn them on in system settings, and no function
needs them unless you build a rule that does.

Trigly does store your rules, your saved values, and any token in a webhook
URL, in a database on the phone. Android's backup can copy that database to the
account signed in on the phone. Settings has a switch for this, on by default.
Turn it off to keep this database out of that backup. A phone with no Google
account and no backup service does not back up this data either way.

Actions that open something also need the "Display over other apps" permission
to work while you are not using the phone. Trigly asks for it and draws nothing
with it. The permission exists only so a rule can put something on the screen
when you are not already looking. Without it these actions still work while the
phone is in use, and the editor says so rather than hiding it.

## What Trigly does not do yet

Each item here has a `TODO` comment at the matching place in the code.

- **An exact alarm.** Trigly asks Android for inexact alarms, which arrive in
  the next maintenance window, and those windows grow further apart the longer
  the phone sleeps. An exact alarm needs a permission Android keeps for alarm
  clock apps. A short wait no longer depends on any of this, because it holds
  the processor awake instead, but a time of day trigger can still be some
  minutes late.
- **A calendar trigger.** Not built yet.
- **Loops.** A rule can save a value, read it, compute from it, run another
  rule and wait. It cannot repeat an action a computed number of times, and
  Trigly runs no script you write. See the reasons under Variables above.
- **Geofencing and activity recognition.** These need Google Play Services.
  Trigly leaves them out on purpose so the app works on a de-Googled device.
  The location trigger uses the plain Android platform API instead.
- **Android can stop Trigly.** Some manufacturers stop an app that sits idle,
  and a stopped app watches nothing. Trigly asks to be excused from battery
  optimisation, and the rules screen says when Android can still stop it.
- **Location needs "Allow all the time".** Android permits a background
  position read only with that setting. The rule shows the requirement and the
  button opens the page that grants it. Two details follow from a position
  read: an area of 3 km or more asks for approximate location rather than
  precise, because an approximate fix answers that question correctly, and
  Trigly asks the cheapest source first and GPS last, because GPS is the most
  exact source and cannot answer indoors, which is where the question is
  usually asked.

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

The file `res/values*/colors.xml` holds the window-background colours and the
icon colours, as hexadecimal values. You must keep these the same as the
colours in `Palette.kt`. That file explains why the app cannot read these
colours from Kotlin code.

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

## License

Trigly is available under the Apache License 2.0. See `LICENSE` for the full
text. The app's own "Used components" screen lists every open source project
it ships, with a copy of that same license.
