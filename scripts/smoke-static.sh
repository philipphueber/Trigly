#!/usr/bin/env bash
#
# Static half of the release smoke test in docs/releasing.md: the artifact and
# signing check, plus checks 1 to 4. It needs no device and takes about a
# minute. That section used to spell these out as shell snippets to copy by
# hand, with results stated in prose ("0.0.11 declares 61 of these strings,
# and all 61 are in the APK"). This script runs the same checks and reports
# the real numbers instead of asking a reader to trust old ones.
#
# It covers the STATIC half only. Checks 5 to 8 need a device, a reboot and an
# in place upgrade, and are still done by hand; see docs/releasing.md.
#
# Usage: smoke-static.sh [--allow-unsigned] <version>
#        e.g. smoke-static.sh 0.1.0
#
#   --allow-unsigned   Accept dist/trigly-<version>-unsigned.apk when there is
#                       no signed artifact, and skip the certificate check.
#                       docs/releasing.md is explicit that a missing signing
#                       key yields an unsigned build on purpose, so that a
#                       contributor with no key can still check that R8 does
#                       not break the release variant. Without this flag an
#                       unsigned artifact is a hard failure: a smoke test of
#                       the wrong file proves nothing, and cutting an actual
#                       release always has a key.
#
# Every check prints PASS or FAIL, and the script exits non-zero if any
# failed, so this is a gate rather than a report to read by eye. Check 3 is
# the one exception: R8 removals need judgement, so it prints its findings
# and asks for an eye rather than deciding on its own.
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The header comment above is the help text, printed by stripping its comment
# markers, so the two cannot drift apart the way a duplicated usage string
# would.
usage() {
    awk 'NR>2 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit "${1:-0}"
}

die() { echo "error: $*" >&2; exit 1; }

ALLOW_UNSIGNED=0
VERSION=""
while [ $# -gt 0 ]; do
    case "$1" in
        --allow-unsigned) ALLOW_UNSIGNED=1; shift ;;
        -h|--help)        usage 0 ;;
        --)               shift; break ;;
        -*)               echo "unknown option: $1" >&2; usage 1 >&2 ;;
        *)
            [ -z "${VERSION}" ] || die "unexpected extra argument: $1"
            VERSION="$1"
            shift
            ;;
    esac
done
[ -n "${VERSION}" ] || { echo "usage: smoke-static.sh [--allow-unsigned] <version>" >&2; exit 1; }

[ -f "${REPO_ROOT}/settings.gradle.kts" ] || die "${REPO_ROOT} does not look like the Trigly checkout."

