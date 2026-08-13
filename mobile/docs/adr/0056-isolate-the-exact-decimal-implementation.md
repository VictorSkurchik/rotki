# Isolate the exact-decimal implementation behind a project-owned type

Shared domain and application APIs will expose a project-owned `ExactDecimal` value type rather than a third-party numeric type. Before selecting the implementation, a characterization spike must verify at least `FVal`-compatible precision, canonical decimal-string round trips, comparison, arithmetic, rounding, serialization, and matching JVM/iOS behavior. `ionspin BigNum` is the first candidate; if it fails the spike, its replacement must not change domain APIs or persisted snapshot schema.
