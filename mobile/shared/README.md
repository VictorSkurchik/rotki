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
and WebSocket notification types stay hidden from Swift. The single generated protocol corpus and
its reusable parser now live in test-only `:core:testing`.

Pairing now has implementation-only `:feature:pairing:domain`, `:feature:pairing:data`, and
`:feature:pairing:presentation` boundaries. The public `PairingFlow` stays here as the stable
Swift/Kotlin adapter, delegates presentation transitions to the feature reducer, and submits raw QR
input through the data implementation of the domain gateway. Pairing data owns strict QR decoding,
registration DTOs/mapping, request construction, and the internal Ktor client. `PairingFlow` and
`PairingConnection` consume the feature ports and Kotlin-only data gateway instead of implementing
those wire details. One non-exported adapter scoped to the facade supplies admission, attempt
ownership, and cleanup operations. Process-recovery cleanup claims a facade cleanup barrier before
touching local key or record material, preventing a concurrent replacement attempt from being
silently deleted. None of the feature modules is exported as an additional Apple API.

The facade-backed in-memory attempt and cleanup implementation remains in this umbrella, along with
unimplemented challenge/proof, Access Session, rename, and revoke Auth DTOs. Native Android Device
Key, idempotency-key, and Pairing-record implementations remain in `:androidApp`; `:shared` consumes
those contracts from `:core:security-api`, Pairing data as an implementation dependency, and generic
retry/lifecycle policy from `:core:network`. The data module has no edge back to `:shared`; its
redacted QR and label authority carriers plus the substitutable registration gateway and outcomes
cross this Kotlin boundary, and every such seam is hidden from Objective-C and Swift.
`DeviceLabelValidator` remains here as the stable
native wrapper over data-owned validation. Raw `Json` stays sealed behind the Kotlin-only protocol
codec, while sensitive request bytes retain a constant redacted diagnostic representation.

Until each slice moves, the implemented source tree remains organized under `org.rotki.mobile`,
currently around `core` and `auth`. `overview`, `portfolio`, `history`, and `sources` are planned
feature boundaries and will be created only when their first real vertical slices land. New code follows
[`../docs/architecture.md`](../docs/architecture.md). Compose, Android UI, SwiftUI, Koin,
Navigation Compose, Room implementation types, Ktor engines, and platform-security
implementations must not leak into domain or Swift-facing public APIs. Infrastructure-only Kotlin
seams in `:core:network` and `:feature:pairing:data` remain hidden from Objective-C and Swift.

Exact-decimal implementation and migration evidence is retained in
[`../docs/characterization/exact-decimal.md`](../docs/characterization/exact-decimal.md).
