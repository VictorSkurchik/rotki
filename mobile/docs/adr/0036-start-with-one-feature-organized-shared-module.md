---
status: superseded by ADR-0090
---

# Start with one feature-organized shared module

The initial KMP implementation will use one `mobile:shared` module organized internally into `core`, `auth`, `overview`, `portfolio`, `history`, and `sources` feature-first packages. Features become separate Gradle modules only when measured build performance, dependency isolation, or test-boundary pressure justifies the added configuration, rather than starting with either per-feature or horizontal layer modules.
