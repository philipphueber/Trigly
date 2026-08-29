# Releasing

## Versions

Two numbers, with different jobs. `versionCode` is a plain monotonic integer
that only ever increases: Android uses it, and nothing else, to decide whether
an installed package is older than the one being installed. `versionName` is
the human-facing string and carries no meaning for the platform.

Both live in `ui/build.gradle.kts` under `defaultConfig`, which is the single
place they are declared. The release tag matches the `versionName` prefixed with
`v`, so `0.0.1` is tagged `v0.0.1`.

The leading zero stays, and the minor number moved to `0.1.0` at the first
beta. Both numbers say the same thing they always did. A leading zero is a
statement about the app's surface being unsettled, and the app is still before
its first stable release. The move from `0.0.x` says the shape has settled
enough to ask people to use it and report what breaks.

Neither number says anything about saved rules. The portable rule JSON is
versioned separately and independently, in the storage section of
`architecture.md`, and rules survive an update at every version. That is not a
promise this file can weaken by changing a number.

## Signing

A release build is signed by a key that is not in this repository and never
will be. What the repository holds instead is a description of where to find
one: `ui/build.gradle.kts` reads a `keystore.properties` at the repo root,
which is gitignored along with `*.jks` and `*.keystore`.

That file names the keystore and the alias. It does **not** hold the password:
that lives in the system keyring, and the build reads it from there. The split
is the point: the two halves have different secrecy, and treating them alike
meant the secret sat in plaintext next to the thing it protects.

A missing key is not a configuration failure. Three things have to be true
before a release is signed (a `keystore.properties`, a keystore file where it
points, and a password that can be found), and if any is absent the release
build simply comes out unsigned. That is what lets a contributor with no key run
unit tests, lint, and debug builds, and even check that R8 does not break the
release variant, without owning signing material for a project they are only
sending a patch to. It is also what keeps a locked or absent keyring from
turning `./gradlew test` into an error.

The consequence to stay aware of: an unsigned build is a silent outcome, not an
error. Verify the artifact rather than assuming the key was picked up. See
below.

### Creating the key

Once per maintainer, not once per release:

    ./scripts/setup-signing.sh

**Run it in a real terminal window.** It is the one step that cannot be
automated or delegated, because it asks for a password, and a password prompt
needs a terminal: routed through a tool or a pipe, `read` gets an empty line and
`keytool` rejects it as "password too short": a complaint about the password
you chose, not about the one it never received. The script refuses to start
without a terminal rather than reproduce that.

Everything else it does for you, from the one password you type:

- creates `~/keys/trigly-release.jks` (RSA 4096, ~27 years), outside the working
  tree so that no `git clean` and no deleted checkout can take it with them;
- stores the password in the system keyring, and reads it back to prove it
  is there;
- writes `keystore.properties` with the keystore path and the alias;
- prints the certificate's SHA-256, which is what identifies the key later.

The password never reaches a file, a command line or the terminal echo.
`keytool` is given it through `-storepass:env`, so it does not appear in `ps`;
the keyring is given it on stdin. `--keystore` and `--alias` override the
defaults, and `--force` replaces an existing key instead of reusing it, which
the script otherwise refuses to do, since replacing a key means nobody can
update an installed build, only reinstall it.

No `JAVA_HOME` needed. `keytool` ships inside the JDK and is not necessarily on
`PATH`: on a machine where the JDK was unpacked by hand rather than installed
by the package manager it is reachable only by full path, and `keytool: command
not found` says nothing about signing. So the script looks for one instead of
demanding it: `JAVA_HOME` if set, then the `org.gradle.java.home` this project
already builds with, then `PATH`, then the usual install locations, newest
first. `JAVA_HOME=/path/to/jdk` still overrides all of that.

This is the same family of trap as `apksigner` below, which does still need a
JDK on `PATH` and not merely a `JAVA_HOME`: both are JDK-adjacent tools that
fail with `command not found` or `exec: java: not found` rather than with
anything that hints at signing.

Losing the keystore file means the app's identity is lost: Android refuses to
update an installed package with one signed by a different key, and there is no
recovery short of a new `applicationId`. Back it up somewhere that is not this
machine, along with the fingerprint the script printed.

### Where the password comes from

`ui/build.gradle.kts` resolves it in two steps.

