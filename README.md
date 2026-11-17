# Dialcode

A small, offline TOTP authenticator for Wear OS, built for a Galaxy Watch 6 Classic. It reads Proton Authenticator exports and plain `otpauth://` lists. It is not made by or affiliated with Proton.

- TOTP (SHA1, SHA256, SHA512, 6 to 8 digits, any period), HOTP and Steam Guard codes.
- Plain Android Views and a Wear tile. The signed release APK is about 390 KB.
- No permissions at all: no internet, no sync service, no jobs, no alarms, no boot receiver, no wake locks. The app only runs while it's on screen, and the tile only when the watch asks for it.
- Accounts are stored in one AES-256-GCM file. The key lives in the Android Keystore, can't be exported, and only works while the watch is unlocked.
- Screenshots and the recents preview are blocked on the code screen (FLAG_SECURE). Backups and device-to-device transfer are turned off.
- Package `dev.spundone.dialcode`.

## Build

You need JDK 17 (`/opt/homebrew/opt/openjdk@17`) and the Android SDK with platform 34 and build-tools 34.0.0.

```bash
scripts/build-release.sh
```

This writes `dist/dialcode.apk`. On the first run it creates the signing key at `~/.config/dialcode/release.jks` and puts its random password in the macOS Keychain item `dialcode-release-keystore`. Keep that keystore: updates must be signed with the same key, or Android refuses to install them over the old version.

## Install

Turn on Settings > Developer options > Wireless debugging on the watch, then:

```bash
adb mdns services
```

```bash
adb connect IP:PORT
```

```bash
adb install -r dist/dialcode.apk
```

## Move your accounts over from Proton Authenticator

1. In Proton Authenticator on your phone or Mac, open Settings and export your codes. Leave the password empty. Dialcode can't read password-protected exports and says so if you push one.
2. With the watch connected and unlocked, run:

   ```bash
   scripts/push-to-watch.sh ~/Downloads/your-export.json
   ```

   The script copies the file into Dialcode's folder on the watch and opens the app. The app reads the file, saves the accounts encrypted, then overwrites and deletes the file. If the watch is locked, unlock it and open Dialcode; the file waits there until then.
3. The script asks whether to delete the export from your Mac. Say yes. The export contains every 2FA secret in plain text.

Check two or three codes against Proton before you rely on Dialcode.

### Keeping it in sync

There's no live sync: the app has no network access, so it can't talk to Proton. To update the watch, export again and run the same command. That mirrors the export: new accounts appear, changed ones update, and accounts you deleted in Proton disappear from the watch. Your tile pins and HOTP counters are kept.

To add accounts without removing anything, use `--merge`. This also takes a text file with one `otpauth://` URI per line (lines starting with `#` are ignored):

```bash
scripts/push-to-watch.sh --merge new-accounts.txt
```

If more than one watch is connected, set `ANDROID_SERIAL` to pick one.

## Using it

- The ring around the edge counts down the 30-second window and turns red for the last 5 seconds. Codes with other periods show their own countdown chip.
- Turn the bezel to scroll.
- Tap an HOTP code to move to the next one.
- Long-press any code to show it on the tile, move it to the top, or delete it.

### Tile

Add the tile from the watch: swipe to the end of your tiles, tap +, and pick "Dialcode codes". It shows up to three codes you've chosen with "Show on tile", in list order. The tile gets codes for the current and the next window and asks for new ones when those run out. The countdown arc is worked out by the watch's tile renderer from its own clock, so Dialcode isn't woken every second. Samsung's renderer won't anchor a shrinking arc at 12 o'clock, so on the tile the arc drains toward the top from both sides instead of clockwise.

Tiles can't block screenshots, so only pin codes you're comfortable having on the tile.

## Removing the Proton watch app

Only do this after Dialcode shows the right codes for every account. Removing Proton's watch app doesn't touch your Proton account or the phone app. Either:

- on the watch, open the app list, long-press Proton Authenticator and choose Uninstall, or
- from the Mac:

  ```bash
  adb uninstall proton.android.authenticator
  ```

If the Play Store reinstalls it, turn off automatic installation of watch apps in the Galaxy Wearable app on your phone, or uninstall it again from the Play Store on the watch.

## Development notes

- `./gradlew assembleDebug` builds "Dialcode Preview" (`dev.spundone.dialcode.preview`). It installs next to the real app and doesn't block screenshots, so you can check the UI. Only put generated test secrets in it. Push to it with `DIALCODE_PKG=dev.spundone.dialcode.preview scripts/push-to-watch.sh ...`.
- The OTP code was checked against the RFC 4226 and RFC 6238 test vectors. On the watch, codes were compared with a Python reference computed on the Mac using generated test secrets. Release-build codes were read with `uiautomator dump`, since FLAG_SECURE blanks screenshots.
- Proton's export format: `{"version": 1, "entries": [{"id", "content": {"uri", "entry_type": "Totp"|"Steam", "name"}, "note"}]}`, from `proton-authenticator/src/entry/exporter.rs` in `protonpass/proton-pass-common`. Encrypted exports are `{"version", "salt", "content"}`.
- Colours are a fixed Material 3 Expressive dark scheme (mint primary, amber notices) in `Palette.java`. One UI Watch's dynamic palette was too washed out on the AMOLED screen.
- `Account.java` parses URIs and generates codes, `Otp.java` holds the HOTP/TOTP/Steam maths, `Vault.java` does the Keystore encryption, `Importer.java` handles imports, `MainActivity.java` is the UI and `CodesTileService.java` is the tile.
