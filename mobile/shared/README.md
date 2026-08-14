# Shared mobile foundation

This is the original production Kotlin Multiplatform module and the single Apple-framework umbrella
for the Portfolio Companion. ADR-0090 supersedes the single-module target: shared behavior is
migrating incrementally into Clean Architecture core and feature modules while Android and iOS keep
native UI and platform-security adapters.

The extracted core leaves are `:core:model`, which owns `ExactDecimal`, its private numeric backend,
characterization tests, vectors, and generator; `:core:common`, which owns `Clock`, application
visibility/controller contracts, and the pure lifecycle policy; `:core:protocol`, whose first
implementation-only tranche owns the strict Companion JSON codec, duplicate-member/syntax scanner,
strict scalar serializers, and generated wire vocabulary; and the first `:core:security-api`
tranche, which owns the secret-free Pairing cleanup journal and revocable secure Snapshot-store
contract plus its application-owned plaintext handle. `:shared` has API dependencies on and exports
the common, model, and security leaves through `RotkiShared`. It consumes `:core:protocol` only as an
implementation dependency: that leaf creates no framework and is not exported to Swift. The
authored lifecycle-fixture parity test, protocol DTOs, and generated protocol fixtures remain here.

Pairing now has implementation-only `:feature:pairing:domain` and
`:feature:pairing:presentation` boundaries. The public `PairingFlow` stays here as the stable
Swift/Kotlin adapter and delegates presentation transitions to the feature reducer. Pairing domain
owns the submission, session, and attempt contracts. `PairingFlow` and `PairingConnection` consume
those ports instead of depending directly on `CompanionFacade`; one non-exported adapter scoped to
the facade supplies admission, attempt ownership, and cleanup operations. Process-recovery cleanup
claims a facade cleanup barrier before touching local key or record material, preventing a concurrent
replacement attempt from being silently deleted. The feature modules retain no QR payload or
credential and are not exported as additional Apple APIs.

Strict QR decoding, protocol DTOs, Engine-origin and protocol value types, registration transport,
and the facade-backed in-memory implementation remain in this umbrella for now. Protocol-dependent
device-proof, Pairing-record, and idempotency ports also remain here until their value dependencies
can move without a cycle. The physical `:core:protocol` leaf is deliberately narrower: it owns only
the codec/scanner/strict-serializer mechanics and generated vocabulary needed by these shared
consumers. Raw `Json` is sealed behind its Kotlin-only codec hidden from Objective-C and Swift; Ktor
wraps sensitive encoded bytes in content with a constant, redacted diagnostic representation and
does not install `ContentNegotiation`. The next protocol tranche moves origin/value primitives and
DTO ownership once their cross-module seams are explicit; `:core:network` and completion of the
security API follow. Only then can a real `:feature:pairing:data` implementation depend on the leaves
without introducing `shared <-> data` cycles; no data module is declared yet.

Until each slice moves, the implemented source tree remains organized under `org.rotki.mobile`,
currently around `core` and `auth`. `overview`, `portfolio`, `history`, and `sources` are planned
feature boundaries and will be created only when their first real vertical slices land. New code follows
[`../docs/architecture.md`](../docs/architecture.md). Compose, Android UI, SwiftUI, Koin,
Navigation Compose, Room implementation types, Ktor engines, and platform-security
implementations must not leak into domain or `commonMain` public APIs.

Exact-decimal implementation and migration evidence is retained in
[`../docs/characterization/exact-decimal.md`](../docs/characterization/exact-decimal.md).