1. A `storePassword` in `keystore.properties`, if there is one. Nothing writes
   that line any more; it is the escape hatch for a machine with no keyring (CI,
   a container, a headless box), and it is checked *first* so a password somebody
   wrote down deliberately is never silently passed over for a stale keyring
   entry.
2. Otherwise `secret-tool lookup service trigly key release-keystore`.

Anything that goes wrong in step 2 resolves to "no password", and no password
means an unsigned build. That is deliberate and it is the common case, not an
edge one: a headless session has no D-Bus, a fresh login may have the keyring
still locked, and libsecret is not installed everywhere. The ten-second timeout
is there for the locked case specifically, where `secret-tool` would otherwise
sit waiting on a prompt no unattended build will ever answer.

Be clear-eyed about what the keyring is for. It keeps the password out of the
repository, out of your shell history and out of any tool transcript. It does
**not** hide it from your own logged-in session: anything running as you while
the keyring is unlocked can read it back with that same `secret-tool lookup`:
an open terminal, a script, an agent. The threat it addresses is a secret at
rest in the wrong place, not a hostile process on your desktop.

Changing the password, or moving to a different key, is the same command again:
`./scripts/setup-signing.sh`.

`storeFile` is resolved with `rootProject.file(...)`, so an absolute path is
used as given. A relative one would resolve inside the checkout, which is
exactly where the key should not be. A path that no longer exists yields an
unsigned build rather than a late and obscure failure in the signer.

## Building

    JAVA_HOME=<jdk17> ./gradlew :ui:distRelease

That assembles the release variant and copies the APK to
**`dist/trigly-<version>.apk`** at the repository root: `dist/trigly-0.0.3.apk`
for `versionName` `0.0.3`. `:ui:assembleRelease` alone still works and still
leaves its output at `ui/build/outputs/apk/release/ui-release.apk`; the extra
step exists because that path is buried and that name describes the *module*
rather than the thing a person is being asked to install.

The version is in the name because these files outlive the directory they were
built in: they get downloaded, forwarded and kept, and three files all called
`trigly.apk` cannot say which is which. `versionName` is declared once, as
`triglyVersionName` in `ui/build.gradle.kts`, and both the manifest and this
filename read it; a second literal would be a version the build could disagree
with itself about, and the only symptom would be an APK whose name lies.

**Read the filename in `dist/` before publishing anything.** It is
`trigly-<version>.apk` when a key was found and
**`trigly-<version>-unsigned.apk`** when none was: the rename is deliberately
conditional, because the build succeeds either way and the filename is the
cheapest signal that signing actually happened. Collapsing both into one name
would remove the only warning there is. `distRelease` prints the path it wrote
for the same reason, filtered to the version just built so an older artifact
sitting in `dist/` is never mistaken for the new one.

`dist/` is gitignored: it is a build output, and the published copy belongs on
the release rather than in the repository. It accumulates past releases, which is
useful locally and is why the publishing step names the version explicitly rather
than globbing.

An APK, not an App Bundle: the distribution channel is a direct download, where
a single installable file is the whole point. `bundleRelease` is the format
Google Play requires and is worth adding the day Play is actually on the table,
not before, since an AAB cannot be installed by a person.

The release variant runs R8 with `isMinifyEnabled` and `isShrinkResources`. It
is worth understanding why that is safe here: triggers and actions are resolved
from a stored `type` string, which R8 cannot follow. They survive only because
the factory lists in `:triggers` and `:actions` reference each implementation
directly. If that ever becomes reflective, the release build starts failing at
runtime on rules that work in debug, and `ui/proguard-rules.pro` needs explicit
keep rules. The note is in that file too.

## Verifying the artifact

Signed-ness and version are both worth checking, because both fail quietly.
`apksigner` is a shell wrapper that calls bare `java`, so it needs a JDK on
`PATH` and not merely a `JAVA_HOME`: otherwise it dies with
`exec: java: not found`, which looks nothing like a signing problem. This is
the same family of trap `scripts/setup-signing.sh` solves for `keytool`, and
`scripts/smoke-static.sh` (below) searches for a JDK the same way.

`apksigner verify --print-certs` must print a certificate: "DOES NOT VERIFY"
is the unsigned outcome described above. Beyond that, it must be the *right*
certificate. A certificate is the public half of the signing key, not a
secret: it ships inside every signed APK for anyone to read, so stating the
expected value here costs nothing and catches a build signed by the wrong key
before it goes out. The real Trigly key's fingerprint is:

    82adfe55e213ca3df6d0eb905042215c24afe9ccb1b5e53a57d1f01603a5cfdb