fails=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fails=$((fails + 1)); }
note() { printf '  ....  %s\n' "$1"; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "== Setup =="

# ANDROID_HOME wins when set, because someone who set it meant it. Otherwise
# fall back to what the build itself uses (local.properties's sdk.dir), then
# to the usual install location, so the script still runs on a machine that
# has never set an environment variable for Android at all.
if [ -n "${ANDROID_HOME:-}" ]; then
    SDK="${ANDROID_HOME}"
    SDK_SOURCE="ANDROID_HOME"
elif [ -f "${REPO_ROOT}/local.properties" ] \
        && grep -q '^[[:space:]]*sdk\.dir[[:space:]]*=' "${REPO_ROOT}/local.properties"; then
    SDK="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "${REPO_ROOT}/local.properties" | tail -1)"
    SDK_SOURCE="local.properties"
else
    SDK="${HOME}/Android/Sdk"
    SDK_SOURCE="default"
fi
[ -d "${SDK}" ] || die "no Android SDK at ${SDK} (from ${SDK_SOURCE}). Set ANDROID_HOME, or add sdk.dir to local.properties."
note "SDK: ${SDK} (from ${SDK_SOURCE})"

# Newest installed build-tools, not a version pinned in the script: build-tools
# ship independently of the app, a checkout gets whichever one sdkmanager last
# installed, and aapt2/apksigner behavior does not depend on picking an old one.
BT_VERSION="$(ls -1 "${SDK}/build-tools" 2>/dev/null | sort -V | tail -1)"
[ -n "${BT_VERSION}" ] || die "no build-tools installed under ${SDK}/build-tools. Install one, e.g. sdkmanager 'build-tools;35.0.0'."
BT="${SDK}/build-tools/${BT_VERSION}"
[ -x "${BT}/apksigner" ] || die "${BT}/apksigner missing or not executable"
[ -x "${BT}/aapt2" ] || die "${BT}/aapt2 missing or not executable"
note "build-tools: ${BT_VERSION} (newest installed)"

# apksigner is a shell wrapper that execs bare `java`, so it needs a JDK on
# PATH and not merely a JAVA_HOME: otherwise it dies with "exec: java: not
# found", which says nothing about signing. docs/releasing.md calls this out
# as the same family of trap as scripts/setup-signing.sh's own JDK search, so
# the search here follows the same order that script uses for keytool: an
# explicit JAVA_HOME first, then the JDK this project already builds with,
# then PATH, then the usual install locations, newest first.
find_jdk_bin() {
    local candidate candidates declared file

    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        printf '%s' "${JAVA_HOME}/bin"
        return 0
    fi

    for file in "${REPO_ROOT}/local.properties" "${REPO_ROOT}/gradle.properties"; do
        [ -f "${file}" ] || continue
        declared="$(sed -n 's/^[[:space:]]*org\.gradle\.java\.home[[:space:]]*=[[:space:]]*//p' "${file}" | tail -1)"
        if [ -n "${declared}" ] && [ -x "${declared}/bin/java" ]; then
            printf '%s' "${declared}/bin"
            return 0
        fi
    done

    if command -v java >/dev/null 2>&1; then
        dirname "$(command -v java)"
        return 0
    fi

    candidates="$(ls -d1 \
        /usr/lib/jvm/*/bin/java \
        /usr/lib64/jvm/*/bin/java \
        /opt/java/*/bin/java \
        "${HOME}"/.local/opt/*/bin/java \
        "${HOME}"/.jdks/*/bin/java \
        "${HOME}"/.sdkman/candidates/java/*/bin/java \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
        2>/dev/null | sort -rV || true)"

    while IFS= read -r candidate; do
        if [ -n "${candidate}" ] && [ -x "${candidate}" ]; then
            dirname "${candidate}"
            return 0
        fi
    done <<< "${candidates}"

    return 1
}

JDK_BIN="$(find_jdk_bin || true)"
[ -n "${JDK_BIN}" ] || die "no JDK found for apksigner. Set JAVA_HOME=/path/to/jdk, or install one."
note "JDK for apksigner: ${JDK_BIN%/bin}"
PATH="${JDK_BIN}:${PATH}"

echo
echo "== Check 0: the artifact is the build to test =="
SIGNED="${REPO_ROOT}/dist/trigly-${VERSION}.apk"
UNSIGNED="${REPO_ROOT}/dist/trigly-${VERSION}-unsigned.apk"

UNSIGNED_RUN=0
if [ -f "${SIGNED}" ]; then
    APK="${SIGNED}"
    pass "filename has no -unsigned suffix: $(basename "${APK}")"
elif [ -f "${UNSIGNED}" ] && [ "${ALLOW_UNSIGNED}" -eq 1 ]; then
    APK="${UNSIGNED}"
    UNSIGNED_RUN=1
    note "using $(basename "${APK}") because --allow-unsigned was given"
    note "SIGNING IS NOT VERIFIED in this run. This checks that R8 does not"
    note "break the release variant; it does not check the release key."
elif [ -f "${UNSIGNED}" ]; then
    fail "dist/ holds $(basename "${UNSIGNED}"): signing did not happen"
    fail "a smoke test of an unsigned file proves nothing; pass --allow-unsigned"
    fail "to test it anyway as a contributor without a signing key, or run"
    fail "scripts/setup-signing.sh to get a signed build"
    exit 1
