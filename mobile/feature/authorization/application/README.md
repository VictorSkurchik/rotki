# Authorization application

This KMP module owns the process-scoped authorization coordinator. It reads an existing durable
Pairing record, requires the existing Device Key, wipes transcript bytes, and retains the Access
Session only in memory behind coarse inspection and opaque-request APIs. Exchange jobs use a private
supervisor bound to the process scope, so cancellation of an arbitrary caller does not cancel work
joined by other callers. Secret-free start and completion events are emitted once by the flight
owner rather than once per joined caller; exact expiry emits its own single owner event.

Acquisition and renewal share one generation-fenced single flight. The coordinator enforces exact
Engine expiry, automatically renews at 300 seconds remaining, atomically replaces a successful
bearer, and retains the still-valid old bearer after a recoverable renewal failure. A proactive
transport failure receives at most one fixture-frozen fresh whole-exchange retry after two seconds;
rate limiting uses a canonical `Retry-After` only through the protocol's five-second hidden-work
limit. Longer waits keep no hidden work alive and surface through the C3 internal adapter.
`challenge_unavailable` receives at most one fresh challenge/proof exchange rather than replaying
the failed proof.

The visibility observer cancels network work while inactive but retains an unexpired bearer; it
cancels work and purges authority on background or system lock. Explicit clear and close also fence
late results. Process-scope cancellation start (and normal completion) performs the same committed
purge and transport shutdown. Cancellation of the caller invoking close cannot skip teardown.

C3 status (2026-08-15): internal consumers submit opaque `AuthorizationRequest` values through the
Ktor-free request authority; they cannot pass arbitrary operation callbacks. The coordinator invokes
one trusted data-owned `AuthorizationRequestExecutor` fixed at construction. For each admitted
request, that executor receives only a write-only credential capability that is one-shot,
revision-fenced, and governed by its own captured-expiry root. The executor and request finalizers
must not re-enter `AuthorizationProcessControl`; feedback returns as the request result or is queued
only after executor unwinding. Successful renewal installs its replacement atomically. Process
invalidation is two-phase: every `begin*` operation fences bearer use immediately and returns a
secret-free completion handle for the remaining request-finalizer drain; process close also closes
the gateway before that handle is returned. At the shared boundary, a teardown epoch rejects
authorization, recovery, request, and coordinator-event admission until both authority and
session-work drains complete. Session-work `beginClose` synchronously and idempotently detaches only
the matching Access Session revision at entry and returns a completion handle. External discovery,
cleaner, and session-work callbacks cannot re-enter the adapter or process control; feedback is
queued only after they unwind. The Pairing cleanup barrier remains claimed through local deletion,
revision-scoped session close, and the authority/request-finalizer drain.

Protocol selection remains Ktor-free. The internal shared adapter's explicit retry performs fresh
discovery, replaces the selected version, explicitly clears prior authority, and starts a fresh
challenge/proof exchange even for the same version. The adapter maps secret-free owner events into
the existing facade, invokes one composite cleanup port only for automatic `not_authorized` or
missing local Pairing, and leaves the root `Connecting` after authority acquisition until Snapshot
reconciliation. Its authenticated session-work controller is a transport-free KMP control contract.

Android now constructs this coordinator through an Objective-C-hidden shared controller and supplies
the real gateway, Device Key, Pairing store, lifecycle visibility, clock, and journaled composite
cleaner. Post-Pairing, biometric restart, background/system-lock, and explicit-retry callbacks are
wired. Consolidated native verification, iOS composition, authenticated requests, and real
session-work/WebSocket ownership remain open. A4.1 and Gate G4 remain open. The module has no Ktor,
serialization, Android, Koin, or Apple framework dependency, and every integration type is hidden
from Objective-C and Swift.
