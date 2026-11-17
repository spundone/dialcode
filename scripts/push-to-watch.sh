#!/bin/sh
# Push an export to Dialcode on the watch over adb.
#
#   scripts/push-to-watch.sh EXPORT.json          mirror: the watch list becomes exactly this export
#   scripts/push-to-watch.sh --merge FILE         merge: only add accounts the watch doesn't have
#
# FILE is an unencrypted Proton Authenticator export (.json) or a text file with one otpauth:// URI per line.
# The app picks the file up the next time it opens, encrypts the accounts, and wipes the file.
# Set ANDROID_SERIAL to choose a device; otherwise the first Galaxy Watch (SM-R96x) is used.
set -eu

PKG="${DIALCODE_PKG:-dev.spundone.dialcode}"
MODE=sync
if [ "${1:-}" = "--merge" ]; then MODE=import; shift; fi
FILE="${1:-}"
if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi

if [ -z "${ANDROID_SERIAL:-}" ]; then
  ANDROID_SERIAL=$(adb devices -l | awk '/model:SM_R96/ && $2=="device" {print $1; exit}')
fi
if [ -z "$ANDROID_SERIAL" ]; then
  echo "No watch found. On the watch: Settings > Developer options > Wireless debugging, then:"
  echo "  adb mdns services   # find the port"
  echo "  adb connect IP:PORT"
  exit 1
fi
export ANDROID_SERIAL

if ! adb shell pm path "$PKG" </dev/null >/dev/null 2>&1; then
  echo "Dialcode isn't installed on $ANDROID_SERIAL. Run: adb install -r dist/dialcode.apk"
  exit 1
fi

case "$FILE" in *.json) EXT=json ;; *) EXT=txt ;; esac
DEST="/sdcard/Android/data/$PKG/files/$MODE"
NAME="push-$(date +%s).$EXT"

# The app must create its own drop folders: a folder made by adb's shell user can't be read by the app.
if ! adb shell "[ -d $DEST ]" </dev/null; then
  adb shell am start -n "$PKG/dev.spundone.dialcode.MainActivity" </dev/null >/dev/null 2>&1
  i=0
  while ! adb shell "[ -d $DEST ]" </dev/null && [ $i -lt 10 ]; do sleep 1; i=$((i + 1)); done
  if ! adb shell "[ -d $DEST ]" </dev/null; then
    echo "Dialcode hasn't set up its folders yet. Unlock the watch, open Dialcode once, then run this again."
    exit 1
  fi
fi
adb push "$FILE" "$DEST/$NAME" </dev/null >/dev/null 2>&1
adb shell am start -n "$PKG/dev.spundone.dialcode.MainActivity" </dev/null >/dev/null 2>&1
if [ "$MODE" = sync ]; then
  echo "Pushed. Dialcode is opening on the watch and will sync to this export."
else
  echo "Pushed. Dialcode is opening on the watch and will add any new accounts."
fi
echo "If the watch is locked, unlock it and open Dialcode; the file waits until then."

printf "Delete %s from this Mac now? [y/N] " "$FILE"
read -r ans || ans=n
case "$ans" in
  y|Y|yes) rm -f "$FILE" && echo "Deleted." ;;
  *) echo "Kept. It holds your 2FA secrets unencrypted, so delete it when you're done." ;;
esac
