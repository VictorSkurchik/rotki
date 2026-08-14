# Core testing

This KMP test-support module owns the single generated copy of the canonical Companion protocol
fixture corpus and the small parser used by authored Kotlin contract tests.

Its only project dependency is `:core:protocol`; its public fixture helpers also declare their
Kotlin serialization API dependency directly. Production source sets must never depend on this module;
consumers add it only to `commonTest` or another test configuration. It contains no production
behavior, platform implementation, UI dependency, or Apple framework.
