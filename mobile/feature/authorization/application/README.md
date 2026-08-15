# Authorization application

This KMP module owns the process-scoped authorization coordinator. It reads an existing durable
Pairing record, requires the existing Device Key, wipes transcript bytes, and retains the Access
Session only in memory behind a coarse inspection API. Exchange jobs use a private supervisor bound
to the process scope, so cancellation of an arbitrary caller does not cancel work joined by other
callers.

Acquisition and renewal share one generation-fenced single flight. The coordinator enforces exact
Engine expiry, automatically renews at 300 seconds remaining, atomically replaces a successful
bearer, and retains the still-valid old bearer after a recoverable renewal failure. A proactive
transport failure receives at most one fixture-frozen fresh whole-exchange retry after two seconds;
rate limiting uses a canonical `Retry-After` only through the protocol's five-second hidden-work
limit. Longer waits keep no hidden work alive; native/root-state surfacing remains C3.
`challenge_unavailable` receives at most one fresh challenge/proof exchange rather than replaying
the failed proof.

The visibility observer cancels network work while inactive but retains an unexpired bearer; it
cancels work and purges authority on background or system lock. Explicit clear and close also fence
late results. Process-scope cancellation start (and normal completion) performs the same committed
purge and transport shutdown. Cancellation of the caller invoking close cannot skip teardown.
Request-authority delegation, native lifecycle delivery/composition, authenticated-work and
WebSocket handling, and facade/root-state mapping remain later authorization slices. The module has
no Ktor, serialization, Android, Koin, or Apple framework dependency, and every integration type is
hidden from Objective-C and Swift.
