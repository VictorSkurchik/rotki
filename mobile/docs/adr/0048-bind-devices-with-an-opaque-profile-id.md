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
