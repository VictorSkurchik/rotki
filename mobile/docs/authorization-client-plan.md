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
   features submit Kotlin-only, Objective-C-hidden opaque `AuthorizationRequest` values. The
   coordinator invokes one trusted data-owned `AuthorizationRequestExecutor` fixed at construction
   and gives it only a write-only, one-shot, revision-fenced credential capability rooted to that
   request's captured expiry. The executor and its finalizers must not re-enter process control;
   feedback returns as the request result or is queued only after executor unwinding.
6. **Challenge and proof form one single-flight exchange.** Concurrent acquisition and renewal
   requests join one coordinator operation. `POST /challenges` and `POST /access-sessions` use
   `RequestReplayPolicy.NEVER`; neither request is retransmitted. A lost challenge response starts
   a new exchange, while a lost proof response also starts from a new challenge because the first
   challenge may already be consumed.
7. **Lifecycle and renewal are shared policy.** Network use is allowed only in active foreground.
   Transient inactivity suspends use without destroying a valid bearer. Background or system lock
   cancels in-flight authorization, signals the authenticated session-work control contract, and
   drops the bearer immediately. One proactive renewal starts at five minutes remaining; the old
   bearer remains usable until a replacement is atomically installed or the old expiry is reached.
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
:feature:authorization:domain
    -> :core:protocol

:feature:authorization:application
    -> :feature:authorization:domain
    -> :core:common
    -> :core:protocol
    -> :core:security-api

:feature:authorization:data
    -> :feature:authorization:domain
    -> :core:network
    -> :core:protocol

:shared facade / native composition roots
    -> :feature:authorization:application
    -> :feature:authorization:data
    -> :feature:authorization:domain
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

Implementation status (2026-08-15): the three modules, ownership move, strict wire boundary,
fixture/MockEngine contracts, module guards, and Swift-leak guards are implemented. To keep the
application module real rather than a placeholder, C1 also includes the bounded foreground
coordinator core: it reads the existing durable Pairing record, requires the existing Device Key,
joins concurrent callers into one challenge/proof exchange, wipes transcript bytes, and
generation-fences the process-memory Access Session. At the C1 boundary, proactive renewal,
request-authority delegation, facade/root-state mapping, and native composition were intentionally
left to C2-C5. Pairing still ends at durable Device Session registration, and this fixture-backed
evidence does not complete A4.1 or Gate G4.

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
- Replace the unused generic renewal gate and threshold policy in `:core:network` with
  coordinator-owned expiry and single-flight behavior; request replay and transport-retry policy
  stay in `:core:network`.

Implementation status (2026-08-15): `:feature:authorization:application` now owns process-memory
Access authority, exact Engine expiry, and automatic renewal at 300 seconds remaining. Acquisition
and renewal share one generation-fenced, caller-independent single flight owned by the process
scope. Successful renewal atomically replaces the bearer; recoverable failure retains only a
still-valid old bearer and never extends its Engine expiry. Proactive transport failure receives one
fixture-frozen fresh whole-exchange retry after two seconds, while rate limiting uses its canonical
`Retry-After` only up to the protocol's five-second hidden-work limit; longer waits do not keep a
hidden retry alive and await C3 facade surfacing. `challenge_unavailable` receives one fresh
challenge/proof exchange. The visibility observer cancels network work but retains authority while
inactive, and cancels work plus purges authority on background or system lock. Explicit clear and
close cancel and fence late completion.
Process-scope cancellation start (and normal completion) performs the same committed purge and
transport shutdown. Cancellation of the caller invoking close cannot skip teardown.
The obsolete network renewal gate and policy are removed; generic replay and transport retry remain
in `:core:network`.

At the C2 boundary, request-authority delegation, facade/root-state mapping, and authenticated-work
handling remained for C3; native lifecycle delivery and composition remained C4. This KMP policy
evidence does not complete A4.1 or Gate G4.

### C3 — Integrate the stable shared facade

- Add an internal facade-scoped Authorization adapter that maps domain outcomes to the existing
  `CompanionCoordinator` events and effects. Do not add secrets or transport types to
  `CompanionStatus`.
- Connect background/system-lock, explicit foreground retry, access-unavailable, WebSocket `1008`,
  locked Engine, Profile Mismatch, incompatible, and revoked transitions to coordinator operations.
- Keep current public Swift names, selectors, enum cases, ordering, and descriptions byte-stable.

Implementation status (2026-08-15): the C3 KMP/shared integration is implemented behind the
unchanged public facade. A non-exported, facade-scoped adapter consumes secret-free coordinator
owner events once per owner flight and maps coarse outcomes into the existing root-state machine.
Internal consumers submit opaque `AuthorizationRequest` values through a Ktor-free request
authority. The coordinator alone invokes its trusted data-owned `AuthorizationRequestExecutor`,
fixed at construction, and lends that executor only a write-only credential application capability.
The capability is one-shot and revision-fenced, and every admitted request has its own root tied to
the captured Access Session expiry. The executor and request finalizers must not re-enter
`AuthorizationProcessControl`; feedback returns through the request result or is queued only after
the executor unwinds. Successful renewal still replaces the bearer atomically.
Process invalidation is two-phase: each `begin*` operation atomically fences bearer use (and closes
the gateway for process close) before returning a secret-free completion handle. The adapter starts
authenticated session-work closure and committed durable cleanup before awaiting cancelled request
finalizers. Its teardown epoch rejects authorization, recovery, request, and coordinator-event
admission until the authority and session-work drains complete. Session-work `beginClose`
synchronously and idempotently detaches only work matching the captured Access Session revision at
entry, then returns a completion handle for its close drain. External discovery, cleaner, and
session-work callbacks cannot re-enter the adapter or process control; feedback is queued only after
they unwind. The Pairing cleanup barrier is released only after local deletion, revision-scoped
session close, and the authority/request-finalizer drain all complete.

