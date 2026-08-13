# Bind devices with an opaque profile ID

Each Profile will gain a random stable Profile ID stored in its encrypted user database and copied into the Control Store only when binding a public Device Key. Authorization compares this opaque ID after unlock, while Profile names, portfolio contents, and credentials never enter the Control Store; the mobile Client uses a local user-chosen Engine label rather than exposing or depending on the Profile name.
