"""Generated Companion Protocol vocabulary. Do not edit by hand."""

from dataclasses import dataclass
from enum import StrEnum
from typing import Final


class AuthRealm(StrEnum):
    ACCESS_SESSION = 'access_session'
    DEVICE_PROOF = 'device_proof'
    FULL_CLIENT = 'full_client'
    PAIRING = 'pairing'
    PUBLIC = 'public'


class Authority(StrEnum):
    ACCESS_BEARER = 'access_bearer'
    BROWSER_COOKIE = 'browser_cookie'
    CHALLENGE_PROOF = 'challenge_proof'
    DEVICE_SESSION_LOOKUP = 'device_session_lookup'
    NONE = 'none'
    PAIRING_BEARER = 'pairing_bearer'


class Capability(StrEnum):
    DEVICE_SESSIONS = 'device_sessions'
    HISTORY_PAGINATION = 'history_pagination'
    PORTFOLIO_SNAPSHOT = 'portfolio_snapshot'
    REFRESH_OPERATIONS = 'refresh_operations'
    WEBSOCKET_NOTIFICATIONS = 'websocket_notifications'


class DeviceSessionState(StrEnum):
    AUTHORIZED = 'authorized'
    REVOKED = 'revoked'


class ErrorAction(StrEnum):
    AUTHENTICATE_FULL_CLIENT = 'authenticate_full_client'
    ENABLE_SOURCE_FULL_CLIENT = 'enable_source_full_client'
    FETCH_SNAPSHOT = 'fetch_snapshot'
    NEW_REQUEST = 'new_request'
    NONE = 'none'
    OBSERVE_ACTIVE = 'observe_active'
    OPEN_BOUND_PROFILE = 'open_bound_profile'
    PAIR_AGAIN = 'pair_again'
    PROVE_DEVICE = 'prove_device'
    REQUEST_CHALLENGE = 'request_challenge'
    RESTART_HISTORY = 'restart_history'
    RETRY = 'retry'
    RETRY_AFTER = 'retry_after'
    UNLOCK_FULL_CLIENT = 'unlock_full_client'
    UPGRADE_ENGINE = 'upgrade_engine'
    USE_FULL_CLIENT = 'use_full_client'


class HttpErrorCode(StrEnum):
    ACCESS_SESSION_UNAVAILABLE = 'access_session_unavailable'
    CHALLENGE_UNAVAILABLE = 'challenge_unavailable'
    FULL_CLIENT_AUTH_REQUIRED = 'full_client_auth_required'
    HISTORY_CHANGED = 'history_changed'
    HISTORY_CURSOR_UNAVAILABLE = 'history_cursor_unavailable'
    IDEMPOTENCY_CONFLICT = 'idempotency_conflict'
    INCOMPATIBLE_PROTOCOL = 'incompatible_protocol'
    INVALID_REQUEST = 'invalid_request'
    LOCKED_ENGINE = 'locked_engine'
    NO_REFRESHABLE_SOURCES = 'no_refreshable_sources'
    NOT_AUTHORIZED = 'not_authorized'
    PAIRING_UNAVAILABLE = 'pairing_unavailable'
    PROFILE_MISMATCH = 'profile_mismatch'
    RATE_LIMITED = 'rate_limited'
    REFRESH_CONFLICT = 'refresh_conflict'
    RESOURCE_NOT_FOUND = 'resource_not_found'
    SCOPE_DENIED = 'scope_denied'
    SNAPSHOT_UNAVAILABLE = 'snapshot_unavailable'
    SOURCE_DISABLED = 'source_disabled'
    UNEXPECTED_ENGINE_ERROR = 'unexpected_engine_error'


class OperationErrorCode(StrEnum):
    OPERATION_INTERRUPTED = 'operation_interrupted'
    SOURCE_REFRESH_FAILED = 'source_refresh_failed'


class Platform(StrEnum):
    ANDROID = 'android'
    IOS = 'ios'


class RootState(StrEnum):
    CONNECTING = 'connecting'
    DEGRADED = 'degraded'
    DEVICE_LOCKED = 'device_locked'
    ENGINE_LOCKED = 'engine_locked'
    INCOMPATIBLE = 'incompatible'
    ONLINE = 'online'
    PROFILE_MISMATCH = 'profile_mismatch'
    REFRESHING = 'refreshing'
    REVOKED = 'revoked'
    UNPAIRED = 'unpaired'
    UNREACHABLE = 'unreachable'


