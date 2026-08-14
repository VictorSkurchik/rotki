# Pairing presentation

This KMP module owns the immutable Pairing presentation state, typed actions, and pure synchronous
reducer. It depends only on `:feature:pairing:domain`; it has no coroutine, Compose, Ktor, Room,
Koin, or platform dependency.

Side effects remain at the composition boundary. `:shared` invokes its strict QR/facade adapter and
feeds the resulting coarse domain outcome into this reducer while preserving the existing
Swift/Kotlin `PairingFlow` API.
