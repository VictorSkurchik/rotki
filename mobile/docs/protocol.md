# Portfolio Companion Engine Protocol

This document incrementally specifies the wire contract between a Portfolio Companion and
an Engine. It refines the decisions in `mobile/docs/adr/` without creating a mobile BFF or
a second API version.

The checked-in Protocol v1 vocabulary, behavioral matrices, and exact wire vectors live in
`mobile/protocol/v1/`. They are the machine-readable source consumed by static validation
now and by the Engine, Kotlin, and Swift contract tests as those implementations land. This
document owns the normative explanation; a change that makes it disagree with those assets
must update both in one change.

## Bootstrap

The bootstrap sequence is:

1. A Client discovers the Engine Protocol version and Capabilities without authentication.
2. During Pairing, a Full Client authorizes registration of the new Client's Device Key.
3. A paired Client requests a short-lived, single-use challenge for its Device Session.
4. The Client signs the challenge with its Device Key and submits the proof.
5. The Engine creates an Access Session only when the Device Session is authorized and its
   bound Profile is currently open.

A known Device Session can request and prove a challenge while the Engine is locked or a
different Profile is open. This authenticates the Client before the Engine reports the
state, but does not authorize Profile data.

### Proof outcomes

| Device Session and proof | Engine state | HTTP outcome | Access Session | Profile data |
|---|---|---|---|---|
| Authorized, valid proof | Bound Profile open | `201 Created` | Created | Allowed by Companion Scope |
| Authorized, valid proof | No Profile open | `423 locked_engine` | Not created | None |
| Authorized, valid proof | Different Profile open | `409 profile_mismatch` | Not created | None |
| Unknown, revoked, or invalid signature | Any | `401 not_authorized` | Not created | None |
| Challenge missing, expired, consumed, or bound differently | Any | `410 challenge_unavailable` | Not created | None |

The non-data outcomes reveal no Profile ID, Profile name, portfolio value, or information
about the currently open different Profile. Pairing and the authenticated offline
Portfolio Snapshot survive `locked_engine` and `profile_mismatch`; after the state is
resolved, the Client obtains a new challenge and proves its Device Key again.

### Client root-state and recovery matrix

The shared coordinator uses the following exhaustive root-state precedence and recovery
contract. Coverage remains a fact of the last authenticated Snapshot while `refreshing` or
`unreachable` is the current root state; those transient states do not erase whether the
retained Snapshot is complete or degraded.

| Root state | Authoritative entry condition | Retained local material | Exit or recovery |
|---|---|---|---|
| `unpaired` | No local Device Session, or local Unpair completed | None | Scan an Engine-generated Pairing QR |
| `device_locked` | A paired Client is backgrounded or system/device authentication is required | Device Key relationship and encrypted Snapshot only; bearer and plaintext are destroyed | Authenticate the device, then enter `connecting` |
| `connecting` | Foreground discovery, Pairing, challenge/proof, renewal, or REST reconciliation is active | Last authenticated Snapshot and Device Session when present | Resolve to the state proven by protocol, proof, Snapshot, and active-operation results |
| `online` | Proof succeeded, the fetched Snapshot has Complete Snapshot Coverage, and no authorized operation is active | Device Session, bearer, and Snapshot | Refresh, transport loss, lifecycle lock, session renewal, or Unpair |
| `refreshing` | REST reconciliation proves an authorized Refresh Operation is queued or running | Prior Published Snapshot and its independent coverage facts | Observe the terminal operation, Fetch, then enter `online` or `degraded` |
| `degraded` | Proof succeeded, the fetched Snapshot has Degraded Snapshot Coverage, and no authorized operation is active | Device Session, bearer, and usable last-known Snapshot | Refresh, repair configuration in the Full Client, transport loss, or Unpair |
| `unreachable` | The bounded foreground transport retry budget is exhausted | Device Session and last authenticated Snapshot; never an invented empty result | Restore transport, then enter `connecting` and reconcile |
| `engine_locked` | A valid Device Key proof returns `423 locked_engine` | Device Session and offline Snapshot; no bearer | Unlock the Engine in the Full Client, then perform fresh proof |
| `profile_mismatch` | A valid Device Key proof returns `409 profile_mismatch` | Device Session and offline Snapshot; no bearer | Open the bound Profile in the Full Client, then perform fresh proof |
| `incompatible` | No shared Protocol version, missing/too-old `device_sessions`, or legacy Companion-route 404 | Any existing Device Session and offline Snapshot; no bearer | Upgrade the Engine or Client and rediscover |
| `revoked` | Device proof returns terminal `401 not_authorized` | None; Device Key relationship, bearer, and Snapshot are deleted | Pair again |

`device_locked` is the privacy gate and takes precedence whenever the application is not
allowed to expose decrypted data. A WebSocket `1008` never selects `engine_locked`,
`profile_mismatch`, or `revoked`; it destroys the bearer, enters `connecting`, and relies on
a fresh HTTP proof to classify the state. `not_authorized` deliberately has terminal Client
semantics even when its indistinguishable server-side cause was an invalid signature: the
Client cannot safely prove that the durable registration remains usable and therefore
requires Pairing again. The complete transition table is executable in
`mobile/protocol/v1/p0_1_cases.json`.

## Protocol and Capability negotiation

`GET /api/1/companion/protocol` requires no credential or version header and returns the
Engine's supported Companion Protocol versions plus independently versioned Capabilities:

```json
{
  "result": {
    "supported_protocol_versions": [1],
    "capabilities": {
      "device_sessions": 1,
      "portfolio_snapshot": 1,
      "history_pagination": 1,
      "refresh_operations": 1,
      "websocket_notifications": 1
    }
  },
  "message": ""
}
```

Protocol versions and Capability versions are positive integers, not application SemVer.
The initial registry means:

| Capability | Version 1 contract |
|---|---|
| `device_sessions` | Pairing, Device Session management, challenge/proof, and Access Sessions |
| `portfolio_snapshot` | Coherent revisioned Snapshot Fetch |
| `history_pagination` | Online History beyond the bounded Snapshot window |
| `refresh_operations` | Global/per-source coalesced Refresh creation and observation |
| `websocket_notifications` | Authenticated Snapshot Revision and Refresh Operation notifications |

The Client selects the greatest protocol version supported by both sides and sends its
canonical decimal value on every other Companion HTTP request and mobile WebSocket Upgrade:

```http
Rotki-Companion-Protocol: 1
```

Missing, duplicate, malformed, or unsupported version headers receive
`426 incompatible_protocol`. Its typed error may add `supported_protocol_versions`, but
contains no Profile data. The selected version is attached to the Access Session and
WebSocket subscriber; it is not a permanent property of the Device Session.

A Capability value is the greatest backward-compatible schema version supported under the
selected protocol version. The Client enables a function only when the named Capability is
present at or above its minimum supported version. Unknown names and higher additive fields
are ignored. A missing or too-old Capability disables only its dependent function; missing
`device_sessions` prevents Pairing/online authorization, while an authenticated offline
Snapshot remains available whenever already stored. A breaking contract requires a new
protocol version or Capability name rather than redefining an existing version.

No shared Protocol version, a legacy 404 for `/companion/protocol`, or missing/too-old
`device_sessions` enters the root `incompatible` state. Missing another Capability is not a
failed discovery response: the Client disables only that feature. If a Client nevertheless
calls a recognized route whose required Capability was not advertised at the negotiated
version, the Engine returns `426 incompatible_protocol` without Profile data.

If the Engine cannot open or fully validate its Control Store, it remains available for
ordinary Full Client operation but omits `device_sessions`. It does not recreate the store,
rotate registrations, or translate storage failure into `not_authorized`; the Client enters
`incompatible` and retains any local Device Session relationship and offline Snapshot.

Engine and Client application versions are diagnostic metadata only and never substitute
for this negotiation. Contract tests cover no shared protocol version, missing each required
Capability, a higher unknown Capability, and legacy Engine 404 behavior.

## Pairing QR and Device Session registration

The Full Client creates Pairing with an empty JSON object and a fresh Idempotency Key:

```http
POST /api/1/companion/pairings
Rotki-Companion-Protocol: 1
Idempotency-Key: <128-bit-request-id>
Content-Type: application/json
```

```json
{}
```

Success is `201 Created`, carries `Cache-Control: no-store`, and returns the cancellation
identity, authoritative expiry, and exact QR string:

```json
{
  "result": {
    "pairing_id": "AAECAwQFBgcICQoLDA0ODw",
    "expires_at": 1786550400,
    "qr_payload": "{\"kind\":\"rotki_companion_pairing\",\"format_version\":1,\"engine_origin\":\"https://rotki.example\",\"pairing_id\":\"AAECAwQFBgcICQoLDA0ODw\",\"pairing_credential\":\"EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8\",\"expires_at\":1786550400}"
  },
  "message": ""
}
```

`qr_payload` is compact UTF-8 JSON with the member order shown, no insignificant whitespace,
and no trailing newline. The Full Client uses the sibling ID and expiry for cancellation and
display timing but never parses, reconstructs, stores, or logs the secret-bearing string.

The Engine, not the browser, constructs the exact UTF-8 string rendered as the Pairing QR:

```json
{
  "kind": "rotki_companion_pairing",
  "format_version": 1,
  "engine_origin": "https://rotki.example",
  "pairing_id": "AAECAwQFBgcICQoLDA0ODw",
  "pairing_credential": "EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8",
  "expires_at": 1786550400
}
```

`pairing_id` is 16 random bytes and `pairing_credential` is 32 random bytes, each encoded as
canonical unpadded Base64URL. `expires_at` is an integer Unix epoch second set two minutes
after creation by the authoritative Engine clock. `engine_origin` is the normalized,
system-trusted HTTPS origin observed through the trusted Starling forwarding boundary: it
has no userinfo, path, query, fragment, trailing slash, or separate WebSocket address.

The QR contains no Profile ID or name, device label, portfolio data, certificate or pin,
application version, Access Session, or long-lived secret. The disposable Pairing record
binds its credential server-side to the open Profile and exact canonical origin. The origin
is not copied into the durable Control Store. The browser receives the plaintext only in
the redacted `POST /pairings` response and renders the Engine-provided payload without
reconstructing fields.

The scanner accepts at most 2,048 UTF-8 bytes and rejects duplicate keys, invalid UTF-8,
wrong required types, non-canonical identifiers, an unknown `kind` or `format_version`, an
invalid HTTPS origin, and a payload whose `expires_at` is less than or equal to Client time.
Unknown additive members are ignored. Client-side expiry is an early UX check only; the
Engine remains authoritative. The Client performs protocol/Capability discovery at
`engine_origin` over system-trusted TLS before it submits the credential.

Registration uses:

```http
POST /api/1/companion/device-sessions
Rotki-Companion-Protocol: 1
Authorization: Bearer <pairing_credential>
Idempotency-Key: <128-bit-request-id>
Content-Type: application/json
```

```json
{
  "pairing_id": "AAECAwQFBgcICQoLDA0ODw",
  "device_label": "Victor's iPhone",
  "platform": "ios",
  "public_key_algorithm": "ecdsa-p256-sha256-p1363",
  "public_key": "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
}
```

`platform` is the closed version-1 enum `android | ios` and is descriptive, never an
authorization input. `device_label` contains 1 through 64 Unicode scalar values and at most
256 UTF-8 bytes. It rejects lone surrogates, leading or trailing Unicode whitespace, and
characters in the Unicode `Control` (`Cc`), `Format` (`Cf`), `Line_Separator` (`Zl`), or
`Paragraph_Separator` (`Zp`) general categories, and is returned byte-for-byte without
Unicode normalization. The Engine verifies the Pairing ID, credential hash, expiry, origin,
open Profile binding, idempotency fingerprint, algorithm identifier, key encoding, and
label constraints before atomically consuming Pairing and committing the Device Session.
Malformed input does not partially consume or persist a registration.

Success is `201 Created` with this exact result shape:

```json
{
  "result": {
    "device_session": {
      "device_session_id": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
      "device_label": "Victor's iPhone",
      "platform": "ios",
      "state": "authorized",
      "paired_at": 1786550300,
      "last_seen_at": null,
      "revoked_at": null
    }
  },
  "message": ""
}
```

It returns neither the Pairing credential nor Profile metadata. The Client persists the ID
beside its canonical Engine origin and native Device Key reference, then immediately
characterizes the new registration through the normal challenge/proof flow.

## Access Session credential

An Access Session is represented by a cryptographically random opaque bearer credential.
The Client keeps the credential only in process memory. The Engine stores only a
cryptographic hash of the credential together with its Device Session binding, Companion
Scope, creation time, and expiry in a disposable in-memory store.

An Engine restart invalidates every Access Session and every outstanding Pairing without
affecting Device Sessions. There is no refresh token: renewal obtains a new single-use
challenge and repeats Device Key proof. Every authenticated request checks both the Access
Session record and the current authorization of its bound Device Session, allowing
revocation to take effect immediately.

### Client lifecycle

The Client suspends outgoing Engine requests and closes its WebSocket as soon as it is no
longer in the active foreground. On a transition to background or on system lock, it also
destroys the Access Session bearer held in process memory. A transient inactive state that
returns directly to the active foreground may retain the bearer, but must not use it while
inactive.

On foreground resume after backgrounding, the Client first requires device authentication
before exposing its encrypted Portfolio Snapshot. It then requests a new challenge and
proves its Device Key to obtain a new Access Session; neither the previous bearer nor a
refresh token participates in renewal.

## Device Session lookup

During Pairing, the Engine generates a uniformly random opaque Device Session ID containing
256 bits of entropy and returns it with the accepted Device Session registration. The Client
persists that ID with its Engine origin and Device Key reference, and supplies it when
requesting an authorization challenge.

The Device Session ID is stable for the lifetime of that Device Session, but is neither a
bearer credential nor an authentication secret. Knowledge of it grants no authority without
a valid Device Key proof. It reveals no public key, key algorithm, Profile binding, device
label, or authorization state, and therefore leaves those representations free to evolve.

Unknown and revoked Device Session IDs produce the same stable `not_authorized` outcome and
expose no existence or Profile metadata. The 256-bit random namespace prevents practical
enumeration; implementations must additionally avoid observably different response bodies
for unknown and revoked records.

The durable Profile binding uses the Profile ID. Backup, restore, and concurrent clones
that preserve one Profile ID are intentionally one authorization lineage; the Engine cannot
and does not reject those copies as collisions or silently rotate their identity. On the
same Engine Host they match the same Device Sessions. Another host has an independent
Control Store and therefore requires Pairing unless its authority was restored separately.

Automatic Profile backups, premium synchronization, and development-instance seed copies
exclude the Control Store. Restoring an older copy of this host-level authorization database
could resurrect a Device Session revoked after the snapshot, so such a restore is unsupported
except as an explicit, consistent whole-host recovery.

Challenge creation is a pre-session lookup stage, not bearer authentication:

```http
POST /api/1/companion/challenges
Rotki-Companion-Protocol: 1
Content-Type: application/json
```

```json
{
  "device_session_id": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
}
```

An authorized Device Session receives `201 Created` and `Cache-Control: no-store`:

