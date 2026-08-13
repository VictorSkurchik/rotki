# Store device registrations in a durable control store

The Engine will add a migrated `control.db` beside its disposable session database to persist opaque 256-bit Device Session IDs, Profile bindings, public Device Keys, device labels, Pairing and last-seen timestamps, and revocation state. Unknown and revoked IDs expose the same protocol outcome. This Control Store is available while the Profile is locked and contains no Profile password, private key, bearer token, portfolio state, or other secret; short-lived Access Session membership remains disposable and separate.