class SourceErrorCode(StrEnum):
    SOURCE_AUTHENTICATION_FAILED = 'source_authentication_failed'
    SOURCE_CONFIGURATION_CHANGED = 'source_configuration_changed'
    SOURCE_RATE_LIMITED = 'source_rate_limited'
    SOURCE_UNEXPECTED_ERROR = 'source_unexpected_error'
    SOURCE_UNREACHABLE = 'source_unreachable'


class WebsocketEventType(StrEnum):
    COMPANION_REFRESH_OPERATION = 'companion_refresh_operation'
    COMPANION_SNAPSHOT_REVISION = 'companion_snapshot_revision'


PROTOCOL_VERSIONS: Final = (1,)
SUPPORTED_PROTOCOL_VERSIONS: Final = PROTOCOL_VERSIONS
PROTOCOL_HEADER: Final = 'Rotki-Companion-Protocol'
IDEMPOTENCY_KEY_HEADER: Final = 'Idempotency-Key'
X_ROTKI_ENGINE_ORIGIN_HEADER: Final = 'X-Rotki-Engine-Origin'
X_ROTKI_CLIENT_IP_HEADER: Final = 'X-Rotki-Client-IP'
ENGINE_ORIGIN_HEADER: Final = X_ROTKI_ENGINE_ORIGIN_HEADER
CLIENT_IP_HEADER: Final = X_ROTKI_CLIENT_IP_HEADER

CAPABILITIES: Final = {
    Capability.DEVICE_SESSIONS: 1,
    Capability.HISTORY_PAGINATION: 1,
    Capability.PORTFOLIO_SNAPSHOT: 1,
    Capability.REFRESH_OPERATIONS: 1,
    Capability.WEBSOCKET_NOTIFICATIONS: 1,
}


LIFETIMES_SECONDS: Final = {
    'access_session': 900,
    'challenge': 60,
    'pairing': 120,
    'proactive_renewal_window': 300,
    'registration_replay': 300,
}

ENCODED_LENGTHS: Final = {
    'access_session_credential': 43,
    'challenge_id': 22,
    'device_session_id': 43,
    'idempotency_key': 22,
    'nonce': 43,
    'pairing_credential': 43,
    'pairing_id': 22,
    'p1363_signature': 86,
    'p256_public_key': 87,
}

DECODED_LENGTHS: Final = {
    key: encoded_length * 6 // 8
    for key, encoded_length in ENCODED_LENGTHS.items()
}

PUBLIC_KEY_ALGORITHMS: Final = ('ecdsa-p256-sha256-p1363',)
WEBSOCKET_CLOSE_CODES: Final = (1000, 1006, 1008, 1012)


@dataclass(frozen=True, slots=True)
class HttpErrorSpec:
    status: int
    retryable: bool
    action: ErrorAction
    device_session_effect: str
    offline_snapshot_effect: str


