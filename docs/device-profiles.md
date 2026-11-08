# Contributing a device profile

The slideshow supports Android API 25+. Hardware power control is an optional Nixplay integration. Profiles describe inspected APK versions; they are not device backups. Never commit serials, credentials, pairing information, or `.rollback/`.

## Inspect your configuration

With your device authorized over ADB, collect:

```sh
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
adb shell dumpsys package com.kitesystems.nix.frame
adb shell run-as com.kitesystems.nix.frame id
adb shell pm path com.kitesystems.nix.frame
```

Pull the APK from the reported path with `adb pull` and inspect its manifest with Android SDK `apkanalyzer manifest print <apk>`. Inspect receiver implementation too: names alone do not prove broadcast compatibility. Do not commit proprietary APKs or full device dumps. Include relevant sanitized findings in the PR.

## Add the profile

Copy `app/src/main/assets/device-profiles/nixplay-8.2.35.json` to a descriptive new JSON filename. Set a unique `Id`, exact numeric `VersionCode`, and `TestedHardware` entries with model, Android version, firmware/build identifier, app version and test notes. Retain existing profiles. One profile per version code is supported; append hardware evidence to an existing profile for the same APK. Conflicting implementations sharing a version code need a code change to introduce a stronger discriminator, not duplicate profiles.

`Package` and `Protocol` currently support `com.kitesystems.nix.frame` and `nixplay-broadcast-v1`. This protocol sends explicit ACTION_SLEEP/ACTION_WAKEUP broadcasts to the existing SleepReceiver/WakeupReceiver classes, with the corresponding receiver extra and POWERBUTTON_PRESSED=true. Changed packages, receiver names, permissions, or broadcast semantics require an adapter change and testing, not merely a JSON entry.

`DisabledComponents` must list the inspected UI activities, non-power receivers and vendor background services to isolate. `PowerReceivers` must contain exactly the two supported power receivers. Lists must be unique and disjoint. Retain required platform/library services only after inspection. Runtime checks verify isolation in addition to matching the version, and fall back to display blanking if unavailable. Rebuild/reinstall the APK after adding or editing a profile; app updates also need the new bundled profile.

## Validate and submit

1. Run `powershell.exe -NoProfile -File tests/FrameScripts.Tests.ps1` and the README Android validation commands.
2. Run `scripts/provision.ps1 -CheckOnly` and confirm the selected profile. Unknown or ambiguous versions are rejected before configuration changes.
3. Provision and verify normal HOME, remote controls, photo playback and ADB connectivity.
4. Verify actual physical display sleep, scheduled wake, remote wake, overnight windows, and reboot/startup. Confirm wake still works after process reclamation. Record exact-alarm permission requirements.
5. Run rollback and verify original HOME, package/component states and screen wake. Preserve the local snapshot until this succeeds.
6. Submit the profile, README table row and sanitized test results, including model, app/version code, Android/API version, firmware identifier and limitations.

Schema 3 rollback snapshots embed the selected profile as `DeviceProfile` so restoration survives profile edits or removal. Rollback also accepts the legacy `PowerProfile` field in existing schema 3 snapshots. Schema 1/2 snapshots remain supported; the root `nixplay-power-components.json` is frozen for schema 2 compatibility. Keep actual `.rollback/` data local.