```json
{
  "result": {
    "challenge_id": "ICEiIyQlJicoKSorLC0uLw",
    "nonce": "MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8",
    "expires_at": 1786550400
  },
  "message": ""
}
```

The proof stage sends only values needed to find and verify the stored Challenge. It does
not echo the nonce, expiry, origin, or algorithm:

```http
POST /api/1/companion/access-sessions
Rotki-Companion-Protocol: 1
Content-Type: application/json
```

```json
{
  "device_session_id": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "challenge_id": "ICEiIyQlJicoKSorLC0uLw",
  "signature": "zKnDT8nsSEMoIxqZIzUybLt-QJJr6mtaaXm6SJMRmK7kJLpg-BP20iAJBHwLqXstAHFxaHwb_vs_jb4hSU6N8A"
}
```

Successful proof returns `201 Created`, `Cache-Control: no-store`, and exactly one bearer:

```json
{
  "result": {
    "access_session_credential": "UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8",
    "expires_at": 1786551300
  },
  "message": ""
}
```

The Challenge ID is 16 random bytes, nonce and Access credential are each 32 random bytes,
and all are canonical unpadded Base64URL. An `access-sessions` response never returns Scope,
Profile identity, Device Key metadata, or a refresh token.

## Authorization challenge transcript

The Engine creates each challenge for exactly one Device Session and canonical Engine
origin. The Client signs the following byte transcript in this exact order:

```text
ASCII("rotki-companion-device-proof/v1")
0x00
uint16_be(byte_length(canonical_engine_origin))
UTF8(canonical_engine_origin)
device_session_id                 // 32 raw bytes
challenge_id                      // 16 raw random bytes
nonce                             // 32 raw random bytes
expires_at                        // uint64_be Unix epoch seconds
```

The origin is the canonical HTTPS origin captured from the trusted request and forwarding
boundary when the Challenge is created, with no path, query, or trailing slash. It is held
only with disposable Pairing or Challenge state, never accepted from the proof request and
never read from the Control Store. The Client signs using the canonical origin it retained
from Pairing; if the trusted Engine origin has changed, proof fails and a new Pairing is
required. The Client signs the decoded binary identifier and nonce values, not their
wire-encoded strings. It copies `expires_at` from the challenge response as an integer; the
Engine remains authoritative for expiry.

The domain separator, field order, field widths, length prefix, and byte order are
normative. Implementations must not sign a JSON serialization, locale-dependent text,
platform-native integer representation, or only the nonce. A future incompatible layout
uses a new domain/version. Cross-language golden vectors must cover the transcript bytes,
valid proof, altered origin, altered Device Session ID, altered challenge, expiry, and
single-use rejection.

### Device proof cryptography

Device Keys use ECDSA over NIST P-256. The signature API hashes the complete transcript
exactly once with SHA-256; Clients must use message-signing mode rather than pre-hashing it.
The protocol algorithm identifier is `ecdsa-p256-sha256-p1363`.

The registration request encodes the public key as uncompressed SEC1/X9.63 form:

```text
0x04 || X_32_BE || Y_32_BE       // exactly 65 bytes before wire encoding
```

The Engine rejects any other prefix or length and verifies that the decoded coordinates
represent a valid point on P-256. It does not accept a DER SubjectPublicKeyInfo container,
PEM, compressed point, or JWK under this algorithm identifier.

The proof encodes its ECDSA signature in IEEE P1363 form:

```text
R_32_BE || S_32_BE               // exactly 64 bytes before wire encoding
```

The Engine rejects any other length and requires `1 <= r,s < n`, where `n` is the P-256
group order. It accepts both mathematically valid high-S and low-S signatures: platform
signers do not guarantee low-S output, and proof replay is prevented by atomically consuming
the Challenge ID rather than comparing signature bytes.

Both binary values use canonical RFC 4648 Base64URL without `=` padding in JSON. Decoders
reject padding, the standard Base64 `+` and `/` characters, invalid characters, and encodings
that do not round-trip to the same unpadded Base64URL string.

Platform adapters may translate their native representation only at the boundary. Android
converts the DER result of `SHA256withECDSA` into P1363; Apple CryptoKit uses the signature's
raw representation; the Python Engine converts P1363 `r` and `s` to the verifier's DER input.
Golden vectors must exercise both conversions and reject malformed DER, out-of-range
components, invalid points, and double-hashed transcripts.

## Credential lifetimes and renewal

An authorization challenge expires 60 seconds after the Engine creates it. The Engine's
clock is authoritative, and the response carries the exact `expires_at` value included in
the signed transcript. A challenge is bound to one Device Session, is accepted at most once,
and is atomically consumed by proof processing so concurrent or repeated proofs cannot both
succeed.

At most one unconsumed challenge exists for a Device Session. Creating a new one atomically
invalidates any previous challenge before returning the new value; a later proof for the
replaced Challenge ID receives the same `410 challenge_unavailable` as an expired or consumed
one. The Client enforces one single-flight challenge request and proof locally, but the
Engine owns this concurrency invariant. Unknown or revoked Device Session IDs create no
challenge or per-identifier limiter state.

Challenge creation and proof verification share three disposable in-memory token buckets:

| Scope | Capacity and refill |
|---|---|
| Known Device Session | 5 requests per minute |
| Trusted source address | 30 requests per minute |
| Engine process | 120 requests per minute |

Every request must have capacity in every applicable bucket before expensive signature work
or state mutation. The trusted source address comes only from the ASGI peer and
Starling-sanitized forwarding metadata, never an arbitrary inbound forwarding header.
Unknown IDs still consume source and Engine capacity without allocating attacker-chosen
per-ID state.

Exhaustion returns `429 rate_limited` with an integer `Retry-After` equal to the earliest
allowed retry and no Device/Profile existence information. Rate-limit checks, challenge
replacement, and proof consumption use monotonic fake-clock tests. Concurrent replacement
and proof tests establish that no two proofs succeed and that an invalid signature consumes
the challenge it attempted.

An Access Session expires 15 minutes after successful proof. Its response includes an
absolute `expires_at`; neither challenge nor session lifetime is chosen by the Client. The
Client never extends an existing bearer. While it remains in the active foreground, it
starts one single-flight challenge/proof renewal when the current session has no more than
five minutes remaining.

A newly issued bearer atomically replaces the old bearer in the Client. The old Engine
record may remain valid until its own expiry, but the Client immediately discards its value
and sends no further request with it. During an in-flight renewal, requests already using
the old bearer may finish while it remains valid.

A transport failure during proactive renewal leaves the old bearer usable until its expiry
and schedules a bounded foreground retry; it does not expose or mutate cached data. Once the
bearer expires, online requests stop until a fresh proof succeeds. A proof outcome of
`locked_engine`, `profile_mismatch`, or `not_authorized` immediately destroys any current
bearer and transitions to the corresponding non-data state. Backgrounding and system lock
follow the stricter destruction rule defined above regardless of remaining lifetime.

All lifetime and threshold behavior uses an injected clock in tests. Boundary cases at
exact expiry, simultaneous renewal triggers, delayed responses, Engine restart, and clock
skew are covered with deterministic tests; authorization always follows Engine time.

## HTTP namespace and authorization boundary

Every new Companion HTTP resource lives under `/api/1/companion/*`. This is an additive
part of the existing Engine Protocol v1, not a mobile BFF or a new API version. The
unauthenticated bootstrap resource is `GET /api/1/companion/protocol`; it is authoritative
for Companion Protocol version and Capability discovery, so the Client does not infer
support from the general `/api/1/info` response or application build version.

The subtree has a dedicated deny-by-default authorization dispatcher with an explicit
method-and-route matrix for five realms: unauthenticated protocol discovery, Full Client
control, single-use Pairing, pre-session Device Key proof, and Access Session. Exempting a
Companion route from the existing browser-cookie gate must only hand it to this dispatcher;
it must never make that route generally unauthenticated.

The dispatcher selects a realm from the exact method-and-route pair, never from supplied
credentials. It processes a request in this order:

1. match the exact method and route; an unlisted pair receives the ordinary indistinguishable
   `404 resource_not_found`, without `405`, `Allow`, redirect, or credential lookup;
2. handle public `/protocol`, which ignores all cookies and authorization credentials without
   parsing or store lookup and returns the same public response as a credential-free request;
3. validate `Rotki-Companion-Protocol` for every other matched Companion route;
4. invoke exactly the route-selected authority or proof stage, with no fallback to another
   credential store;
5. after successful authority, enforce Companion Scope, Engine/Profile lifecycle, and
   resource ownership in that order.

Full Client routes inspect only the active browser cookie and ignore Bearer/MCP headers;
without that cookie they return `401 full_client_auth_required`. Registration checks a
Bearer only in the Pairing store; missing, malformed, expired, consumed, Access, MCP, or
random tokens collapse to `410 pairing_unavailable`. Challenge creation and proof use their
declared request-body stages and ignore transport credentials. Access routes inspect a
Bearer only in the Access Session store; missing, malformed, expired, Pairing, MCP, or random
tokens collapse to `401 access_session_unavailable`. A currently valid Access bearer whose
Device Session has since been revoked returns terminal `401 not_authorized`.

A Full Client cookie authorizes only the explicitly listed Pairing and device-management
control routes within the subtree. A Pairing secret authorizes only one Device Session
registration. A Companion Access Session authorizes only its Companion Scope routes and
never authenticates any route outside `/api/1/companion/*`. No HTTP route accepts more than
one realm. `scope_denied` is used only after a valid Access Session reaches a listed Access
route without its required Scope permission; wrong-realm credentials use the route-selected
authentication error, while absent, cross-Profile, and unauthorized resources use the same
`404 resource_not_found`.

WebSocket remains the existing same-origin `/ws` endpoint rather than moving beneath this
HTTP namespace. Its mobile authentication and per-session subscription boundary are
specified separately.

### WebSocket handshake and lifetime

The native Client opens the same-origin `wss` `/ws` route with its current Access Session:

```http
Rotki-Companion-Protocol: 1
Authorization: Bearer <access-session-credential>
```

The ASGI handshake dispatcher selects exactly one realm. A browser connection continues to
use the existing active HttpOnly session cookie; a mobile connection uses the Bearer header.
A request carrying both credentials is rejected as ambiguous. Query parameters, URL paths,
cookies, and WebSocket subprotocol values must not carry a mobile bearer.

Before accept, a mobile Upgrade uses the normal Companion error envelope: missing,
duplicate, malformed, or unsupported protocol header is `426 incompatible_protocol`;
cookie plus Bearer or a bearer in a forbidden transport location is `400 invalid_request`;
an unavailable Access Session is `401 access_session_unavailable`; and a valid session
without notification Scope is `403 scope_denied`. Rejection creates no subscriber and no
WebSocket close event. Pairing and MCP bearers are checked only as Access tokens and are
indistinguishable from an unavailable Access Session.

Before `websocket.accept`, the Engine validates the Access Session hash, expiry, bound Device
Session authorization, Companion Scope, and currently open bound Profile. An invalid
handshake is rejected without creating a notifier subscriber. An accepted mobile subscriber
is tagged with its authorization realm, Device Session, Profile binding, scope, and session
expiry; it never joins the unrestricted browser broadcast audience.

The Engine closes that subscriber immediately when its Device Session is revoked, the
Engine locks, a different Profile opens, or its Access Session expires. Expiry uses a timer
rather than waiting for the next event. After Access Session renewal, the Client replaces
the old connection with a handshake carrying the new bearer. WebSocket connection state is
not authorization state: reconnect always performs the complete current validation.

Every mobile event passes a server-side Companion event allowlist and Profile/operation
ownership check before serialization. Client-side filtering is defense in depth only and
must not be the control that prevents unrestricted Full Client events or another Profile's
data from reaching the socket.

### WebSocket delivery model

An accepted mobile connection has no subscribe, unsubscribe, authentication, or other
application-message handshake. The Engine automatically emits only two Companion event
families visible to the bound Profile:

1. a newer Snapshot Revision is available;
2. an authorized global or per-source Refresh Operation changed state or progress.

There is no event replay buffer and delivery is at-most-once per live connection. Events
may be lost during disconnection, duplicated around reconnect, or observed after a newer
REST response; they are notifications rather than authoritative state. They never contain
portfolio amounts, balances, addresses, History records, credentials, or a full Portfolio
Snapshot.

Immediately after each successful connection, the Client Fetches `/snapshot` and lists
currently active `/refresh-operations` before relying on later notifications. A Snapshot
Revision notification triggers a Fetch only when it differs from the locally held revision.
A Refresh notification is reconciled with its REST operation resource. Thus a disconnect,
process death, or missed terminal event cannot leave the Client's durable state dependent on
WebSocket delivery. Source Health in the next Snapshot records any completed Refresh
outcome; an operation lost in an abrupt Engine restart is not fabricated as either success
or failure, and the fetched Snapshot remains authoritative for committed portfolio state.

#### Event envelope and ordering

Companion notifications preserve the existing rotki WebSocket envelope with exactly one
`type` string and one typed `data` object. A Snapshot notification is:

```json
{
  "type": "companion_snapshot_revision",
  "data": {
    "revision": "opaque-snapshot-revision"
  }
}
```

Snapshot Revisions are opaque and have no lexical or numeric ordering. If the advertised
value differs from the Client's current revision, it coalesces concurrent notifications into
one Snapshot Fetch; the fetched document is authoritative even if its revision has changed
again.

#### Snapshot Revision derivation

For Snapshot schema version 1, `revision` is exactly 32 SHA-256 bytes encoded as 43
characters of canonical unpadded Base64URL. The Engine is the only producer; Clients
validate the encoding and compare it for exact equality but never calculate it, order it,
extract time from it, or treat it as authorization or an authenticity proof.

The Engine first builds a revisionless semantic candidate containing every version-1
Snapshot field except `revision`, `captured_at`, and the outer `{result, message}` HTTP
envelope. Transport receipt time, request headers, JSON whitespace, database row order,
in-memory insertion order, and presentation-only derivations are not semantic fields.
Exact decimals are normalized to their canonical string form before this step.

Every array has a schema-owned deterministic order before canonicalization. Version 1 uses:

- Sources by `source_id`;
- Manual Entries by `manual_balance_id`;
- Balance Contributions by `(origin.kind, origin identifier, category, asset_id)`;
- Allocation Locations by canonical location value;
- bounded History Groups by `occurred_at_ms` descending and then opaque `group_id`
  ascending, with each group's nested History Events ordered by their unique dense
  `sequence`; each Group `locations` array is ordered by canonical location value,
  every Event and Group `source_ids` array plus the History `removed_source_ids` closure is
  ordered by canonical Source ID bytes, and Summary role arrays follow nested Event order;
- any nested collection by its explicitly documented domain key, never raw query order.

Objects are then encoded with RFC 8785 JSON Canonicalization Scheme over the strict JSON
subset used by the Snapshot. Exact financial values remain JSON strings, timestamps and
sequence indices are finite integers, and NaN, Infinity, floating-point financial values,
duplicate object keys, and lone Unicode surrogates are forbidden. RFC 8785 sorts object
keys but preserves arrays, which is why the domain ordering above is mandatory. String
content is not Unicode-normalized or otherwise rewritten.

