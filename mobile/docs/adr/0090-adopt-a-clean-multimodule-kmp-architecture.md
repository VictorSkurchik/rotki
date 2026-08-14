---
status: accepted
---

# Adopt a clean multimodule KMP architecture

The bootstrap `:shared` module has proven the platform and is no longer the target architecture. The Portfolio Companion will move incrementally to feature-first Gradle modules with explicit Clean Architecture boundaries: domain and application contracts point inward, data modules implement those contracts, platform presentation depends on application contracts, and composition roots alone select implementations. A thin `:shared` umbrella will export the stable KMP facade to Swift without becoming an implementation dumping ground. SOLID is applied pragmatically at these boundaries: constructor injection and small consumer-owned ports are required, while interfaces without an actual substitution or isolation need are not. Modules are created with real production code rather than as empty placeholders, and cross-feature access goes through public contracts rather than another feature's implementation. This accepts additional Gradle configuration in exchange for dependency isolation, parallel feature ownership, faster focused builds, and enforceable platform boundaries, and supersedes ADR-0036.
