# Preserve exact financial values in the client

Engine Protocol amounts and prices will remain decimal strings and will map to a shared arbitrary-precision value type whose semantics and supported precision are compatible with `FVal`. Floating-point types are forbidden in transport, domain state, sorting, aggregation, and financial calculations; a lossy projection is allowed only at a rendering boundary under an explicit display policy. Authoritative accounting and PnL calculations remain in the Engine, and rounding occurs only for presentation.
