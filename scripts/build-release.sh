#!/bin/sh
# Builds a signed release APK into dist/.
# Keystore: ~/.config/dialcode/release.jks, password in the macOS Keychain item "dialcode-release-keystore".
# First run creates both.
set -eu
cd "$(dirname "$0")/.."
export JAVA_HOME="${DIALCODE_JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"  # AGP 8.5 needs JDK 17
KS="${DIALCODE_KEYSTORE:-$HOME/.config/dialcode/release.jks}"
ITEM=dialcode-release-keystore

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  chmod 700 "$(dirname "$KS")"
  PASS=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-28)
  security add-generic-password -U -s "$ITEM" -a "$USER" -w "$PASS"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 -alias dialcode \
    -keyalg EC -groupname secp256r1 -validity 10000 -dname "CN=Dialcode" \
    -storepass "$PASS" -keypass "$PASS" >/dev/null 2>&1
  chmod 600 "$KS"
  echo "Created keystore $KS (password in Keychain item $ITEM)"
fi

DIALCODE_KS_PASS=$(security find-generic-password -s "$ITEM" -w)
export DIALCODE_KS_PASS DIALCODE_KEYSTORE="$KS"
export VERSION_CODE="${VERSION_CODE:-$(( $(date +%s) / 60 ))}"
./gradlew --no-daemon -q :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/dialcode.apk
"$HOME/Library/Android/sdk/build-tools/34.0.0/apksigner" verify --print-certs dist/dialcode.apk | grep -E "Signer #1 certificate (DN|SHA-256)"
ls -l dist/dialcode.apk