HTTP_ERROR_SPECS: Final = {
    HttpErrorCode.INVALID_REQUEST: HttpErrorSpec(
        status=400,
        retryable=False,
        action=ErrorAction.NONE,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.ACCESS_SESSION_UNAVAILABLE: HttpErrorSpec(
        status=401,
        retryable=False,
        action=ErrorAction.PROVE_DEVICE,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.FULL_CLIENT_AUTH_REQUIRED: HttpErrorSpec(
        status=401,
        retryable=False,
        action=ErrorAction.AUTHENTICATE_FULL_CLIENT,
        device_session_effect='unchanged',
        offline_snapshot_effect='unchanged',
    ),
    HttpErrorCode.NOT_AUTHORIZED: HttpErrorSpec(
        status=401,
        retryable=False,
        action=ErrorAction.PAIR_AGAIN,
        device_session_effect='delete',
        offline_snapshot_effect='delete',
    ),
    HttpErrorCode.SCOPE_DENIED: HttpErrorSpec(
        status=403,
        retryable=False,
        action=ErrorAction.USE_FULL_CLIENT,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.RESOURCE_NOT_FOUND: HttpErrorSpec(
        status=404,
        retryable=False,
        action=ErrorAction.NONE,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.HISTORY_CHANGED: HttpErrorSpec(
        status=409,
        retryable=False,
        action=ErrorAction.FETCH_SNAPSHOT,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.IDEMPOTENCY_CONFLICT: HttpErrorSpec(
        status=409,
        retryable=False,
        action=ErrorAction.NEW_REQUEST,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.NO_REFRESHABLE_SOURCES: HttpErrorSpec(
        status=409,
        retryable=False,
        action=ErrorAction.USE_FULL_CLIENT,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.PROFILE_MISMATCH: HttpErrorSpec(
        status=409,
        retryable=False,
        action=ErrorAction.OPEN_BOUND_PROFILE,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.REFRESH_CONFLICT: HttpErrorSpec(
        status=409,
        retryable=False,
        action=ErrorAction.OBSERVE_ACTIVE,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.SOURCE_DISABLED: HttpErrorSpec(
        status=409,
        retryable=False,
        action=ErrorAction.ENABLE_SOURCE_FULL_CLIENT,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.CHALLENGE_UNAVAILABLE: HttpErrorSpec(
        status=410,
        retryable=False,
        action=ErrorAction.REQUEST_CHALLENGE,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.HISTORY_CURSOR_UNAVAILABLE: HttpErrorSpec(
        status=410,
        retryable=False,
        action=ErrorAction.RESTART_HISTORY,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.PAIRING_UNAVAILABLE: HttpErrorSpec(
        status=410,
        retryable=False,
        action=ErrorAction.PAIR_AGAIN,
        device_session_effect='not_established',
        offline_snapshot_effect='not_established',
    ),
    HttpErrorCode.LOCKED_ENGINE: HttpErrorSpec(
        status=423,
        retryable=False,
        action=ErrorAction.UNLOCK_FULL_CLIENT,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.INCOMPATIBLE_PROTOCOL: HttpErrorSpec(
        status=426,
        retryable=False,
        action=ErrorAction.UPGRADE_ENGINE,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.RATE_LIMITED: HttpErrorSpec(
        status=429,
        retryable=True,
        action=ErrorAction.RETRY_AFTER,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.UNEXPECTED_ENGINE_ERROR: HttpErrorSpec(
        status=500,
        retryable=False,
        action=ErrorAction.NONE,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
    HttpErrorCode.SNAPSHOT_UNAVAILABLE: HttpErrorSpec(
        status=503,
        retryable=True,
        action=ErrorAction.RETRY,
        device_session_effect='keep',
        offline_snapshot_effect='keep',
    ),
}


@dataclass(frozen=True, slots=True)
class RoutePolicyRow:
    route_id: str
    method: str
    path: str
    realm: AuthRealm
    authority: Authority
    requires_protocol_header: bool
    scope: str | None
    identity_source: str | None
    success_status: int


ROUTE_POLICY_ROWS: Final = (
    RoutePolicyRow(
        route_id='get_protocol',
        method='GET',
        path='/protocol',
        realm=AuthRealm.PUBLIC,
        authority=Authority.NONE,
        requires_protocol_header=False,
        scope=None,
        identity_source=None,
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='create_pairing',
        method='POST',
        path='/pairings',
        realm=AuthRealm.FULL_CLIENT,
        authority=Authority.BROWSER_COOKIE,
        requires_protocol_header=True,
        scope=None,
        identity_source=None,
        success_status=201,
    ),
    RoutePolicyRow(
        route_id='cancel_pairing',
        method='DELETE',
        path='/pairings/{pairing_id}',
        realm=AuthRealm.FULL_CLIENT,
        authority=Authority.BROWSER_COOKIE,
        requires_protocol_header=True,
        scope=None,
        identity_source=None,
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='register_device_session',
        method='POST',
        path='/device-sessions',
        realm=AuthRealm.PAIRING,
        authority=Authority.PAIRING_BEARER,
        requires_protocol_header=True,
        scope=None,
        identity_source=None,
        success_status=201,
    ),
    RoutePolicyRow(
        route_id='list_device_sessions',
        method='GET',
        path='/device-sessions',
        realm=AuthRealm.FULL_CLIENT,
        authority=Authority.BROWSER_COOKIE,
        requires_protocol_header=True,
        scope=None,
        identity_source=None,
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='rename_device_session',
        method='PATCH',
        path='/device-sessions/{device_session_id}',
        realm=AuthRealm.FULL_CLIENT,
        authority=Authority.BROWSER_COOKIE,
        requires_protocol_header=True,
        scope=None,
        identity_source=None,
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='revoke_device_session',
        method='DELETE',
        path='/device-sessions/{device_session_id}',
        realm=AuthRealm.FULL_CLIENT,
        authority=Authority.BROWSER_COOKIE,
        requires_protocol_header=True,
        scope=None,
        identity_source=None,
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='rename_current_device_session',
        method='PATCH',
        path='/device-sessions/current',
        realm=AuthRealm.ACCESS_SESSION,
        authority=Authority.ACCESS_BEARER,
        requires_protocol_header=True,
        scope='self_manage',
        identity_source='access_session',
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='revoke_current_device_session',
        method='DELETE',
        path='/device-sessions/current',
        realm=AuthRealm.ACCESS_SESSION,
        authority=Authority.ACCESS_BEARER,
        requires_protocol_header=True,
        scope='self_manage',
        identity_source='access_session',
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='create_challenge',
        method='POST',
        path='/challenges',
        realm=AuthRealm.DEVICE_PROOF,
        authority=Authority.DEVICE_SESSION_LOOKUP,
        requires_protocol_header=True,
        scope=None,
        identity_source=None,
        success_status=201,
    ),
    RoutePolicyRow(
        route_id='create_access_session',
        method='POST',
        path='/access-sessions',
        realm=AuthRealm.DEVICE_PROOF,
        authority=Authority.CHALLENGE_PROOF,
        requires_protocol_header=True,
        scope=None,
        identity_source=None,
        success_status=201,
    ),
    RoutePolicyRow(
        route_id='fetch_snapshot',
        method='GET',
        path='/snapshot',
        realm=AuthRealm.ACCESS_SESSION,
        authority=Authority.ACCESS_BEARER,
        requires_protocol_header=True,
        scope='snapshot_read',
        identity_source=None,
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='page_history',
        method='GET',
        path='/history',
        realm=AuthRealm.ACCESS_SESSION,
        authority=Authority.ACCESS_BEARER,
        requires_protocol_header=True,
        scope='history_read',
        identity_source=None,
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='create_refresh_operation',
        method='POST',
        path='/refresh-operations',
        realm=AuthRealm.ACCESS_SESSION,
        authority=Authority.ACCESS_BEARER,
        requires_protocol_header=True,
        scope='refresh_start',
        identity_source=None,
        success_status=202,
    ),
    RoutePolicyRow(
        route_id='list_refresh_operations',
        method='GET',
        path='/refresh-operations',
        realm=AuthRealm.ACCESS_SESSION,
        authority=Authority.ACCESS_BEARER,
        requires_protocol_header=True,
        scope='refresh_observe',
        identity_source=None,
        success_status=200,
    ),
    RoutePolicyRow(
        route_id='get_refresh_operation',
        method='GET',
        path='/refresh-operations/{operation_id}',
        realm=AuthRealm.ACCESS_SESSION,
        authority=Authority.ACCESS_BEARER,
        requires_protocol_header=True,
        scope='refresh_observe',
        identity_source=None,
        success_status=200,
    ),
)


@dataclass(frozen=True, slots=True)
class RealmPolicyRow:
    realm: AuthRealm
    credential_selection: str
    missing_or_wrong_authority: HttpErrorCode | None
    foreign_credentials: str


REALM_POLICY_ROWS: Final = (
    RealmPolicyRow(
        realm=AuthRealm.PUBLIC,
        credential_selection='ignore_all',
        missing_or_wrong_authority=None,
        foreign_credentials='ignore_without_parsing',
    ),
    RealmPolicyRow(
        realm=AuthRealm.FULL_CLIENT,
        credential_selection='browser_cookie_only',
        missing_or_wrong_authority=HttpErrorCode.FULL_CLIENT_AUTH_REQUIRED,
        foreign_credentials='ignore_without_parsing',
    ),
    RealmPolicyRow(
        realm=AuthRealm.PAIRING,
        credential_selection='pairing_store_only',
        missing_or_wrong_authority=HttpErrorCode.PAIRING_UNAVAILABLE,
        foreign_credentials='collapse_to_route_error',
    ),
    RealmPolicyRow(
        realm=AuthRealm.DEVICE_PROOF,
        credential_selection='request_body_stage_only',
        missing_or_wrong_authority=None,
        foreign_credentials='ignore_without_parsing',
    ),
    RealmPolicyRow(
        realm=AuthRealm.ACCESS_SESSION,
        credential_selection='access_store_only',
        missing_or_wrong_authority=HttpErrorCode.ACCESS_SESSION_UNAVAILABLE,
        foreign_credentials='collapse_to_route_error',
    ),
)
