# Foglietto

A small, temporary notepad for iPhone and Android. Open it, write something,
copy or share it, and move on. **Every time you come back the page is blank**;
the last ten notes you were writing are kept in **Recent notes** in case you
need one back.

Foglietto is built with native SwiftUI and UIKit on iOS 26, and native Jetpack
Compose on Android, over a Kotlin Multiplatform module for editing logic,
settings and local recovery storage. Each platform uses its own design language:
Liquid Glass and native menus on iOS, Material 3 on Android.

## What you can do

- Write plain text with automatic Markdown list continuation.
- Copy and paste the whole buffer, and indent or unindent lines from the bottom
  toolbar.
- Share text through the system share panel.
- Clear the page. The text goes to Recent notes first, so it is not lost: on iOS
  the clear is a normal undo, on Android you get a one-tap restore in the
  confirmation bar.
- Restore one of the last ten saved notes from the menu.
- On iOS, open the recovery text files directly through the Files app.

Text is saved to Recent notes when the app goes to the background, when you
clear the page, and before an incoming share replaces it. A page that is only
whitespace is never saved. Recovery is a short safety net for an ephemeral
notepad, not a notebook: older notes fall out as new ones arrive.

The interface follows the system appearance — light and dark — and the strings
are localized with an English fallback.

**Nothing leaves your device.** Foglietto has no account, no sync, no
analytics, and asks for no network permission. What you write stays in local
files.

## Availability

The iOS version requires **iOS 26 or later** and is being tested through
TestFlight. The Android version is tested through Google Play internal testing.
Both are still under development.

## Development

```text
shared/     Kotlin Multiplatform domain and data code, with common and JVM tests
iosApp/     Native iOS app, Swift package bridge, unit tests and UI tests
androidApp/ Native Android app (Jetpack Compose)
tool/       Structural verification scripts
.github/    Quality checks plus the iOS and Android build/release workflows
docs/       Product vision, behaviour spec and UI design bible (kept locally, not versioned)
```

### Shared code on Linux or macOS

Install **JDK 17**, then run:

```bash
./gradlew check
```

For a scoped test run:

```bash
./gradlew :shared:jvmTest
```

### iOS app on macOS

Use **Xcode 26** and JDK 17. Build the shared framework first:

```bash
./gradlew :shared:assembleSharedReleaseXCFramework
open iosApp/iosApp.xcodeproj
```

Select the `iosApp` scheme and an iOS 26 simulator or device. Device builds
require your own signing configuration. The local Swift package at
`iosApp/SharedPackage/Package.swift` references the generated XCFramework;
CocoaPods is not required.

GitHub Actions runs shared tests and structural checks on Linux, plus iOS
archives and simulator tests on macOS. Release tags beginning with `v` also
run the signed TestFlight upload using repository secrets.

### Android app

Install **JDK 17** and an Android SDK with platform 36, then:

```bash
./gradlew :androidApp:assembleDebug
```

GitHub Actions builds a signed release bundle and a release APK on every push
and pull request. On a `v*` tag — or a manual *Android Release* dispatch — it
uploads that bundle to the Google Play Console (internal testing by default).
The workflow needs these repository secrets:

- `ANDROID_KEYSTORE_BASE` — the upload keystore, base64-encoded.
- `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- `PLAY_SERVICE_ACCOUNT_JSON_BASE` — a Play Developer API service-account key,
  base64-encoded.

Without them the release bundle still builds, unsigned, and the log says so.

`versionName` defaults to `1.0.0`; `versionCode` starts from the Actions run
number. Set the repository variables `ANDROID_MARKETING_VERSION` and
`ANDROID_BUILD_NUMBER_OFFSET` to pin them (the offset must exceed any
versionCode already uploaded to Play).

## License

The Kotlin Multiplatform and native iOS/Android implementation is by Paolo
Santucci. Licensed under the [GNU General Public License v3.0](LICENSE).
