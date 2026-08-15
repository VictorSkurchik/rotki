# Core network

This KMP infrastructure module owns the reusable Companion transport execution boundary:

- the hardened Ktor client configuration, bounded WebSocket queues, and request timeouts;
- OkHttp engines for Android/JVM and the cache-disabled Darwin engine for iOS;
- bounded HTTP response reading, strict JSON response metadata checks, and typed transport outcomes;
- replay classification and bounded full-jitter transport-retry policy.

Process-memory Access authority, expiry, proactive renewal, and authorization single-flight now
belong to `:feature:authorization:application`. The former generic renewal policy and gate were
removed from this infrastructure layer.

Its only project dependency is `:core:protocol`, which supplies protocol limits, wire policy, and
the strict envelope decoder. Ktor, coroutines, and serialization are infrastructure dependencies of
this module; Compose, Koin, Room, native UI, and application composition are forbidden.

`:feature:pairing:data` consumes `:core:network` as an implementation dependency for protocol
discovery and Device Session registration. The facade-scoped lifecycle, durability, and cleanup
orchestration remains in `:shared`. Public Kotlin network seams exist only where infrastructure
callers cross the module boundary; all are hidden from Objective-C and Swift. The module creates no
Apple framework and is not exported through `RotkiShared`.

Transport bodies may contain credentials or other sensitive protocol material. They stay bounded,
must never be logged or persisted, and use constant redacted diagnostic representations where a
body-bearing wrapper can be rendered.
