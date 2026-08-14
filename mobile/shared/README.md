# Shared mobile foundation

This is the original production Kotlin Multiplatform module and the single Apple-framework umbrella
for the Portfolio Companion. ADR-0090 supersedes the single-module target: shared behavior is
migrating incrementally into Clean Architecture core and feature modules while Android and iOS keep
native UI and platform-security adapters.

The first extracted leaf is `:core:model`, which owns `ExactDecimal`, its private numeric backend,
characterization tests, vectors, and generator. `:shared` has an API dependency on that module and
exports it through `RotkiShared`; it does not produce a second framework. New stable leaves should
follow the same incremental pattern, while behavior that has not yet moved remains here.

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
in this umbrella for now. The next extraction establishes coherent core common, protocol, network,
and security leaves first. Only then can a real `:feature:pairing:data` implementation depend on
those leaves without introducing `shared <-> data` cycles; no data module is declared yet.

Until each slice moves, the implemented source tree remains organized under `org.rotki.mobile`,
currently around `core` and `auth`. `overview`, `portfolio`, `history`, and `sources` are planned
feature boundaries and will be created only when their first real vertical slices land. New code follows
[`../docs/architecture.md`](../docs/architecture.md). Compose, Android UI, SwiftUI, Koin,
Navigation Compose, Room implementation types, Ktor engines, and platform-security
implementations must not leak into domain or `commonMain` public APIs.

Exact-decimal implementation and migration evidence is retained in
[`../docs/characterization/exact-decimal.md`](../docs/characterization/exact-decimal.md).