To keep `captured_at` meaningful without making each Fetch a new Snapshot, the Engine keeps
one encrypted Profile-scoped publication record containing the candidate fingerprint,
published `captured_at`, and revision. Under the coherent Snapshot publication lock:

1. an unchanged semantic candidate reuses the prior `captured_at` and revision byte for
   byte, including after a normal restart or Profile backup/restore;
2. a changed candidate receives one UTC Unix-second `captured_at`, which is then added to
   the revisionless document;
3. the Engine persists the new publication record atomically before notifying Clients.

Thus `captured_at` is the time that semantic Snapshot content was published, not the time
of an HTTP Fetch. It is included in the final canonical revision document. A failed build
or interrupted publication leaves the previous record authoritative and emits no revision
notification.

Let `P` be the Profile's stable internal Profile ID bytes and `J` be the RFC 8785 bytes of
the complete revisionless document including `schema_version: 1` and the stable
`captured_at`. The exact preimage is:

```text
ASCII("rotki-companion-snapshot-revision") || 0x00 ||
UINT32_BE(1) ||
UINT32_BE(length(P)) || P ||
UINT64_BE(length(J)) || J
```

`revision = BASE64URL_NOPAD(SHA256(preimage))`. Length prefixes are byte lengths. The
domain separator and schema version prevent another digest use or future schema from
sharing this namespace. Including the non-public random Profile ID makes identical
documents from different Profiles produce unrelated revisions without exposing that ID.
The digest is not a MAC and never replaces Access Session authorization, trusted HTTPS, or
authenticated encryption of the offline document.

Golden vectors pin the preimage and result across field changes, Unicode strings, exact
decimals, null valuations, negative known net value, and empty collections. Permuting map,
query, Source, Manual Entry, contribution, or History input order must preserve the result;
changing any included semantic field must change it. Separate vectors prove identical
content survives restart and backup/restore, equal content under different Profile IDs has
different revisions, excluded internal-label or request-time churn has no effect, and the
`revision` field can never recursively enter its own preimage.

#### Atomic Snapshot publication

`GET /snapshot` reads only the last Published Snapshot stored with the encrypted Profile;
it never assembles a response from mutable blockchain, exchange, manual-balance, price, or
History caches. While any Refresh Operation is queued or running, Fetch immediately returns
that prior published document and its unchanged Revision. It does not block on external
work, return an in-progress document, or expose a Source merely because its upstream query
finished before its covering operation settled.

Each Refresh executes external work outside the short Profile-scoped publication mutex and
writes results only to operation-local staging. A staged Source result includes its frozen
Source ID and execution-configuration generation, exact contributions, price availability,
capture facts, and safe outcome. It is not visible to Snapshot Fetch, another operation, or
the Full Client's authoritative live surfaces before Companion publication. Generation and
Profile-lifecycle guards are checked again at commit, so deleted, disabled, reconfigured,
locked, or replaced Profile state cannot be restored by late work.

The Engine owns durable encrypted Companion source-state records separate from mutable
legacy caches. Under one publication mutex and one user-database transaction it:

1. reloads the latest committed Companion source state rather than the operation's starting
   document;
2. applies each still-valid successful staged result and new Source Health;
3. retains failed Sources' prior contributions while committing their failure health;
4. reads Manual Entries, bounded History, metadata, and Profile settings in that same
   transaction and builds the full semantic candidate;
5. derives or reuses `captured_at` and Revision, atomically replaces the Published Snapshot
   and publication record, and commits;
6. only after commit, transitions the Refresh Operation to its terminal representation and
   emits its operation event followed by a Snapshot Revision notification when the Revision
   changed.

A global operation has one publication after every Source in its frozen selection settles;
successful Sources and failed-Source last-known data therefore become visible together in
one Complete or Degraded Snapshot. It never publishes per-Source intermediate revisions.
A targeted operation has at most one publication after its one Source settles. A failure
that changes Source Health may publish a new Degraded Snapshot even when balances remain
unchanged; a failure before any coherent source-state effect leaves the prior revision.

Independent targeted operations may run concurrently. Their short commits serialize on the
publication mutex and each merges into the latest committed source state, so the later
commit includes the earlier commit rather than overwriting it with the operation's stale
starting document. Each terminal operation points to the exact revision produced by its own
commit. A later publication may supersede that revision immediately; the direct operation
resource remains historical evidence while current `GET /snapshot` remains authoritative.

Manual Entry or in-scope Full Client configuration/History mutations use the same
publication service: the mutation transaction commits first, then synchronously schedules
or performs one short rebuild before announcing a Revision. During the rebuild, Fetch keeps
serving the previous valid document. A publication build or storage failure does not expose
partial state, destroy the prior document, or emit a notification; the Companion endpoint
returns the last valid publication and protected Engine diagnostics record the fault.

Before Companion Snapshot access is enabled for an existing or new Profile, the Engine
performs one Configuration Bootstrap under the normal publication mutex and user-database
transaction. It performs no blockchain, exchange, oracle, History synchronization, or
other external request and imports no aggregate `timed_balances`, `timed_location_data`,
in-memory exchange result, or legacy blockchain balance cache into Companion source state.
Those stores cannot prove the complete per-Source origin and capture semantics required by
this protocol.

The bootstrap document contains current Source configuration and identities, Manual
Entries, the currently durable bounded History window, Profile settings, and their complete
Asset Catalog. Every external Source has no contributions and begins with:

```json
{
  "data_state": "never_refreshed",
  "data_captured_at": null,
  "last_attempt_at": null,
  "last_attempt_outcome": "never",
  "last_error": null
}
```

Each Source retains its actual `enabled` value. Snapshot Coverage is therefore Degraded for
every non-empty Source collection: enabled Sources contribute `never_refreshed`, while
disabled Sources contribute `disabled`. Their absence from contributions never means a
zero balance. Native Clients present an explicit initial-refresh state and offer the normal
user-initiated global Refresh when at least one Source is enabled; bootstrap never starts it
automatically.

Manual Entry amounts remain authoritative local facts. The bootstrap may attach a value
only when it can be derived from coherent local data without an external request; otherwise
it emits `value: null` and Partial Valuation Coverage. Durable History is included exactly
as stored and is not refreshed. The resulting document follows the ordinary Revision and
atomic publication rules and is a valid offline Snapshot immediately after Pairing.

A Profile with no configured Sources and no Manual Entries is a valid explicit empty
Published Snapshot. `GET /snapshot` returns `503 snapshot_unavailable / retry` only while
Configuration Bootstrap has not completed or publication/recovery failed; it never invents
an empty document as a fallback. Engine startup rebuilds a missing or invalid publication
from durable Companion source state and Profile data without querying upstream Sources;
after initial bootstrap it does not reset existing Sources to `never_refreshed`.

#### Bounded History read model

Snapshot schema version 1 contains a curated group-centric History read model rather than
the response shape of the general `/history/events` API. The bound is applied to complete
display groups: a Snapshot contains the newest measured number of History Groups that fit
under a measured encoded-byte ceiling, and it never truncates a group's nested events to
make the document fit. The exact group count and byte ceiling are frozen from the Phase
D3.2 owner-Profile and golden-Profile measurements. The whole History section changes only
as part of an atomic Published Snapshot replacement.

Each History Group has an opaque Profile-scoped `group_id`, a typed summary, one
`occurred_at_ms`, a required non-empty `locations` array, and its complete ordered array of
projected History Events. Each History Event has an opaque Profile-scoped `event_id`, an
explicit `sequence`, typed entry kind, event type and subtype, one required canonical
`location`, canonical `asset_id`, non-floating-point exact decimal `amount`, a nullable
exact-decimal `value`, and zero or more sorted opaque `source_ids`. A group's `locations`
and `source_ids` are exactly the sorted, deduplicated unions of those respective Event
fields. Optional detail payloads and the Group's required `summary` are closed typed
variants with explicit field allowlists. The Snapshot never copies arbitrary `extra_data`,
a polymorphic web-API response, accounting metadata, or an unknown detail object for
forward compatibility.

`occurred_at_ms` is exactly the maximum integer millisecond timestamp among every included
History Event in the complete curated group. The Engine computes it after the entitlement
window is selected, exact movement, bridge, and other display joining is complete, and the
projection rules below are applied, but before Snapshot bounds or request filters; a filter
never recomputes it from only matching legs. An empty group is invalid. Normal groups whose
legs share a timestamp retain that value, while a multi-time movement or bridge is placed
at its latest, usually completion, leg so recent activity is not hidden by an older
constituent.

Every edit, re-decode, join, or split recomputes occurrence time from the newly committed
complete group. A timestamp change may move the same Group ID when History Lineage remains
proven: time is ordering and presentation data, never identity. Any resulting order,
entitlement, filter, or bounded-window change increments History Generation and republishes
the Snapshot before a later cursor chain can observe it.

Curated History applies these inclusion rules after the global underlying-group entitlement
window and exact join closure are fixed:

1. Engine-derived `hidden` events that exist only to consolidate or avoid duplicate display
   are removed from the projection. Their hidden flag, fields, and technical duplicate do
   not enter a History Group, summary, occurrence time, Asset Catalog, or public response.
2. Accounting state never controls visibility. Customized events retain their current
   edited financial fields, and events or groups marked ignored for accounting remain
   ordinary History. The `customized` and `ignored_in_accounting` markers themselves are
   not serialized.
3. Asset-ignore state is applied at the complete display-group boundary. If every remaining
   event references an asset currently ignored by the Profile, the whole group is absent.
   If at least one event references a non-ignored asset, the complete group is included with
   every remaining event, including ignored-asset legs. A swap, movement, bridge, fee set,
   or other logical activity is never made partial merely to hide one asset.

An underlying group excluded by these rules still consumed its position in the pre-filter
History Entitlement window; neither exclusion nor a narrow filter backfills from older raw
groups. Hidden-only and all-ignored-asset groups receive no public card, while the terminal
entitlement state remains based on the Engine window rather than the number of projected
cards. Any change to hidden topology, accounting customization content, or ignored-asset
membership that can alter projection content, ordering, or inclusion increments History
Generation and triggers coherent Snapshot republication.

Version 1 preserves useful transaction inspection offline through this positive allowlist:

- a group has derived non-empty canonical `locations`, derived sorted `source_ids`, and a
  closed Summary whose `kind` and Event references are derived from proven topology rather
  than copied prose;
- every Event has one required canonical `location` independently of its Source Attribution
  and any transfer-rail blockchain in typed detail;
- every Event has its own sorted `source_ids`, which may be empty when provenance is not
  provable and may contain more than one Source for a genuine multi-account Event;
- every Event has `value` as a canonical exact-decimal string in the Snapshot's
  `valuation_currency`, or null when no deterministic local historical valuation was
  materialized;
- every event may carry one validated canonical `protocol_id`; unknown or free-form
  counterparties are omitted rather than serialized under that field;
- `onchain` detail contains a canonical blockchain value, validated transaction reference,
  and optional interacted address;
- `asset_movement` detail contains direction plus optional canonical blockchain,
  transaction reference, and transfer address;
- `eth_withdrawal` detail contains validator index and `is_exit`;
- `eth_block` detail contains validator index and block number;
- `eth_deposit` detail contains validator index, transaction reference, and optional
  depositor address;
- an event with no recognized safe variant has no detail object but retains all common
  fields and remains visible.

History Source Attribution is a durable Profile-owned relation attached to the Event's
Companion identity and lineage, not a value inferred while serializing a request. The Engine
adds a Source ID only when provenance resolves exactly to an identity in the Source ledger:
a blockchain Event may resolve through its canonical blockchain and tracked address, and an
exchange Event may resolve through one exact named connection. A provider location alone,
`location_label`, account-looking text, transaction address, or display-group membership is
never sufficient. Imported, manual, synthetic, unconfigured, and ambiguous records use an
empty array; empty means unknown or not applicable, never that the Event has no external
origin in reality.

The provenance relation is stored in the encrypted Profile DB independently of editable
labels and active Source configuration. An exact lineage-preserving edit or re-decode
retains it unless newly committed structured evidence proves a different relation;
ambiguous replacement clears attribution rather than guessing. Movement, bridge, or other
display joining preserves each Event's array and derives only the group union. Attribution
updates commit in the same transaction as the History mutation, identity reconciliation,
`history_generation`, and publication-dirty marker. Snapshot construction validates the
relation against the Source identity ledger and fails closed on a dangling or malformed ID.
Event location and the derived Group set neither prove Event provenance nor limit the Source
union of a joined multi-location activity.

`sequence` is a required zero-based dense projected ordinal, not the Engine's stored
`sequence_index`. For a Group with N included Events, every integer in `[0, N)` appears
exactly once and no other value appears; the `events` array is in ascending `sequence`
order. Consequently `event_id` is not an ordering tie-break inside a valid Group. The
ordinal is assigned after exact joining, Curated History inclusion, and canonical
presentation ordering, but before Summary role arrays, occurrence time, valuation,
Snapshot bounds, or request filters are serialized.

Raw `sequence_index` may inform canonical projection but never crosses the Companion
boundary. Its database uniqueness applies only within one underlying group, its schema does
not prove a non-negative range, gaps are normal, and independently joined groups can repeat
the same value. A raw-index edit that preserves the final projected Event order and all
other public content causes no Companion change. A true order change recomputes dense
ordinals, advances History Generation and Revision, and preserves Event and Group IDs when
their lineage remains otherwise proven. Filtering returns the complete stored Group and
never removes or renumbers Events for a particular request.

Canonical History Presentation Order is determined before dense ordinals. A `swap` places
all proven spend-role Events first, then receive-role Events, then fee-role Events, followed
by every remaining included auxiliary Event. A `movement` or `bridge` places out-role Events
first, then in-role Events, then fees, then auxiliaries. An `activity` preserves the
Engine-authored order of all included Events because no version-1 topology truthfully
classifies them into semantic buckets.

Within each role or auxiliary bucket, the Engine preserves deterministic authored order;
for joined constituents it first uses persisted topology rank, then the constituent's
authored order, with opaque `event_id` only as a final tie-break for otherwise equal internal
keys. Database query order is never authoritative. An auxiliary is any included Event not
referenced by the specialized Summary roles, including gas, informational, adjustment, and
future unknown-code Events. The projector never assigns an auxiliary to a role merely from
event type/subtype, value, asset, note, or array position. Summary role arrays follow the
resulting Event order, and `sequence = 0..N-1` is assigned afterward. Any change to the
public order advances History Generation without by itself changing proven identity.

History `location` uses the stable lower-snake-case Engine Protocol enum and is distinct
from excluded `location_label`. A Group's `locations` array is non-empty, contains no
duplicates, and is ordered by canonical enum value only for deterministic transport; its
order never means from/to. Movement and bridge presentation derives direction by
dereferencing `out_event_ids` and `in_event_ids`. A one-sided topology contains only the
location of Events actually present. Neither Source metadata nor `detail.blockchain` adds a
location: for example, a Kraken withdrawal over Ethereum remains at location `kraken` until
an included Ethereum Event proves the other side.

