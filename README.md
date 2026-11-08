# Custom Frame

An Android photo slideshow for Android picture frames, originally tested on a 10.1-inch Nixplay Smart Digital Picture Frame. Downloads JPEG and PNG photos from Dropbox for offline playback, with remote-friendly settings and a D-pad keyboard.

## Build and install

Requires JDK 17, Android SDK 36, and Android 7.1 / API 25 or later. Set the SDK location in Android Studio or an untracked `local.properties` (`sdk.dir=/path/to/Android/sdk`).

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Use `gradlew.bat` on Windows, or build, install, and launch from Windows PowerShell:

```powershell
.\scripts\launch.ps1
```

Validation:

```sh
./gradlew testDebugUnitTest lintDebug
powershell.exe -NoProfile -File tests/FrameScripts.Tests.ps1
```

Release builds use R8 shrinking and require your own signing configuration.

## Configure the frame

### Connect and debug your Nixplay

For initial USB access, see E.Z. Hart's [Digital Frame Hacking — Part 1](https://ezhart.com/posts/digital-frame-hacking-1). Follow [Part 2: Remote Development](https://ezhart.com/posts/digital-frame-hacking-2), linked from [ClearFrame](https://github.com/hartez/ClearFrame), to connect over Wi-Fi with ADB and debug from Android Studio. These posts describe a Nixplay W13D; hardware and firmware may differ on your frame. Use this project's provisioning instructions below to configure the home app and Nixplay components.

### Provisioning

Run from Windows PowerShell in the project root with the frame connected and authorized over ADB:

```powershell
.\scripts\provision.ps1 -CheckOnly
.\scripts\provision.ps1
```

Setup requires Android user 0, API 25+, both Nixplay packages (`com.kitesystems.nix.frame` and `com.kitesystems.nix.prod`), and `cmd package set-home-activity`. Hardware sleep requires a matching bundled device profile and existing debuggable `run-as` access. Unknown versions are rejected before setup changes; normal APK installation remains available with display blanking as the fallback. See the supported versions and contribution guide below.

The script builds and installs the debug APK, saves the original HOME and package/component states to `.rollback/<serial>/state.json`, and makes Custom Frame HOME. It disables Nixplay UI, background services, non-power receivers, and the companion package, retaining only sleep/wake receivers. Nixplay cloud features, motion wake, updates, and scheduling become unavailable. The vendor process still initializes for power requests, but its UI activities remain disabled.

Keep `.rollback/` for restoration. Existing snapshots are never overwritten, and setup attempts restoration if installation or configuration fails. No root, device-admin enrollment, firmware flashing, or reboot is required.

Script options and tools:

- ADB: `-AdbPath`, `ADB_EXE`, PATH, or the Android SDK. Select a device with `-Serial SERIAL` or `ANDROID_SERIAL`.
- Java: `JAVA_HOME`, PATH, or Android Studio's bundled JDK. Setup needs JDK 17+ and checks build dependencies before installation; downloads may require network access.
- `-CheckOnly` checks the device and basic tools without building or changing it.
- If PowerShell blocks execution, use `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\provision.ps1` (also works for the other scripts).

After provisioning, check rebooting, remote controls, and ADB reconnection.

### Supported Nixplay configurations

| Nixplay app version | Version code | Tested hardware                                | Power protocol                            | Profile                                                                       |
| ------------------- | ------------ | ---------------------------------------------- | ----------------------------------------- | ----------------------------------------------------------------------------- |
| 8.2.35              | 26071516     | Nixplay Smart Digital Picture Frame, 10.1-inch | SleepReceiver / WakeupReceiver broadcasts | [nixplay-8.2.35.json](app/src/main/assets/device-profiles/nixplay-8.2.35.json) |

Profiles match the installed **app version code**, not a device serial number. Hardware listed here records testing evidence; it does not guarantee that every device with that app version behaves identically. Android API 25+, user 0, package isolation, and exact wake alarms remain requirements for hardware sleep. Other Android devices can use the slideshow and display blanking without Nixplay provisioning.

