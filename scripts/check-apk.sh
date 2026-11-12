#!/usr/bin/env bash
# What an APK declares and carries, checked on the built file rather than believed from the build
# script. CI runs it on every push's builds and the release workflow on what it is about to publish.
#
#   scripts/check-apk.sh <apk> <kind> [release]
#
# <kind> is `standard` (the full build), `fdroid` or `notifications`:
#   - only `notifications` may declare a notification listener or name its permission; the other
#     two must have nothing of it in the manifest (Google Play Protect blocks installing an app
#     from outside the Play Store that can read notifications);
#   - `standard` and `notifications` carry the wake-word model, stored uncompressed, and the
#     engine's library for arm64; `fdroid` carries neither;
#   - with `release`, no x86_64 library (only an emulator would load it).
set -euo pipefail
apk="$1"; kind="$2"; release="${3:-}"
[ -f "$apk" ] || { echo "check-apk: no $apk" >&2; exit 1; }
fail() { echo "check-apk: $(basename "$apk"): $1" >&2; exit 1; }

aapt2="$(find "${ANDROID_HOME:?ANDROID_HOME is not set}/build-tools" -name aapt2 -type f | sort -V | tail -1)"
[ -x "$aapt2" ] || fail "no aapt2 under ANDROID_HOME"
manifest="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
listener=0
grep -qiE "notification_?listener" <<<"$manifest" && listener=1
case "$kind" in
  notifications) [ "$listener" = 1 ] || fail "the notifications build declares no notification listener" ;;
  standard|fdroid) [ "$listener" = 0 ] || fail "a $kind build must not declare a notification listener" ;;
  *) fail "unknown kind $kind" ;;
esac

files="$(unzip -v "$apk")"
models="$(awk '$NF ~ /^assets\/wakeword\/.*\.onnx$/' <<<"$files")"
if [ "$kind" = fdroid ]; then
  [ -z "$models" ] || fail "the fdroid build carries the wake-word model"
  ! grep -q " lib/.*libsherpa" <<<"$files" || fail "the fdroid build carries the wake-word engine"
else
  [ "$(wc -l <<<"$models")" = 3 ] || fail "expected the three model files in assets/wakeword"
  ! awk '$2 != "Stored"' <<<"$models" | grep -q . || fail "a model file is compressed (the engine's files must be stored)"
  grep -q " assets/wakeword/version$" <<<"$files" || fail "no assets/wakeword/version"
  grep -q " lib/arm64-v8a/libsherpa-onnx-jni.so$" <<<"$files" || fail "no arm64 wake-word engine"
fi
if [ "$release" = release ]; then
  ! grep -q " lib/x86" <<<"$files" || fail "a release must carry no x86 library"
fi
size="$(stat -c %s "$apk")"
echo "check-apk: $(basename "$apk"): $kind${release:+ $release}, $((size / 1024 / 1024)) MB, as it should be"