For a single-location Group, native UI displays that location. For a specialized
multi-location Group it displays the directional Event locations; for generic activity it
may display the primary Event's location plus a localized count of the remaining distinct
locations, with each expanded Event retaining its own value. A syntactically valid future
location maps to generic localized display while preserving the Event; a malformed or
missing location invalidates the candidate. Any committed Event-location or derived-union
change is canonical History content and advances History Generation.

`entry_kind`, `event_type`, and `event_subtype` are three distinct History Protocol Code
domains. Each field is a required JSON string whose UTF-8 representation is 1 through 64
ASCII bytes and matches `^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$`. `event_subtype: "none"` is an
ordinary required value; null, an empty string, spaces, uppercase, punctuation, non-ASCII,
or an overlong value is malformed. Companion codes come from an explicit checked-in
vocabulary table and are never produced at runtime by lowercasing a Python enum member or
copying the general History API's display serialization. Once issued inside one domain, a
code is never renamed, reused for another meaning, or silently moved between domains.

Shared KMP represents each domain with its own project-owned `Known | Unknown(rawCode)`
wrapper and a bare-string serializer. A syntactically valid unknown code is retained exactly
in the authenticated model and encrypted Snapshot, participates in Revision content, and
survives decode/encode round-trips. It never causes its Event or complete Group to be
dropped. Native UI uses generic localized Event presentation and does not infer accounting,
direction, topology, security, or navigation behavior from the raw string. An independently
valid known Summary or typed Detail remains authoritative and usable even when one Event
code is unknown; an unknown Event code alone can never create a specialized Summary or
Detail.

An Engine candidate containing a malformed required code fails before publication. A Client
that receives one in a Snapshot rejects the entire newer Snapshot and retains its last valid
authenticated Snapshot; one malformed older-page Event rejects that complete page atomically
without pruning a Group or installing partial Asset/Removed-Source closures. Previously
accepted pages remain usable, while the contract failure is surfaced and not automatically
retried as a transient transport error.

Deleting a Source retires its opaque ID without deleting established History relations.
The bounded History section carries a sorted `removed_source_ids` closure containing exactly
the retired Source IDs referenced by its included Events and no unrelated tombstones. A
referenced ID resolves either to one active Snapshot Source or to this removed closure,
never both; absence from both is an invalid Snapshot. Native Clients render the latter as a
localized Removed Source with no recovered address, provider, or old label. Re-adding the
same natural origin creates a new Source ID and never rebinds earlier Events. Older online
History pages carry the equivalent page-local removed-ID closure for their returned groups.
Profile migration backfills only uniquely provable relations before enabling the Capability;
an unattributed Event is valid and does not block publication.

History Valuation is materialized in an encrypted Profile-owned projection keyed by the
Event's Companion identity, Event timestamp, asset and amount, and valuation currency. It
uses the Event's own timestamp rather than the Group's `occurred_at_ms`. The selector may
consume only historical-price inputs already available locally under the versioned History
Price Policy; Configuration Bootstrap, publication, Snapshot Fetch, and `/history` page
reads never call a remote oracle or fall back to a current price. A missing price is
`value: null`, while a genuine exact zero valuation is the canonical string `"0"`.

Version 1 floors the Event's integer millisecond timestamp to a UTC epoch second. If
`asset_id` equals `valuation_currency`, the unit price is exactly `1` without a cache row.
Otherwise the selector considers only a direct local `(asset_id, valuation_currency)` pair
from the effective allowed historical-source set captured for that projection run. It does
not derive a general cross rate through USD or another intermediate asset. The inclusive
candidate window is plus or minus 3,600 seconds around the Event second, except that a
fiat-to-fiat pair uses plus or minus 86,400 seconds.

Candidates are ordered first by absolute timestamp distance. Only equal-distance candidates
are ordered with MANUAL first, followed by the captured effective Engine historical-oracle
order and then canonical source identifier as the final total tie-break. A source outside
the captured allowed set is ineligible even if its row is closer. The selected exact unit
price is multiplied by the Event's exact amount and canonically normalized without
floating-point conversion or presentation rounding. Stored price zero produces `"0"`; no
eligible candidate produces null. Database insertion order and row ID never affect output.

The materialized value is canonical History content, not a best-effort response decoration.
A relevant Event edit, Profile main-currency change, or controlled update to the selected
local historical-price input stages and commits the affected valuations with the History
generation and publication-dirty record. If any public value changes, `history_generation`
advances and the Snapshot is republished before a later cursor chain can observe it. Raw
global-cache churn that has not passed through this controlled projection update cannot
change Snapshot or page output. An active generation therefore returns the same value for
the same Event on every page and after response replay.

Historical-price add, edit, delete, range deletion or cache replacement; effective oracle
eligibility or order changes; main-currency changes; Event asset, amount or timestamp
changes; and a History Price Policy version change all schedule controlled revaluation for
the affected Profile projection. The work captures one policy version and one source order,
stages its results, then atomically replaces materialized values and advances History
Generation only if public values changed. A failure preserves the prior materialization and
Published Snapshot, retains the dirty work item, and never mixes policies inside one
generation.

The wire contract contains no unit price, price timestamp, oracle/source metadata,
confidence, parallel USD value, or per-Event currency. It also defines no generic History
Group total: spend, receive, fee, movement, and staking legs have different semantics, so
summing their monetary values would double-count or imply a false net result. Older pages
use the same materialized projection and include only the ordinary page-local Asset Catalog
closure; History valuations never become Asset Catalog metadata.

Each History Summary is a topology-only discriminated union. Every variant has a stable
lower-snake-case `kind` and one `primary_event_id`; a specialized variant adds only ordered
arrays of `event_id` references under its declared semantic roles. It never repeats an
Event's asset, amount, value, timestamp, Source attribution, protocol, detail, or localized
label. Native Clients dereference the referenced Events and format those authoritative
facts, so a summary cannot disagree with its Group after an edit or revaluation.

Every referenced ID must identify exactly one included Event in the same Group. Required
roles are non-empty, role arrays follow the Group's canonical Event order, duplicates are
forbidden, and roles declared mutually exclusive by a variant cannot overlap. A specialized
summary is valid only when every included Event relevant to that topology has one truthful
role. If the Engine sees mixed activities, incomplete legs, multiple independent
sub-activities, ambiguous roles, or a future pattern that the negotiated schema cannot
represent, it emits the generic primary-Event form rather than dropping Events or guessing.
Summary classification is presentation topology, not Group identity; a classification or
role change is canonical History content and advances History Generation.

The Engine derives summary topology after exact joining and Curated History inclusion, but
before occurrence-time calculation, Snapshot bounds, and request filters. Filtering never
rebuilds a summary from only matching Events. Clients do not infer specialized cards from
raw event type/subtype combinations, free-form notes, array position, or transaction data.
A future summary kind must retain the common `primary_event_id` so an older tolerant Client
can render the complete Group through its generic Event presentation while ignoring unknown
role fields.

Schema version 1 defines exactly these Summary shapes:

```json
{
  "kind": "swap",
  "primary_event_id": "opaque-event-id",
  "spend_event_ids": ["opaque-event-id"],
  "receive_event_ids": ["opaque-event-id"],
  "fee_event_ids": []
}
```

```json
{
  "kind": "movement",
  "primary_event_id": "opaque-event-id",
  "out_event_ids": ["opaque-event-id"],
  "in_event_ids": ["opaque-event-id"],
  "fee_event_ids": []
}
```

`bridge` has the same common and directional role fields as `movement`, with
`kind: "bridge"`. The generic form is only:

```json
{
  "kind": "activity",
  "primary_event_id": "opaque-event-id"
}
```

`swap.spend_event_ids` and `swap.receive_event_ids` are each non-empty;
`fee_event_ids` may be empty. A spend-only or receive-only candidate is not a valid swap
Summary and falls back to `activity`. For `movement` and `bridge`, at least one of
`out_event_ids` or `in_event_ids` is non-empty and the other array may be empty. A one-sided
Summary is permitted only when a typed Asset Movement or typed bridge Event proves that
direction after Curated History projection. Its missing counterpart is not represented by
a placeholder, synthesized Event, raw transaction metadata, or an ID outside the Group.

A two-sided `movement` or `bridge` Summary requires the exact persisted relation that joined
the directional Events into the complete Group. Event type/subtype strings, notes,
addresses, protocol labels, equal assets or amounts, and temporal proximity cannot create
the second side or establish the relation. A fee-only Group is always `activity`; fee Events
may join a specialized Summary only when their association with its proven topology is
unambiguous. These rules preserve useful unmatched deposits, withdrawals, and bridge legs
without claiming that the Engine observed a counterpart.

The Engine assigns `primary_event_id` after Curated History inclusion and role assignment,
using only canonical `(sequence ASC, event_id ASC)` order inside the relevant candidate set:

- `swap` selects the first `spend_event_ids` member;
- `movement` selects the first non-fee typed Asset Movement Event, which must also occur in
  its applicable directional role;
- `bridge` selects the first `out_event_ids` member, or the first `in_event_ids` member when
  the Summary is one-sided inbound;
- `activity` selects the first non-fee included Event, or the first included Event when the
  complete Group is fee-only.

`primary_event_id` always resolves exactly once in the Group. A fee cannot become primary
while an eligible non-fee candidate exists. Asset, amount, nullable value, Source,
transaction reference, database row ID, and raw query order never rank candidates. The
choice is presentation topology rather than identity: a committed edit, role change, or
canonical reordering may change primary and advances History Generation, but it does not by
itself retire or transfer the Group ID or any Event ID. Filtering returns the stored complete
Summary and never reselects primary from matching legs.

The Summary discriminator is the Group-kind filter field. Staking, rewards, income,
expense, DeFi, governance, mixed activities, multiple independent swaps or movements,
incomplete or ambiguous topology, and every unrecognized version-1 pattern use `activity`;
their precise meaning remains available through the complete Events' entry kind, event type,
event subtype, protocol and typed detail. Version 1 does not add a new Group kind merely
because an Event type/subtype is added. A new specialized discriminator requires a
negotiated schema or Capability version.

Transaction references and addresses are financial History data, never identities or
authorization values. Each is validated and canonically encoded according to its typed
blockchain variant; a malformed required value aborts publication, while a malformed
optional detail is omitted with protected diagnostics. Unknown fields and future detail
kinds are ignored by tolerant Clients but are not persisted by the Engine's version-1
projection. The Snapshot contains no explorer URL, remote metadata, or embedded binary.

Version 1 deliberately excludes `location_label` and other account labels, `user_notes`,
`auto_notes`, exchange/provider internal references, merchant or IBAN text, arbitrary
counterparty strings, URLs, accounting flags, state markers, hidden flags, and every
unrecognized `extra_data` member. Native Clients produce localized summaries from typed
fields. They never parse prose to recover an address, amount, transaction reference, or
protocol fact. A Boolean `has_user_note` may advertise that additional text exists online,
but the note itself is not part of Snapshot content.

All History fields, including transaction references, addresses, protocol IDs, and opaque
IDs, are sensitive for diagnostics: they never enter logs, notifications, analytics,
crash payloads, filenames, OS restoration state, or unencrypted caches. Online-only History
details and older pages live only in the unlocked foreground session and are purged on
background, lock, Profile transition, or acceptance of a new Snapshot. The Client retains
exactly one encrypted Snapshot document; replacement logically removes deleted History
from the Client, but an offline device necessarily keeps its last published document until
it reconnects and Fetches a newer Revision.

`group_id` and `event_id` belong to the Companion contract. Each is generated from 16
cryptographically random bytes and encoded as exactly 22 canonical unpadded Base64URL
characters. The encrypted Profile DB stores identities and retired tombstones independently
from cascade-deleted `history_events` rows; collisions are rejected and regenerated. They
survive Profile backup/restore, are never reused, and do not reveal or alias the
Engine's integer row identifier, `group_identifier`, `actual_group_identifier`,
`sequence_index`, transaction reference, or database ordering. Those values may be used by
the Engine to project and reconcile the read model but are never public fallback identities.
The IDs are equality-only: Clients do not parse, sort semantically, or infer a transaction
reference from them.

Identity is stable only while continuity is provable. A normal edit that retains the same
logical row preserves its Event ID even when presentation fields or ordering change. A
re-decode preserves an ID only through an exact, unique, versioned internal History Lineage
anchor. Amount, asset, timestamp, mutable sequence, notes, or a similarity score can never
establish lineage by themselves. If no unique one-to-one match exists, including a real
split, merge, duplicate, or ambiguous reordered result, the Engine retires every affected
old ID and mints new IDs. A true deletion retires its IDs; recreating equivalent content
does not reactivate them.

A display group composed by joining multiple underlying groups has its own persisted Group
ID and exact membership/link topology, not the identifier of an arbitrarily selected child.
Its constituent Event IDs may remain stable. The composite Group ID survives only while
the durable link and exact logical composite continue; deleting and recreating that link,
or a split/merge whose continuation is ambiguous, retires the prior Group ID and mints the
successor identity.

Every History mutation updates the identity mapping, a monotonic `history_generation`, and
a durable publication-dirty record in the same user-database transaction. Re-decode first
stages replacement output, then atomically replaces one logical group, reconciles exact
lineage, updates identities, and marks the generation; it never commits deletion before
replacement is ready. A bulk re-decode may commit independent logical groups but holds a
durable publication barrier and publishes once after the batch settles. Mapping failure
rolls back that logical mutation.

Snapshot construction only reads established active mappings; it never opportunistically
mints or guesses IDs. Missing, duplicate, or inconsistent mappings abort the candidate,
leave the prior Published Snapshot and Revision authoritative, emit no notification, and
retain the dirty record for startup or explicit recovery. Bootstrap/backfill allocates all
current identities transactionally before enabling the Capability. Snapshot publication
records the consumed `history_generation` and clears dirty state only if no newer History
mutation committed meanwhile.

Groups are presented by `occurred_at_ms` descending with `group_id` ascending as a total
tie-break; nested events are presented by `sequence` ascending with `event_id` ascending as
a total tie-break. Pagination operates on whole groups and uses the same order. Older pages
and details are held in memory for the current foreground session only and are never
appended to the encrypted offline Snapshot. A Client deduplicates them by the opaque
Companion IDs, not by raw Engine keys.

#### Older History pagination

The additive Companion History resource uses generation-fenced keyset pagination, never
the general History API's live `limit + offset` behavior. Its consistency claim covers one
unchanged History Generation: every accepted continuation returns the next complete groups
without a gap or duplicate in `(occurred_at_ms DESC, group_id ASC)` order. It does not keep
a database read transaction or materialized historical view alive across HTTP requests.

Companion preserves the Engine's existing History Entitlement instead of creating a mobile
bypass. The effective limit is 1,000 for a free or inactive subscription and the positive
`history_events_limit` resolved for the active plan; an Engine representation normalizes a
truly unlimited value to null. If premium status or limits cannot be resolved, the existing
safe fallback is the free limit. Snapshot publication and Companion Fetch/History requests
never contact the premium service: they consume the effective entitlement already held by
the Engine's premium lifecycle, and an unresolved state is free until that lifecycle
successfully updates it.

