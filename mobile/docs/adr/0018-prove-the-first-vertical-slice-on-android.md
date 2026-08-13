---
status: superseded by ADR-0037
---

# Prove the first vertical slice on Android

The first end-to-end implementation will run on Android and cover Pairing, Dashboard retrieval, secure persistence, and offline display before an iOS UI is started. This isolates shared-core and Engine Protocol risks from Kotlin-to-Swift interoperability; iOS remains the second mobile target and must consume the same shared behavior once the Android tracer bullet is sound.
