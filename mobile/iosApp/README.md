# rotki companion for iOS

This directory contains the checked-in native SwiftUI host for the KMP `shared` module. It
targets iPhone on iOS 17 or newer and uses the bundle identifier `com.rotki.companion`.

The current slice is intentionally narrow:

- it reads the current `CompanionFacade` and `PairingFlow` values from `RotkiShared`;
- it exposes the unpaired introduction and an honest pairing placeholder;
- it includes the native four-destination shell (Overview, Portfolio, History, Sources)
  behind a preview/UI-test launch argument;
- it does not use the camera, network, persisted pairing records, or native security APIs.

## Build

The Xcode build phase invokes `:shared:embedAndSignAppleFrameworkForXcode`; no project
generator or package manager is required. From `mobile/iosApp` on an Apple Silicon Mac:

```bash
xcodebuild \
  -project RotkiCompanion.xcodeproj \
  -scheme RotkiCompanion \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO \
  build
```

To verify the KMP framework independently, run from `mobile`:

```bash
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
```

## Test

List the installed simulator names, then run the unit and UI smoke tests against one of
them (the example uses the current Xcode default device name):

```bash
xcrun simctl list devices available

xcodebuild \
  -project RotkiCompanion.xcodeproj \
  -scheme RotkiCompanion \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=latest' \
  -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO \
  test
```

Debug and UI-test builds may launch with `--rotki-ui-tabs` to open the four-tab placeholder
shell directly. Release builds ignore this argument, so it cannot bypass pairing or recovery
states in production behavior.
