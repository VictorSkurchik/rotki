# Portfolio Companion

The Portfolio Companion is a native mobile Rotki client for private portfolio viewing and Refresh operations. It depends on a self-hosted Engine that remains authoritative for portfolio data and financial behavior, while the Full Client retains administrative workflows.

## Language

**Engine**:
The self-hosted Rotki runtime that owns portfolio data and performs synchronization, transaction decoding, accounting, and reporting.
_Avoid_: Backend, server

**Client**:
A user-facing Rotki application that reads and changes state through an Engine without becoming the authoritative owner of portfolio data.
_Avoid_: Frontend, UI

**Full Client**:
The existing Vue Client that can unlock a Profile and perform administration, configuration, accounting, export, and other workflows outside Companion Scope.
_Avoid_: Web Client, Vue UI, admin Client

**Engine Host**:
A user-controlled, always-on machine that runs an Engine for access by the user's Clients.
_Avoid_: Cloud backend, Rotki cloud

**Locked Engine**:
A reachable Engine on which the Profile paired with a Client is not currently open and therefore cannot serve that Profile's current portfolio state.
_Avoid_: Offline Engine, authentication failure

**Engine Protocol**:
The versioned request, response, event, and authentication contract through which every Client interacts with an Engine.
_Avoid_: Mobile API, frontend API

**Capability**:
A named Engine Protocol behavior advertised by an Engine and required by a specific Client function.
_Avoid_: Feature flag, application version

**Profile**:
An encrypted collection of one user's portfolio data, credentials, preferences, and accounting state opened by an Engine.
_Avoid_: Account, database

**Profile ID**:
A stable opaque identity for one Profile Lineage. It reveals neither the Profile name nor
its contents and is an internal identity rather than an authentication secret.
_Avoid_: Username, Profile name

**Profile Lineage**:
A Profile and every restore or clone that preserves its Profile ID. All members share one
Device Session authorization domain even when more than one copy exists concurrently.
_Avoid_: Independent Profile copy, username

**Profile Mismatch**:
A reachable Engine state in which the Profile bound to a Client's Device Session is not the Profile currently open on that Engine.
_Avoid_: Revocation, Locked Engine

**Device Session**:
A durable, independently revocable authorization for one Client installation and Profile, represented by its registered Device Key rather than a reusable bearer credential.
_Avoid_: Login, global session

**Revoked Device Session**:
A former Device Session whose registered Device Key authority has been removed while its
non-authorizing identity remains for the owner's device audit.
_Avoid_: Logged-out Client, expired Access Session

**Device Session ID**:
An Engine-generated opaque 256-bit identifier used to look up one Device Session without exposing its public key, Profile binding, or authorization state. It is high entropy but is not an authentication secret.
_Avoid_: API key, access token, public-key fingerprint

**Device Key**:
A non-exportable signing key created by a Client installation and registered with an Engine during Pairing to prove that installation's identity.
_Avoid_: API key, Profile password

**Access Session**:
A short-lived API authorization minted after a Client proves possession of its Device Key.
_Avoid_: Device Session, refresh token

**Companion Scope**:
The Engine-enforced allowlist of read and Refresh operations available to a Portfolio Companion's Access Session.
_Avoid_: Full API access, hidden UI

**Control Store**:
An Engine-local durable store for non-secret authorization metadata needed before a Profile is unlocked, including registered Device Keys and revocation state.
_Avoid_: User database, global database, session database

**Engine Origin**:
The canonical, system-trusted HTTPS origin that identifies an Engine relationship during
Pairing and Device proof. It is not a user-authored Engine label or a separate WebSocket URL.
_Avoid_: Engine label, API base path, WebSocket origin

**Pairing**:
A disposable one-time approval flow in which an authorized Client grants a new Client installation its own Device Session.
_Avoid_: Login, password sharing

**Unpair**:
Removal of a Client installation's local relationship with its Engine and destruction of that installation's Device Key and Portfolio Snapshot, with remote revocation attempted when the Engine is reachable.
_Avoid_: Logout, session expiry

**Portfolio Companion**:
A Client that presents and refreshes portfolio state while leaving profile administration, integrations, accounting configuration, and financial actions to the Full Client.
_Avoid_: Read-only client, full client

