# Terminate HTTPS before Starling

An operator-managed reverse proxy or private-network ingress will terminate system-trusted HTTPS and forward requests to Starling over the protected Engine Host network. Starling remains an internal HTTP supervisor and router rather than acquiring ACME and certificate lifecycle responsibilities; the repository documents the required proxy and forwarded-header contract but does not mandate or bundle a specific proxy product.