else
    fail "no ${SIGNED} and no ${UNSIGNED}. Run :ui:distRelease first."
    exit 1
fi

if [ "${UNSIGNED_RUN}" -eq 1 ]; then
    note "certificate check skipped (--allow-unsigned)"
else
    # The expected value below is a certificate fingerprint, not a secret. A
    # certificate is the public half of the signing key: it ships inside every
    # signed APK for anyone to read with `apksigner verify --print-certs`, and
    # docs/releasing.md already treats it as the public label that identifies
    # which key produced a build. Keeping it here means an unexpected key is
    # caught by this script, not only by a person who happens to compare it by
    # hand. The password that makes a new signature is the actual secret, and
    # it is never in this repository; this hash cannot be used to sign anything.
    EXPECTED_CERT="82adfe55e213ca3df6d0eb905042215c24afe9ccb1b5e53a57d1f01603a5cfdb"
    CERT="$("${BT}/apksigner" verify --print-certs "${APK}" 2>/dev/null \
            | grep -i 'SHA-256 digest' | head -1 | tr -d ' ' | cut -d: -f2-)"
    if [ -z "${CERT}" ]; then
        fail "apksigner printed no certificate: DOES NOT VERIFY, or java is not on PATH"
    elif [ "${CERT}" = "${EXPECTED_CERT}" ]; then
        pass "signed by the real key (82adfe55...)"
    else
        fail "signed by an UNEXPECTED key: ${CERT}"
        fail "the replaced throwaway is 60c73cd5...; anything else is worse"
    fi
fi

BADGING="$("${BT}/aapt2" dump badging "${APK}" 2>/dev/null | head -1)"
# A here-string, not `echo ... | grep -q`: pipefail is set above, and grep -q
# exits the moment it finds a match, which can close the pipe out from under
# echo before echo finishes writing. echo then dies of SIGPIPE, and pipefail
# reports that as the pipeline's exit status even though grep matched, which
# turns a real PASS into a FAIL. A here-string has no second process to race.
if grep -q "versionName='${VERSION}'" <<< "${BADGING}"; then
    pass "manifest versionName is ${VERSION}"
else
    fail "manifest disagrees with the filename: ${BADGING}"
fi

