# Rotki Portfolio Companion

`mobile/` is an isolated Gradle build. It does not orchestrate or alter the repository's
Python, pnpm, or Cargo builds.

New production code follows the mandatory
[KMP and native architecture rules](docs/architecture.md). The existing bootstrap modules are
migrated incrementally to that target rather than through a build-breaking rewrite.

The production build currently contains:

- `core:common`: platform-neutral `Clock`, application-visibility/controller contracts, and the
  exhaustive lifecycle policy. It exposes only the coroutines `StateFlow` API required by that
  contract and has no protocol, transport, persistence, UI, or platform implementation;
- `core:model`: the first extracted KMP leaf module. It owns the project-defined
  `ExactDecimal` value type, its private BigNum backend, common characterization tests,
  generated vectors, and no UI or platform implementation;
- `core:protocol`: a platform-neutral KMP leaf containing the strict Companion JSON codec,
  duplicate-member/syntax scanner, strict scalar serializers, generated wire vocabulary, and
  fixed-width protocol value types and the Ktor-free Engine-origin parser, plus the bounded HTTP
  control-envelope decoder, discovery negotiation, authored error mapping, and the bounded WebSocket
  notification decoder with its typed refresh/snapshot notification model. It owns no Auth DTOs,
  transport, or Apple framework of its own;
- `core:network`: the reusable KMP Ktor execution boundary. It owns the hardened Companion client,
  bounded HTTP response handling, retry/session-renewal policies, and OkHttp/Darwin engine actuals.
  It depends only on `core:protocol` inside the project and remains hidden from Swift;
- `core:security-api`: the platform-neutral security-contract leaf. It owns the secret-free Pairing
  cleanup journal, Device-proof and idempotency ports, the redacted Pairing-record persistence
  contract, and the secure Snapshot-store contract with its revocable application-owned plaintext
  handle. It depends only on protocol value types and contains no native implementation;
- `core:testing`: the test-only KMP home for the single generated Companion protocol corpus and its
  shared fixture parser. Production source sets cannot depend on it;
- `feature:pairing:data`: the real Pairing data implementation. It owns strict QR decoding,
  registration DTOs and mapping, Pairing-specific request construction, and the Ktor-backed
  discovery/registration client. It implements the domain submission gateway, depends only on the
  approved core/domain leaves, and remains hidden from Swift;
- `feature:pairing:domain`: platform-neutral Pairing submission contracts, coarse secret-free
  outcomes, and opaque session/attempt ports;
- `feature:pairing:presentation`: the pure synchronous Pairing UDF state/action/reducer layer,
  depending only on Pairing domain contracts;
- `shared`: the single Swift-facing `RotkiShared` framework umbrella and the migration home for
  behavior not yet extracted, including challenge/proof and Access Session DTOs plus the
  facade-scoped Pairing ownership, lifecycle, durable registration, and cleanup transaction. Its
  existing `PairingFlow` and `PairingConnection` remain ABI-compatible adapters over the extracted
  feature ports and data implementation; one facade-scoped internal adapter supplies both.
  `shared` consumes `core:network` and Pairing data as implementation dependencies and re-exports
  only the stable public surfaces of `core:common`, `core:model`, `core:protocol`, and
  `core:security-api` through the single framework. Protocol credentials, data/network seams, codec
  mechanics, and wire vocabulary remain Kotlin-only and hidden from Objective-C and Swift;
- `android:navigation`: an Android-only leaf with no project dependencies. It owns the typed,
  argument-free Overview, Portfolio, History, and Sources destinations plus their authenticated
  `NavHost` and interim placeholder shell. The app maps authoritative root status to its narrow
  `HomeConnectionBannerState` input before entering the host;
- `androidApp`: a native Jetpack Compose Material 3 shell with `dev`, `stage`, and
  `prod` environment flavors, an Android-only Koin process composition root, CameraX/ML Kit
  scanning, Android Keystore-backed Device Keys, atomic Pairing records, and Android 17
  local-network permission recovery. It owns the fail-closed privacy and root-state guard outside
  `NavHost` and creates the navigation leaf only after authenticated authority is present. Room and
  the complete Atomic Design-based Rotki design system remain deliberately deferred;