Explicit foreground retry performs fresh discovery through a Ktor-free delegate, reselects the
coordinator protocol version, explicitly clears prior authority, and then starts a fresh
challenge/proof exchange even when the selected version did not change. Internal entry points also
cover access-session unavailability and WebSocket `1008`. The authenticated session-work controller
is a transport-free control contract only; C3 does not claim a real WebSocket or native event
source. Among automatic Authorization outcomes, only `not_authorized` or missing local Pairing
invokes the single composite local-authority cleanup port; recoverable, locked, Profile-Mismatch,
incompatible, contract, and transport failures do not infer destructive cleanup. Successful
authorization reports authority readiness but intentionally leaves
`CompanionRootState.Connecting` in place until Snapshot reconciliation can perform the later
root-state transition.

C4 remains open for native coordinator construction, delivery of real lifecycle callbacks, a real
composite cleaner, and real session-work/WebSocket connection and event wiring. Device Key reuse
through that native composition and the live Engine/Starling path also remain unproved, so C3 does
not complete A4.1 or Gate G4.

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

Implementation status (2026-08-15): Android now constructs one retained Authorization coordinator
and facade adapter from the real platform gateway, Pairing record store, non-exportable Device Key
signer, visibility controller, injected clock, and existing journaled composite cleaner. A fresh
discovery/select/proof operation is enqueued only after Pairing has returned `REGISTERED`; paired
restart authorization is triggered only after the existing Snapshot biometric boundary reports a
real unlock. Background/system lock enters the same adapter purge path, and native explicit retry
now performs discovery and proof rather than only mutating root state. The consolidated C4 test,
managed-device, quality, assembly, secret-scan, and ABI run is intentionally deferred to the final
cross-phase verification pass. Authenticated request execution and real WebSocket/session-work
ownership remain later data-plane work, so A4.1 and Gate G4 remain open.

### C5 — Complete iOS after the Android gate

- Implement the native non-exportable P-256 Device Key signer and manual iOS composition adapter;
  keep Keychain/Secure Enclave policy and lifecycle ownership in Swift.
- Consume the same shared coordinator and root-state mapping; add Swift cancellation, background
  purge, process-restart reproof, redaction, and interop tests.
- Require exact framework/header/modulemap comparison throughout development. G5 still requires one
  physical iPhone; Simulator success proves behavior and interop only.

Implementation status (2026-08-15): the Swift host now owns a manual composition with a persistent,
non-exportable Secure Enclave P-256 Device Key, strict X9.63/P1363 conversion through the existing
KMP value types, `ThisDeviceOnly` Keychain Pairing record and cleanup marker, secure idempotency
generation, paired-process restoration, and `scenePhase`-driven inactive/background/device-auth
handling. The pre-C5 `RotkiShared` header remains unchanged. That requirement also exposes the
remaining blocker: the shared Authorization controller is correctly hidden from Objective-C, so
Swift cannot invoke it without either a new secret-free interop selector (which would change the
header) or a later internal auto-install design. Camera Pairing UI, Authorization proof/restart
reproof, tests, and physical-iPhone evidence therefore remain open; C5 and G5 are not complete.

### C6 — Run live client evidence without expanding this scope

- Point the production KMP implementation at the real HTTPS Engine/Starling harness once its routes
  exist; do not add Engine code as part of these Client slices.
- Complete D3.4 fixture parity and A4.1/G4 only when discovery, Pairing, proof, Access acquisition,
  restart reproof, and the Android tracer all pass without `MockEngine` or TLS bypass.
- Begin Snapshot/data-plane work only after the Authorization authority capability is stable and its
  lifecycle tests are green.

Implementation status (2026-08-15): the production KMP Pairing and Authorization clients use the
platform HTTPS engines and strict serializers, but the repository does not yet provide the D3.4
live Gradle task, golden Engine/Starling HTTPS harness, trusted test CA orchestration, or Android
instrumentation driver. The challenge and Access Session paths are classified by the Engine
request boundary but remain deliberately unadvertised and have no production route handlers in
this tranche. C6 therefore remains externally blocked: `MockEngine` evidence cannot be relabelled
as live evidence, no backend or Starling code is added by this client plan, and A4.1/G4 stay open.
Once the external host exists, the remaining client work is a production-Ktor driver for discovery,
registration, proof, Access acquisition, and restart reproof plus the real Android tracer. The
Snapshot/Refresh/WebSocket portion of the full G4 tracer remains a later data-plane slice.

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
