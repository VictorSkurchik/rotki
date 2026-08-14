# Shared mobile foundation

This is the original production Kotlin Multiplatform module and the single Apple-framework umbrella
for the Portfolio Companion. ADR-0090 supersedes the single-module target: shared behavior is
migrating incrementally into Clean Architecture core and feature modules while Android and iOS keep
native UI and platform-security adapters.

The extracted core leaves are `:core:model`, which owns `ExactDecimal`, its private numeric backend,
characterization tests, vectors, and generator; `:core:common`, which owns `Clock`, application
visibility/controller contracts, and the pure lifecycle policy; and the first
`:core:security-api` tranche, which owns the secret-free Pairing cleanup journal and revocable
secure Snapshot-store contract plus its application-owned plaintext handle. `:shared` has API
dependencies on all three modules and exports them through `RotkiShared`; none produces a second
framework. The common and security leaves own their autonomous tests, while the authored
lifecycle-fixture parity test remains here beside the generated protocol assets and JSON decoder.

Pairing now has implementation-only `:feature:pairing:domain` and
`:feature:pairing:presentation` boundaries. The public `PairingFlow` stays here as the stable
Swift/Kotlin adapter and delegates presentation transitions to the feature reducer. Pairing domain
owns the submission, session, and attempt contracts. `PairingFlow` and `PairingConnection` consume
those ports instead of depending directly on `CompanionFacade`; one non-exported adapter scoped to
the facade supplies admission, attempt ownership, and cleanup operations. Process-recovery cleanup
claims a facade cleanup barrier before touching local key or record material, preventing a concurrent
replacement attempt from being silently deleted. The feature modules retain no QR payload or
credential and are not exported as additional Apple APIs.

Strict QR decoding, registration transport, and the facade-backed in-memory implementation remain
in this umbrella for now. Protocol-dependent device-proof, Pairing-record, and idempotency ports also
remain here until the protocol leaf exists. The next extractions are coherent protocol and network
leaves, followed by completion of the security API. Inside `:shared`, strict JSON configuration is
already protocol-owned and auth-specific success envelopes are auth-owned, so protocol no longer
imports network or auth packages. A non-exported codec boundary must still replace the network
layer's direct use of the internal JSON instance before those packages become separate modules. Only
then can a real `:feature:pairing:data` implementation depend on the leaves without introducing
`shared <-> data` cycles; no data module is declared yet.

Until each slice moves, the implemented source tree remains organized under `org.rotki.mobile`,
currently around `core` and `auth`. `overview`, `portfolio`, `history`, and `sources` are planned
feature boundaries and will be created only when their first real vertical slices land. New code follows
[`../docs/architecture.md`](../docs/architecture.md). Compose, Android UI, SwiftUI, Koin,
Navigation Compose, Room implementation types, Ktor engines, and platform-security
implementations must not leak into domain or `commonMain` public APIs.

Exact-decimal implementation and migration evidence is retained in
[`../docs/characterization/exact-decimal.md`](../docs/characterization/exact-decimal.md).
