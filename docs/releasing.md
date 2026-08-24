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

That file names the keystore and the alias. It does **not** hold the password —
that lives in the system keyring, and the build reads it from there. The split
is the point: the two halves have different secrecy, and treating them alike
meant the secret sat in plaintext next to the thing it protects.

A missing key is not a configuration failure. Three things have to be true
before a release is signed — a `keystore.properties`, a keystore file where it
points, and a password that can be found — and if any is absent the release
build simply comes out unsigned. That is what lets a contributor with no key run
unit tests, lint, and debug builds, and even check that R8 does not break the
release variant, without owning signing material for a project they are only
sending a patch to. It is also what keeps a locked or absent keyring from
turning `./gradlew test` into an error.

The consequence to stay aware of: an unsigned build is a silent outcome, not an
error. Verify the artifact rather than assuming the key was picked up — see
below.

### Creating the key

Once per maintainer, not once per release:

    ./scripts/setup-signing.sh

**Run it in a real terminal window.** It is the one step that cannot be
automated or delegated, because it asks for a password, and a password prompt
needs a terminal: routed through a tool or a pipe, `read` gets an empty line and
`keytool` rejects it as "password too short" — a complaint about the password
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
defaults, and `--force` replaces an existing key instead of reusing it — which
the script otherwise refuses to do, since replacing a key means nobody can
update an installed build, only reinstall it.

No `JAVA_HOME` needed. `keytool` ships inside the JDK and is not necessarily on
`PATH` — on a machine where the JDK was unpacked by hand rather than installed
by the package manager it is reachable only by full path, and `keytool: command
not found` says nothing about signing. So the script looks for one instead of
demanding it: `JAVA_HOME` if set, then the `org.gradle.java.home` this project
already builds with, then `PATH`, then the usual install locations, newest
first. `JAVA_HOME=/path/to/jdk` still overrides all of that.

This is the same family of trap as `apksigner` below, which does still need a
JDK on `PATH` and not merely a `JAVA_HOME` — both are JDK-adjacent tools that
fail with `command not found` or `exec: java: not found` rather than with
anything that hints at signing.

Losing the keystore file means the app's identity is lost: Android refuses to
update an installed package with one signed by a different key, and there is no
recovery short of a new `applicationId`. Back it up somewhere that is not this
machine, along with the fingerprint the script printed.

### Where the password comes from

`ui/build.gradle.kts` resolves it in two steps.

1. A `storePassword` in `keystore.properties`, if there is one. Nothing writes
   that line any more; it is the escape hatch for a machine with no keyring — CI,
   a container, a headless box — and it is checked *first* so a password somebody
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
the keyring is unlocked can read it back with that same `secret-tool lookup` —
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

That assembles the release variant and copies the APK to **`dist/trigly.apk`**
at the repository root. `:ui:assembleRelease` alone still works and still leaves
its output at `ui/build/outputs/apk/release/ui-release.apk`; the extra step
exists because that path is buried and that name describes the *module* rather
than the thing a person is being asked to install.

**Read the filename in `dist/` before publishing anything.** It is `trigly.apk`
when a key was found and **`trigly-unsigned.apk`** when none was — the rename is
deliberately conditional, because the build succeeds either way and the filename
is the cheapest signal that signing actually happened. Collapsing both into one
name would remove the only warning there is. `distRelease` prints the path it
wrote for the same reason.

`dist/` is gitignored: it is a build output, and the published copy belongs on
the release rather than in the repository.

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

    $ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs dist/trigly.apk
    $ANDROID_HOME/build-tools/<ver>/aapt2 dump badging dist/trigly.apk | head -1

The first must print a certificate — "DOES NOT VERIFY" is the unsigned outcome
described above. The second must show the `versionCode` and `versionName` the
build file declares.

The full test gate in `CLAUDE.md` — including connected tests on two API levels
— is a precondition for tagging, not an afterthought to it.
