# Pairing presentation

This KMP module owns the immutable Pairing presentation state, typed actions, and pure synchronous
reducer. It depends only on `:feature:pairing:domain`; it has no coroutine, Compose, Ktor, Room,
Koin, or platform dependency.

Side effects remain at the composition boundary. The `:shared` `PairingFlow` invokes its strict QR
decoder, admits decoded material through the facade-scoped domain port adapter, and feeds only the
resulting coarse outcome into this reducer while preserving the existing Swift/Kotlin API.
