#!/usr/bin/env bash
#
# One-time signing setup for a Trigly maintainer.
#
# Run this yourself, in a real terminal window. Everything is automated except
# the password, which you type once: the script creates the release keystore,
# puts its password in the system keyring, and writes the keystore.properties
# that the build reads. After that `./gradlew :ui:assembleRelease` signs without
# anyone being asked anything — including an automated session driving the build.
#
# The password is never written to a file, never passed on a command line, and
# never echoed. keytool receives it through `-storepass:env`, so it does not
# appear in `ps`; the keyring receives it on stdin.
#
# What this does NOT protect against: anything running as your user in a session
# where the keyring is unlocked can read the secret back with
# `secret-tool lookup service trigly key release-keystore`. That includes a
# terminal you left open, and it includes Claude Code. The keyring keeps the
# password out of the repository, out of your shell history, and out of any
# transcript — not out of your own logged-in session.
#
# Usage:  ./scripts/setup-signing.sh [--keystore PATH] [--alias NAME] [--force]
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

KEYSTORE="${HOME}/keys/trigly-release.jks"
ALIAS="trigly"
FORCE=0

# Keyring coordinates. These two attributes are the lookup key and must match
# the ones ui/build.gradle.kts uses; they are repeated there deliberately rather
# than shared, because a build file cannot source a shell script.
SECRET_SERVICE="trigly"
SECRET_KEY="release-keystore"

# Certificate subject. Not a secret and not load-bearing for Android, which
# identifies an app by the key rather than by the name on it — but it is what
# `apksigner verify --print-certs` shows, so it should read as something.
DNAME="CN=Trigly, O=Trigly, C=DE"
VALIDITY_DAYS=10000
KEY_SIZE=4096

# The header comment above is the help text, printed by stripping its comment
# markers — so the two cannot drift apart the way a duplicated usage string does.
usage() {
    awk 'NR>2 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit "${1:-0}"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --keystore) KEYSTORE="$2"; shift 2 ;;
        --alias)    ALIAS="$2";    shift 2 ;;
        --force)    FORCE=1;       shift ;;
        -h|--help)  usage 0 ;;
        *) echo "unknown option: $1" >&2; usage 1 >&2 ;;
    esac
done