To support your version, copy the closest profile into `app/src/main/assets/device-profiles/`, give it a unique ID and exact version code, inspect its APK components, and update the disabled-component list. Keep the sleep/wake receivers enabled. Run setup with `-CheckOnly`, test setup, scheduled sleep/wake, remote wake, reboot, and rollback on your hardware, then submit the profile, hardware/software test details, and a row in this table. **Do not just change the version number without inspecting and testing the APK.** See [the device profile contribution guide](docs/device-profiles.md) for commands and the PR checklist. Both provisioning and the built app read these same profiles; adding support for another version with the existing receiver protocol requires no Kotlin changes.

### Roll back provisioning

```powershell
.\scripts\provision-rollback.ps1 -CheckOnly
.\scripts\provision-rollback.ps1
```

Rollback verifies the serial and firmware fingerprint, restores recorded HOME and package/component states, opens HOME, requests a screen wake, and archives the snapshot. It stops Custom Frame and cancels its wake alarms when restoring another HOME or the original chooser.

The app and its data stay installed; rollback does not restore an older APK or reverse data migrations. Older schema 1 snapshots restore package/HOME states only. Neither setup nor rollback reboots.

### Update the app

After initial setup, connect the frame and run from Windows PowerShell:

```powershell
.\scripts\launch.ps1
```

This builds your current local source, installs with `adb install -r`, and launches the app. Pull source updates first if needed. For multiple devices, use `.\scripts\launch.ps1 -Serial SERIAL`; supply `-AdbPath` or set `ADB_EXE` to the Windows path of `adb.exe` if needed.

Set `$env:ANDROID_SERIAL = 'SERIAL'` for multiple devices. Updates preserve settings, photos, HOME, Nixplay configuration, and the rollback snapshot. Keep the same application ID and signing key; resolve signing mismatches with the original key. Do not uninstall or rerun setup for updates.

### Restart Android

```powershell
.\scripts\reboot.ps1 -CheckOnly
.\scripts\reboot.ps1
```

Sends `adb reboot`, including with the battery connected; it does not physically disconnect power. Requires an authorized device with Nixplay installed and uses the ADB options above. Allow time for boot and ADB reconnection after the command returns.

## App settings

Open Settings with the remote's Settings button.

1. **Wi-Fi:** scan, select a network, enter its password, and Connect. Scanning needs location permission and may need location services. Android throttles repeated scans. Android 10+ uses system Wi-Fi settings; the older in-app connector does not support enterprise Wi-Fi.
2. **Dropbox:** enter an access token with file metadata/content read permissions and a folder such as `/Photos/Frame`. `/` or blank uses the accessible root; subfolders are included. App Folder accounts see their app folder as root. Tokens must be replaced manually when expired or revoked; OAuth sign-in and refresh are not supported.
3. **Save:** queues sync when credentials change or the last successful sync is over ten minutes old. A blank token disables downloads. Background sync runs roughly hourly with connectivity, subject to Android scheduling.

### Slideshow settings

In the **Slideshow settings** card, **Show blurred background image** is enabled by default. Turn it off to show solid black behind the primary photo. Select **Save** to apply and remember your choice.

### Time and sleep

The **Time and wake window** card opens system date, time, and time-zone settings. Disable automatic time to set the clock manually. Clock changes apply immediately; save wake-window edits to apply them.

The default wake window is **8:00 AM–10:00 PM** in the frame's local time zone. Overnight windows are supported; equal start/end times or disabling the schedule keeps the frame awake all day.

On the supported, isolated Nixplay firmware, the app uses vendor receivers to turn the display off/on and arms an exact wake alarm before sleeping. Alarms survive process reclamation and are rebuilt after boot, updates, and clock/time-zone changes. Without supported hardware control or exact-alarm access, it shows black at minimum brightness, which may leave a faint glow. Newer Android versions may require exact-alarm access in system settings.

During scheduled sleep, Power wakes the app until the next window ends; press again to sleep until the next start. Other remote buttons are ignored while blanked. Overrides survive activity/process recreation but reset on schedule saves, reboot, or clock/time-zone changes. The tested remote uses F1/key code 131; firmware may intercept the standard Android Power key.

