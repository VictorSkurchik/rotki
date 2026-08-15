# Authorization client plan

This plan completes the Portfolio Companion's Client-side challenge, Device Proof, and Access
Session behavior without adding or changing Engine routes. It refines A4.1 and I5 while preserving
the live Engine and Starling gates as separate end-to-end evidence.

## Resolved decisions

1. **One shared behavior, two native hosts.** Shared KMP owns authorization contracts, transport
   mapping, orchestration, renewal, and deterministic state transitions. Android is wired first as
   the existing tracer target; iOS follows with the same shared behavior and native security/UI
   adapters. Native UI, navigation, lifecycle bindings, and key implementations remain native.
2. **Authorization is not Pairing.** `:feature:pairing` ends after durable Device Session
   registration. A separate `:feature:authorization` owns recurring challenge, Device Proof, and
   Access Session behavior. Neither feature depends on the other's implementation; composition
   passes the durable `PairingRecord` and core security ports inward.
3. **Client completion is not the live tracer gate.** The client-only vertical may merge after
   fixture-backed `MockEngine`, platform wiring, lifecycle, ABI, and security gates pass. A4.1 and
   Gate G4 remain incomplete until the same behavior passes against the real Engine through the
   Docker/Starling HTTPS path.
4. **Use real Clean Architecture layers.** Add `:feature:authorization:domain` for Ktor-free ports
   and outcomes, `:feature:authorization:application` for the process-scoped coordinator, and
   `:feature:authorization:data` for DTOs, strict decoding, transcript bytes, headers, and Ktor.
   Do not add a presentation module: authorization has no independent screen and maps into the
   existing root state. `:shared` remains a thin native facade and composition adapter.
5. **One owner for Access authority.** The application coordinator is the sole owner of the current
   bearer. It keeps it only in process memory, never places it in `CompanionStatus`, persistence,
   native APIs, diagnostics, or exception text, and exposes no raw long-lived getter. Later data
   features receive only a Kotlin-only, Objective-C-hidden, scoped request-authority capability
   whose contract forbids retention and logging.
6. **Challenge and proof form one single-flight exchange.** Concurrent acquisition and renewal
   requests join one coordinator operation. `POST /challenges` and `POST /access-sessions` use
   `RequestReplayPolicy.NEVER`; neither request is retransmitted. A lost challenge response starts
   a new exchange, while a lost proof response also starts from a new challenge because the first
   challenge may already be consumed.
7. **Lifecycle and renewal are shared policy.** Network use is allowed only in active foreground.
   Transient inactivity suspends use without destroying a valid bearer. Background or system lock
   cancels in-flight authorization, closes authenticated work, and drops the bearer immediately.
   One proactive renewal starts at five minutes remaining; the old bearer remains usable until a
   replacement is atomically installed or the old expiry is reached.
8. **Remote failures have exact local effects.** `locked_engine` and `profile_mismatch` keep the
   durable Device Session and encrypted Snapshot but remove online authority. `challenge_unavailable`
   starts a fresh exchange. `access_session_unavailable` reproves the Device Key. Only
   `not_authorized` is destructive: it enters Revoked and invokes the existing fail-closed local
   key, Pairing-record, and Snapshot cleanup path. Contract, parsing, transport, and unexpected
   failures never infer revocation.
9. **Platform parity is explicit, not simulated.** Android wires the coordinator into its existing
   process Koin composition, `ApplicationVisibilityController`, durable Pairing store, and real
   Keystore signer without widening the Android AAR or Swift framework. After Gate G4, iOS adds a
   native Device Key signer and manual composition root, then consumes the same coordinator through
   the stable facade/SKIE boundary; simulator tests do not claim Secure Enclave evidence.
10. **Every layer has a hard acceptance gate.** Contract fixtures and golden transcript bytes run on
    JVM, Android host, and iOS Simulator; coordinator tests use fake clocks and lifecycle state;
    Android managed devices prove process restart and real signer reuse; Apple headers/modulemaps
    remain stable; secret-seeded redaction scans cover values, exceptions, and logs. Live HTTPS
    contract, Android UI tracer, and physical iPhone security remain the later G4/G5 gates.

## Target modules and dependency direction

```text
:feature:authorization:application
    -> :feature:authorization:domain
    -> :core:common
    -> :core:protocol
    -> :core:security-api

:feature:authorization:data
    -> :feature:authorization:domain
    -> :core:common
    -> :core:network -> :core:protocol

:shared facade / native composition roots
    -> :feature:authorization:application
    -> :feature:authorization:data
```

- Domain exposes only coarse, redacted outcomes and small consumer-owned ports.
- Application receives the remote gateway, `PairingRecordStore`, `DeviceProofSigner`, visibility,
  clock, and delay/cancellation seams by constructor injection. It never constructs Ktor or a
  platform adapter.
- Data owns the challenge/access request and response DTOs, envelope mapping, exact Device Proof
  transcript, Authorization route constants, and the internal Ktor client. Ktor and bearer strings
  do not appear in public module signatures.
