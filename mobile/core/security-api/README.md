# Core security API

This platform-neutral KMP leaf owns fail-closed contracts for local security material. It contains
ports and typed outcomes only. Platform implementations remain native-owned: Android currently
implements them, while iOS security integration remains deferred.

The first extracted tranche contains:

- `PairingCleanupJournal`, the secret-free durable cleanup marker that must be committed before
  Pairing key or record mutation;
- `SecureSnapshotStore`, including its revocable application-owned plaintext handle.

The remaining device-proof, Pairing-record, and idempotency ports stay in `:shared` until their
protocol value dependencies move into a legal lower-level module. This module has no project,
serialization, Ktor, database, Compose, Koin, or native UI dependency.

`:shared` consumes this module as an API dependency and exports it through the existing
`RotkiShared` Apple framework. Packages and native-facing declarations remain unchanged, and this
module does not create a second framework.
