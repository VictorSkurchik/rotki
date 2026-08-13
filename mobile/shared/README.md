# Shared mobile foundation

This is the single production Kotlin Multiplatform module for the Portfolio Companion.
It owns shared behavior and domain types, while Android and iOS keep native UI and
platform-security adapters.

The source tree is feature-organized under `org.rotki.mobile`: `core`, `auth`,
`overview`, `portfolio`, `history`, and `sources`. Compose, Android UI, SwiftUI, Ktor
engines, and platform-security implementations must not leak into `commonMain` public
APIs.

Exact-decimal implementation evidence is retained in
[`../docs/characterization/exact-decimal.md`](../docs/characterization/exact-decimal.md).