- `:shared` depends on the three Authorization modules but exports none of their implementation
  types as a separate Apple framework. Existing Swift-facing state and facade symbols remain stable.

## Execution slices

### C1 — Extract the contract and wire boundary

- Create the three Authorization modules with graph and CI guards.
- Move only challenge/access DTOs, their envelopes, `AuthorizationChallenge`, Access response
  mapping, and `encodeDeviceProofTranscript` out of `:shared`; leave rename/revoke ownership for the
  later Device Session management slice.
- Preserve serializer names, exact JSON, golden transcript bytes, strict Base64URL values, bounded
  response decoding, `Cache-Control: no-store`, protocol header, and redacted rendering.
- Add a Ktor-free domain gateway for challenge creation and proof submission. Its production
  implementation uses the hardened platform `HttpClient` and exposes no `HttpClient`/engine type.

### C2 — Implement the shared authorization coordinator

- Read the durable Engine origin and Device Session ID from `PairingRecordStore`; require the
  existing Device Key rather than silently creating one.
- Run one foreground single-flight sequence: request challenge, build the exact transcript, sign it,
  wipe temporary byte arrays in `finally`, submit proof once, and atomically install the returned
  bearer and Engine expiry.
- Make cancellation generation-fenced so a late response cannot install authority after background,
  Unpair, revocation cleanup, or process-level clear.
- Own proactive renewal and expiry with injected time. Coalesce simultaneous callers, retain the old
  bearer during a recoverable renewal, and never extend the Engine-provided expiry locally.
- Replace the unused generic renewal gate in `:core:network` with coordinator-owned single-flight
  behavior; retry policy stays in `:core:network`.

### C3 — Integrate the stable shared facade

- Add an internal facade-scoped Authorization adapter that maps domain outcomes to the existing
  `CompanionCoordinator` events and effects. Do not add secrets or transport types to
  `CompanionStatus`.
- Connect background/system-lock, explicit foreground retry, access-unavailable, WebSocket `1008`,
  locked Engine, Profile Mismatch, incompatible, and revoked transitions to coordinator operations.
- Keep current public Swift names, selectors, enum cases, ordering, and descriptions byte-stable.

### C4 — Wire and prove Android

- Construct one Authorization coordinator in `AndroidSecurityComposition` from the retained
  Pairing store, real Device Proof signer, visibility controller, and platform transport gateway.
- Drop bearer state in the existing background/system-lock lifecycle callback and preserve Activity,
  Koin, receiver, `FLAG_SECURE`, storage, and key lifetimes.
- Characterize first foreground authorization after Pairing and after process restart, transient
  inactive resume, concurrent acquisition, proactive renewal, expiry, cancellation, locked/mismatch,
  transport loss, and destructive `not_authorized` cleanup.
- Run JVM/Android host tests, API 28/API 36 managed-device signer/composition tests, lint, ktlint,
  detekt, module-graph checks, release assembly, and secret scans.

### C5 — Complete iOS after the Android gate

- Implement the native non-exportable P-256 Device Key signer and manual iOS composition adapter;
  keep Keychain/Secure Enclave policy and lifecycle ownership in Swift.
- Consume the same shared coordinator and root-state mapping; add Swift cancellation, background
  purge, process-restart reproof, redaction, and interop tests.
- Require exact framework/header/modulemap comparison throughout development. G5 still requires one
  physical iPhone; Simulator success proves behavior and interop only.

### C6 — Run live client evidence without expanding this scope

- Point the production KMP implementation at the real HTTPS Engine/Starling harness once its routes
  exist; do not add Engine code as part of these Client slices.
- Complete D3.4 fixture parity and A4.1/G4 only when discovery, Pairing, proof, Access acquisition,
  restart reproof, and the Android tracer all pass without `MockEngine` or TLS bypass.
- Begin Snapshot/data-plane work only after the Authorization authority capability is stable and its
  lifecycle tests are green.

## Definition of done for the client-only vertical

- All new code is physically outside `:shared` except the thin facade adapter.
- The Access bearer is process-only, redacted, generation-fenced, and destroyed on every required
  lifecycle and terminal-authority path.
- Challenge/proof requests are single-flight and never replayed automatically.
- Golden request bytes, transcript bytes, strict response mapping, error effects, expiry, renewal,
  and cancellation pass on every KMP target.
- Android reuses the durable Device Session and native Device Key after process restart without
  Pairing again.
- The Swift API remains byte-identical, no feature framework is produced, and no Ktor/Auth DTO or
  credential symbol leaks into native headers.
- The roadmap continues to report A4.1/G4 as incomplete until the live HTTPS tracer passes.

## Out of scope

- Engine endpoints, capability advertisement, Starling changes, or Full Client controls.
- Snapshot Fetch, Room, WebSocket event delivery, Refresh, and the four data-bearing destinations.
- Pairing Screen/ViewModel relocation, design-system expansion, or UI redesign.
- Persisting, refreshing, exporting, or exposing an Access Session credential.
