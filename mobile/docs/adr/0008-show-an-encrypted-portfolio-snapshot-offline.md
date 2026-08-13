# Show an encrypted portfolio snapshot offline

The Portfolio Companion will persist an encrypted Portfolio Snapshot after each successful Fetch that yields a new Snapshot Revision and may display it while the Engine is unreachable. Offline views must show when the snapshot was captured and clearly mark it as stale; the Client will not queue commands or accept offline changes for later synchronization, and the Engine remains authoritative.
