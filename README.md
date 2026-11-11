# Foglietto

A small, temporary notepad for iOS. Open it, write something, copy or share it,
and move on. Each fresh launch starts with a blank page; the last 10 saved notes
are available in **Recent notes** when you need them again.

Foglietto is built with native SwiftUI and UIKit on iOS 26, with a Kotlin
Multiplatform module for editing logic, settings and local recovery storage.
The interface uses the system's Liquid Glass controls and native menus.

## What you can do

- Write and edit plain text, with automatic Markdown list continuation.
- Copy, paste, indent and unindent text from the bottom toolbar.
- Share text through the system share panel.
- Clear the page with an undoable action. The text is saved to recovery before clearing.
- Restore one of the last 10 saved notes from the menu.
- Access recovery text files through the iOS Files app.

Non-empty text is saved to recovery when the app enters the background.
Recovery is a short history for an ephemeral notepad, not a permanent notebook.
The interface follows the system appearance and includes localized strings with
English fallback.

## Availability

The iOS version requires **iOS 26 or later** and is being tested through
TestFlight. It is still under development.

An **Android version** lives in `androidApp/` — a native Jetpack Compose app over
the same shared module. GitHub Actions builds a signed release bundle and, on a
release tag, uploads it to Google Play internal testing.

## Development

```text
shared/     Kotlin Multiplatform domain and data code, with common and JVM tests
iosApp/     Native iOS app, Swift package bridge, unit tests and UI tests
androidApp/ Native Android app (Jetpack Compose)
tool/       Structural verification scripts
.github/    Quality checks plus the iOS and Android build/release workflows
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

- `ANDROID_KEYSTORE_BASE64` — the upload keystore, base64-encoded.
- `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- `PLAY_SERVICE_ACCOUNT_JSON_BASE64` — a Play Developer API service-account key.

`versionName` defaults to `1.0.0`; `versionCode` starts from the Actions run
number. Set the repository variables `ANDROID_MARKETING_VERSION` and
`ANDROID_BUILD_NUMBER_OFFSET` to pin them (the offset must exceed any
versionCode already uploaded to Play).

## License

The Kotlin Multiplatform and native iOS implementation is by Paolo Santucci.
Licensed under the [GNU General Public License v3.0](LICENSE).
