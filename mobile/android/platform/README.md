# Android platform

`:android:platform` owns narrow Android lifecycle, durable Pairing-storage, Device-proof, and
idempotency seams. `AndroidCompanionLifecycle` coordinates application visibility and fail-closed
background callbacks over `:core:common`. Public factories return only the `PairingRecordStore`,
`PairingCleanupJournal`, `DeviceProofSigner`, and `IdempotencyKeyGenerator` ports from
`:core:security-api`; every concrete adapter and codec remains an implementation detail.

Both durable files keep their established names and bytes under `Context.noBackupFilesDir` and use
`AtomicFile` rollback. Every adapter type has one process-wide lock, so independently created factory
instances still serialize access to the same canonical file. The application retains one pair for
its process lifetime and preserves startup reconciliation and cleanup ordering.

The Device-proof factory uses the established `AndroidKeyStore` provider and canonical alias for a
non-exportable P-256 signing key. It preserves the sign-only SHA-256/ECDSA policy, strict X9.63
public-key encoding, strict DER decoding, and 64-byte P1363 wire signature. One process-wide lock
serializes alias access across every factory instance. The idempotency factory keeps one guarded
`SecureRandom` source and emits the established 16-byte protocol value. Neither factory accepts or
retains a Context or Activity. `:androidApp` retains one instance of each returned port for its
process lifetime.

The module does not retain an `Activity` or `Application` and does not own Koin composition,
receivers, `FLAG_SECURE`, authoritative root state, UI, navigation, biometric or Snapshot-encryption
policy, or permissions. Those responsibilities remain in `:androidApp`, including the
identity-checked Activity attach/detach broker. Its only production project dependencies are
`:core:common`, `:core:security-api`, and the protocol value types required by its private codecs; it
has no edge to `:shared`.

The root module guard permits only `kotlinx-coroutines-core` as a direct production external
dependency. Its broad AndroidX and kotlinx bans are bypassed only for that module and for the exact
AndroidX Test/JUnit artifacts declared from test buckets.

Storage and Device-proof instrumentation run on the module-owned API 28 and API 36 Gradle-managed
devices:

```bash
./gradlew :android:platform:pixel2Api28DebugAndroidTest
./gradlew :android:platform:pixel8Api36DebugAndroidTest
```

This Android-only module is not exported through the `RotkiShared` Apple framework.
