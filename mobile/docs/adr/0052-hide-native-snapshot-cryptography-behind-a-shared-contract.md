# Hide native snapshot cryptography behind a shared contract

The shared core will depend on a `SecureSnapshotStore` contract while each platform owns its key envelope and authenticated encryption. Android uses a non-exportable biometric-gated AES-GCM key in Android Keystore; iOS wraps an AES-GCM content key with a biometric-gated non-exportable Secure Enclave key and retains plaintext content-key material only for the unlocked in-memory session. Every atomic document write uses a unique nonce, and the shared core never persists or receives a long-lived plaintext key.
