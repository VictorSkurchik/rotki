# Verify handwritten transport DTOs against the engine

The KMP Client will use explicit Kotlin serialization DTOs that preserve Engine Protocol field names and map into separate domain models. HTTP and WebSocket contract tests will exercise those DTOs against a live deterministic golden-profile Engine in CI; dynamic JSON maps and an up-front OpenAPI client-generation migration are rejected for the initial Companion scope.