**Portfolio Snapshot**:
A bounded, non-authoritative copy of current portfolio state and recent history captured from an Engine at a stated time for offline presentation by a Client.
_Avoid_: Cache, offline portfolio

**Published Snapshot**:
The last complete immutable Portfolio Snapshot atomically committed by an Engine and served by Snapshot Fetch while newer Source work is staged but unpublished.
_Avoid_: Live balance view, in-progress Snapshot

**Configuration Bootstrap**:
The first Published Snapshot built without external requests from current Profile configuration, Manual Entries, and bounded History, with every Portfolio Source initially represented as never refreshed and contributing no invented balance.
_Avoid_: Automatic Refresh, legacy balance import, empty portfolio

**Snapshot Revision**:
A Profile-scoped opaque SHA-256 digest of one versioned canonical Portfolio Snapshot, used by a Client only to detect whether its local document changed.
_Avoid_: Database version, block number

**Fetch**:
A Client request that reads the state already held by an Engine without asking it to query external data sources.
_Avoid_: Refresh, sync

**Refresh**:
A user-requested operation that asks an Engine to update portfolio state from configured blockchains, exchanges, and other external sources before a Client Fetches it.
_Avoid_: Fetch, sync

**Refresh Operation**:
An Engine-owned task that updates one or more Portfolio Sources and may be observed by multiple Clients without being duplicated.
_Avoid_: Client job, Fetch

**Portfolio Source**:
A single independently refreshable configured external origin that contributes balances or History Events to a Profile, such as one blockchain-and-address pair or one named exchange connection.
_Avoid_: Integration, provider

**Manual Entry**:
A user-authored fungible asset or liability recorded in the Full Client and presented by the Portfolio Companion as a read-only portfolio contribution. It is not an external origin, has no Source Health, and cannot be refreshed.
_Avoid_: Manual Source, Portfolio Source

**Manual Balance ID**:
A stable opaque identity assigned to one Manual Entry, preserved when its editable fields change and permanently retired when that entry is deleted.
_Avoid_: Manual Entry label, database row ID

**Balance Contribution**:
A unique non-negative exact fungible amount and valuation for one `(origin, category, asset)` tuple, attributed to exactly one Portfolio Source or Manual Entry before the Engine derives portfolio aggregates.
_Avoid_: Unattributed balance, Source balance

**Asset**:
A positive-magnitude Balance Contribution owned by the Profile and added to gross assets when deriving net value.
_Avoid_: Positive balance

**Liability**:
A positive-magnitude Balance Contribution owed by the Profile and subtracted from gross assets only when deriving net value.
_Avoid_: Negative balance, debt asset

**Valuation Currency**:
The Profile's current main currency in which every monetary value and aggregate in one Portfolio Snapshot is expressed.
_Avoid_: Asset currency, display-only currency, implicit USD

**Asset Catalog**:
The deduplicated metadata table embedded in one Portfolio Snapshot for every asset identifier referenced by its portfolio, history, or Valuation Currency.
_Avoid_: Global asset database, per-row metadata, external icon catalog

**History Group**:
A curated display unit that summarizes one logical activity and contains its ordered History Events without exposing the Engine's mutable database grouping keys.
_Avoid_: Database event group, raw API group

**Curated History**:
The Companion's group-complete History projection, which removes Engine-derived display duplicates while preserving every meaningful leg needed to understand each included activity.
_Avoid_: Raw History, accounting History, Full Client History response

**History Occurrence Time**:
The latest event time in one complete History Group, representing when that logical activity most recently occurred for feed ordering and bounded inclusion.
_Avoid_: Header timestamp, group start time, identity timestamp

**History Event**:
One typed ordered leg inside a History Group, projected from Engine history into the bounded Companion contract rather than copied from the general history API.
_Avoid_: Database row, raw history event

**History Event Sequence**:
The zero-based dense ordinal of one Event in its complete Curated History Group after canonical presentation ordering.
_Avoid_: Engine sequence index, identity, Summary-role position

**History Presentation Order**:
The deterministic pre-ordinal Event order: topology roles first for a specialized Group and Engine-authored order for generic activity.
_Avoid_: Database row order, global type sort, value ranking

**History Protocol Code**:
A bounded canonical open string for History entry kind, event type, or event subtype, preserved exactly when a Client does not yet recognize it.
_Avoid_: Python enum name, closed wire enum, collapsed unknown

