# Authorization data

This KMP module owns the strict challenge/proof wire boundary: DTOs and envelopes, response mapping,
exact Device Proof transcript bytes, route/header construction, bounded response decoding, and its
internal hardened Ktor client. Challenge and proof POSTs are each executed once and require
`Cache-Control: no-store` on success.

Ktor, DTO, credential, route, and implementation types remain internal or explicitly hidden from
Objective-C and Swift. The module creates no Apple framework.