Anything else, including the throwaway test key this project replaced early on
(fingerprint starting `60c73cd5...`), means the artifact was not signed by the
key people's installs already trust.

`aapt2 dump badging` must show the `versionCode` and `versionName` the build
file declares. `scripts/smoke-static.sh` runs both of these checks, as the
first thing it does.

## Smoke testing the release build

Every instrumented test in this project runs on the **debug** build. R8 and
`isShrinkResources` are therefore never exercised by the suite, and a minified
APK that crashes on launch passes the whole pre-merge gate. That is the reason
this section exists: the release build gets a check by hand, and that check is
part of cutting a release rather than an optional extra.

Do the static half first. It costs about a minute, and it names a cause instead
of only observing a crash: a missing class in `classes.dex` and a crash on
launch are the same fault, but the first one says which class.

    ./scripts/smoke-static.sh <version>

That one command runs the artifact and signing check above plus the four
checks below, and prints PASS or FAIL for each. Run it with `--allow-unsigned`
if there is no signing key on this machine: `dist/` then holds
`trigly-<version>-unsigned.apk` instead, which is the expected outcome for a
contributor without a key (see Signing, above), and the flag runs the checks
against that file anyway, while its output says plainly that signing itself
was not verified. Without the flag, an unsigned artifact is a hard failure, on
purpose: a smoke test of the wrong file proves nothing. `--help` prints the
rest of this usage information from the script itself.

What follows is what the script checks and why, for understanding its output.
The exact commands live in `scripts/smoke-static.sh`; this section is not the
place to copy them from by hand any more.

**1. Did R8 keep the components the manifest names?** They are instantiated by
the platform, by name, so a rename here is fatal and silent until launch. The
script reads every dex file, not `classes.dex` alone: 0.1.0 fits in one, and
the day it does not, a check that reads only the first file would report a
missing class that is in the second one.

**The list of components is read from the APK's own manifest, not kept here.**
This section used to name four classes, and by 0.1.0 the manifest named ten, so
the accessibility service, the notification listener service, the shortcut
target activity and the backup agent were never checked at all. A list in a
document is a list somebody has to remember to update, and a component added
later is now covered without anyone editing anything.

Two details make that reading honest. `android:backupAgent` is a separate
attribute, so a pattern that reads only `android:name` misses
`TriglyBackupAgent`, which is how it escaped the old list. And `android:name`
means a class only inside a component element: on `<action>` it is an intent
action and on `<uses-permission>` it is a permission. Both of those are strings
that are in the dex for their own reasons, so reading them as classes reports a
pass for something the check never verified.

**2. Did the stored `type` strings survive?** This is the check that matters
most, because a rule names its trigger and its action by string, and R8 cannot
follow a string. Factory classes get renamed, which is correct and expected.
The strings must not go, and the script checks the whole declared set rather
than a sample, since it costs the same. 0.0.11 declares 61 of these strings,
and all 61 are in the APK.

**Strip the line before you anchor a match to it.** A dex file stores each
string with a length prefix byte, and `strings` keeps that byte on the line
when the byte happens to be printable. A type string of exactly nine
characters has length `0x09`, which is a tab, so anchoring `^auto_sync$`
directly against `strings` output finds nothing and reads as "R8 dropped it".
`auto_sync`, `gps_state`, `nfc_state` and `set_alarm` are all nine characters
long, and all four are present. The script strips that byte before comparing,
which is what makes the comparison honest. The same byte is why check 1 does
not anchor either: a class name arrives as
`%Lapp/phueber/trigly/ui/EngineService;`, prefix included.

**3. Did R8 delete an app class outright?** In `usage.txt` a line **with** a
trailing colon means the class was kept and only some members went. A line
without one is a full removal. Most of what this prints is correct, and
reading it needs the list of shapes that hold nothing at runtime:

- an `object` or a `$Companion` that holds only `const val`s, because the
  compiler puts the value at every use and leaves the holder empty. This is the
  common one and it looks alarming: `ScreenContentTrigger` is removed from every
  release build, while `ScreenContentTriggerFactory` and the string
  `screen_content` both stay. Check 2 is what covers this case, and it is why
  check 2 exists;
