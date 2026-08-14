---
status: accepted
---

# Use Room KMP for relational persistence

When the Portfolio Companion needs a local relational database, it will use Room KMP from one shared database module with platform-provided builders, exported schemas, explicit migrations, and exclusive ownership of data-layer-only entities and DAOs. Feature data modules consume narrow DAO APIs and own repositories and persistence-to-domain mapping; the database module never depends back on a feature. Domain models remain independent of Room annotations, and production builds never use destructive migration fallback. Room is a persistence mechanism rather than a requirement to turn every value into a row: access credentials, Pairing secrets, private keys, and decrypted portfolio data may never be stored there. ADR-0021 remains authoritative for the offline Portfolio Snapshot, which stays one biometric-gated authenticated-encrypted atomic document unless a separately reviewed cross-platform encryption design supersedes that boundary. This gives structured client-owned metadata one supported multiplatform database without weakening the existing secret and offline-data model or duplicating the Engine's authoritative database.
