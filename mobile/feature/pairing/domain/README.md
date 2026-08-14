# Pairing domain

This platform-neutral KMP leaf owns the one-shot Pairing submission port, its closed secret-free
outcome vocabulary, and the narrow session/attempt ports used by presentation and connection
orchestration. It has no project or framework dependencies.

The raw QR payload is an ephemeral method argument. Domain results contain only coarse rejection
reasons, so credentials, Engine origins, and Pairing identifiers cannot become retained state or
appear in their representations. `PairingSessionPort` admits decoded material without exposing it,
while `PairingAttemptPort` passes opaque, session-scoped attempt and cleanup capabilities without a
domain-level material getter. Implementations may retain secret-bearing material only inside the
active in-memory Pairing session and must never persist or log it.

The current implementation remains behind a non-exported, facade-scoped adapter in `:shared`.
Core common contracts are now extracted, but strict QR decoding and registration transport remain in
`:shared` until the protocol, network, and security leaves can move without a dependency cycle. A
real `:feature:pairing:data` module is created only with that production implementation; no
placeholder data module exists.