- `*Kt` file facades, `R` classes, and a subclass R8 merges into its parent;
- an interface's `$DefaultImpls`, once every implementation in the app overrides
  the default. The default body then only serves the test fakes, which are not in
  this build;
- an in memory implementation of a store or a repository, for the same reason:
  the app builds the Room one, and only a test builds the other.

Anything outside those shapes needs an explanation before the tag. The script
filters what it can, prints only what is left, and asks for an eye rather than
deciding on its own: this is the one check where a pattern that is too broad
would hide a real removal, so `scripts/smoke-static.sh` errs toward showing a
line rather than filtering it, and its own comments say which bullet above
each of its patterns implements.

**4. Did `shrinkResources` keep what only code refers to?** A resource named
from Kotlin and from no layout is the one it can drop. The engine's
notification is all of that: its channel strings, its plural, and its icon.
The script asserts each of the six resources below is present by name, rather
than only counting matches, since a stale count could pass while the one
resource that actually matters is gone:
`ic_notification`, `engine_watching`, `engine_channel_name`,
`engine_channel_description`, `engine_starting`, `engine_none_started`. A
dropped one costs the foreground service its notification, which the platform
then refuses, which stops every rule.

Then the dynamic half, on a device or an emulator image. It is still by hand:
it needs a device, a reboot and an in place upgrade, none of which a script
running on the build machine can do for you.

    APK=dist/trigly-<version>.apk

**5. It starts.**

    adb install -r $APK
    adb logcat -c
    adb shell am start -n app.phueber.trigly/app.phueber.trigly.ui.MainActivity

`pidof app.phueber.trigly` must be non-empty, the activity must be the
`ResumedActivity`, and `logcat` must hold no `FATAL`, `ClassNotFound`,
`NoSuchMethod` or `NoClassDefFound`.

**6. It starts itself after a restart of the phone.** This one needs a real
`adb reboot`. `BOOT_COMPLETED` is a protected broadcast, so `am broadcast`
cannot send it; a release build is not debuggable, so `run-as` cannot seed a
rule; and `EngineService` is not exported, so `am start-foreground-service` is
refused. Reboot, and read the two lines the platform prints itself:

    Start proc ...:app.phueber.trigly for broadcast {.../.ui.BootReceiver}
    Background started FGS: Allowed [... cmp=.../.ui.EngineService; code:BOOT_COMPLETED ...]

Together they prove the receiver resolved under R8, the service was built, and
`startForeground` accepted its type. With no rule enabled the service then stops
itself, so an empty `pidof` afterwards is the correct outcome. The absence of
those two log lines is the failure, not the absence of a process.

**7. Exercise a rule end to end, through the UI.** Make one rule with one
trigger and one action, switch it on, and press Test. A factory that R8 dropped
shows up here and nowhere in the static checks, because the editor resolves a
type string at the moment you pick it.

**8. The upgrade, when the database version changed.** Install the **published**
APK of the release before this one, not the new one, and use it: make a rule,
and write a saved value. Then install the new APK over it.

    adb install -r dist/trigly-<previous>.apk
    # make a rule and a saved value in the app
    adb install -r $APK

The rule must still be there and still be on, the saved value must still hold
what it held, and `logcat` must hold no SQLite, foreign key or migration error.
Then use whatever the new schema version added, so that the new tables are
written on a **migrated** database and not only on one Room created from
scratch. `MigrationTest` covers the migration itself; this covers the migration
plus everything the app does afterwards.

`dist/` keeps past releases, which is why the previous APK is at hand. Say in
the release notes which API level the upgrade ran on. A migration that was
checked on one image and no other is worth stating as exactly that.

**Never `am force-stop` during any of this.** A force-stopped package receives
no broadcast at all until a person launches it, and `BOOT_COMPLETED` carries
`FLAG_EXCLUDE_STOPPED_PACKAGES`. The reboot check then fails with no process, no
service and no crash log, which reads as a broken receiver. Launch the activity
instead.

What not to misread: an emulator image prints its own unrelated warnings, and
the artifact is only the release build if the filename has no `-unsigned`
suffix. A smoke test of the wrong file proves nothing.

Two gates come before a tag, and they cover different things. The full test
gate in `CLAUDE.md`, connected tests on two API levels included, covers the
debug build and the code. The smoke test above covers the artifact people
actually install. Neither is an afterthought to the tag, and neither replaces
the other.
