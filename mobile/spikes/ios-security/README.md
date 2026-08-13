# iOS native-security spike (P0.3)

This disposable Swift package and minimal checked-in SwiftUI host characterize the iOS
half of P0.3. They target iOS 17 and contain no Engine client, shared KMP code, production
mobile API, or signing credentials. The spike does not depend on SKIE; the independent
SKIE experiment remains in `../skie-interop`.

## What this proves in automation

- `SecureEnclave.P256.Signing.PrivateKey` is wired as the Device proof signer. Its
  opaque Secure-Enclave-bound representation is stored in a
  `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` Keychain item. The exported public
  key is checked as a 65-byte uncompressed X9.63 point and signatures are checked as
  64-byte CryptoKit `rawRepresentation` / IEEE P1363 values.
- A different permanent P-256 Secure Enclave key has
  `privateKeyUsage + biometryCurrentSet` access control. It wraps and unwraps a random
  32-byte AES content key with
  `eciesEncryptionCofactorX963SHA256AESGCM`.
- The snapshot uses AES-256-GCM with fresh system-generated nonces and fixed versioned
  authenticated data. A bounded binary envelope carries the wrapped content key and the
  combined nonce/ciphertext/tag.
- The envelope is atomically replaced with complete file protection on iOS. Its file and
  containing directory are excluded from backup.
- Typed states distinguish unavailable hardware, missing enrollment/passcode,
  cancellation, temporary biometric lockout, biometric-key invalidation, corrupt data,
  and success. Locking drops the retained plaintext content key. Zeroization is
  best-effort because Swift and `Data` cannot promise removal of compiler temporaries or
  historical copy-on-write buffers.
- When the biometric wrapping key is missing or invalidated, the snapshot and wrapping
  key are destroyed. The physical harness also deletes the separate Device proof key so
  the next successful setup must Pair as a new installation identity. It also revokes
  in-memory creation authorization and removes continuity markers; retry and relaunch
  therefore remain fail-closed until an explicit continuity re-check opens a fresh
  Pairing path.
- An operation epoch plus `LAContext.invalidate()` prevents a late biometric callback from
  reopening after background lock. Inactive UI is privacy-covered without cancelling a
  biometric prompt; background and protected-data-unavailable perform the crypto lock.
- Matching random markers in the backup-excluded app container and ThisDeviceOnly
  Keychain distinguish a continuing install from reinstall. A missing/mismatched marker
  purges any surviving snapshot and key identities before initializing a fresh Pairing
  path. Cleanup failures are typed and never reported as successful re-Pairing cleanup.
- A separate non-secret, device-only Keychain marker records that the biometry-bound
  wrapping key is expected. If enrollment/passcode changes remove that key before the
  first snapshot is sealed, setup still invalidates the installation and requires new
  Pairing instead of silently minting a replacement wrapper.

The biometric policy is
`deviceOwnerAuthenticationWithBiometrics`, with an empty fallback title. It never asks
for `deviceOwnerAuthentication`, so this spike intentionally offers no passcode/PIN
fallback.

## Layout

- `DeviceProofSigner.swift` — Secure Enclave signer plus pure X9.63/P1363 validation.
- `DeviceProofTranscript.swift` — exact, canonical-HTTPS-bound protocol transcript.
- `BiometricSecureEnclaveKeyWrapper.swift` — biometric-only ECIES key envelope.
- `SnapshotEnvelope.swift` — bounded codec and AES-GCM operations.
- `SnapshotStorage.swift` — atomic, protected, backup-excluded file storage.
- `SecurityState.swift` and `SecureSnapshotStore.swift` — typed state/outcome boundary.
- `IOSSecurityPhysicalHarness.swift` — the small API the checked-in iOS host invokes.
- `InstallationContinuity.swift` — reinstall/Keychain continuity guard.
- `iosApp/IOSSecurityHost.xcodeproj` — checked-in non-production SwiftUI physical host.
- `Tests/` — software-only codec, state, tamper, cleanup, and persistence tests.

The binary envelope is:

```text
"RKP03IOS" | version:u8 | wrapped-key-length:u16be | sealed-length:u32be |
wrapped-content-key | AES.GCM combined(nonce | ciphertext | tag)
```

