# Core network

This KMP infrastructure module owns the reusable Companion transport execution boundary:

- the hardened Ktor client configuration, bounded WebSocket queues, and request timeouts;
- OkHttp engines for Android/JVM and the cache-disabled Darwin engine for iOS;
- bounded HTTP response reading, strict JSON response metadata checks, and typed transport outcomes;
- replay classification, bounded full-jitter retry policy, proactive session-renewal policy, and the
  single-flight renewal gate.

Its only project dependency is `:core:protocol`, which supplies protocol limits, wire policy, and
the strict envelope decoder. Ktor, coroutines, and serialization are infrastructure dependencies of
this module; Compose, Koin, Room, native UI, and application composition are forbidden.

`:shared` consumes `:core:network` as an implementation dependency while Pairing-specific Auth DTOs,
request construction, and orchestration remain there. Public Kotlin seams exist only where those
callers or authored fixture-parity tests cross the module boundary; all are hidden from Objective-C
and Swift. The module creates no Apple framework and is not exported through `RotkiShared`.

Transport bodies may contain credentials or other sensitive protocol material. They stay bounded,
must never be logged or persisted, and use constant redacted diagnostic representations where a
body-bearing wrapper can be rendered.
