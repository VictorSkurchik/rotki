# Core protocol

This platform-neutral KMP leaf is a deliberately narrow protocol extraction. It owns:

- the strict Companion JSON codec;
- duplicate-member and JSON-syntax scanning;
- strict JSON scalar serializers;
- the generated protocol vocabulary;
- fixed-width protocol IDs, credentials, signatures, public keys, and their fail-closed parsers.

Protocol DTOs, Engine-origin parsing, error mapping, and network transport remain in `:shared` for
the next migration tranche. This module has no project, Ktor, database, Compose, Koin, native UI, or
platform implementation dependency.

`DeviceSessionId`, `IdempotencyKey`, `P1363Signature`, `X963PublicKey`, and their parse outcomes keep
their established native API and are exported through the single `RotkiShared` umbrella. Secret
credentials, raw-byte helpers, codec mechanics, and generated vocabulary are Kotlin-only seams
hidden from Objective-C and Swift. This module creates no Apple framework of its own.

Codec input text, parsed elements, and encoded bytes may contain credentials or other sensitive
protocol material. Pass them only to the bounded decoder or redacted transport that owns the next
operation; never log or persist them.
