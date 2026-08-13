# Use a two-minute single-use pairing secret

An authorized Vue Client may create a cryptographically random Pairing secret only for the currently open Profile. Its QR payload contains the trusted HTTPS Engine URL and that secret, expires after two minutes, is consumed atomically by the first successful Device Key registration, and becomes invalid on Profile change; expired, replayed, or failed-consumption attempts require a new QR, and Pairing material is excluded from logs and analytics.
