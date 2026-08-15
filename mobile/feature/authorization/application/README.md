# Authorization application

This KMP module owns the process-scoped authorization exchange coordinator. C1 reads an existing
durable Pairing record, requires the existing Device Key, joins concurrent foreground callers into
one challenge/proof exchange, wipes transcript bytes, and retains the resulting Access Session only
in memory behind a coarse inspection API.

Renewal, request-authority delegation, native lifecycle composition, and facade/root-state mapping
remain later authorization slices. The module has no Ktor, serialization, Android, Koin, or Apple
framework dependency, and every integration type is hidden from Objective-C and Swift.