echo
echo "== Check 1: R8 kept the components the manifest names =="
unzip -o -q "${APK}" "classes*.dex" -d "${WORK}/dex" || { fail "no dex in the APK"; exit 1; }
note "$(ls "${WORK}"/dex/*.dex | wc -l) dex file(s)"
strings "${WORK}"/dex/*.dex > "${WORK}/dexstrings.txt"

# Read the class names out of the APK's own manifest rather than from a list
# kept here. A list in this file is a list somebody has to remember to update:
# docs/releasing.md named four classes, and by 0.1.0 the manifest named eleven,
# so the two accessibility and notification services and the backup agent were
# never checked at all. Asking the artifact removes that whole class of drift,
# and a component added later is covered without editing this script.
#
# android:name carries the activities, services and receivers. android:backupAgent
# is a separate attribute and is missed by any pattern that only reads
# android:name, which is how TriglyBackupAgent escaped the old list.
# Track which element each attribute belongs to. android:name means a class only
# inside a component element: on <action> it is an intent action and on
# <uses-permission> it is a permission, and both of those are strings that live
# in the dex for their own reasons. Reading them as classes would report a PASS
# for something this check never verified, which is worse than checking less.
"${BT}/aapt2" dump xmltree --file AndroidManifest.xml "${APK}" 2>/dev/null \
    | awk '
        /^[[:space:]]*E:/ {
            element = $2
            sub(/\(.*/, "", element)
            next
        }
        /android:(name|backupAgent)\(/ {
            if (element !~ /^(application|activity|activity-alias|service|receiver|provider)$/) next
            if (match($0, /"[^"]*"/) == 0) next
            value = substr($0, RSTART + 1, RLENGTH - 2)
            if (value ~ /^app\.phueber\.trigly\./) print value
        }
    ' | sort -u > "${WORK}/components.txt"

COMPONENTS="$(wc -l < "${WORK}/components.txt")"
if [ "${COMPONENTS}" -eq 0 ]; then
    fail "read no component names from the manifest: the dump or the filter is wrong"
else
    note "the manifest names ${COMPONENTS} class(es) of this app"
    missing_components=0
    while read -r c; do
        # Match the class name as the platform stores it, so a partial match on
        # some other string cannot pass for the component itself.
        if grep -qF "${c##*.}" "${WORK}/dexstrings.txt"; then
            pass "${c}"
        else
            fail "${c} MISSING: the platform instantiates it by name, so this is fatal"
            missing_components=$((missing_components + 1))
        fi
    done < "${WORK}/components.txt"
    if [ "${missing_components}" -eq 0 ]; then
        note "every class the platform instantiates by name survived R8"
    fi
fi

echo
echo "== Check 2: the stored type strings survived =="
grep -rhoE 'const val TYPE = "[a-z0-9_]+"' "${REPO_ROOT}/triggers/src/main" "${REPO_ROOT}/actions/src/main" \
    | grep -oE '"[a-z0-9_]+"' | tr -d '"' | sort -u > "${WORK}/types.txt"
# Strip the dex length-prefix byte before comparing: a nine-character string is
# prefixed with 0x09, a tab, which otherwise makes a present string look absent.
sed -e 's/^[[:blank:]]*//' "${WORK}/dexstrings.txt" | sort -u > "${WORK}/dex.txt"
MISSING="$(comm -23 "${WORK}/types.txt" "${WORK}/dex.txt")"
DECLARED="$(wc -l < "${WORK}/types.txt")"
if [ -z "${MISSING}" ]; then
    pass "all ${DECLARED} type strings are in the APK"
else
    fail "type strings MISSING from the APK, every saved rule using one breaks:"
    echo "${MISSING}" | sed 's/^/          /'
fi

echo
echo "== Check 3: classes R8 removed outright (needs an eye) =="
USAGE="${REPO_ROOT}/ui/build/outputs/mapping/release/usage.txt"
if [ ! -f "${USAGE}" ]; then
    fail "no ${USAGE}"
else
    grep -E "^app\.phueber\.trigly" "${USAGE}" | grep -v ":$" > "${WORK}/removed.txt"
    COUNT="$(wc -l < "${WORK}/removed.txt")"

    # Shapes docs/releasing.md names as correct removals under "Did R8 delete an
    # app class outright?", one variable per bullet so the pattern reads instead
    # of being one long alternation. Each comment quotes enough of the sentence
    # in docs/releasing.md to find it again.
    #
    # "an `object` or a `$Companion` that holds only `const val`s, because the
    # compiler puts the value at every use and leaves the holder empty"
    #
    # Only the $Companion shape is filtered here. A top-level const-only object
    # (ScreenContentTrigger is the named example) is NOT matched by this and
    # stays in the unexplained list on purpose: check 2 is what proves its
    # string survived, and the note printed below points at it explicitly.
    COMPANION='\$Companion$'

    # "`*Kt` file facades, `R` classes, and a subclass R8 merges into its parent"
    KT_FACADE='Kt$'
    R_CLASS='(^|\.)R(\$[A-Za-z]+)?$'

    # "an interface's `$DefaultImpls`, once every implementation in the app
    # overrides the default. The default body then only serves the test fakes,
    # which are not in this build"
    DEFAULT_IMPLS='\$DefaultImpls$'

    # "an in memory implementation of a store or a repository, for the same
    # reason: the app builds the Room one, and only a test builds the other"
    IN_MEMORY='(^|\.)InMemory[A-Za-z]*'

    # These four are not named individually in docs/releasing.md. They are
    # Kotlin/R8 synthetic classes that hold no code of their own (an inlined
    # lambda copy, an anonymous lambda class, the array a `when` on an enum
    # compiles to, a numbered anonymous inner class), so they fall under the
    # same opening reasoning docs/releasing.md gives for the whole list: "the
    # list of shapes that hold nothing at runtime". Kept because removing them
    # from the filter would put dozens of harmless lines back in front of a
    # reader for no gain; if that stops being true, tighten this rather than
    # the shapes above.
    INLINED='\$\$inlined'
    LAMBDA='\$lambda'
    WHEN_MAPPINGS='\$WhenMappings$'
    NUMBERED_ANON='\$[0-9]+$'

    EXPLAINED="${COMPANION}|${KT_FACADE}|${R_CLASS}|${DEFAULT_IMPLS}|${IN_MEMORY}|${INLINED}|${LAMBDA}|${WHEN_MAPPINGS}|${NUMBERED_ANON}"
    grep -vE "${EXPLAINED}" "${WORK}/removed.txt" > "${WORK}/unexplained.txt"
    EXPLAINED_COUNT=$((COUNT - $(wc -l < "${WORK}/unexplained.txt")))
    note "${COUNT} class(es) fully removed; ${EXPLAINED_COUNT} match a shape"
    note "docs/releasing.md already explains."
    if [ ! -s "${WORK}/unexplained.txt" ]; then
        pass "nothing removed that the documented shapes do not cover"
    else
        note "These $(wc -l < "${WORK}/unexplained.txt") need an eye before the tag:"
        sed 's/^/          /' "${WORK}/unexplained.txt"
        note "ScreenContentTrigger is a known-correct one: it is a const-only"
        note "object, and check 2 is what covers it. See docs/releasing.md."
    fi
fi

echo
echo "== Check 4: shrinkResources kept what only code refers to =="
# The real claim is that each of these six specific resources survived, not
# that some count of matching lines is at or above six: a resource named
# differently could keep a stale count passing while the one that actually
# matters is gone. So check each by name, and report the broader grep count
# only as information alongside it.
EXPECTED_RESOURCES=(
    "drawable/ic_notification"
    "plurals/engine_watching"
    "string/engine_channel_description"
    "string/engine_channel_name"
    "string/engine_none_started"
    "string/engine_starting"
)
RESOURCES="$("${BT}/aapt2" dump resources "${APK}" 2>/dev/null)"
res_missing=0
# Here-strings throughout this check, not `echo "$RESOURCES" | grep ...`:
# pipefail is set above, and a `grep -q` that matches early can close the pipe
# before echo finishes writing its (large) output. echo then dies of SIGPIPE,
# and pipefail reports that as the pipeline's failure even though grep found
# what it was looking for, which would turn real PASSes into FAILs here.
for r in "${EXPECTED_RESOURCES[@]}"; do
    if ! grep -qE "resource 0x[0-9a-f]+ ${r}\$" <<< "${RESOURCES}"; then
        fail "${r} MISSING"
        res_missing=1
    fi
done
if [ "${res_missing}" -eq 0 ]; then
    pass "all ${#EXPECTED_RESOURCES[@]} expected engine_/ic_notification resources are present"
else
    fail "a dropped engine_ string costs the foreground service its"
    fail "notification, which the platform then refuses, which stops every rule"
fi
MATCHING="$(grep -cE "engine_|ic_notification" <<< "${RESOURCES}")"
note "${MATCHING} matching resource(s) total (informational; the count is not the assertion)"
if grep -q "license_apache" <<< "${RESOURCES}"; then
    note "license_apache raw resource present (only relevant once Attribution ships)"
fi

echo
if [ "${fails}" -eq 0 ]; then
    if [ "${UNSIGNED_RUN}" -eq 1 ]; then
        echo "Static half: all automated checks passed, but SIGNING WAS NOT VERIFIED"
        echo "(--allow-unsigned). This is not sufficient for cutting a release."
    else
        echo "Static half: all automated checks passed. Check 3 still needs an eye."
    fi
else
    echo "Static half: ${fails} FAILED. Do not tag."
fi
exit "${fails}"
