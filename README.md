# Dialcode

A small, offline 2FA code app for Wear OS watches. It shows your TOTP, HOTP and Steam Guard codes on your wrist and on a tile, and it never touches the network. It can take your accounts from a Proton Authenticator export or from setup keys typed on the watch. Dialcode isn't made by or affiliated with Proton.

- No permissions at all: no internet, no sync service, no background jobs, no alarms, no wake locks. The app only runs while it's on screen, and the tile only when the watch asks for it.
- Accounts are stored in one AES-256-GCM file. Its key lives in the watch's Android Keystore, can't be exported, and only works while the watch is unlocked.
- Screenshots and the recents preview are blocked on the code screen. Backups and device-to-device transfer are off.
- TOTP (SHA1, SHA256, SHA512, 6 to 8 digits, any period), HOTP and Steam Guard.
- The APK is about 390 KB. It needs Wear OS 3 or later (Android 11+).

Built and tested on a Galaxy Watch 6 Classic. It should work on any Wear OS 3+ watch, but nothing else has been tested yet.

## Set it up

You need a computer (macOS, Linux or Windows) with `adb`, on the same Wi-Fi as the watch. The watch has no network access, so accounts reach it over adb.

**1. Install adb**

- macOS: `brew install --cask android-platform-tools`
- Linux: `sudo apt install adb` (Debian/Ubuntu), `sudo dnf install android-tools` (Fedora) or `sudo pacman -S android-tools` (Arch)
- Windows: `winget install Google.PlatformTools`, or download [SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) and add the folder to your PATH

**2. Get the `dialcode` command**