The entitlement window is selected before any Client filter. It is the newest N distinct
underlying Engine history groups in deterministic `(MAX(timestamp) DESC,
group_identifier ASC)` order, where N is the effective limit; the raw identifiers never
leave the Engine. Curated display groups are then formed. A joined display group is entitled
when any constituent underlying group intersects that window, and its complete exact join
closure is returned even if another constituent lies just outside it. Consequently the
number of visible curated groups or event legs need not equal N; this does not spend extra
quota or expose an additional unrelated group.

The bounded Snapshot and older pages are consecutive slices of that one entitled curated
feed, not separate allowances. Date, type, asset, and Source filters run only after the
global entitlement window, curated projection, and Snapshot-tail exclusion. A narrow filter
therefore cannot search past the plan boundary or repurpose unmatched entitlement slots.
The Snapshot includes this semantic object:

```json
{
  "history_entitlement": {
    "group_limit": 1000,
    "window_truncated": true
  }
}
```

`group_limit` is a positive integer or null for unlimited. `window_truncated` is true only
when at least one underlying group exists beyond the effective window. Plan names and raw
or filtered total counts are never part of the Snapshot or page response. Entitlement facts
affect Snapshot Revision. A change to effective limit or truncation increments
`history_generation`, marks publication dirty, invalidates every active cursor through the
ordinary `history_changed / fetch_snapshot` path, and republishes the newly entitled bounded
prefix. A downgrade cannot remotely rewrite a disconnected Client's prior authenticated
Snapshot; the smaller window becomes authoritative after its next successful Fetch.

The first request has no cursor and requires `base_snapshot_revision` equal to the current
Published Snapshot Revision. In one user-database read transaction, the Engine verifies
that publication's consumed History Generation equals the current `history_generation`,
derives its exclusive bounded-History tail, and reads only groups older than that boundary.
The publication record stores the boundary internally as exactly one of `exhausted`,
`after(last_included_occurred_at_ms, last_included_group_id)`, or `before_all` when no group
fit but older History exists. No live or expiring cursor is embedded in Snapshot content or
its Revision.

A first request may include a bounded `limit` plus the version-1 date, group/event type,
location, asset-ID, and Source-ID filters. Values inside one filter dimension use OR and
different dimensions use AND. Date and group-kind predicates evaluate the complete Group.
For all active Event-scoped dimensions, there must exist one and the same included Event
whose `location`, `asset_id`, event type, event subtype, and proven `source_ids` satisfy
them; an absent dimension is true, and a Source dimension matches by non-empty set
intersection. The derived group-level location and Source unions cannot satisfy a predicate
on behalf of a different Event.

Version 1 has exact-code filters for `event_type` and `event_subtype`, but no `entry_kind`
filter. Filter values use the same grammar and exact case-sensitive equality. A Client may
echo a syntactically valid unknown code that it observed from the connected Engine; the
Engine necessarily recognizes its own current vocabulary. A malformed code or a valid code
outside that Engine's current filter vocabulary returns `400 invalid_request` without
revealing data. Known-code pickers advertise the Client vocabulary, while an observed
unknown may appear only as a generic exact-code filter token rather than guessed semantics.

After selection, the response returns the unchanged complete Group and every included
Event. Filtering never prunes legs or recomputes occurrence time, summary, group Source
union, or Asset Catalog closure. For example, a `spend ETH / receive USDC` swap does not
match `asset_id=ETH` together with `event_subtype=receive`, because no single Event has both
facts. The Engine canonicalizes and binds the full filter set and page limit to the cursor
chain. A continuation supplies only `cursor`; changing filters, direction, boundary, or
limit starts a new first request.

The response is:

```json
{
  "result": {
    "groups": [],
    "asset_catalog": {},
    "removed_source_ids": [],
    "next_cursor": null,
    "terminal": {
      "kind": "history_exhausted"
    }
  },
  "message": ""
}
```

`groups` contains at most the requested number of whole projected History Groups and also
obeys the response byte ceiling. `asset_catalog` is the closed minimal catalog for every
asset referenced by this page, because an older page may use assets absent from the current
Snapshot catalog. The Engine reads one extra matching group to decide whether to issue
`next_cursor`. `terminal` is null whenever a cursor exists. When `next_cursor` is null it is
exactly one of `{ "kind": "history_exhausted" }` or
`{ "kind": "history_entitlement_limit", "group_limit": N }`; the latter is used whenever
the global entitlement window is truncated, even if the current filters matched no group
near that boundary. This lets native UI distinguish actual exhaustion from a plan boundary
without exposing totals. There are no page numbers, offsets, `has_more`, or mutation-
sensitive total counts. A group too large to fit by itself fails
the page without advancing the cursor and is never silently skipped or truncated.

For a non-terminal page, the next seek is exclusive:

```text
occurred_at_ms < last_occurred_at_ms OR
(occurred_at_ms = last_occurred_at_ms AND group_id > last_group_id)
```

Each History Cursor is a uniformly random 24-byte handle encoded as exactly 32 canonical
unpadded Base64URL characters. A bounded in-memory Engine record binds it to the Profile,
Device Session, negotiated History Capability version, base Snapshot Revision, History
Generation, canonical filters, limit, boundary, exclusive last key, and expiry. It grants
no authority and every use still requires a valid Access Session for its Device Session.
Binding to the Device Session lets a normal Access Session proof renewal preserve a
foreground pagination chain. Cursor records and their cached child handles are replayable
until expiry, so retrying after a lost response returns the same logical page and
continuation without advancing twice.

The cursor registry is disposable foreground state: a chain has a 15-minute absolute
lifetime and is cleared on Profile lock/transition, Device Session revocation, or Engine
restart. The Client never parses a cursor, writes it to the encrypted Snapshot, backup, OS
restoration state, URL history, or diagnostics, and discards it with all online-only pages
on background, lock, Profile transition, or acceptance of a new Snapshot.

Every mutation that can affect curated History membership, identity, order, filters, typed
projection, or exact group topology increments `history_generation` atomically with that
mutation. If it differs on any continuation, the Engine returns:

```json
{
  "result": null,
  "message": "History changed while it was being read",
  "error": {
    "code": "history_changed",
    "retryable": false,
    "action": "fetch_snapshot"
  }
}
```

with `409 Conflict`. The Client atomically removes every online-only History page and
detail, Fetches the current Published Snapshot, and begins a new cursor chain. It never
merges pages from different History Generations; opaque-ID deduplication is defense in depth,
not the consistency mechanism. This restart rule covers new recent or backdated groups,
edits, deletes, re-decode, and join/split changes.

A well-formed but expired, evicted, restarted, or wrong-binding cursor returns the same
`410 history_cursor_unavailable / restart_history` without revealing whether a cursor exists.
The Client discards its older pages and starts a new first request from its current Snapshot;
if that Snapshot is no longer current, the first request then returns `history_changed`.
A malformed cursor encoding or an invalid first-request filter remains `400 invalid_request`.
All History query values and cursor tokens are redacted from access and application logs.

A Refresh notification carries the same typed operation state returned by its REST resource:

```json
{
  "type": "companion_refresh_operation",
  "data": {
    "operation_id": "opaque-operation-id",
    "version": 3,
    "created_at": 1786550400,
    "started_at": 1786550401,
    "finished_at": null,
    "target": {
      "kind": "global"
    },
    "state": "running",
    "progress": {
      "completed": 2,
      "total": 5
    },
    "result_snapshot_revision": null,
    "error": null
  }
}
```

`target.kind` is `global` with no Source ID or `source` with one opaque Source ID. A
per-Source operation always has `progress: null` in protocol version 1. A global operation
uses non-negative integer Source units with `completed <= total`; it never contains a
provider-defined percentage, monetary value, Source ID, or upstream detail. A terminal
success may carry its resulting Snapshot Revision. A terminal failure carries the same
typed `code`, `retryable`, and `action` object used by HTTP failures and may also carry a
resulting Snapshot Revision when the failure produced committed Source Health or partial
portfolio changes.

#### Refresh Operation lifecycle

`state` is exactly one of `queued`, `running`, `succeeded`, or `failed`. `queued` and
`running` are active states; `succeeded` and `failed` are terminal and immutable. The legal
transitions are `queued -> running`, `queued -> failed`, `running -> succeeded`, and
`running -> failed`. The Engine still increments `version` for each transition even when a
fast operation advances through states before a Client observes them.

Every operation representation includes Engine-authored integer Unix UTC seconds:

- `created_at` is required in every state;
- `started_at` is null while queued and becomes immutable when work starts;
- `finished_at` is null in active states and becomes immutable on either terminal state.

A queued operation that fails before work starts has null `started_at` and a non-null
`finished_at`. Otherwise exposed values obey
`created_at <= started_at <= finished_at` wherever those fields exist. The Engine clamps an
exposed wall-clock value to the preceding exposed value if its system clock moves backward;
the monotonic clock, not these presentation timestamps, determines transition order,
retention TTL, timeouts, rate limits, and scheduling. Clients never calculate authorization
or operation validity from these timestamps.

`operation_id` is exactly 16 cryptographically random bytes encoded as 22 characters of
canonical unpadded Base64URL. It is an opaque lookup identifier, not a credential, and
encodes no Profile, target, timestamp, lifecycle state, sequence, or Engine instance data.
The Engine checks the complete live and retained registry and regenerates on collision
before publishing an operation. Clients compare it only for exact equality and never parse,
sort, or manufacture it.

Protocol version 1 has no `paused`, `cancelling`, or `cancelled` state and exposes no
Companion pause or cancellation route. Client backgrounding, disconnection, process death,
Access Session expiry, and observer cancellation stop observation only; none changes or
cancels Engine-owned work.

Closing or locking the bound Profile is a separate Engine security boundary, not a Client
cancellation request. During controlled Profile teardown, the Engine first rejects new
Refresh creation, transitions every `queued` or `running` operation for that Profile to
`failed` with `operation_interrupted`, increments its version, and emits the terminal
notification on a best-effort basis. It then requests cancellation of the underlying work,
invalidates the Profile's Access Sessions, closes its mobile subscribers, and clears that
Profile's operation and idempotency registry before another Profile can open.

Every underlying task is bound to the Profile lifecycle generation in which it started. A
task that does not reach a cancellation checkpoint before teardown may finish only as an
abandoned task: its late progress, result, Snapshot write, operation mutation, and
notification are discarded. It can neither access a subsequently opened Profile nor
publish into that Profile's audience. Reopening the original Profile starts with no retained
operation resource and uses its last committed portfolio state; the Client does not infer a
new outcome from the missing operation.

During a controlled Engine shutdown or Profile teardown, every active operation is
transitioned to `failed` before connected subscribers are closed, with this operation
error:

```json
{
  "code": "operation_interrupted",
  "retryable": true,
  "action": "retry"
}
```

That terminal notification is best-effort and the operation registry is not durable across
an Engine process restart. After an abrupt restart, previously active operation resources
are absent from the active collection; the Client must not infer a terminal outcome. On
either restart path it performs fresh capability discovery and Device Key proof, lists the
new active set, and Fetches the current Snapshot. A retry is always a new explicit user
action with a new Idempotency Key.

The registry retains a terminal operation in process memory for 15 minutes after its
terminal transition, measured with the Engine's monotonic clock. It retains at most 256
terminal operations for one Profile and evicts the oldest terminal entry first when that
limit is exceeded; active operations never count toward the terminal limit and are never
evicted to satisfy it. Both the operation representation and its Idempotency Key replay
record are removed together. All registry entries disappear on Engine restart.

#### Refresh target overlap

The Engine coordinates Refreshes across all authorized Clients and API surfaces for one
Profile before spawning underlying work. `queued` and `running` both count as active. The
version 1 overlap matrix is:

| Existing active work | Requested target | Result |
|---|---|---|
| None | Global or one Source | Create a new operation |
| Source A | Source A | Coalesce into Source A's operation |
| Source A | Different Source B | Create an independent operation within Engine limits |
| Global | Global | Coalesce into the global operation |
| Global | Any Source | Coalesce into the covering global operation |
| One or more Sources | Global | Reject with `409 refresh_conflict` |

A source request coalesced into a global operation receives that operation's global
`target` and `coalesced: true`; the response does not create a source-shaped alias. The
request's Idempotency Key caches that exact response. A global request is never queued
behind active source work and never silently absorbs source work that already started,
because either behavior would make the accepted target and progress ambiguous in version
1. The caller handles the conflict by reading the active collection, observing it to
completion, and offering a later explicit global Refresh with a new Idempotency Key.

```json
{
  "result": null,
  "message": "Source refresh operations are already active",
  "error": {
    "code": "refresh_conflict",
    "retryable": false,
    "action": "observe_active"
  }
}
```

The conflict body does not enumerate operations; the already authorized active collection
is the single discovery surface. No automatic request retry is permitted for this `409`.

#### Source identity

Portfolio balance attribution uses a required discriminated origin rather than inferring an
origin from asset, location, label, or list position. Every Balance Contribution refers to
exactly one of these shapes:

```json
{
  "kind": "source",
  "source_id": "opaque-source-id"
}
```

```json
{
  "kind": "manual_entry",
  "manual_balance_id": "manual-balance-id"
}
```

Every contribution also carries the required closed `category` enum `asset` or `liability`.
Its decimal `amount` and every non-null numeric `value` are canonical exact non-negative
magnitudes; a negative magnitude is a contract failure rather than an alternative liability
encoding. The same asset may appear in both categories and must remain distinguishable
instead of being netted into one signed row. Zero follows the same explicit category and is
never used to encode the opposite category.

Version 1 contains exactly one Balance Contribution for each distinct
`(origin, category, asset_id)` tuple and assigns no separate contribution identifier. Before
serialization, the Engine exactly sums every internal leaf that maps to the same tuple. In
particular, blockchain `BalanceSheet` protocol labels, the default address label, adapter
buckets, and provider-specific grouping keys do not cross the Companion boundary. A Manual
Entry naturally owns one tuple because its durable Manual Balance ID already identifies one
configured asset or liability entry.

Duplicate tuples are a contract failure rather than an instruction for Clients to merge
rows. Contributions from different origins or from different categories remain separate
even when they share an asset. The collapsed `amount` is the exact sum of all constituent
amounts. Its `value` is their exact sum only when every constituent valuation is available;
if any is unavailable, the collapsed value is null rather than an understated partial sum.
This gives Android, iOS, and aggregate invariants one deterministic cardinality without
presenting an uneven blockchain-only protocol breakdown as a cross-Source concept.

Protocol, lending-position, staking, and DeFi breakdowns are intentionally absent from the
version-1 contribution. If later mobile needs require them, they must use a separate typed
positions Capability and schema rather than adding semantic meaning to internal labels or
changing this tuple's identity. Churn that changes only excluded internal labels without
changing a collapsed contribution or any other contract field does not change canonical
Snapshot content or its Revision.

The Snapshot embeds one deduplicated `asset_catalog` object keyed by canonical Engine asset
identifier. Its keys cover exactly every distinct `asset_id` referenced by Balance
Contributions or bounded History plus the `valuation_currency`; the Snapshot builder takes
this closure after assembling all sections. Each value has this minimal shape:

```json
{
  "name": "Bitcoin",
  "symbol": "BTC",
  "asset_type": "crypto"
}
```

