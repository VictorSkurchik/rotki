# Gate expansion on a real Android tracer bullet

Before implementing data-bearing Companion destinations or the iOS tracer/security layer, a
real Engine and Android Client must complete QR Pairing, Device Key registration, Access
Session acquisition, live net-worth Fetch, authenticated-encrypted Portfolio Snapshot
persistence, and offline reopening after device authentication. Shared state-machine tests,
a live Engine Protocol contract test, and an automated critical Android UI flow are required
gates; a mock-only or manual demonstration does not complete the milestone. Once this gate
passes, iOS is the next feature target and must consume the same shared behavior.

Amendment (2026-08-13): a non-data-bearing native iOS build/interop shell may exist before
that gate so Kotlin/Native framework integration, Swift ABI drift, Xcode configuration, and
basic fail-closed navigation remain continuously testable. The shell must not scan QR codes,
perform network Pairing, persist credentials or portfolio data, implement native security,
or render real Portfolio content. Its success is not iOS tracer or physical-device evidence
and does not relax the Android hard gate.
