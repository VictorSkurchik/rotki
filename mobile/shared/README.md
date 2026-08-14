# Shared mobile foundation

This is the original production Kotlin Multiplatform module and the single Apple-framework umbrella
for the Portfolio Companion. ADR-0090 supersedes the single-module target: shared behavior is
migrating incrementally into Clean Architecture core and feature modules while Android and iOS keep
native UI and platform-security adapters.

The extracted core leaves are `:core:model`, which owns `ExactDecimal`, its private numeric backend,
characterization tests, vectors, and generator; `:core:common`, which owns `Clock`, application
visibility/controller contracts, and the pure lifecycle policy; `:core:protocol`, whose first
tranches own the strict Companion JSON codec, duplicate-member/syntax scanner, strict scalar
serializers, generated wire vocabulary, fixed-width protocol value types, bounded HTTP control
envelopes, discovery negotiation, authored error mapping, and the bounded WebSocket notification
decoder with its typed refresh/snapshot model and the Ktor-free Engine-origin parser; and the
completed `:core:security-api` boundary, which owns the Pairing cleanup journal, Device-proof signer,
idempotency-key generator, Pairing-record persistence, and revocable secure Snapshot-store contracts.
`:core:network` now owns the hardened Ktor client, bounded HTTP execution, replay and renewal policy,
single-flight renewal gate, and OkHttp/Darwin engine actuals. `:shared` consumes that leaf as an
implementation dependency, while its API dependencies on the four Swift-facing core leaves export
their stable public surfaces through `RotkiShared`. Core leaves create no framework of their own;
credentials, DTO/decoder seams, network seams, byte helpers, codec mechanics, generated vocabulary,
and WebSocket notification types stay hidden from Swift. Authored downstream fixture-parity tests,
Auth DTOs, and generated protocol fixtures remain here.

Pairing now has implementation-only `:feature:pairing:domain` and
`:feature:pairing:presentation` boundaries. The public `PairingFlow` stays here as the stable
Swift/Kotlin adapter and delegates presentation transitions to the feature reducer. Pairing domain
owns the submission, session, and attempt contracts. `PairingFlow` and `PairingConnection` consume
those ports instead of depending directly on `CompanionFacade`; one non-exported adapter scoped to
the facade supplies admission, attempt ownership, and cleanup operations. Process-recovery cleanup
claims a facade cleanup barrier before touching local key or record material, preventing a concurrent
replacement attempt from being silently deleted. The feature modules retain no QR payload or
credential and are not exported as additional Apple APIs.

Strict QR decoding, Auth DTOs, Pairing-specific registration request construction, and the
facade-backed in-memory implementation remain in this umbrella for now. Their native Android
Device-key, idempotency-key, and Pairing-record implementations remain in `:androidApp`; `:shared`
consumes those contracts from `:core:security-api` and delegates generic transport execution to
`:core:network`. Raw `Json` is sealed behind the Kotlin-only codec in `:core:protocol`; Ktor wraps
sensitive encoded bytes in content with a constant, redacted diagnostic representation and does not
install `ContentNegotiation`. The next coherent move can place QR decoding and registration in a
real `:feature:pairing:data` module without introducing `shared <-> data` cycles; no placeholder data
module is declared.

Until each slice moves, the implemented source tree remains organized under `org.rotki.mobile`,
currently around `core` and `auth`. `overview`, `portfolio`, `history`, and `sources` are planned
feature boundaries and will be created only when their first real vertical slices land. New code follows
[`../docs/architecture.md`](../docs/architecture.md). Compose, Android UI, SwiftUI, Koin,
Navigation Compose, Room implementation types, Ktor engines, and platform-security
implementations must not leak into domain or Swift-facing public APIs. Infrastructure-only Kotlin
seams in `:core:network` remain hidden from Objective-C and Swift.

Exact-decimal implementation and migration evidence is retained in
[`../docs/characterization/exact-decimal.md`](../docs/characterization/exact-decimal.md).