`name` and `symbol` are nullable strings because the Engine data model does not guarantee
either for every custom or legacy asset. `asset_type` is a required stable lower-snake-case
Engine Protocol value; when the Engine cannot resolve a type, it emits `unknown`. A Client
maps an unknown future type to its local Unknown Asset Type presentation without dropping
the catalog entry or any referencing domain object. The map key, never name or symbol, is
the asset identity and comparison key.

If the existing asset-mapping query omits an identifier, the Snapshot builder synthesizes
its catalog entry with null name, null symbol, and `asset_type: "unknown"`. Consequently a
valid Snapshot never contains a dangling asset reference. Native presentation may prefer a
non-blank symbol or name, but it always falls back to the exact identifier; absent metadata
never hides a contribution, History Event, or Valuation Currency. A duplicate logical key,
non-canonical identifier, missing catalog entry, blank non-null field, or mismatched map key
is a contract failure.

The catalog deliberately excludes binary icons and URLs, oracle identifiers, collection
graphs, spam heuristics, prices, protocol labels, and global-database implementation fields.
It is part of the authenticated encrypted Snapshot and has no separate mobile persistence
or on-demand endpoint in version 1. A metadata change changes canonical Snapshot content
and its Revision so offline presentation remains an exact view of that revision. Map order
has no semantic meaning and cannot by itself change the Revision.

Overview exposes exact `known_assets_value`, `known_liabilities_value`, and
`known_net_value`. The Engine and shared core enforce this invariant over all represented
contributions whose value is available:

```text
known_assets_value      = sum(non-null value where category == asset)
known_liabilities_value = sum(non-null value where category == liability)
known_net_value         = known_assets_value - known_liabilities_value
```

`known_assets_value` and `known_liabilities_value` are non-negative; `known_net_value` may
be negative, zero, or positive. These names remain unchanged even under complete valuation
so no field silently changes meaning. Percentage and sign formatting are presentation
derivations and are absent from the wire contract. Whenever a numeric value is present, the
category and magnitude rules above apply.

The Snapshot has exactly one required `valuation_currency`, encoded as the canonical Engine
asset identifier of the Profile's `main_currency` at capture time. Every non-null Balance
Contribution or History Event `value`, known aggregate, and any later monetary aggregate in
that Snapshot is expressed in that one currency. Per-row currency fields, parallel USD
values, and implicit currency defaults are forbidden. An amount remains denominated in its
own `asset_id`; `valuation_currency` describes only monetary valuations, never fungible
amounts.

The Engine captures the Profile main-currency setting inside the same coherent Snapshot
boundary as its contributions and aggregates. Changing that setting in the Full Client
changes canonical Snapshot content and therefore produces a new Snapshot Revision even
when amounts and source data are unchanged. An older encrypted Snapshot retains its stated
currency and is never re-labelled or locally converted after a setting change. Historical
resources that internally persist USD must convert into the Snapshot's valuation currency
at the Engine boundary or expose no valuation; their legacy USD convention never leaks
into this contract.

A contribution uses `value: null` only when the Engine cannot produce a valuation in
`valuation_currency`. A genuine exact zero valuation is the canonical decimal string
`"0"`; a Client must never coerce null to zero, hide its amount, or infer that it has no
effect on the unknown full portfolio value. A later available or unavailable valuation is
canonical Snapshot content and changes the Snapshot Revision.

Valuation Coverage is derived exhaustively in shared code from all contributions: it is
Complete only when every contribution has a non-null value and Partial otherwise. Partial
reasons identify the affected contribution by its origin, category, and asset only inside
the unlocked domain model; they are never emitted to diagnostics. It is not serialized as
a duplicate top-level flag because the complete contribution collection is authoritative.
Both native Clients receive the same derived value and present known totals explicitly as
known or partial rather than as full net worth.

Valuation Coverage is orthogonal to Snapshot Coverage and Source Health. A successfully
refreshed current Source can contain an asset without an available price, while a stale
Source can retain a complete valuation captured earlier. Neither state substitutes for the
other, and an unavailable price alone does not turn a current Source into `last_known`.

Each contribution inherits exactly one canonical Allocation Location from its origin. A
blockchain-account Source uses `blockchain`; an exchange Source uses the canonical Engine
location of its exchange provider; and a Manual Entry uses the location selected for that
entry in the Full Client. A future Source kind must expose its canonical location in the
common Source envelope before it can participate in this Capability. Location values are
stable lower-snake-case Engine Protocol enums and are financial allocation facts, not
localized display labels.

`location` is therefore required in every Source common envelope and Manual Entry, while a
Balance Contribution refers to the origin rather than repeating the field. All
contributions from one origin necessarily share its location. An unknown Source kind still
retains its common location; a missing or malformed location is a contract failure. Editing
a Manual Entry's location changes Snapshot content but not its Manual Balance ID.

For every distinct location represented by at least one contribution, shared code derives:

```text
known_assets_value(location) = sum(non-null asset values whose origin has location)
known_liabilities_value(location) = sum(non-null liability values whose origin has location)
known_net_value(location) = known_assets_value(location) - known_liabilities_value(location)
```

Overall known values equal the exact sums of their corresponding location values. A
liability reduces only its own location; the Companion deliberately does not copy the
legacy all-balances behavior that subtracts every liability from `blockchain`. A location's
known net may be negative. Its Valuation Coverage is Partial when any contribution assigned
to it has a null value, independently of Source freshness. Percentages are derived for
presentation and remain absent from the wire contract.

A Manual Entry is the read-only Companion projection of one manually tracked asset or
liability configured in the Full Client. It is not a Portfolio Source: it has no Source ID,
enabled flag, Source Health, execution-configuration generation, Refresh target, or
coverage reason. It remains available in the Portfolio breakdown and aggregate totals and
is stored inside the authenticated encrypted Snapshot. The Companion exposes no create,
edit, or delete operation for it.

The Engine builds contribution-level Snapshot data before the existing aggregate balance
paths discard manual provenance. Aggregate amounts and known values are exact sums of their
contributions; no Manual Entry may be silently folded into an unattributed remainder or
invented as a non-refreshable Source.

Every Manual Entry has a Profile-scoped Manual Balance ID consisting of exactly 16
cryptographically random bytes encoded as 22 characters of canonical unpadded Base64URL.
The Engine assigns it once, checks for collision, and persists it with the encrypted Profile
data. It remains unchanged when the Full Client edits the entry's label, asset, amount,
location, balance category, tags, or any later editable presentation field. Profile backup
and restore preserve active and retired Manual Balance IDs.

Deleting a Manual Entry permanently retires its Manual Balance ID; the Profile retains only
the opaque tombstone needed to prevent reuse. Re-adding the same natural entry creates a new
random ID even if every editable field matches the deleted record. The existing integer
`manually_tracked_balances.id` remains an Engine-internal legacy CRUD key and never appears
as `manual_balance_id`, because SQLite may reuse it after deletion. Manual Balance IDs are
not derived from that integer, labels, assets, locations, amounts, or deterministic hashes.

A transactional Profile migration assigns random IDs to all existing Manual Entries before
the Companion Snapshot Capability becomes available. Migration collision or persistence
failure rolls back the entire change. Manual Balance IDs grant no authority but remain
privacy-sensitive in logs and diagnostics. A Client compares them only for exact equality.

Every Portfolio Source has a Profile-scoped Source ID consisting of exactly 16
cryptographically random bytes encoded as 22 characters of canonical unpadded Base64URL.
The Engine assigns it once, persists it with the encrypted Profile data, checks for a
collision, and exposes it only through authorized Profile resources. It is an opaque lookup
identifier rather than a credential; Clients compare it only for exact equality.

Identity is assigned at the smallest independently refreshable configuration boundary. One
blockchain account is the pair `(blockchain, address)` and receives its own Source ID; the
same address configured on two blockchains is two Sources. One named exchange connection is
one Source; two credential sets for the same exchange provider are two Sources. A future
external origin becomes a Source only when the Engine can refresh and report its health as
an independent unit. Provider, blockchain, address, and display label are metadata and
never substitute for Source identity. Assets and individual balances within a Source are
not Sources.

Snapshot Source metadata is a discriminated union. A blockchain account is:

```json
{
  "source_id": "opaque-source-id",
  "kind": "blockchain_account",
  "location": "blockchain",
  "enabled": true,
  "blockchain": "ethereum",
  "address": "0x0000000000000000000000000000000000000000",
  "label": null,
  "health": {}
}
```

An exchange connection is:

```json
{
  "source_id": "opaque-source-id",
  "kind": "exchange",
  "location": "kraken",
  "enabled": true,
  "exchange": "kraken",
  "label": "Primary Kraken",
  "health": {}
}
```

`blockchain` and `exchange` are stable lower-snake-case Engine Protocol enums. `address` is
the complete chain-canonical address string and `label` is a nullable user-authored account
label for blockchain Sources; an exchange connection's label is its required non-empty
user-authored connection name. Variant-specific fields are required and fields from the
other variant are absent rather than null. Source ID remains the only equality key if any
editable metadata changes.

All wire Source objects keep `source_id`, `kind`, `location`, `enabled`, and `health` as
their required common envelope. A Client that receives a syntactically valid but unknown
lower-snake-case `kind` maps that object to its local `UnsupportedSource` variant, retains
only the common fields, and ignores unknown variant-specific fields. The Source remains in
the authenticated encrypted Snapshot, its balances remain in aggregate totals, and any
already active global or externally started operation can still be observed.

Native UI renders a generic localized Unsupported Source label plus safe common health. It
does not offer targeted Refresh because the Client cannot validate the kind's semantics;
global Refresh remains available and the Full Client remains the configuration path. An
unknown kind alone neither rejects the complete Snapshot nor silently drops the Source. A
malformed kind or missing common field is still a contract failure, and a schema that cannot
preserve the common envelope requires a new Capability version rather than pretending to be
additive.

These values appear only in an authorized Portfolio Snapshot, including its biometric-gated
encrypted offline copy. The native UI abbreviates an address for ordinary display but offers
explicit copy of the complete value after unlock. API credentials, credential fingerprints,
provider account identifiers, secrets, and provider response metadata are absent. Full
addresses and labels remain redacted from HTTP/WebSocket diagnostics and are never repeated
in Refresh Operation events.

The Source ID remains unchanged when a Source is renamed, its credentials are rotated, or
other editable configuration changes. Deleting a Source destroys its credentials and active
configuration but permanently retires its ID. The Profile retains only the opaque retired
ID needed to prevent reuse; re-adding the same address, exchange account, or natural source
creates a new random ID. Profile backup and restore preserve active and retired IDs, while
deleting the Profile removes both.

An upgrade assigns random IDs transactionally to every pre-existing blockchain-and-address
pair and named exchange connection before the Companion resources become available. IDs are
never derived from an address, exchange name, API key, credential fingerprint, database row
number, or deterministic hash. Source IDs are treated as privacy-sensitive in logs and
diagnostics even though possession grants no authority. Missing, retired, and cross-Profile
IDs receive the same authorized `404 resource_not_found` response.

The sole retired-ID exception is History provenance. A History Event may retain a retired
ID and declare it in the bounded or page-local `removed_source_ids` closure without former
Source metadata. Source lookup, contribution attribution, coverage, and Refresh still treat
that ID as retired and return the same `404`. An authenticated `/history` filter may accept
an exactly encoded retired ID only when the open Profile's tombstone ledger proves it was
issued there; a never-issued or cross-Profile value remains indistinguishable. This narrow
exception preserves historical provenance and grants no Source authority or metadata.

#### Source Health and disabled Sources

Source configuration owns the `enabled` boolean. The persisted Source Health object contains
orthogonal durable facts rather than one mutually exclusive UI status:

```json
{
  "data_state": "last_known",
  "data_captured_at": 1786550000,
  "last_attempt_at": 1786550400,
  "last_attempt_outcome": "failed",
  "last_error": {
    "code": "source_unreachable",
    "retryable": true,
    "action": "retry"
  }
}
```

`data_state` is exactly `never_refreshed`, `current`, or `last_known`:

- `never_refreshed` has null `data_captured_at` and no portfolio value for that Source;
- `current` has a non-null `data_captured_at` produced by the most recent settled attempt,
  which succeeded under the current execution configuration;
- `last_known` has a non-null `data_captured_at`, but a later failure, disable, or relevant
  configuration change means the retained value is no longer claimed as current.

`last_attempt_outcome` is exactly `never`, `succeeded`, or `failed`. `never` requires null
`last_attempt_at` and null `last_error`; `succeeded` requires a non-null attempt time and null
error; `failed` requires both a non-null attempt time and typed error. `last_attempt_at` is
the Engine UTC Unix second at which the attempt settled, while `data_captured_at` is when the
retained Source data was captured. Neither timestamp controls validity or scheduling.

`last_error` and a failed targeted Refresh Operation use this closed Source error registry:

| `code` | `retryable` | `action` | Meaning |
|---|---:|---|---|
| `source_unreachable` | `true` | `retry` | Network or upstream availability prevented a response |
| `source_rate_limited` | `true` | `retry` | The upstream Source rejected work because of its rate limit |
| `source_authentication_failed` | `false` | `use_full_client` | Configured upstream credentials were rejected |
| `source_configuration_changed` | `false` | `use_full_client` | Execution-relevant configuration changed during the attempt |
| `source_unexpected_error` | `false` | `none` | A safe fallback for every other Source failure |

The registry is deliberately independent of provider-specific error taxonomies. No upstream
code, response body, exception text, URL, credential, address, amount, or provider message
enters the Companion Snapshot, HTTP response, WebSocket event, or Client diagnostic. The
Engine may retain a sanitized technical diagnostic in its protected local logs under the
project-wide redaction policy. Unknown future Source codes map to a typed unexpected Source
failure without dropping last-known data.

A successful attempt sets `last_attempt_outcome: "succeeded"` and clears `last_error`. A
failed targeted operation carries the same safe error object committed to Source Health. A
global operation never copies individual Source errors into its representation or event; it
uses only aggregate `source_refresh_failed`, and Clients Fetch Source Health for details.

Elapsed wall-clock time alone never changes `data_state`. In particular, `current` means
that the most recently settled attempt under the current execution configuration succeeded;
it does not promise that the capture is younger than a universal freshness threshold. The
Client presents the absolute capture time and derived age independently. No timer rewrites
Source Health, changes the Snapshot's degraded reasons, or creates a new content-derived
Snapshot Revision merely because data became older. Failure, disable, relevant configuration
change, and later successful Refresh are the events that change this state.

An active Refresh Operation is the only authority for live `queued` or `running` state and
is overlaid onto Source Health after REST reconciliation. It does not rewrite durable health
to `refreshing`, so an encrypted offline Snapshot cannot retain a phantom in-progress flag
after disconnection, process death, or Engine restart. A global operation overlays every
Source in its frozen selection; a per-Source operation overlays only its target.

Snapshot Coverage is derived from the complete Source collection rather than serialized as
redundant top-level `degraded` or `reasons` fields. The shared core applies this exhaustive
rule:

| Source facts | Coverage contribution | Presentation reason |
|---|---|---|
| `enabled: false` | Degraded | `disabled` |
| Enabled and `data_state: never_refreshed` | Degraded | `never_refreshed` |
| Enabled and `data_state: last_known`, last attempt failed | Degraded | The typed `last_error` |
| Enabled and `data_state: last_known`, no later failed attempt | Degraded | `refresh_required` |
| Enabled and `data_state: current` | Complete | None |

