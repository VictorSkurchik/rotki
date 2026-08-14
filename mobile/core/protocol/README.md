# Core protocol

This platform-neutral KMP leaf is the first, deliberately narrow protocol extraction. It owns only:

- the strict Companion JSON codec;
- duplicate-member and JSON-syntax scanning;
- strict JSON scalar serializers;
- the generated protocol vocabulary.

Protocol DTOs, Engine-origin and protocol value types, error mapping, and network transport remain in
`:shared` for the next migration tranche. This module has no project, Ktor, database, Compose, Koin,
native UI, or platform implementation dependency.

The Kotlin-visible declarations that cross the Gradle boundary are implementation seams and are
hidden from Objective-C and Swift. `:shared` consumes this leaf with an implementation dependency,
does not export it through `RotkiShared`, and this module creates no Apple framework of its own.

Codec input text, parsed elements, and encoded bytes may contain credentials or other sensitive
protocol material. Pass them only to the bounded decoder or redacted transport that owns the next
operation; never log or persist them.
