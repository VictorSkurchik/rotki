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
`:core:network` now owns the hardened Ktor client, bounded HTTP execution, request-replay and
transport-retry policy, and OkHttp/Darwin engine actuals. Authorization renewal state and
single-flight ownership live in `:feature:authorization:application`. `:shared` consumes the network
leaf as an implementation dependency, while its API dependencies on the four Swift-facing core
leaves export their stable public surfaces through `RotkiShared`. Core leaves create no framework of
their own;
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

The facade-backed in-memory Pairing attempt and cleanup implementation remains in this umbrella,
together with the Device Session rename/revoke DTOs and stable `DeviceLabelValidator` wrapper.
Challenge/proof and Access Session DTOs, envelopes, mapping, and transcript bytes now live in
`:feature:authorization:data`; their Ktor-free contracts live in
`:feature:authorization:domain`, and `:feature:authorization:application` owns process-memory Access
authority, caller-independent acquisition/renewal single-flight, exact expiry, atomic replacement,
bounded fresh-exchange recovery, the shared visibility policy, secret-free owner events, and a
Ktor-free authority for opaque `AuthorizationRequest` values. The coordinator invokes one trusted
data-owned executor fixed at construction. Its credential capability is write-only, one-shot,
revision-fenced, and rooted to each request's captured expiry. Executor/finalizer re-entry into
process control is forbidden; feedback returns as the request result or is queued after unwinding.
A successful renewal still replaces the bearer atomically. Process invalidation is two-phase:
bearer use is fenced before a secret-free completion handle is returned, allowing the facade to
start authenticated session-work closure and committed durable cleanup before cancelled request
finalizers finish. The adapter's teardown epoch blocks authorization, recovery, request, and
coordinator-event admission through authority and session-work drain completion. Session-work
`beginClose` synchronously and idempotently detaches only the matching Access Session revision at
entry and returns a completion handle. External discovery, cleaner, and session-work callbacks
cannot re-enter the adapter or process control; feedback is queued only after they unwind. Its
Pairing cleanup barrier is released only after local deletion, revision-scoped session close, and
the authority/request-finalizer drain all complete.

As of 2026-08-15, `:shared` consumes all three Authorization modules as implementation dependencies
and exports none of them. Its non-exported, facade-scoped adapter maps owner-flight outcomes into the
existing state machine and accepts only opaque requests through the Ktor-free authority. Explicit
retry performs fresh discovery, coordinator protocol reselection, an explicit authority clear, and
a fresh challenge/proof exchange. The authenticated session-work controller is the transport-free,
revision-scoped detach-and-drain contract described above.

Automatic Authorization handling invokes one composite local-authority cleanup port only for
`not_authorized` or missing local Pairing. Successful authorization reports authority readiness but
leaves the root `Connecting` until Snapshot reconciliation performs the later root-state transition.
The Android host now consumes a Kotlin-only, Objective-C-hidden construction controller that owns
one retained coordinator and adapter. It supplies the real platform gateway, Device Key, Pairing
store, lifecycle visibility, and journaled composite cleaner; post-Pairing, biometric restart,
background/system-lock, and explicit-retry callbacks enter that controller. iOS composition,
authenticated requests, real session-work/WebSocket ownership, and the consolidated native gate
remain open.

`:shared` also consumes the security contracts from `:core:security-api`, Pairing data as an
implementation dependency, and generic request-replay/transport-retry policy from `:core:network`.
The data modules have no edge back to `:shared`; every integration seam is hidden from Objective-C
and Swift. Raw `Json` stays sealed behind the Kotlin-only protocol codec, while sensitive request
bytes retain a constant redacted diagnostic representation.

Until each slice moves, the implemented source tree remains organized under `org.rotki.mobile`,
currently around `core` and `auth`. `overview`, `portfolio`, `history`, and `sources` are planned
feature boundaries and will be created only when their first real vertical slices land. New code follows
[`../docs/architecture.md`](../docs/architecture.md). Compose, Android UI, SwiftUI, Koin,
Navigation Compose, Room implementation types, Ktor engines, and platform-security
implementations must not leak into domain or Swift-facing public APIs. Infrastructure-only Kotlin
seams in `:core:network` and `:feature:pairing:data` remain hidden from Objective-C and Swift.

Exact-decimal implementation and migration evidence is retained in
[`../docs/characterization/exact-decimal.md`](../docs/characterization/exact-decimal.md).
