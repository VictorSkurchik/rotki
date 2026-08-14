# Core common

This platform-neutral KMP leaf owns the small cross-feature runtime contracts that have no protocol,
transport, persistence, UI, or platform implementation responsibility:

- `Clock` for injected epoch time;
- `ApplicationVisibility` and its closed lifecycle-state vocabulary;
- the fail-closed `ApplicationVisibilityController` used by native composition boundaries;
- `ApplicationVisibilityPolicy` and its secret-free lifecycle decision.

The module uses the shared KMP library convention and targets JVM, Android host tests, `iosArm64`,
and `iosSimulatorArm64`. Its only production dependency is the public `kotlinx-coroutines-core` API
required by `ApplicationVisibility.state`; it has no project, serialization, Ktor, database, Compose,
Koin, or native UI dependency.

Autonomous common tests cover the injected clock, controller progression, redacted representation,
and the exhaustive three-state policy matrix. The authored protocol-fixture parity test deliberately
remains in `:shared`, where the generated protocol assets and JSON decoder already live; they are not
dependencies of this leaf.

`:shared` consumes this module as an API dependency and exports it through the existing
`RotkiShared` Apple framework. The packages and native-facing declarations remain unchanged, and
`:core:common` does not create a second framework.
