# Emit no automatic mobile telemetry

The mobile Clients will include no analytics, crash-reporting SDK, remote logging, or automatic diagnostic upload. Local structured logs must redact Engine URLs, Pairing material, credentials, addresses, balances, and History Events in every build type; diagnostics leave the device only through an explicit user-created redacted export.