## Slideshow controls

| Control         | Action                |
| --------------- | --------------------- |
| Left / right    | Previous / next photo |
| Center / Enter  | Pause / resume        |
| Power (131)     | Sleep / wake display  |
| Slideshow (132) | Return to slideshow   |
| Settings (134)  | Open settings         |
| Back            | Navigate back         |

Photos advance every ten seconds with a half-second fade, fitted over a dimmed, blurred background by default. Disable **Show blurred background image** in Settings to use black behind the photo. The sync badge shows background work; downloaded photos remain available offline.

## Storage and sync

Each sync selects the configured number of photos (1–200; leave blank for Automatic), favoring those shown less recently. The app stores a record of when photos were shown in a Room database. Downloads are written to temporary `.part` files, and a lock ensures that only one sync job runs at a time. Failures retry through WorkManager without updating the successful-sync timestamp.

Photos live in private `files/photos` storage. Tokens and Wi-Fi credentials use app-private preferences without additional encryption; app backup is disabled. Clearing app data or uninstalling removes photos and settings.

Automatic batch size is computed each sync as `min(200, floor(max(0, free bytes − 100 MB) / 5 MB))`. An explicit setting overrides this estimate. Each download checks the reported image size against free space while reserving 100 MB; this is a best-effort reserve, since other apps can consume space concurrently.

After all selected downloads succeed, sync deletes images from previous batches, including deleted/renamed Dropbox photos and photos from previous accounts/folders, plus abandoned `.part` files. Display history is retained for photos still in Dropbox. Failed or space-limited downloads skip batch cleanup and retry, retaining previous photos (successful individual downloads may already have replaced or added files). No images are removed when folder listing fails. An empty Dropbox folder clears the local collection after a successful listing. If Automatic computes zero for a nonempty folder, sync retries without clearing photos.

Selected files are downloaded again on each sync. Replacement needs space for both the old batch and incoming photos; lower the batch limit or free device storage if sync cannot complete. Clearing app data removes photos and settings.

## Troubleshooting and application log

Settings → **Application log** shows the newest 32 KB of the current journal. The app keeps UTC timestamps, device uptime, process IDs, crashes (exception types and stack frames), sync starts/results/retries, settings saves, navigation, boot/update/time broadcasts, screen transitions, hardware power requests, and UI stalls lasting at least five seconds while foreground and interactive. Tokens, passwords, SSIDs, Dropbox paths, photo names, and exception messages are excluded from this journal.

The journal lives in app-private `files/diagnostics/`: `events.log` plus three rotated files, each at most 256 KB (about 1 MB total). It survives updates, process restarts, and reboots; clearing app data or uninstalling removes it. Routine writes use a bounded background queue, which may drop entries under extreme load. Crash writes are synchronous and best effort. Native crashes, abrupt system kills, power loss, or a runtime too stalled to execute the watchdog may leave no final entry; Android's own reports remain important.

From Windows PowerShell, collect diagnostics **before rebooting**:

```powershell
.\scripts\diagnostics.ps1
# Optional: -Serial SERIAL -AdbPath C:\path\to\adb.exe -OutputDirectory C:\path\to\report
```

The script saves all available journal files, Android logcat, retained ANR/crash reports, memory information, and activity state to a timestamped `.diagnostics/` folder. It reads the device without restarting apps or clearing logs. Journal extraction requires a debuggable installed build (`scripts/launch.ps1` installs one); the in-app viewer also works in release builds. Missing rotated files are normal. Android system reports can contain private data from other apps; review collected reports before sharing them.

### Dropbox

Dropbox API connections require Java SDK 7.0.0 or newer for Dropbox's 2026 certificate changes (the app uses the version catalog dependency). Older builds using SDK 5.4.4 can fail during the TLS handshake before token validation. Update the app first; if the journal then reports `InvalidAccessTokenException`, replace the saved Dropbox token in Settings and Save to queue a fresh sync. Failed syncs retain the downloaded collection for offline playback.

## License

Licensed under the [MIT License](LICENSE). Copyright (c) 2026 Brian Gradin.