The whole Snapshot is complete only when every Source contributes Complete; an empty Source
collection is complete for its empty configured set. Unsupported Source kinds use their
common enabled and health fields in the same rule. Reasons retain Source IDs only inside the
unlocked domain model and are never logged. Android and iOS receive the same derived domain
value and do not independently reinterpret health.

The Engine sends only normalized Source facts, and cross-language contract vectors recompute
the expected coverage. Because coverage is derived, no duplicate flag can contradict Source
Health and no Snapshot Revision changes merely from recomputation or presentation.

A configured Source remains present in the Portfolio Snapshot when the Full Client disables
its queries. Its Source representation contains `enabled: false`; Source Health is
`last_known` when data exists or `never_refreshed` otherwise. Any last-known portfolio data
remains attributed to that Source with its unchanged captured time; disabling never deletes
it, advances its freshness, or turns it into zero. That exact last-known value remains
included in aggregate totals, while a Source with no last-known data contributes no invented
balance.

The presence of any disabled Source makes the Snapshot degraded, including when that Source
has never produced data. The Snapshot degradation reason remains `disabled`, distinct from
`refresh_failed`, while Source Health carries the underlying data state and capture time so
native UI can explain the total's coverage. A successful global operation can therefore
result in a Degraded Snapshot: operation success means all selected enabled Sources
refreshed, not that disabled Sources became current. Re-enabling a Source does not change
`last_known` to `current`; degradation persists until a successful Refresh establishes
current data for it.

Only enabled Sources are selected when a global Refresh is accepted and therefore only they
contribute to the frozen `progress.total`. A targeted Refresh checks current configuration
before operation creation. For a disabled Source it creates no operation or Idempotency
replay record and returns:

```json
{
  "result": null,
  "message": "The portfolio source is disabled",
  "error": {
    "code": "source_disabled",
    "retryable": false,
    "action": "enable_source_full_client"
  }
}
```

with `409 Conflict`. The Portfolio Companion exposes no override or enable action; the user
must change Source configuration in the Full Client and then initiate a new Refresh with a
new Idempotency Key. Missing, retired, and cross-Profile IDs still use the indistinguishable
`404 resource_not_found` instead of revealing whether a hidden Source is disabled.

If a global request finds no enabled Sources at its atomic acceptance point, it likewise
creates neither a zero-work operation nor an Idempotency replay record and returns:

```json
{
  "result": null,
  "message": "No portfolio sources are enabled for refresh",
  "error": {
    "code": "no_refreshable_sources",
    "retryable": false,
    "action": "use_full_client"
  }
}
```

with `409 Conflict`. This collapses a Profile with no configured Sources and one whose
Sources are all disabled into the same operation-creation outcome. After configuration in
the Full Client, the user initiates a new explicit request with a new Idempotency Key.

#### Concurrent Source configuration changes

Every Source has an execution-configuration generation independent of its stable Source ID.
Changing credentials, disabling, or deleting the Source increments that generation in the
same transaction that commits the configuration change. A display-label rename does not,
because it changes no Refresh input and preserves the running operation.

Each Refresh captures the relevant generation before it starts external work and checks it
again before every progress publication, portfolio mutation, coherent Snapshot commit, and
terminal success. A mismatch atomically settles that Source's work as failed and discards
all later results from the stale task. For a targeted operation the terminal error is:

```json
{
  "code": "source_configuration_changed",
  "retryable": false,
  "action": "use_full_client"
}
```

For a global operation, the invalidated Source increments `progress.completed` as a failed
Source; other selected Sources continue, and the global operation eventually uses the
aggregate `source_refresh_failed` outcome. If the Source still exists, its prior portfolio
data remains last-known and its current enabled/health configuration wins. If it was
deleted, its ID is retired and it is absent from the next Snapshot. Neither case permits the
stale task to restore old configuration, data, Source Health, or notifications.

The Full Client configuration change is not blocked on external work. The operation
coordinator observes the generation change before that control request returns, but task
cancellation remains cooperative; the generation guard is the authoritative protection
against a late completion.

#### Partial Refresh outcome

A global Refresh is `succeeded` only when every Source selected at its start completes
successfully. If one or more selected Sources fail, the global operation becomes `failed`
even when other Sources succeeded. Successful Source results advance, failed Sources retain
their last-known portfolio data, and each Source Health records its own outcome. The Engine
atomically publishes that Degraded Snapshot before the terminal operation transition, then
sets `result_snapshot_revision` to the revision that contains those results and uses:

```json
{
  "code": "source_refresh_failed",
  "retryable": true,
  "action": "retry"
}
```

The operation error is only a safe aggregate signal and never embeds Source identifiers,
upstream messages, credentials, addresses, balances, or exception text. The Client Fetches
the referenced/current Snapshot for per-Source health and presentation. It does not roll
back successful Sources, infer that a failed Source has zero balance, or automatically
repeat the global operation. Retry is a new explicit user action with a new Idempotency Key.

An operation-level failure before any new coherent Snapshot can be committed has
`result_snapshot_revision: null`. Thus `state: failed` means that the requested target was
not fully refreshed; it does not imply that the operation had no committed effects.

#### Refresh progress

When a global operation is accepted, the Engine freezes the set of selected Sources and
sets `progress.total` to the number of enabled Sources in that set. `progress.completed`
starts at zero and advances exactly once whenever one selected Source reaches either a
successful or failed terminal outcome. It measures settled work, not successful work:
every normally terminal global operation, including `failed / source_refresh_failed`, has
`completed == total`. Each visible increment advances the operation `version`.

Success/failure counts and per-Source outcomes are intentionally absent from live operation
progress. They become authoritative together in Source Health when the coherent Snapshot is
committed. A Source request coalesced into a global operation observes these same global
units. Protocol version 1 does not expose provider-specific phases or estimate a percentage
for a per-Source operation; it remains indeterminate with `progress: null` until terminal.

`version` starts at one and increases for every observable state or progress change within
one operation. The Client applies only a version greater than the latest one seen for that
operation; duplicate and older versions are ignored. Version is unrelated to Snapshot
Revision and resets for each operation.

Unknown event types and additive fields are ignored for forward compatibility. A known
event missing a required field or violating an invariant is discarded, recorded only as a
redacted diagnostic, and reconciled through REST; malformed WebSocket input never replaces
valid local state.

#### Close and reconnect semantics

The Companion adds no private WebSocket close-code namespace and never derives a domain
state from a textual close reason. It uses these standard codes:

| Code | Initiator and meaning | Foreground Client behavior |
|---:|---|---|
| `1000` | Client enters background or replaces a socket after Access Session renewal | Do not reconnect the old socket |
| `1008` | Engine determines that the accepted mobile connection is no longer authorized | Stop using that session and perform a fresh challenge/proof |
| `1012` | Engine performs a controlled service restart | Back off until reachable, then perform fresh capability discovery and proof |
| `1006` | Client-local observation of an abnormal transport loss; never sent in a close frame | Reconnect with the current bearer while it remains valid, otherwise perform fresh proof |

The Engine sends `1008` without a state-specific reason when the Access Session expires, the
Device Session is revoked, the Engine locks, or a different Profile opens. After it, only
the HTTP proof response establishes `locked_engine`, `profile_mismatch`, or
`not_authorized`. A failed pre-accept Upgrade is an HTTP authentication response and never
creates a WebSocket close event.

All automatic reconnect behavior is foreground-only, bounded, and cancelled immediately
when the Client leaves the active foreground. Every successful reconnect performs the REST
reconciliation defined above.

### P0.1 threat-test matrix

The exact case IDs and structured expectations are checked in at
`mobile/protocol/v1/p0_1_cases.json`. Phase P0.1 validates matrix completeness; the listed
implementation owner later turns each row into a runtime test without changing its outcome.

| Threat family | Required cases and exact outcome | Security invariant | Runtime owner |
|---|---|---|---|
| Network interception | Non-HTTPS origin and untrusted TLS are rejected locally | No cleartext or trust-all fallback; no Engine mutation | KMP transport and native trust adapters |
| Pairing replay | Expired/replayed secret is `410 pairing_unavailable`; exact creation replay is byte-identical `201`; changed retained key is `409 idempotency_conflict` | At most one Pairing/Device Session mutation and no Profile disclosure | Engine E1.3 |
| Proof transplant and replay | Altered origin or invalid signature is terminal `401 not_authorized` and consumes a found Challenge; expired/replaced/consumed/wrong-bound Challenge is indistinguishable `410 challenge_unavailable`; concurrent proof has exactly one winner | A proof cannot move across origin, device, challenge, or lifetime | Engine E1.3 plus cross-language crypto vectors |
| Device existence oracle | Unknown and revoked Device Session lookup/proof paths have the same status, body, headers, and practical timing class | No Device/Profile existence disclosure | Engine E1.3 |
| Stolen Access bearer | Expiry/restart is `401 access_session_unavailable`; observed revocation is terminal `401 not_authorized`; background/system lock purges bearer and plaintext locally | Bearer is short-lived, memory-only, and independent of durable Device Key authority | Engine E1.3 and native lifecycle tests |
| Resource exhaustion | Device/source/process token buckets reject before signature work with `429 rate_limited` and integer `Retry-After`; unknown IDs allocate no per-ID state | Attacker-controlled identifiers cannot create unbounded state or expensive work | Engine E1.3 |
| Realm and Scope confusion | Cross-realm matrix uses only the route-selected verifier; unlisted Companion route is `404 resource_not_found`; Access bearer outside the subtree never authenticates; missing permission after valid Access auth is `403 scope_denied` | Credentials never widen the method/route allowlist | Engine E1.3/E1.4 |
| Object confusion | `/current` derives the target from Access Session; missing, cross-Profile, and out-of-scope objects are indistinguishable `404 resource_not_found` | Caller-supplied identity cannot redirect self-management | Engine E1.4 |
| WebSocket authorization | Cookie plus Bearer and forbidden bearer transport are rejected before accept; every event passes family and owner allowlists; `1008` carries no state reason and HTTP proof classifies it | No unrestricted browser event stream or another Profile's event reaches mobile | Engine E1.3 |
| Diagnostic exposure | Seeded origin, QR secret, bearer, Profile marker, address, balance, and History text are absent from response diagnostics, logs, crash export, filenames, and notifications | Sensitive values have no secondary plaintext channel | Engine, shared core, and both native adapters |
| Stolen locked phone | Only the authenticated encrypted Snapshot remains at rest; device authentication gates plaintext and background clears it | Protection is claimed for a locked device, not a currently unlocked compromised OS | Android/iOS security slices |

A compromised Engine Host, malicious operating system, or already-unlocked rooted or
jailbroken phone remains outside the protection claim. This boundary is itself a required
test/documentation case and must not be weakened into an unsupported security promise.

### Resource and authorization matrix

All paths in this table are relative to `/api/1/companion`. A method not explicitly listed
is rejected. `Full Client` means the existing active HttpOnly browser session for the open
Profile; `Pairing` means the unconsumed two-minute Pairing credential; `Device proof` means
the challenge-bound signature exchange and does not imply an Access Session.

| Method | Path | Required realm | Purpose |
|---|---|---|---|
| `GET` | `/protocol` | Public | Discover protocol version and Capabilities |
| `POST` | `/pairings` | Full Client | Create a two-minute single-use Pairing credential and QR payload |
| `DELETE` | `/pairings/{pairing_id}` | Full Client | Cancel an outstanding Pairing credential |
| `POST` | `/device-sessions` | Pairing | Consume Pairing and register one Device Key |
| `GET` | `/device-sessions` | Full Client | List Device Sessions bound to the open Profile |
| `PATCH` | `/device-sessions/{device_session_id}` | Full Client | Rename one listed Device Session |
| `DELETE` | `/device-sessions/{device_session_id}` | Full Client | Revoke one listed Device Session |
| `PATCH` | `/device-sessions/current` | Access Session | Rename only the caller's Device Session |
| `DELETE` | `/device-sessions/current` | Access Session | Revoke only the caller's Device Session during Unpair |
| `POST` | `/challenges` | Device proof | Request a challenge using a Device Session ID in the redacted body |
| `POST` | `/access-sessions` | Device proof | Submit a challenge-bound signature and obtain an Access Session |
| `GET` | `/snapshot` | Access Session | Fetch one coherent Portfolio Snapshot revision |
| `GET` | `/history` | Access Session | Page older History beyond the bounded offline window |
| `POST` | `/refresh-operations` | Access Session | Start or coalesce an approved global or per-source Refresh |
| `GET` | `/refresh-operations` | Access Session | List currently active authorized Refresh Operations |
| `GET` | `/refresh-operations/{operation_id}` | Access Session | Observe one authorized Refresh Operation |

The Full Client cancels Pairing with `DELETE /pairings/{pairing_id}`. A syntactically valid
ID receives `200 OK` with `{"result":{"cancelled":true},"message":""}` whether the
current Profile's Pairing was active or had already become unavailable; a malformed ID is
`400 invalid_request`. This makes cancellation idempotent without exposing expiry or prior
consumption.

The canonical Device Session representation is:

```json
{
  "device_session_id": "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
  "device_label": "Victor's iPhone",
  "platform": "ios",
  "state": "authorized",
  "paired_at": 1786550300,
  "last_seen_at": 1786550400,
  "revoked_at": null
}
```

`state` is `authorized | revoked`. Authorized records require `revoked_at: null`; revoked
records require an immutable integer `revoked_at`. `last_seen_at` is nullable until the first
successful proof and otherwise records the most recent successful proof, not arbitrary
traffic. All three timestamps are non-negative signed 64-bit Unix epoch seconds. Neither
mutable timestamp moves backward; if the Engine wall clock regresses, it clamps the new
timestamp to the record's durable timeline so that revocation can never be blocked by clock
skew. The first revocation atomically removes the public key and algorithm and records
`revoked_at`; version 1 retains the remaining ID, Profile binding, label, platform, and
timestamps indefinitely for the owner's audit. That record can never be reauthorized,
renamed, or updated by later proof, and its Device Session ID is never reused. The wire
`state` is derived from the revocation facts rather than stored as independent state.

`GET /device-sessions` returns
`{"result":{"device_sessions":[...]},"message":""}` for the open Profile, including
revoked records retained for the owner's device audit, ordered by `paired_at` descending and
then `device_session_id` ascending by its decoded unsigned 32-byte lexicographic value. The
Base64URL text is a wire representation and is never the ordering key.

Both rename routes accept exactly `{"device_label":"..."}` and return `200 OK` with
`{"result":{"device_session":<Device Session>},"message":""}`. Both revoke routes
return `200 OK` with `{"result":{"revoked":true},"message":""}`. Repeating a revoke
of the same authorized Profile record is successful and changes no timestamp; missing,
cross-Profile, or never-visible resources use `404 resource_not_found`. A revoked record
cannot be renamed. `/current` accepts no supplied Device Session ID in its path, query, or
body and derives the target only from the Access Session. Its successful response is sent
before that same action invalidates the caller's bearer and closes its WebSocket.

