# Core security API

This platform-neutral KMP leaf owns fail-closed contracts for local security material. It contains
ports and typed outcomes only. Platform implementations remain native-owned: Android currently
implements them, while iOS security integration remains deferred.

The completed contract boundary contains:

- `PairingCleanupJournal`, the secret-free durable cleanup marker that must be committed before
  Pairing key or record mutation;
- `DeviceProofSigner` and its fail-closed public-key, signing, and deletion outcomes;
- `IdempotencyKeyGenerator`, which supplies protocol-valid request identities;
- `PairingRecordStore`, its redacted `PairingRecord`, and typed persistence outcomes;
- `SecureSnapshotStore`, including its revocable application-owned plaintext handle.

Its only project dependency is `:core:protocol`, which owns the Engine-origin, session-ID,
idempotency-key, signature, and public-key value types used by these contracts. The module does not
directly declare serialization, Ktor, database, Compose, Koin, or native UI dependencies.

`:shared` consumes this module as an API dependency and exports it through the existing
`RotkiShared` Apple framework. Packages and native-facing declarations remain unchanged, and this
module does not create a second framework.
