# Pairing data

This KMP data module owns the implemented Pairing boundary that turns an ephemeral QR payload into
validated Pairing material and performs Companion protocol discovery and Device Session
registration.

Strict QR DTOs, parsing details, Auth registration DTOs/envelopes, request serialization, and the
Ktor-backed protocol client remain internal. The module implements the domain-owned
`PairingSubmissionGateway`. Its data-specific cross-module material is limited to the redacted
`PairingQr` used by the facade-scoped in-memory session and an opaque validated device label; other
registration arguments use existing redacted core protocol values. The shared connection
orchestrator consumes a Kotlin-only, Objective-C-hidden registration gateway and factory whose API
exposes no Ktor type. None of these values may be retained or logged.

Production project edges are limited to `:core:common`, `:core:protocol`, `:core:network`, and
`:feature:pairing:domain`. `:core:testing` is a test-only dependency for the canonical authored
protocol corpus. Compose, Koin, Room, native UI, platform plugins, and `:shared` are forbidden. This
module creates no Apple framework and is not exported through `RotkiShared`.

Challenge/proof, Access Session, rename, and revoke DTOs are not part of the implemented
registration slice and remain in `:shared` until their production flows move. Pairing connection
ownership, durable cleanup, and the stable Swift-facing `PairingConnection` also remain in
`:shared` because their current capabilities are facade-scoped.
