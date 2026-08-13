# Manage paired devices from the full client

The Full Client will act as the control plane for Device Sessions: it creates Pairing codes and lists device name, platform, Pairing time, last-seen time, and revocation controls. A mobile Client may rename and Unpair only its own installation; Unpair deletes local keys and data immediately, while an unreachable Engine may retain a harmless stale public-key registration until it is revoked from the Full Client.
