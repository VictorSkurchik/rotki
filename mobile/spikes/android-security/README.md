# Android native-security spike (P0.3)

This is a disposable, standalone Android application for the Android half of P0.3. It
targets the Android 9 / API 28 floor and deliberately contains no KMP module, Engine client,
Compose UI, or production adapter. The independent iOS and SKIE experiments live in sibling
spike roots.

## Security contract exercised

- `AndroidKeyStoreProbe` creates a persistent P-256 `SHA256withECDSA` signing key in
  `AndroidKeyStore`. The private key reports no format or encoded bytes. Its public key is
  translated to the protocol's exact 65-byte uncompressed SEC1 representation.
- Device-proof signing receives the complete normative transcript, hashes it exactly once in
  the signature provider, and strictly translates the provider's DER signature to the exact
  64-byte IEEE P1363 form. The inverse translation rejects non-canonical DER, zero, negative,
  oversized, and out-of-range components.
- A different persistent AES-256 key permits only GCM/NoPadding encrypt/decrypt, requires user
  authentication for every use, opts into enrollment invalidation, and allows only
  `BIOMETRIC_STRONG`. The prompt never enables `DEVICE_CREDENTIAL`, so it has no PIN/pattern/
  password fallback.
- Every encryption initializes a fresh cipher and generated 12-byte IV. A bounded,
  versioned envelope stores only IV plus authenticated ciphertext in `noBackupFilesDir` via
  `AtomicFile`; the sample plaintext is never written.
- Typed state distinguishes missing hardware/enrollment, temporary unavailability, required
  security update, cancellation/lockout, key invalidation, locked, and repair-required.
  Missing prerequisites block Pairing. Cancellation and temporary lockout retain material.
  Enrollment invalidation or incomplete key creation destroys both aliases and the envelope.

Hardware backing is diagnostic, not an acceptance requirement: Android's documented security
boundary is a non-exportable Keystore key, and API 28 devices are not required to provide
StrongBox. A TEE/StrongBox result is recorded when the platform exposes it.

## Layout

- `app/src/main/.../AndroidKeyStoreProbe.kt` — exact key generation, biometric eligibility,
  signing, and authenticated-cipher boundary.
- `ProtocolEncoding.kt` and `ProofVector.kt` — strict transcript, SEC1, P1363/DER, and
  unpadded Base64URL encodings.
- `EncryptedBlobCodec.kt` and `EncryptedBlobStore.kt` — bounded `RP03` envelope, atomic
  no-backup storage.
- `SecurityStateMachine.kt` — prerequisite, authentication, lockout, and invalidation rules.
- `SecuritySpikeActivity.kt` — minimal manual harness; no production UI architecture.
- `src/test` — 17 host JVM tests for checked transcript bytes, strict encodings, envelope,
  and state transitions.
- `src/androidTest` — deterministic API/device Keystore and storage checks plus a separately
  opted-in biometric prompt suite.

The Android envelope is:

```text
"RP03" | version:u8 | iv-length:u8 | ciphertext-length:u32be | iv | ciphertext-and-GCM-tag
```

A fixed versioned domain separator is authenticated as AAD. Envelope decoding is strict
and bounded to 1 MiB; the serialized header is validated rather than included in the AAD.

## Frozen toolchain

| Component | Pin |
| --- | --- |
| Gradle wrapper | 9.5.0 with SHA-256 verification |
| Android Gradle Plugin | **9.1.0** |
| Kotlin compiler | 2.4.10 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 28 |
| AndroidX Biometric | 1.1.0 |
| AndroidX Fragment | 1.9.0 |
| AndroidX Test / ext JUnit | 1.7.0 / 1.3.0 |
| JUnit | 4.13.2 |

AGP 9 uses built-in Kotlin: the app applies only `com.android.application`, and the root
buildscript overrides AGP's bundled compiler with
`classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")`. It intentionally does not
apply `org.jetbrains.kotlin.android` or enable the built-in-Kotlin opt-out flags.

The local AGP 9.1.0 metadata reports that it was tested through compile SDK 36.1 and warns
for compile SDK 37. That warning is retained rather than silently moving outside the
reviewed 9.1.0/Kotlin 2.4.10 compatibility row. The local command-line tools also report an
SDK XML v3/v4 skew; neither warning failed compilation, lint, or packaging.

## Automated verification

From this directory:

```sh
./gradlew --no-daemon p03AndroidBuild
./gradlew --no-daemon p03AndroidApi28Check
```

`p03AndroidBuild` runs JVM tests, lint, and assembles both the debug app and instrumentation
APK. `p03AndroidApi28Check` creates a clean Gradle-managed Pixel 2 API 28 Google image and
runs the non-interactive suite. On a clean image, that suite proves the signing key behavior,
the exact protocol vector, no-backup envelope behavior, and that AES setup is refused when no
strong biometric is enrolled. The interactive prompt tests are skipped unless explicitly
enabled.