## Automated verification

From this directory:

```sh
swift test
xcodebuild \
  -scheme IOSSecuritySpike \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO \
  build
xcodebuild \
  -scheme IOSSecuritySpike \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest' \
  CODE_SIGNING_ALLOWED=NO \
  test
xcodebuild \
  -project iosApp/IOSSecurityHost.xcodeproj \
  -scheme IOSSecurityHost \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO \
  build
xcodebuild \
  -project iosApp/IOSSecurityHost.xcodeproj \
  -scheme IOSSecurityHost \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=latest' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The host and simulator suites exercise pure behavior only. A simulator does not prove
Secure Enclave key residency, biometric ACL invalidation, or absence of credential
fallback.

Observed locally on 2026-08-13 with Xcode 26.6 (build 17F113) and the iOS 26.5 SDK:

- `swift test`: 33 passed, 0 failed;
- generic `arm64-apple-ios17.0` build with signing disabled: succeeded;
- generic SwiftUI host build for `arm64-apple-ios17.0`: succeeded;
- SwiftUI host build for iPhone 17 / iOS 26.5 simulator: succeeded;
- physical iPhone / iOS 17.x: not run, and therefore still pending below.

## Checked-in physical host

Open `iosApp/IOSSecurityHost.xcodeproj`, select the shared `IOSSecurityHost` scheme and an
iOS 17.x physical iPhone, choose a personal development team locally if Xcode requires
one, then Run. No team ID, profile, certificate, or signing secret belongs in the repo.
The project already links the local package, targets iOS 17, and includes
`NSFaceIDUsageDescription`.

At launch the host establishes installation continuity before any key operation. Its
buttons then exercise the repository golden Device proof transcript, biometric prepare,
seal, lock, unlock, destroy, and explicit continuity re-check. It reports typed states and
byte counts without showing snapshot plaintext or key bytes.

For persistence checks that need the actual public bytes, instantiate
`SecureEnclaveDeviceProofSigner` directly and retain only the returned
`DeviceProof.publicKeyX963` value in the test log. Do not log plaintext snapshots or key
material.

## Physical iOS 17 acceptance — pending

Use a Secure-Enclave-capable physical iPhone running iOS 17.x. Record the OS build,
hardware model, Face ID/Touch ID type, Xcode version, and each observed typed outcome.
Every item below is intentionally pending; simulator results must not check them off.

- [ ] **PENDING — Device signer shape and verification.** With a passcode set, sign the
  repository's exact Device proof transcript, observe a 65-byte X9.63 public key beginning
  with `0x04`, a 64-byte P1363 signature, and successful CryptoKit verification over the
  original message (not a caller-prehashed digest).
- [ ] **PENDING — Device signer persistence.** Force-quit and relaunch the host, sign a
  fresh message, and observe the same public key and a valid fresh signature.
- [ ] **PENDING — Separate keys.** Confirm the Device signer and snapshot wrapping key use
  different Keychain identifiers; deleting/recreating the wrapper must not silently reuse
  the signer.
- [ ] **PENDING — Missing prerequisites block setup.** With no device passcode, then with
  no enrolled biometric, observe `passcodeRequired` / `biometryNotEnrolled`; no snapshot or
  successful Pairing state may be created. The non-secret installation-continuity marker
  may initialize first; it must not turn the missing-passcode result into a generic launch
  failure.
- [ ] **PENDING — Biometric-only prompt.** Prepare and unlock using Face ID/Touch ID;
  cancel the prompt and observe `cancelled`. Verify that neither a fallback button nor a
  device-passcode/PIN route is offered.
- [ ] **PENDING — Snapshot round trip.** Prepare, seal known bytes, lock, and unlock.
  Observe exact plaintext only after biometric success and a different AES-GCM combined
  value on two writes of identical plaintext.
- [ ] **PENDING — Background/system lock purge.** Use the checked-in host's
  background and protected-data-unavailable callbacks. Confirm that the inactive phase
  immediately shows the privacy cover but does not self-cancel the biometric system sheet.
  Returning from actual background must stay locked until a new biometric unlock.
- [ ] **PENDING — Atomic and backup-excluded file.** Inspect the snapshot URL after
  replacement: the previous or new complete envelope exists, its bytes do not contain the
  plaintext, complete file protection is active, and `isExcludedFromBackup` is true for
  the directory and file.
- [ ] **PENDING — Tamper fails closed.** Flip one byte in the envelope, wrapped key,
  ciphertext, and tag in separate runs. No plaintext is returned; the state is corrupt or
  otherwise locked and never falls back to an unverified snapshot.
- [ ] **PENDING — Temporary lockout retains material.** Trigger biometric lockout and
  observe `lockedOut`; the snapshot and both key identities remain present. Recover
  biometrics through the operating system, then unlock the same snapshot.
- [ ] **PENDING — Enrollment change forces new Pairing.** With a sealed snapshot, change
  the enrolled biometric set. The old `biometryCurrentSet` key must be unusable; the next
  prepare/unlock returns `requiresPairing`, destroys the inaccessible snapshot/wrapper,
  and deletes the Device proof signer. A later setup produces a different public Device
  key.
- [ ] **PENDING — Enrollment change before first seal.** Prepare the wrapping key but do
  not seal a snapshot, then change enrollment. The durable expectation marker must make
  the missing wrapper invalidate the Device proof identity and require new Pairing.
- [ ] **PENDING — Unpair cleanup.** Call `destroyAllSpikeMaterial()` and confirm the file,
  wrapping key, and Device signer are absent before creating a new identity.
- [ ] **PENDING — Reinstall/transfer isolation.** Uninstall/reinstall the checked-in host
  and restore/move a device backup. No old snapshot, wrapper, or proof identity is restored;
  setup requires new Pairing.

## Disposal

This is characterization evidence, not the future app. M2.1 supersedes the standalone
SwiftUI shell when the real iOS project is scaffolded. The accepted native-security
boundary and relevant tests move into the production iOS implementation milestone; this
entire spike directory is then removed. Do not evolve this host into the product UI.

## Repository contracts and primary API references

This spike implements only the boundaries already recorded in:

- `../../../docs/roadmap.md` (P0.3)
- `../../../docs/protocol.md` (Device proof transcript and encodings)
- `../../../docs/adr/0015-gate-offline-data-with-device-authentication.md`
- `../../../docs/adr/0030-use-device-bound-keys-for-client-authorization.md`
- `../../../docs/adr/0031-require-pairing-after-reinstall-or-device-transfer.md`
- `../../../docs/adr/0052-hide-native-snapshot-cryptography-behind-a-shared-contract.md`
- `../../../docs/adr/0054-set-a-modern-mobile-platform-floor.md`

Apple primary references:

- [Protecting keys with the Secure Enclave](https://developer.apple.com/documentation/security/protecting-keys-with-the-secure-enclave)
- [SecureEnclave](https://developer.apple.com/documentation/cryptokit/secureenclave)
- [SecureEnclave.P256.Signing.PrivateKey](https://developer.apple.com/documentation/cryptokit/secureenclave/p256/signing/privatekey)
- [WhenPasscodeSetThisDeviceOnly](https://developer.apple.com/documentation/security/ksecattraccessiblewhenpasscodesetthisdeviceonly)
- [biometryCurrentSet](https://developer.apple.com/documentation/security/secaccesscontrolcreateflags/biometrycurrentset)
- [ECIES cofactor X9.63 SHA-256 AES-GCM](https://developer.apple.com/documentation/security/seckeyalgorithm/eciesencryptioncofactorx963sha256aesgcm)
- [deviceOwnerAuthenticationWithBiometrics](https://developer.apple.com/documentation/localauthentication/lapolicy/deviceownerauthenticationwithbiometrics)
- [AES.GCM](https://developer.apple.com/documentation/cryptokit/aes/gcm)
- [AES.GCM combined representation](https://developer.apple.com/documentation/cryptokit/aes/gcm/sealedbox/combined)
- [isExcludedFromBackupKey](https://developer.apple.com/documentation/foundation/urlresourcekey/isexcludedfrombackupkey)

Physical acceptance is the remaining P0.3 gate. Passing the automated suites alone is
not a positive Secure Enclave result.
