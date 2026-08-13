# Bind devices with an opaque profile ID

Each Profile will gain a cryptographically random stable 256-bit Profile ID, generated once
as 32 raw bytes and stored in the singleton `profile_metadata` record inside its encrypted
user database. The database enforces a BLOB type, exact 32-byte length, and uniqueness.
Fresh Profiles commit the schema, Profile ID, and database version atomically, and the
current unreleased user-database migration backfills the value for existing Profiles;
ordinary restart, backup, and restore preserve the same bytes. Backend code treats it as the
distinct `ProfileID` type and reads it through the internal `DBHandler.get_profile_id()`
boundary. The value is independent of the username and Profile contents, is not an
authentication secret, and is never returned by ordinary public APIs, DTOs, or diagnostics.
It is copied into the Control Store only when binding a public Device Key. Authorization
compares the opaque bytes after unlock, while Profile names, portfolio contents, and
credentials never enter the Control Store; the mobile Client uses a local user-chosen Engine
label rather than exposing or depending on the Profile name.

Preserving the identifier through backup and restore deliberately defines authorization
lineage: a restored Profile and any concurrently running clone with the same Profile ID are
the same authorization domain. The identifier alone cannot distinguish those copies, so the
Engine neither attempts a cross-copy collision rejection nor silently rotates an identity.
A future workflow that needs an independent copy must explicitly mint a new Profile ID and
require new Device Session bindings.