- `iosApp`: a checked-in native SwiftUI host that imports `RotkiShared` and exercises the
  unpaired flow plus the four-destination shell on iOS Simulator. Camera, transport,
  persistence, and native iOS security remain deliberately disabled until their gates.

Run the host-side KMP and Android checks from this directory:

```bash
./gradlew --no-daemon mobileCheck
```

`mobileCheck` includes the non-mutating ktlint/detekt gate. Run it directly while iterating on Kotlin
quality and the Clean Architecture module-graph guard, or apply the canonical formatter locally:

```bash
./gradlew --no-daemon --continue qualityCheck
./gradlew --no-daemon qualityFormat
```

`qualityFormat` is a local developer command and never runs in CI.

Verify the checked-in cross-platform contract fixtures from the repository root:

```bash
uv run python mobile/core/model/tools/generate_exact_decimal_vectors.py
uv run python tools/scripts/generate_companion_protocol_vocabulary.py --check
uv run python mobile/shared/tools/generate_companion_protocol_fixtures.py --check
```

On Apple Silicon macOS, add the Kotlin/Native gates:

```bash
./gradlew --no-daemon appleCheck
```

`appleCheck` runs the iOS Simulator tests and device-test links for every KMP module, then links
the two `RotkiShared` umbrella frameworks consumed by the native iOS host.

Build and test the SwiftUI host on an installed iOS Simulator:

```bash
cd iosApp
xcodebuild -project RotkiCompanion.xcodeproj -scheme RotkiCompanion \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=latest' \
  -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO test
```

This simulator gate checks Swift/KMP interop and native navigation only. It is not evidence
for Secure Enclave, biometric enrollment, reinstall, or physical-device behavior; those
checks remain explicitly deferred until a physical iPhone is available.

Android platform-security instrumentation runs on two clean Gradle-managed devices:

```bash
./gradlew --no-daemon \
  :androidApp:pixel2Api28DevDebugAndroidTest \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect

./gradlew --no-daemon \
  :androidApp:pixel8Api36DevDebugAndroidTest \
  -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect
```

The first device covers the Android 9 / API 28 compatibility floor. The second covers
Android 16 / API 36, the current stable Android runtime. Android 17 / API 37 remains a
preview and is not used as the current-device acceptance gate. The API 37 build still
declares and requests `ACCESS_LOCAL_NETWORK` before contacting a private/LAN Engine; denial
is a recoverable Pairing state. HTTPS remains mandatory, with both system and user-installed
CA roots accepted for self-hosted Engines. CI installs each Google APIs system image in a
separate KVM-enabled matrix job and retains the managed-device reports.

Clean managed devices intentionally do not claim enrolled-biometric, TEE/StrongBox, OEM
fallback, or physical secure-deletion evidence. On an enrolled physical Android device, run
the opt-in production-store checks and authorize each visible prompt with a strong biometric:

```bash
./gradlew --no-daemon :androidApp:connectedDevDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.rotki.biometric=true
```

Before retiring the Android characterization spike, record the device/API/build fingerprint
and verify: PIN/device credential never appears as an allowed fallback; cancel and temporary
lockout retain Pairing while keeping the Snapshot locked; a biometric enrollment change makes
the old AES key unusable and deletes the Snapshot, Pairing record, Device Key, and AES marker;
and background/screen lock cancels an active prompt and revokes every plaintext handle. These
checks remain pending until an enrolled device is available; skipped opt-in tests are not
counted as passing evidence.

The build pins Kotlin 2.4.10, AGP 9.1.0, Gradle 9.5.0, JDK 17, and compile/target SDK
37. Kotlin 2.4.10 documents AGP support only through 9.1.0, while this local AGP reports
its tested compile SDK ceiling as 36.1. The project deliberately targets the current
platform SDK under ADR-0054, so that known compatibility warning is explicitly suppressed
until a Kotlin-compatible AGP release closes the matrix gap.
