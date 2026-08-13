---
status: superseded by ADR-0027
---

# Replace the client before the engine

Rotki KMP will replace the existing user-facing client while retaining the current local Python/Rust engine and its API contracts. This keeps the mature portfolio, accounting, blockchain, exchange, and data-migration behavior in place while the new client is developed; replacing the engine is outside the initial scope and would require a separate decision.