Download `dialcode` (macOS and Linux) or `dialcode.ps1` (Windows) from the [latest release](https://github.com/spundone/dialcode/releases/latest), or clone this repository and use the copies in `scripts/`. On macOS and Linux, make it executable:

```bash
chmod +x dialcode
```

**3. Turn on Wireless debugging on the watch**

On the watch, open Settings > About watch > Software information and tap Software version until developer options are on. Then open Settings > Developer options and turn on ADB debugging and Wireless debugging.

**4. Run setup**

```bash
./dialcode setup
```

On Windows:

```powershell
.\dialcode.ps1 setup
```

Setup finds the watch on your network. The first time, it asks you for a pairing code: on the watch, tap Wireless debugging > Pair new device and type the address and code it shows. Setup then downloads the latest Dialcode release, checks it against `SHA256SUMS`, installs it and opens it.

**5. Follow the steps on the watch**

The watch walks you through adding accounts, picking codes for the tile, and adding the tile.

## Add your accounts

### From Proton Authenticator

1. In Proton Authenticator on your phone or computer, open Settings and export your codes. Leave the password empty: Dialcode can't read password-protected exports, and says so if you send one.
2. With the watch unlocked, run:

   ```bash
   ./dialcode sync ~/Downloads/your-export.json
   ```

   On Windows, run `.\dialcode.ps1 sync $HOME\Downloads\your-export.json`.

   Dialcode opens on the watch. It reads the file, saves the accounts encrypted, then overwrites and deletes the file. If the watch is locked, unlock it and open Dialcode. The file waits there until then.
3. The command asks whether to delete the export from your computer. Say yes, because the export holds every 2FA secret in plain text.

Check two or three codes against Proton before you rely on Dialcode.

### By typing a setup key on the watch

When a site shows a QR code for 2FA, it usually also offers a text key ("can't scan it?"). In Dialcode, tap "Type a setup key", then enter a name and that key. Typed keys use the usual settings: SHA1, 6 digits, every 30 seconds. For anything else, put an `otpauth://` URI in a text file and use `dialcode add`.

### Keeping in sync

There's no live sync, because the app has no network access. To update the watch, export again and run `dialcode sync` again. That mirrors the export: new accounts appear, changed ones update, and accounts you deleted in Proton disappear from the watch. Your tile picks and HOTP counters are kept, but accounts you typed on the watch are removed unless they're also in the export.

To add accounts without removing anything, use `add`. It also takes a text file with one `otpauth://` URI per line (lines starting with `#` are ignored):

```bash
./dialcode add new-accounts.txt
```

If more than one device is connected, set `ANDROID_SERIAL` to pick one.

## Using it

- The ring around the edge counts down the 30-second window and turns red for the last 5 seconds. Codes with other periods show their own countdown chip.
- Turn the bezel or crown to scroll.
- Tap an HOTP code to move to the next one.
- Long-press any code to show it on the tile, move it to the top, or delete it.

### Tile

To add the tile, swipe left from the watch face to the end of your tiles, tap +, and pick "Dialcode codes". It shows up to three codes you've picked, in list order. The tile gets codes for the current and the next window and asks for new ones when those run out. The watch's tile renderer draws the countdown arc from its own clock, so Dialcode isn't woken every second. On Samsung watches the arc shrinks toward the top from both sides, because Samsung's renderer won't anchor a shrinking arc at 12 o'clock.

Tiles can't block screenshots, so only pin codes you're comfortable having on the tile.

## Updating

```bash
./dialcode install
```

This installs the latest release over the old one and keeps your accounts. Updates must be signed with the same key as the version you have. If the install fails with a signature error, you'd have to uninstall Dialcode first, which deletes its accounts.

## Removing the Proton watch app

Only do this after Dialcode shows the right codes for every account. Removing Proton's watch app doesn't touch your Proton account or the phone app. Either:

- on the watch, open the app list, long-press Proton Authenticator and choose Uninstall, or
- from the computer:

  ```bash
  adb uninstall proton.android.authenticator
  ```

If the Play Store puts it back, turn off automatic installation of watch apps in your phone's watch app (Galaxy Wearable on Samsung), or uninstall it again from the Play Store on the watch.

## Building from source

You need JDK 17 and the Android SDK with platform 34 and build-tools 34.0.0.

```bash
scripts/build-release.sh
```

This writes `dist/dialcode.apk`. The script is for macOS: its first run creates a signing key at `~/.config/dialcode/release.jks` and puts its random password in the Keychain item `dialcode-release-keystore`. On Linux or Windows, set `DIALCODE_KEYSTORE` and `DIALCODE_KS_PASS` to your own keystore and run `./gradlew assembleRelease`. Keep the keystore, because updates must be signed with the same key. When you run `dialcode` from a clone, `install` and `setup` use `dist/dialcode.apk` if it exists.

`./gradlew assembleDebug` builds "Dialcode Preview" (`dev.spundone.dialcode.preview`). It installs next to the real app and doesn't block screenshots, so you can check the UI. Only put generated test secrets in it. Point the command at it with `DIALCODE_PKG=dev.spundone.dialcode.preview`.

## Development notes

- The OTP code was checked against the RFC 4226 and RFC 6238 test vectors. On the watch, codes were compared with a Python reference using generated test secrets.
- `dialcode` (POSIX sh) was run on macOS. `dialcode.ps1` mirrors it for Windows but hasn't been run on Windows yet.
- Proton's export format: `{"version": 1, "entries": [{"id", "content": {"uri", "entry_type": "Totp"|"Steam", "name"}, "note"}]}`, from `proton-authenticator/src/entry/exporter.rs` in `protonpass/proton-pass-common`. Encrypted exports are `{"version", "salt", "content"}`.
- The colours are a fixed Material 3 Expressive dark scheme (mint primary, amber notices) in `Palette.java`. One UI Watch's dynamic palette was too washed out on an AMOLED screen.
- `Account.java` parses URIs and generates codes, `Otp.java` holds the HOTP/TOTP/Steam maths, `Vault.java` does the Keystore encryption, `Importer.java` handles imports, `MainActivity.java` is the UI and setup wizard, and `CodesTileService.java` is the tile.