**History Summary**:
A closed topology-only description of one History Group that assigns semantic roles by referencing its Events without duplicating their financial facts.
_Avoid_: Display sentence, copied amount, Client-inferred grouping

**History Locations**:
The non-empty canonical set of locations derived from all Events in a History Group, with direction resolved through Summary roles rather than array order.
_Avoid_: Primary location, Source location, transfer blockchain

**History Source Attribution**:
A durable zero-or-more relation from one History Event to the exact Portfolio Sources whose provenance the Engine can prove, with absence meaning unknown rather than an inferred location.
_Avoid_: Location label, provider guess, group-wide origin

**History Detail**:
A closed structured set of transaction, protocol, movement, or staking facts attached to a History Event for useful offline inspection without copying free-form Engine data.
_Avoid_: Event metadata, raw extra data, note

**History Group ID**:
An opaque durable Profile-scoped Companion identity for one History Group, preserved only while the Engine can prove logical continuity and permanently retired when continuity is lost or the group is deleted.
_Avoid_: Group identifier, transaction hash

**History Event ID**:
An opaque durable Profile-scoped Companion identity for one History Event, preserved only while the Engine can prove one-to-one lineage and never inferred from mutable financial content or sequence.
_Avoid_: Event row ID, sequence index

**History Lineage**:
Internal typed provenance that lets the Engine prove that a History Group or Event before and after an edit or re-decode is the same logical item without exposing that provenance to a Client.
_Avoid_: Fuzzy match, public transaction identity

**History Generation**:
A Profile-scoped monotonic marker changed by every committed mutation that can alter curated History membership, identity, order, filtering, or projected content.
_Avoid_: Snapshot Revision, database version

**History Cursor**:
A short-lived opaque continuation for reading whole older History Groups in one fixed History Generation after the boundary of a specific Published Snapshot.
_Avoid_: Offset, page number, Snapshot cursor

**History Filter Match**:
A group selection proven by its group fields and one included History Event that satisfies every active Event-scoped filter without pruning the returned group.
_Avoid_: Cross-leg match, filtered event subset

**History Valuation**:
A nullable exact monetary value materialized for one History Event at its own occurrence time in the Snapshot's Valuation Currency, without a network lookup during Companion reads.
_Avoid_: Current-price estimate, Group total, implicit zero

**History Price Policy**:
The versioned deterministic rule that selects one eligible local historical-price record for materializing a History Valuation.
_Avoid_: Oracle lookup during Fetch, nearest unspecified price, Client pricing

**History Entitlement**:
The current Engine-owned limit on the newest underlying History groups visible through all Clients, applied before filters and shared by the bounded Snapshot and older History pagination.
_Avoid_: Mobile History limit, page size, Snapshot bound

**Valuation Coverage**:
A deterministic assessment of whether every Balance Contribution has a monetary valuation in the Snapshot's Valuation Currency. Partial coverage preserves exact known totals without treating an unavailable price as zero.
_Avoid_: Snapshot Coverage, Source Health, zero-priced portfolio

**Allocation Location**:
The canonical Profile location owned by a Balance Contribution's origin and used to derive exact per-location asset, liability, and known-net totals.
_Avoid_: Source ID, display group, all-liabilities blockchain bucket

**Source ID**:
A stable opaque identity assigned to one Portfolio Source, preserved when its editable details change and permanently retired when that Source is deleted.
_Avoid_: Address, exchange name, credential fingerprint

**Unsupported Source**:
A Portfolio Source whose kind a Client does not understand, while its common identity, health, and contribution to portfolio totals remain usable.
_Avoid_: Missing Source, invalid Source

**Source Health**:
The enabled or disabled condition, freshness, in-progress state, and most recent Refresh outcome of one Portfolio Source.
_Avoid_: Connection status, source balance

**Degraded Snapshot**:
A usable Portfolio Snapshot whose current coverage is incomplete because at least one Portfolio Source is disabled, failed to Refresh, or otherwise retains last-known data.
_Avoid_: Failed snapshot, partial portfolio

**Snapshot Coverage**:
A deterministic assessment of whether every configured Portfolio Source contributes current data, together with the Source-specific reasons when coverage is degraded.
_Avoid_: Engine connection status, Refresh Operation result
