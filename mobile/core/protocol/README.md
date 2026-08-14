# Core protocol

This platform-neutral KMP leaf is a deliberately narrow protocol extraction. It owns:

- the strict Companion JSON codec;
- duplicate-member and JSON-syntax scanning;
- strict JSON scalar serializers;
- the generated protocol vocabulary;
- fixed-width protocol IDs, credentials, signatures, public keys, and their fail-closed parsers;
- the Ktor-free `EngineOrigin` value and parser, preserving the established canonical HTTPS-origin
  contract and REST/WebSocket endpoint derivation;
- generic HTTP control envelopes, bounded preflight decoding, discovery negotiation, and authored
  error mapping;
- bounded WebSocket notification decoding and the typed refresh/snapshot notification model.

The generic Ktor execution and platform-engine boundary lives in `:core:network`. Auth DTOs and
Pairing-specific request construction remain in `:shared` for the next feature-data tranche. This
module has no project, Ktor, database, Compose, Koin, native UI, or platform implementation
dependency. The Engine-origin extraction preserves the existing accepted canonical bytes and
validation precedence; it does not introduce a new DNS, IP, or IPv6 host grammar.

`EngineOrigin`, `DeviceSessionId`, `IdempotencyKey`, `P1363Signature`, `X963PublicKey`, and their
parse outcomes keep their established native API and are exported through the single `RotkiShared`
umbrella. Secret credentials, raw-byte helpers, codec mechanics, and generated vocabulary are
Kotlin-only seams hidden from Objective-C and Swift. The WebSocket decoder and typed notification
graph are also Kotlin-only and hidden from Objective-C and Swift. This module creates no Apple
framework of its own.

Codec input text, parsed elements, and encoded bytes may contain credentials or other sensitive
protocol material. Pass them only to the bounded decoder or redacted transport that owns the next
operation; never log or persist them. Decoded WebSocket notifications may retain opaque revision,
operation, and source identifiers for typed routing, but their diagnostic representations must stay
constant and redacted; callers must not log those raw properties.
