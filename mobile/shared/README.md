# Shared mobile foundation

This is the original production Kotlin Multiplatform module and the single Apple-framework umbrella
for the Portfolio Companion. ADR-0090 supersedes the single-module target: shared behavior is
migrating incrementally into Clean Architecture core and feature modules while Android and iOS keep
native UI and platform-security adapters.

The first extracted leaf is `:core:model`, which owns `ExactDecimal`, its private numeric backend,
characterization tests, vectors, and generator. `:shared` has an API dependency on that module and
exports it through `RotkiShared`; it does not produce a second framework. New stable leaves should
follow the same incremental pattern, while behavior that has not yet moved remains here.

Until each slice moves, the implemented source tree remains organized under `org.rotki.mobile`,
currently around `core` and `auth`. `overview`, `portfolio`, `history`, and `sources` are planned
feature boundaries and will be created only when their first real vertical slices land. New code follows
[`../docs/architecture.md`](../docs/architecture.md). Compose, Android UI, SwiftUI, Koin,
Navigation Compose, Room implementation types, Ktor engines, and platform-security
implementations must not leak into domain or `commonMain` public APIs.

Exact-decimal implementation and migration evidence is retained in
[`../docs/characterization/exact-decimal.md`](../docs/characterization/exact-decimal.md).
