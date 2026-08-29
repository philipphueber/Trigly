#!/usr/bin/env bash
#
# Drive the installed app by the text on screen, for the dynamic half of the
# release smoke test in docs/releasing.md.
#
# Checks 5 to 8 there need the app exercised through its own UI. A release build
# is not debuggable, so `run-as` cannot reach its files and no rule can be seeded
# from outside: the only way to make a rule is to make it the way a person does.
# This is the part that does the tapping. It does not decide anything, so the
# checks themselves stay in docs/releasing.md where the reasoning is.
#
# Usage:
#   smoke-ui.sh texts  <device>            list every label on screen
#   smoke-ui.sh tap    <device> <text>     tap the control with exactly this label
#   smoke-ui.sh tapish <device> <text>     tap the first control containing this
#   smoke-ui.sh has    <device> <text>     print yes or no
#   smoke-ui.sh fields <device>            print "x y" for each text field
#
#   <device> is an adb serial, such as emulator-5554. Pass it explicitly: this
#   test runs on two API levels and a command that guesses the device will
#   quietly exercise one of them twice.
#
# Three traps this handles, all of which cost time before it did:
#
#   1. A label can appear twice, once as the search box holding what was typed
#      and once as the result. Matching is scored so a real control beats an
#      EditText holding the same string, because tapping the search box looks
#      like nothing happening.
#   2. A dialog moves when the keyboard opens. Coordinates read before the
#      keyboard appeared then land outside the dialog and dismiss it, which
#      reads as the app rejecting the input. Read the field positions again
#      after the keyboard is up, which is what `fields` is for.
#   3. `input text` breaks on spaces. Use %s for a space, or type one word.
#
# It also cannot see anything the accessibility tree does not carry, so a
# control with no label and no content description is unreachable from here.
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/.." && pwd)"
FINDER="${HERE}/smoke-ui-find.py"

die() { echo "error: $*" >&2; exit 1; }

# Same SDK resolution as smoke-static.sh, for the same reason: ANDROID_HOME when
# somebody set it, otherwise what the build itself uses.
if [ -n "${ANDROID_HOME:-}" ]; then
    SDK="${ANDROID_HOME}"
elif [ -f "${REPO_ROOT}/local.properties" ] \
        && grep -q '^[[:space:]]*sdk\.dir[[:space:]]*=' "${REPO_ROOT}/local.properties"; then
    SDK="$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "${REPO_ROOT}/local.properties" | tail -1)"
else
    SDK="${HOME}/Android/Sdk"
fi
ADB="${SDK}/platform-tools/adb"
[ -x "${ADB}" ] || die "no adb at ${ADB}. Set ANDROID_HOME, or add sdk.dir to local.properties."
[ -f "${FINDER}" ] || die "no ${FINDER} beside this script"

usage() {
    awk 'NR>2 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
    exit "${1:-0}"
}

dump_to() { # dump_to <device> <file>
    "${ADB}" -s "$1" shell uiautomator dump /sdcard/trigly-ui.xml >/dev/null 2>&1
    "${ADB}" -s "$1" shell cat /sdcard/trigly-ui.xml 2>/dev/null | tr -d '\r' > "$2"
}

tap_by() { # tap_by <device> <text> <exact|sub>
    local f xy
    f="$(mktemp)"
    dump_to "$1" "${f}"
    xy="$(python3 "${FINDER}" "${f}" "$2" "$3")"
    rm -f "${f}"
    [ -n "${xy}" ] || { echo "NOT_FOUND: $2" >&2; return 1; }
    # shellcheck disable=SC2086
    "${ADB}" -s "$1" shell input tap ${xy}
    echo "tapped '$2' at ${xy}"
}

case "${1:-}" in
    -h|--help) usage 0 ;;
    texts)
        [ $# -ge 2 ] || usage 1 >&2
        f="$(mktemp)"; dump_to "$2" "${f}"
        grep -oE '(text|content-desc)="[^"]+"' "${f}" \
            | sed -E 's/^[a-z-]+="//; s/"$//' | grep -v '^$' | sort -u
        rm -f "${f}"
        ;;
    tap)    [ $# -ge 3 ] || usage 1 >&2; tap_by "$2" "$3" exact ;;
    tapish) [ $# -ge 3 ] || usage 1 >&2; tap_by "$2" "$3" sub ;;
    has)
        [ $# -ge 3 ] || usage 1 >&2
        f="$(mktemp)"; dump_to "$2" "${f}"
        if grep -qF "$3" "${f}"; then echo yes; else echo no; fi
        rm -f "${f}"
        ;;
    fields)
        [ $# -ge 2 ] || usage 1 >&2
        f="$(mktemp)"; dump_to "$2" "${f}"
        python3 "${FINDER}" "${f}" "" fields
        rm -f "${f}"
        ;;
    *) usage 1 >&2 ;;
esac