Observed locally on 2026-08-13:

- `p03AndroidBuild`: passed (17 JVM tests, lint, app APK, and test APK);
- `p03AndroidApi28Check`: passed on a clean managed Pixel 2 API 28 (6 non-interactive
  tests passed; 3 explicitly opt-in biometric-prompt tests skipped).

## API 28 biometric run

Gradle-managed devices are intentionally clean and cannot perform biometric enrollment as a
normal non-interactive test API. For the prompt round-trip, create a disposable API 28 AVD,
boot it with cold state, set a screen PIN, and enroll fingerprint ID `1` once through Android
Settings. Enrollment is a trusted Settings flow; do not grant tests shell access to fake
Keystore authentication.

For Apple Silicon use `arm64-v8a`; Linux CI normally uses `x86_64`:

```sh
sdkmanager 'platforms;android-28' 'system-images;android-28;google_apis;arm64-v8a'
avdmanager create avd --force --name rotki-p03-api28 \
  --package 'system-images;android-28;google_apis;arm64-v8a' --device 'pixel_2'
emulator -avd rotki-p03-api28 -wipe-data -no-snapshot -no-boot-anim
adb wait-for-device
adb shell getprop ro.build.version.sdk
```

After completing PIN and fingerprint enrollment in Settings, install and run the opted-in
suite. Every prompt needs one injected touch and release; use a second terminal or a small
driver that waits for the prompt before each pair:

```sh
./gradlew --no-daemon :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -r \
  -e p03.biometric true \
  -e class org.rotki.mobile.spikes.security.BiometricAesGcmInstrumentedTest \
  org.rotki.mobile.spikes.security.debug.test/androidx.test.runner.AndroidJUnitRunner

# Once per visible prompt, from another terminal:
adb emu finger touch 1
adb emu finger remove
```

The opted-in suite checks the generated key's purposes, size, modes, padding, per-use auth,
and enrollment-invalidation metadata; distinct IVs for identical plaintext; authenticated
encrypt/decrypt including a stored reload; and GCM tag rejection. The harness Activity also
allows the same checks by hand and links to enrollment Settings.

To characterize state behavior manually:

1. Cancel and trigger temporary fingerprint lockout; observe that keys and envelope remain
   and the app stays locked.
2. Enroll or remove a fingerprint after creating material; the old AES key must become
   permanently invalid. The next use must delete both aliases plus the envelope and require
   Pairing again.
3. Confirm no credential-fallback button appears and a PIN cannot authorize the CryptoObject.
4. Background the Activity during a prompt; it cancels the operation and retains no plaintext.

`adb emu finger` simulates a recognized sensor event only after enrollment. It does not prove
real sensor hardware, real TEE/StrongBox residency, enrollment-invalidation behavior on a
vendor implementation, absence of an OEM credential fallback, resistance to a compromised
OS, or physical secure deletion. Those claims require the P0.3 physical Android acceptance
run. Emulator tests are debug evidence and cannot replace it.

## CI feasibility and supersession

The host gate is suitable for ordinary Linux CI. The clean managed API 28 suite is also CI
feasible with KVM/nested virtualization, an installed Google API 28 system image, adequate
disk, and emulator acceleration; without those it should be a separately reported platform
gate, not silently skipped. Enrolled biometric prompt automation is unsuitable for a hermetic
hosted runner because enrollment needs the trusted Settings UI. Keep it opt-in and retain a
physical-device checklist.

This root is evidence, not production architecture. M2.1 replaces its isolated Gradle shell
with the real `mobile/` build; M2.3 moves accepted encodings, Android security adapters, and
instrumentation coverage into production modules. Remove this spike after equivalent M2.3
coverage is green.

## Authoritative references

- [Android Keystore system](https://developer.android.com/privacy-and-security/keystore)
- [Authenticate using biometrics](https://developer.android.com/identity/sign-in/biometric-auth)
- [KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
- [BiometricPrompt](https://developer.android.com/reference/androidx/biometric/BiometricPrompt)
- [Android Emulator console fingerprint command](https://developer.android.com/studio/run/emulator-console#fingerprint)
- [Gradle-managed devices](https://developer.android.com/studio/test/gradle-managed-devices)
- [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Kotlin/AGP compatibility](https://developer.android.com/build/kotlin-support)
- [`../../docs/roadmap.md`](../../docs/roadmap.md) (P0.3 and M2.3)
- [`../../docs/protocol.md`](../../docs/protocol.md) (Device proof wire contract)
