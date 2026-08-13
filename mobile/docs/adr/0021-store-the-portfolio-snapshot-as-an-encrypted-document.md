# Store the portfolio snapshot as an encrypted document

The Client will persist one versioned serialized Portfolio Snapshot document rather than introduce a local relational database. Each replacement is authenticated-encrypted with a device-protected key and written atomically while retaining the last known-good document until the new write succeeds; format upgrades are explicit snapshot migrations, and unpairing destroys both the document and its key.