`/challenges` and `/access-sessions` keep the stable Device Session ID in their redacted JSON
bodies rather than URL paths or query strings. Server request logging must redact these
bodies before serialization. Full Client collection routes return only records for the open
Profile. The `{device_session_id}` control routes reject records from any other Profile,
while `/current` derives identity exclusively from the Access Session and accepts no target
ID supplied by the Client.

Revoking a Device Session invalidates all of its Access Sessions and closes only its
WebSocket connections. Local Unpair succeeds and deletes local material even when the
remote `/current` request cannot run; the Full Client can later revoke the stale record.

The Refresh Operation collection returns only `queued` and `running` operations authorized
for the caller's bound Profile. A direct operation read also returns a retained `succeeded`
or `failed` representation. After terminal TTL or capacity eviction, and after Engine
restart, the direct resource returns the ordinary indistinguishable
`404 resource_not_found`; the Client Fetches the Snapshot and uses Source Health rather
than treating disappearance as an operation outcome or retaining an unbounded operation
history.

### Refresh Operation creation response

Every successful `POST /refresh-operations` returns `202 Accepted`, whether it created new
Engine work or joined an equivalent active operation. The response includes an
absolute-path `Location` reference to the authoritative operation resource and a complete
operation representation at acceptance time:

```http
HTTP/1.1 202 Accepted
Location: /api/1/companion/refresh-operations/opaque-operation-id
```

```json
{
  "result": {
    "operation": {
      "operation_id": "opaque-operation-id",
      "version": 1,
      "created_at": 1786550400,
      "started_at": null,
      "finished_at": null,
      "target": {
        "kind": "source",
        "source_id": "opaque-source-id"
      },
      "state": "queued",
      "progress": null,
      "result_snapshot_revision": null,
      "error": null
    },
    "coalesced": false
  },
  "message": ""
}
```

`coalesced` is `false` when first processing of that Idempotency Key creates the operation
and `true` when it attaches that request to an operation that already covers the requested
target. It describes the original acceptance decision, not the operation's later state.
An exact retry with the same Idempotency Key returns the original `202`, `Location`, body,
and `coalesced` value byte-for-byte even if the operation has since advanced. The Client
then follows `Location` or reconciles through the collection to read current state. A
different Idempotency Key that joins the same active operation gets its own cached response
with `coalesced: true` and the same operation identity.

This is an asynchronous status-monitor response in the sense of
[RFC 9110 section 15.3.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.3):
acceptance does not assert eventual success, and completion is represented only by the
operation resource, a notification hint, and the resulting Snapshot.

### Credential transport

Both the single-use Pairing credential and an Access Session credential use the standard
HTTP authorization header on the route that requires them:

```http
Authorization: Bearer <opaque-credential>
```

Each credential contains 256 random bits and is encoded as canonical unpadded Base64URL.
The Pairing credential appears in the QR payload and in the registration request's header,
but never in its JSON body. An Access Session credential exists only in Client process
memory and authorization headers. Neither credential may appear in a URL path, query,
cookie, request or response log, diagnostic, crash report, or persisted Client state.

The route's authorization matrix selects exactly one verifier and credential store before
the header is evaluated. Pairing and Access Session credentials have independent hash
namespaces and cannot authenticate each other's routes even if their opaque encodings look
identical. The browser's session cookie cannot satisfy a Pairing, Device proof, or Access
Session route, and a Bearer credential cannot satisfy a Full Client control route or any
route outside the Companion subtree. An irrelevant credential must never broaden access or
change which realm the route requires.

The parser accepts one `Authorization` header with a case-insensitive `Bearer` scheme and
one canonical token. It rejects duplicate or combined authorization headers, missing or
empty tokens, padding, non-Base64URL characters, and decoded values of any other length.
Invalid credentials use the route's typed authentication error; raw tokens are never
included in that response. Negative matrix tests submit every credential type to every
other realm.

## Idempotency and request replay

The following state-creating requests require one `Idempotency-Key` header:

- `POST /pairings`;
- `POST /device-sessions`;
- `POST /refresh-operations`.

The value is 16 cryptographically random bytes encoded as 22 characters of canonical
unpadded Base64URL. It is an operation identifier, not a credential, and never bypasses the
route's normal Full Client, Pairing, or Access Session authorization. The Engine validates
the current required authority before any ordinary idempotency lookup. The sole narrow
exception is the replay-only registration verifier described below: it can recover one
already authorized result after atomic Pairing consumption, but can authorize no new work.

The Engine scopes a key to the authenticated realm, caller identity, HTTP method, and route,
and stores a canonical request fingerprint with the first result. Repeating the same key
and identical semantic payload returns the same status and response rather than performing
the operation again. Reusing it with a different payload returns:

```json
{
  "result": null,
  "message": "The idempotency key was already used for another request",
  "error": {
    "code": "idempotency_conflict",
    "retryable": false,
    "action": "new_request"
  }
}
```

with `409 Conflict`. JSON member order and insignificant whitespace do not change the
semantic fingerprint. Idempotency records and any cached credential-bearing response are
redacted from logs and bounded to the operation's replay lifetime.

For Refresh creation, authorization, canonical request validation, retained-key conflict or
replay lookup, and target preconditions are evaluated in that order. An exact retained-key
replay returns its cached response regardless of later configuration changes. An unused key
that encounters `source_disabled`, `no_refreshable_sources`, or another pre-operation
precondition failure is not reserved; no operation side effect has occurred. Clients still
create a new key for every later explicit action rather than relying on that implementation
detail.

For Pairing creation, the cached secret-bearing response survives only in disposable memory
through the Pairing credential's lifetime. For Device Session registration, atomic success
replaces the consumable Pairing record with a five-minute replay tombstone containing only
the credential hash, caller/route binding, Idempotency Key, canonical request fingerprint,
and successful non-secret registration response. The route-selected Pairing verifier may
use that tombstone only to return the byte-identical cached `201` for an exact replay or
`409 idempotency_conflict` for the same retained key with changed semantic input. It cannot
register another key, authenticate another route, restore Pairing plaintext, or reveal any
Profile fact; after expiry it collapses to `410 pairing_unavailable`.

For Refresh, the key remains attached to the retained Refresh Operation; equivalent requests
with different keys are still governed independently by Engine coalescing. A terminal
Refresh operation and its replay record expire together after 15 minutes or earlier capacity
eviction. Reuse after that boundary is outside the replay guarantee; a Client never reuses
an old key for a new explicit action.

An Engine restart discards every outstanding Pairing and replay record and never persists
Pairing plaintext or an Access bearer to recover them. A Client that cannot recover a
Pairing result creates a new Pairing; the Full Client can revoke any harmless stale Device
Session. This exception is tested and does not relax credential storage policy.

`POST /challenges` and `POST /access-sessions` do not use idempotency caching. If a challenge
response is lost, the Client requests a new challenge. If a proof response is lost, its
challenge remains consumed and the Client starts a new challenge/proof exchange; any
unobserved Access Session expires normally. PATCH uses replacement semantics, DELETE is
idempotent at the domain boundary, and safe-read retry policy is specified separately.

### Automatic retry policy

One shared request policy owns all automatic retries; platform HTTP engines must not add a
second hidden retry layer for Companion application requests.

- Safe GET requests permit at most two retries after the initial attempt: three total
  attempts.
- PATCH, DELETE, and a POST carrying its unchanged `Idempotency-Key` permit at most one
  retry after the initial attempt: two total attempts. The method, route, selected protocol
  version, semantic body, credential realm, and idempotency key remain unchanged.
- `POST /challenges` and `POST /access-sessions` are never retransmitted. Recovery creates a
  new challenge/proof exchange instead.

A policy-eligible method retries only a transport failure before a complete response or an
HTTP `408`, `429`, `502`, `503`, or `504`. It never retries another 4xx, a response whose
typed action requires user intervention, a serialization/contract failure, or
`unexpected_engine_error`. `error.retryable=true` is necessary when a typed Companion error
is present but does not override method eligibility, attempt limits, lifecycle, or safety.
Gateway-generated `408`, `502`, and `504` responses may lack a Companion JSON envelope and
are classified by status without parsing their body.

Retry delays use exponential full jitter with a 250 ms base and a 2-second cap. A valid
`Retry-After` from `429` is honored only when it is no more than five seconds; a longer wait
is surfaced as rate-limited state rather than keeping hidden work alive. All counters reset
for an explicit user action, foreground resume, or a distinct Idempotency Key.

Retries, timers, response parsing, and in-flight calls are cancelled immediately when the
Client leaves the active foreground or loses the credential required for the request. Once
attempts are exhausted, a read retains the last authenticated Snapshot and exposes a typed
reachable/unreachable or degraded state; it never substitutes zero or an empty success.
Fake clocks and deterministic randomness test exact attempt counts, jitter bounds,
`Retry-After`, cancellation, response-loss replay, and the absence of duplicate side effects.

## HTTP response envelope

Companion resources preserve the existing rotki top-level response shape. A successful
response contains the endpoint-specific typed value and no `error` member:

```json
{
  "result": {},
  "message": ""
}
```

A non-successful response uses the semantic non-2xx HTTP status, a null result, and a
required typed error object:

```json
{
  "result": null,
  "message": "The Engine is locked",
  "error": {
    "code": "locked_engine",
    "retryable": false,
    "action": "unlock_full_client"
  }
}
```

`error.code` and `error.action` are stable lower-snake-case protocol enums. `retryable`
states whether retrying the same operation without the prescribed action can reasonably
succeed; it does not itself authorize an automatic retry. `message` is a short, redacted,
non-localized diagnostic fallback. A Client must never parse or present it as its primary
UI copy: shared code maps the typed fields to a domain outcome and each native Client owns
localized presentation.

All three fields in `error` are required on every Companion failure, including validation,
authentication, authorization, rate-limit, and unexpected internal failures. An unknown
additive response field is ignored. An unknown `code` or `action` maps to a redacted
`unexpected_engine_error` domain outcome while preserving any authenticated Portfolio
Snapshot; a missing required field is a contract failure. Success responses never encode a
domain failure inside `result` merely to force HTTP 200.

The Engine uses a Companion-specific response helper rather than changing the envelope of
unrelated v1 routes. Tests assert that raw exception text, credentials, Profile metadata,
portfolio values, and request bodies cannot enter `message` or server logs.

### General status and recovery mapping

The Companion uses the following small semantic HTTP taxonomy. `Keep` means the Client does
not discard an already established local Device Session or authenticated offline Snapshot;
it does not imply that online access is currently available.

| HTTP | `error.code` | `retryable` | `action` | Local Device Session | Offline Snapshot |
|---:|---|---:|---|---|---|
| `400` | `invalid_request` | `false` | `none` | Keep | Keep |
| `401` | `access_session_unavailable` | `false` | `prove_device` | Keep | Keep |
| `401` | `full_client_auth_required` | `false` | `authenticate_full_client` | Unchanged | Unchanged |
| `401` | `not_authorized` | `false` | `pair_again` | Delete | Delete |
| `403` | `scope_denied` | `false` | `use_full_client` | Keep | Keep |
| `404` | `resource_not_found` | `false` | `none` | Keep | Keep |
| `409` | `idempotency_conflict` | `false` | `new_request` | Keep | Keep |
| `409` | `profile_mismatch` | `false` | `open_bound_profile` | Keep | Keep |
| `409` | `refresh_conflict` | `false` | `observe_active` | Keep | Keep |
| `409` | `no_refreshable_sources` | `false` | `use_full_client` | Keep | Keep |
| `409` | `source_disabled` | `false` | `enable_source_full_client` | Keep | Keep |
| `409` | `history_changed` | `false` | `fetch_snapshot` | Keep | Keep |
| `410` | `pairing_unavailable` | `false` | `pair_again` | Not established | Not established |
| `410` | `challenge_unavailable` | `false` | `request_challenge` | Keep | Keep |
| `410` | `history_cursor_unavailable` | `false` | `restart_history` | Keep | Keep |
| `423` | `locked_engine` | `false` | `unlock_full_client` | Keep | Keep |
| `426` | `incompatible_protocol` | `false` | `upgrade_engine` | Keep | Keep |
| `429` | `rate_limited` | `true` | `retry_after` | Keep | Keep |
| `503` | `snapshot_unavailable` | `true` | `retry` | Keep | Keep |
| `500` | `unexpected_engine_error` | `false` | `none` | Keep | Keep |

`not_authorized` is the only remote error with terminal Client semantics for the durable
Device Session, so it is the only error that deletes the local Device Key relationship and
Snapshot. Its collapsed causes deliberately do not prove whether the record was revoked,
unknown, or paired with a signature that failed verification; after any of them the Client
cannot safely assume the relationship remains usable and requires Pairing again. A missing
or expired Access Session is recoverable by Device Key proof and does not imply revocation.
Likewise, malformed, unknown, or unexpected responses never trigger destructive cleanup.

`resource_not_found` is identical for an absent resource and one outside the caller's
Profile or Companion Scope. `rate_limited` includes a valid `Retry-After` header. A `503`
never turns unavailable source data into zero and never replaces a last-good Snapshot.
`unexpected_engine_error` always has a generic redacted message rather than exception text.

An Engine that recognizes the Companion Protocol but cannot serve the negotiated protocol
version or required Capability uses `426 incompatible_protocol`. An older Engine with no
Companion route may instead return its legacy 404 envelope; bootstrap maps that condition to
the same incompatible domain state without parsing the legacy message.

### Device proof status mapping

The successful proof response is `201 Created` and carries the new Access Session in
`result`. A proof that establishes the Device Key but cannot authorize Profile access uses
one of these errors:

| HTTP status | `error.code` | `retryable` | `action` |
|---|---|---:|---|
| `423 Locked` | `locked_engine` | `false` | `unlock_full_client` |
| `409 Conflict` | `profile_mismatch` | `false` | `open_bound_profile` |

Those outcomes are evaluated only after successful signature verification. They contain no
Profile identifier, name, value, or information about a different open Profile.

Credential failures deliberately collapse distinguishable causes:

| HTTP status | `error.code` | `retryable` | `action` | Indistinguishable causes |
|---|---|---:|---|---|
| `401 Unauthorized` | `not_authorized` | `false` | `pair_again` | Unknown or revoked Device Session; invalid signature |
| `410 Gone` | `challenge_unavailable` | `false` | `request_challenge` | Unknown, expired, consumed, or wrongly bound Challenge ID |

The body, headers, and practical response timing within each row must not disclose which
collapsed cause occurred. Proof processing atomically consumes a found challenge before
returning an invalid-signature response, so retrying that proof cannot become an oracle.
Neither error returns a bearer or Profile data.

## Data-plane DTOs owned by later slices

P0.1 freezes every Protocol discovery, Pairing, Device Session, challenge, proof, error,
authorization, and WebSocket bootstrap shape. The remaining success DTOs are intentionally
owned by their implementation slices rather than left as control-plane ambiguity:

- D3.1 freezes the complete `GET /snapshot` response after the measured Snapshot document
  can be emitted by the Engine;
- D3.3 freezes Refresh Operation collection and direct-resource success envelopes around the
  already frozen operation representation.

Refresh Operation creation and older History pagination are already exact in this document.
