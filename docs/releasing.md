# Releasing

## Versions

Two numbers, with different jobs. `versionCode` is a plain monotonic integer
that only ever increases — Android uses it, and nothing else, to decide whether
an installed package is older than the one being installed. `versionName` is
the human-facing string and carries no meaning for the platform.

Both live in `ui/build.gradle.kts` under `defaultConfig`, which is the single
place they are declared. The release tag matches the `versionName` prefixed with
`v`, so `0.0.1` is tagged `v0.0.1`.

`0.0.x` is deliberate for now: the portable rule JSON is versioned separately
and independently (see the storage section of `architecture.md`), so a leading
zero here is a statement about the app's surface being unsettled, not about
whether saved rules survive an update — they must, at every version.

## Signing

A release build is signed by a key that is not in this repository and never
will be. What the repository holds instead is a description of where to find
one: `ui/build.gradle.kts` reads a `keystore.properties` at the repo root,
which is gitignored along with `*.jks` and `*.keystore`.

A missing `keystore.properties` is not a configuration failure. The signing
config is wired with `signingConfigs.findByName("release")`, which yields null
when no key is described, so the release build simply comes out unsigned. That
is what lets a contributor with no key run unit tests, lint, and debug builds —
and even check that R8 does not break the release variant — without owning the
signing material for a project they are only sending a patch to.

The consequence to stay aware of: an unsigned build is a silent outcome, not an
error. Verify the artifact rather than assuming the key was picked up — see
below.

### Creating the key

Once per maintainer, not once per release. Keep it outside the working tree, so
that no `git clean` and no deleted checkout can take it with them:

    <jdk17>/bin/keytool -genkeypair -v \
      -keystore ~/keys/trigly-release.jks \
      -alias trigly -keyalg RSA -keysize 4096 -validity 10000

`keytool` ships inside the JDK and is not necessarily on `PATH` — on a machine
where the JDK was unpacked by hand rather than installed by the package
manager, it is reachable only by full path. This is the same trap as
`apksigner` below: both are JDK-adjacent tools that fail with
`command not found` or `exec: java: not found` rather than anything that hints
at signing.

Then describe it in `keystore.properties` at the repo root:

    storeFile=/home/<you>/keys/trigly-release.jks
    storePassword=<store password>
    keyAlias=trigly
    keyPassword=<key password>

`storeFile` is resolved with `rootProject.file(...)`, so an absolute path is
used as given. A relative one would resolve inside the checkout, which is
exactly where the key should not be.

Losing this key means the app's identity is lost: Android refuses to update an
installed package with one signed by a different key, and there is no recovery
short of a new `applicationId`. Back it up somewhere that is not this machine.

## Building

    JAVA_HOME=<jdk17> ./gradlew :ui:assembleRelease

The artifact lands in `ui/build/outputs/apk/release/`, and its name is the
first thing to read: `ui-release.apk` when a key was found, and
`ui-release-unsigned.apk` when none was. The build succeeds either way, so
the filename is the cheapest signal that signing actually happened.

An APK, not an App Bundle: the distribution channel is a direct download, where
a single installable file is the whole point. `bundleRelease` is the format
Google Play requires and is worth adding the day Play is actually on the table —
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
`PATH` and not merely a `JAVA_HOME` — otherwise it dies with
`exec: java: not found`, which looks nothing like a signing problem:

    export PATH="<jdk17>/bin:$PATH"

    $ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs \
        ui/build/outputs/apk/release/ui-release.apk
    $ANDROID_HOME/build-tools/<ver>/aapt2 dump badging \
        ui/build/outputs/apk/release/ui-release.apk | head -1

The first must print a certificate — "DOES NOT VERIFY" is the unsigned outcome
described above. The second must show the `versionCode` and `versionName` the
build file declares.

The full test gate in `CLAUDE.md` — including connected tests on two API levels
— is a precondition for tagging, not an afterthought to it.
