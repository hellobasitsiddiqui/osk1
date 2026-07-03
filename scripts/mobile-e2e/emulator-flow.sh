#!/usr/bin/env bash
# emulator-flow.sh — OSK-160 Android emulator e2e flow (runs INSIDE the booted emulator step).
#
# WHY THIS IS A COMMITTED FILE, NOT AN INLINE `script:` -------------------------
# `reactivecircus/android-emulator-runner` does NOT run the action's `script:` as
# one shell process: it feeds it to the runner line-by-line, so each line executes
# under its OWN `/usr/bin/sh -c <line>`. Consequences that broke earlier runs:
#   * `set -o pipefail` is rejected outright — the per-line shell is dash, which
#     has no pipefail ("Illegal option -o pipefail").
#   * `set -eu` does NOT carry to later lines (each line is a fresh shell).
#   * a variable assigned on one line (e.g. `APK=$(find …)`) is EMPTY on the next,
#     so `adb install -r "$APK"` ran with no path ("filename doesn't end .apk").
# Moving the whole flow into this file and invoking it as a SINGLE
# `bash scripts/mobile-e2e/emulator-flow.sh` fixes all three: it is one bash
# process, so variables persist, `set -euo pipefail` works, and pipelines fail
# loudly. The action's `script:` is now just the one-line invocation.
#
# INPUTS (from the workflow's env) --------------------------------------------
#   SCREENSHOT_DIR  where per-milestone PNGs are harvested (default matches the driver).
#   CDP_PORT        local TCP port the WebView DevTools socket is forwarded to.
# Both have sane defaults so the script is runnable standalone for debugging.
#
# THE FLOW (what this proves) --------------------------------------------------
#   boot: install + launch the debug APK, screenshot the native/splash frame.
#   forward: discover the debuggable WebView's DevTools abstract socket and forward
#            it to CDP_PORT so the Node CDP driver can reach it.
#   drive: drive-webview.mjs attaches over CDP and asserts the home UI renders and
#          the signed-OUT sign-in screen (#auth-signin + #google-btn) renders,
#          harvesting one screenshot per milestone.
#   verify: exactly 3 per-step screenshots (01 boot, 02 home, 03 sign-in) exist.

set -euo pipefail

# Defaults keep the script self-contained; the workflow overrides both via env.
SCREENSHOT_DIR="${SCREENSHOT_DIR:-mobile-e2e-artifacts/screenshots}"
CDP_PORT="${CDP_PORT:-9222}"
export SCREENSHOT_DIR CDP_PORT

mkdir -p "$SCREENSHOT_DIR"

echo "::group::Install + launch the debug APK"
# Resolve the debug APK built by the prior `Assemble debug APK` step (same job,
# same workspace). `-print -quit` returns the FIRST match and stops — no `| head`,
# so there is no SIGPIPE to trip `pipefail`. Guard hard if it is empty: an empty
# path is what silently broke the earlier inline-script runs.
APK=$(find android/app/build/outputs/apk/debug -maxdepth 1 -name '*.apk' -print -quit)
if [ -z "$APK" ]; then
  echo "ERROR: no debug APK found under android/app/build/outputs/apk/debug" >&2
  echo "       (did the 'Assemble debug APK' step run in this job?)" >&2
  ls -lR android/app/build/outputs/apk 2>/dev/null || true
  exit 1
fi
echo "installing $APK"
adb install -r "$APK"
# Launch the single launcher activity (component is stable:
# dev.openskeleton.app/.MainActivity).
adb shell am start -n dev.openskeleton.app/.MainActivity
echo "::endgroup::"

echo "::group::Milestone 1 — boot / native splash screenshot"
# Capture straight after launch: this is the native shell + OSK-158 splash frame,
# taken before any WebView DevTools target exists (so it is done here with adb,
# not by the CDP driver). `exec-out` streams a raw PNG with no CRLF mangling.
sleep 3
adb exec-out screencap -p > "$SCREENSHOT_DIR/01-boot-splash.png"
test -s "$SCREENSHOT_DIR/01-boot-splash.png"
echo "captured 01-boot-splash.png"
echo "::endgroup::"

echo "::group::Forward the WebView DevTools socket"
# The debuggable WebView publishes its DevTools server on an abstract unix socket
# named webview_devtools_remote_<pid>. Discover the actual name from /proc/net/unix
# (robust to the pid) and forward it to CDP_PORT so the driver can reach
# http://127.0.0.1:$CDP_PORT/json. The `|| true` keeps a no-match grep from
# tripping `set -e`/`pipefail` while we are still polling for the socket.
SOCKET=""
for i in $(seq 1 30); do
  SOCKET=$(adb shell cat /proc/net/unix 2>/dev/null | tr -d '\r' \
    | grep -o 'webview_devtools_remote_[0-9]*' | head -n1 || true)
  if [ -n "$SOCKET" ]; then break; fi
  echo "waiting for the WebView devtools socket (attempt $i)…"
  sleep 2
done
if [ -z "$SOCKET" ]; then
  echo "ERROR: WebView remote-debugging socket never appeared" >&2
  adb shell cat /proc/net/unix | tr -d '\r' | grep -i webview || true
  exit 1
fi
echo "forwarding tcp:$CDP_PORT -> localabstract:$SOCKET"
adb forward "tcp:$CDP_PORT" "localabstract:$SOCKET"
echo "::endgroup::"

echo "::group::Milestones 2 & 3 — drive the WebView (home UI + signed-out sign-in)"
node scripts/mobile-e2e/drive-webview.mjs
echo "::endgroup::"

echo "::group::Verify the harvested screenshot count"
# Exactly three per-step milestone screenshots must have been harvested
# (01 boot, 02 home, 03 sign-in). 99-failure.png (a '9' prefix) is excluded by the
# 0*-*.png glob, so a failed run cannot pad the count.
count=$(find "$SCREENSHOT_DIR" -maxdepth 1 -name '0*-*.png' | wc -l | tr -d ' ')
echo "harvested $count per-step screenshots:"
ls -l "$SCREENSHOT_DIR"
if [ "$count" -ne 3 ]; then
  echo "ERROR: expected exactly 3 per-step screenshots, got $count" >&2
  exit 1
fi
echo "::endgroup::"
