# Rotki Portfolio Companion

`mobile/` is an isolated Gradle build. It does not orchestrate or alter the repository's
Python, pnpm, or Cargo builds.

The production build currently contains:

- `shared`: project-owned domain and behavior for Android, JVM test execution,
  `iosArm64`, and `iosSimulatorArm64`;
- `androidApp`: a native Jetpack Compose Material 3 shell with `dev`, `stage`, and
  `prod` environment flavors;
- no production iOS app yet; the Xcode project is added in Phase 5 after the real
  Android tracer gate.

Run the host-side Android and shared checks from this directory:

```bash
./gradlew --no-daemon mobileCheck
```

Verify the checked-in cross-platform contract fixtures from the repository root:

```bash
uv run python mobile/shared/tools/generate_exact_decimal_vectors.py
uv run python tools/scripts/generate_companion_protocol_vocabulary.py --check
uv run python mobile/shared/tools/generate_companion_protocol_fixtures.py --check
```

On Apple Silicon macOS, add the Kotlin/Native gates:

```bash
./gradlew --no-daemon :shared:iosSimulatorArm64Test \
  :shared:linkDebugTestIosArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 \
  :shared:linkDebugFrameworkIosArm64
```

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
preview and is not used as the current-device acceptance gate. CI installs each Google APIs
system image in a separate KVM-enabled matrix job and retains the managed-device reports.

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