die() { echo "error: $*" >&2; exit 1; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

# --- preconditions ----------------------------------------------------------
#
# Each of these fails later in a way that blames the wrong thing, so they are
# checked up front where the message can say what is actually missing.

# The reason this script exists at all. Without a terminal, `read -rs` gets an
# empty line and keytool rejects it as "password too short" — which reads as a
# complaint about the password you chose rather than about the one it never
# received.
[ -t 0 ] || die "no terminal on stdin. Run this in a real terminal window, not through a tool or a pipe."

command -v secret-tool >/dev/null 2>&1 \
    || die "secret-tool is not installed (package: libsecret / libsecret-tools)."

[ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] \
    || die "no D-Bus session bus, so there is no keyring to talk to. Run this from your desktop session."

# keytool lives inside the JDK and is not necessarily on PATH — on a machine
# where the JDK was unpacked by hand rather than installed by a package manager
# it is reachable only by full path, and `keytool: command not found` says
# nothing about signing.
#
# So it is searched for rather than demanded. Requiring a `JAVA_HOME=...` prefix
# would make the JDK path a second thing you have to remember, and the point of
# this script is that the password is the only one.
find_keytool() {
    local candidate candidates declared file

    # An explicit JAVA_HOME wins, because someone who set it meant it.
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/keytool" ]; then
        printf '%s' "${JAVA_HOME}/bin/keytool"
        return 0
    fi

    # Then whatever this project already builds with. Gradle reads
    # org.gradle.java.home from either file, and a JDK good enough to compile the
    # app is good enough to write a keystore.
    for file in "${REPO_ROOT}/local.properties" "${REPO_ROOT}/gradle.properties"; do
        [ -f "${file}" ] || continue
        declared="$(sed -n 's/^[[:space:]]*org\.gradle\.java\.home[[:space:]]*=[[:space:]]*//p' "${file}" | tail -1)"
        if [ -n "${declared}" ] && [ -x "${declared}/bin/keytool" ]; then
            printf '%s' "${declared}/bin/keytool"
            return 0
        fi
    done

    if command -v keytool >/dev/null 2>&1; then
        command -v keytool
        return 0
    fi

    # Then the usual places, newest first: distro packages, hand-unpacked JDKs
    # under a home directory, and the ones a version manager or an IDE installs.
    # Any JDK will do — keytool has written PKCS12 keystores by default since 9,
    # so this is not the place to be fussy about the version.
    candidates="$(ls -d1 \
        /usr/lib/jvm/*/bin/keytool \
        /usr/lib64/jvm/*/bin/keytool \
        /opt/java/*/bin/keytool \
        "${HOME}"/.local/opt/*/bin/keytool \
        "${HOME}"/.jdks/*/bin/keytool \
        "${HOME}"/.sdkman/candidates/java/*/bin/keytool \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/keytool \
        2>/dev/null | sort -rV || true)"

    while IFS= read -r candidate; do
        if [ -n "${candidate}" ] && [ -x "${candidate}" ]; then
            printf '%s' "${candidate}"
            return 0
        fi
    done <<< "${candidates}"

    return 1
}

KEYTOOL="$(find_keytool || true)"
[ -n "${KEYTOOL}" ] || die "no JDK found. Install one, or point this at yours: JAVA_HOME=/path/to/jdk $0"

[ -f "${REPO_ROOT}/settings.gradle.kts" ] \
    || die "${REPO_ROOT} does not look like the Trigly checkout."

PROPERTIES="${REPO_ROOT}/keystore.properties"

# --- the one thing you have to provide --------------------------------------

step "Signing password"

if [ -e "${KEYSTORE}" ] && [ "${FORCE}" -eq 0 ]; then
    cat <<EOF
A keystore already exists at:
  ${KEYSTORE}

It will be kept, and the password you type must be the one it already has —
this run will verify that before changing anything. Pass --force to move the
existing keystore aside and generate a new one instead.

Replacing a key is not a small thing: Android accepts an update only if it is
signed by the same key as the installed version, so a new key means anyone who
installed the old build cannot update, only uninstall and reinstall.
EOF
    REUSING=1
else
    REUSING=0
fi

printf 'Password for the release key (not echoed): '
read -rs PASSWORD
printf '\n'
printf 'Again: '
read -rs PASSWORD_CONFIRM
printf '\n'

[ "${PASSWORD}" = "${PASSWORD_CONFIRM}" ] || die "the two entries did not match."
unset PASSWORD_CONFIRM

# keytool's own floor. Checked here so the failure names the rule rather than
# arriving four steps later out of a Java stack trace.
[ "${#PASSWORD}" -ge 6 ] || die "keystore passwords must be at least 6 characters."

# Handed to keytool through the environment rather than as an argument, so it
# never appears in the process list.
export TRIGLY_SIGNING_PASSWORD="${PASSWORD}"

# --- the keystore -----------------------------------------------------------

if [ "${REUSING}" -eq 1 ]; then
    step "Checking the password against the existing keystore"
    "${KEYTOOL}" -list -keystore "${KEYSTORE}" -alias "${ALIAS}" \
        -storepass:env TRIGLY_SIGNING_PASSWORD >/dev/null \
        || die "that password does not open ${KEYSTORE} (or it has no alias '${ALIAS}')."
    echo "ok — the existing key opens with this password."
else
    if [ -e "${KEYSTORE}" ]; then
        BACKUP="${KEYSTORE}.replaced-$(date +%Y%m%d-%H%M%S)"
        step "Moving the existing keystore aside"
        mv -- "${KEYSTORE}" "${BACKUP}"
        echo "kept at ${BACKUP} — delete it once you are sure you do not need it."
    fi

    step "Creating the release keystore"
    # Outside the working tree on purpose: no `git clean` and no deleted
    # checkout can take the key with them.
    mkdir -p -- "$(dirname -- "${KEYSTORE}")"
    chmod 700 -- "$(dirname -- "${KEYSTORE}")"
    "${KEYTOOL}" -genkeypair \
        -keystore "${KEYSTORE}" \
        -alias "${ALIAS}" \
        -keyalg RSA -keysize "${KEY_SIZE}" -validity "${VALIDITY_DAYS}" \
        -dname "${DNAME}" \
        -storepass:env TRIGLY_SIGNING_PASSWORD \
        -keypass:env TRIGLY_SIGNING_PASSWORD
    chmod 600 -- "${KEYSTORE}"
    echo "created ${KEYSTORE}"
fi

# --- the keyring ------------------------------------------------------------

step "Storing the password in the keyring"
printf '%s' "${PASSWORD}" | secret-tool store \
    --label="Trigly release signing key" \
    service "${SECRET_SERVICE}" key "${SECRET_KEY}"

STORED="$(secret-tool lookup service "${SECRET_SERVICE}" key "${SECRET_KEY}" || true)"
[ "${STORED}" = "${PASSWORD}" ] \
    || die "the keyring did not return what was stored. Nothing else was changed."
unset STORED
echo "stored, and read back correctly."

# --- the properties file ----------------------------------------------------

step "Writing keystore.properties"
cat > "${PROPERTIES}" <<EOF
# Written by scripts/setup-signing.sh. Gitignored.
#
# Deliberately holds no password. The store and key passwords come from the
# system keyring, which ui/build.gradle.kts reads at configure time:
#
#     secret-tool lookup service ${SECRET_SERVICE} key ${SECRET_KEY}
#
# Re-run scripts/setup-signing.sh to change either. Adding a storePassword=
# line here still works and takes precedence, which is the escape hatch for a
# machine with no keyring — at the cost of putting the secret on disk.
storeFile=${KEYSTORE}
keyAlias=${ALIAS}
EOF
chmod 600 -- "${PROPERTIES}"
echo "wrote ${PROPERTIES}"

# --- proof ------------------------------------------------------------------

step "Certificate"
"${KEYTOOL}" -list -v -keystore "${KEYSTORE}" -alias "${ALIAS}" \
    -storepass:env TRIGLY_SIGNING_PASSWORD \
    | grep -E "Owner:|Valid from:|SHA256:" || true

unset TRIGLY_SIGNING_PASSWORD PASSWORD

cat <<EOF

Done. Nothing else needs the password.

Build a signed release with:

    JAVA_HOME=<jdk17> ./gradlew :ui:assembleRelease

and check the artifact is called ui-release.apk rather than
ui-release-unsigned.apk — the build succeeds either way, so the filename is the
cheapest signal that signing actually happened. docs/releasing.md has the
apksigner verification step.

Record the SHA-256 above somewhere outside this machine, along with a backup of
${KEYSTORE}. Losing that file loses the app's identity: Android will not accept
an update signed by a different key.
EOF
