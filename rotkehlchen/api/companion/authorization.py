"""Closed route policy and trusted-header validation for Companion requests."""

from __future__ import annotations

import ipaddress
import re
from dataclasses import dataclass
from typing import Final, cast

from rotkehlchen.api.companion.codec import EngineOrigin, InvalidCompanionInput
from rotkehlchen.api.companion.generated_protocol import (
    REALM_POLICY_ROWS,
    ROUTE_POLICY_ROWS,
    SUPPORTED_PROTOCOL_VERSIONS,
    Authority,
    AuthRealm,
    Capability,
    HttpErrorCode,
)

COMPANION_PATH_PREFIX: Final = '/api/1/companion'
_PLACEHOLDER_PATTERN: Final = re.compile(r'\{[a-z][a-z0-9_]*\}')
_ROUTE_CAPABILITIES: Final = {
    'cancel_pairing': Capability.DEVICE_SESSIONS,
    'create_pairing': Capability.DEVICE_SESSIONS,
    'register_device_session': Capability.DEVICE_SESSIONS,
    'list_device_sessions': Capability.DEVICE_SESSIONS,
    'rename_device_session': Capability.DEVICE_SESSIONS,
    'revoke_device_session': Capability.DEVICE_SESSIONS,
    'rename_current_device_session': Capability.DEVICE_SESSIONS,
    'revoke_current_device_session': Capability.DEVICE_SESSIONS,
    'create_challenge': Capability.DEVICE_SESSIONS,
    'create_access_session': Capability.DEVICE_SESSIONS,
    'fetch_snapshot': Capability.PORTFOLIO_SNAPSHOT,
    'page_history': Capability.HISTORY_PAGINATION,
    'create_refresh_operation': Capability.REFRESH_OPERATIONS,
    'list_refresh_operations': Capability.REFRESH_OPERATIONS,
    'get_refresh_operation': Capability.REFRESH_OPERATIONS,
}


class InvalidProtocolHeader(ValueError):
    """The protocol header is absent, ambiguous, non-canonical, or unsupported."""

    supported_protocol_versions: Final = SUPPORTED_PROTOCOL_VERSIONS

    def __init__(self) -> None:
        super().__init__('Incompatible Companion Protocol version')


@dataclass(frozen=True, slots=True)
class RoutePolicy:
    route_id: str
    method: str
    path: str
    realm: AuthRealm
    authority: Authority
    requires_protocol_header: bool
    scope: str | None
    identity_source: str | None
    success_status: int
    required_capability: Capability | None


ROUTE_POLICIES: Final = tuple(
    RoutePolicy(
        route_id=row.route_id,
        method=row.method,
        path=row.path,
        realm=row.realm,
        authority=row.authority,
        requires_protocol_header=row.requires_protocol_header,
        scope=row.scope,
        identity_source=row.identity_source,
        success_status=row.success_status,
        required_capability=_ROUTE_CAPABILITIES.get(row.route_id),
    )
    for row in ROUTE_POLICY_ROWS
)
REALM_POLICIES: Final = {row.realm: row for row in REALM_POLICY_ROWS}


def _compile_route_pattern(template: str) -> re.Pattern[str]:
    parts: list[str] = []
    offset = 0
    for match in _PLACEHOLDER_PATTERN.finditer(template):
        parts.extend((re.escape(template[offset:match.start()]), '([^/]+)'))
        offset = match.end()
    parts.append(re.escape(template[offset:]))
    return re.compile(f'^{"".join(parts)}$')


_EXACT_ROUTE_POLICIES: Final = {
    (policy.method, policy.path): policy
    for policy in ROUTE_POLICIES
    if _PLACEHOLDER_PATTERN.search(policy.path) is None
}
_PARAMETERIZED_ROUTE_POLICIES: Final = tuple(
    (
        policy,
        _compile_route_pattern(policy.path),
    )
    for policy in ROUTE_POLICIES
    if _PLACEHOLDER_PATTERN.search(policy.path) is not None
)


def is_companion_path(path: object) -> bool:
    """Recognize only the exact Companion namespace or one of its descendants."""
    return type(path) is str and (
        path == COMPANION_PATH_PREFIX or path.startswith(f'{COMPANION_PATH_PREFIX}/')
    )


def _relative_companion_path(path: object) -> str | None:
    if type(path) is not str:
        return None
    if is_companion_path(path):
        relative = path[len(COMPANION_PATH_PREFIX):]
    elif path.startswith('/'):
        relative = path
    else:
        return None
    if (
            relative in ('', '/') or
            relative.endswith('/') or
            '//' in relative or
            '%' in relative or
            '\\' in relative or
            '?' in relative or
            '#' in relative or
            any(not 0x21 <= ord(character) <= 0x7E for character in relative) or
            any(segment in ('.', '..') for segment in relative.split('/'))
    ):
        return None
    return relative


def match_route_policy(method: object, path: object) -> RoutePolicy | None:
    """Match a concrete full or Companion-relative path against the closed route matrix.

    Exact routes are selected before single-segment placeholders. Unsupported methods,
    encoded separators, empty segments, traversal, and trailing slashes never match.
    """
    if type(method) is not str or method != method.upper():
        return None
    relative_path = _relative_companion_path(path)
    if relative_path is None:
        return None
    exact = _EXACT_ROUTE_POLICIES.get((method, relative_path))
    if exact is not None:
        return exact
    for policy, pattern in _PARAMETERIZED_ROUTE_POLICIES:
        if policy.method == method and pattern.fullmatch(relative_path) is not None:
            return policy
    return None


def get_route_policy(method: object, path: object) -> RoutePolicy | None:
    """Alias with the concrete-path semantics used by the Flask catch-all resource."""
    return match_route_policy(method, path)


def validate_protocol_header(values: list[str] | tuple[str, ...]) -> int:
    """Return the negotiated version, or raise a redacted InvalidProtocolHeader."""
    if type(values) not in (list, tuple) or len(values) != 1 or type(values[0]) is not str:
        raise InvalidProtocolHeader
    value = values[0]
    if value not in {str(version) for version in SUPPORTED_PROTOCOL_VERSIONS}:
        raise InvalidProtocolHeader
    return int(value)


def extract_asgi_header_values(scope: object, header_name: str) -> tuple[str, ...]:
    """Read one header from the original ASGI bytes without WSGI alias folding.

    WSGI maps both hyphens and underscores to the same ``HTTP_*`` key. Trusting
    Flask's reconstructed headers would therefore let an inbound underscore
    alias impersonate a Starling-owned private header. The ASGI request head is
    the security boundary: aliases, malformed entries, and non-ASCII values all
    fail closed before realm or credential processing.
    """
    if (
            type(scope) is not dict or
            type(header_name) is not str or
            header_name == '' or
            '_' in header_name
    ):
        raise InvalidCompanionInput
    try:
        target = header_name.encode('ascii').lower()
    except UnicodeEncodeError:
        raise InvalidCompanionInput from None
    if any(not (byte == 0x2D or chr(byte).isalnum()) for byte in target):
        raise InvalidCompanionInput

    scope_dict = cast('dict[object, object]', scope)
    raw_headers = scope_dict.get('headers')
    if type(raw_headers) not in (list, tuple):
        raise InvalidCompanionInput
    headers = cast('list[object] | tuple[object, ...]', raw_headers)
    values: list[str] = []
    for entry in headers:
        if type(entry) not in (list, tuple):
            raise InvalidCompanionInput
        entry_values = cast('list[object] | tuple[object, ...]', entry)
        if (
                len(entry_values) != 2 or
                type(entry_values[0]) is not bytes or
                type(entry_values[1]) is not bytes
        ):
            raise InvalidCompanionInput
        raw_name_object, raw_value_object = entry_values
        raw_name = cast('bytes', raw_name_object)
        raw_value = cast('bytes', raw_value_object)
        lowered_name = raw_name.lower()
        if lowered_name.replace(b'_', b'-') != target:
            continue
        if lowered_name != target:
            raise InvalidCompanionInput
        try:
            values.append(raw_value.decode('ascii', errors='strict'))
        except UnicodeDecodeError:
            raise InvalidCompanionInput from None
    return tuple(values)


def _single_header_value(values: list[str] | tuple[str, ...]) -> str:
    if type(values) not in (list, tuple) or len(values) != 1 or type(values[0]) is not str:
        raise InvalidCompanionInput
    value = values[0]
    if value == '' or value != value.strip() or ',' in value:
        raise InvalidCompanionInput
    return value


def validate_engine_origin_header(
        values: list[str] | tuple[str, ...],
        *,
        required: bool = True,
) -> EngineOrigin | None:
    """Validate Starling's trusted origin header, optionally allowing it to be absent."""
    if type(required) is not bool:
        raise InvalidCompanionInput
    if type(values) in (list, tuple) and len(values) == 0 and required is False:
        return None
    return EngineOrigin.parse(_single_header_value(values))


def validate_client_ip_header(
        values: list[str] | tuple[str, ...],
        *,
        required: bool = True,
) -> str | None:
    """Return one canonical IPv4/IPv6 address from trusted ingress metadata."""
    if type(required) is not bool:
        raise InvalidCompanionInput
    if type(values) in (list, tuple) and len(values) == 0 and required is False:
        return None
    value = _single_header_value(values)
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        raise InvalidCompanionInput from None
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        raise InvalidCompanionInput
    canonical = str(address)
    if canonical != value:
        raise InvalidCompanionInput
    return canonical


def missing_authority_error(realm: AuthRealm) -> HttpErrorCode | None:
    """Return the fixture-defined collapsed authentication error for a realm."""
    if type(realm) is not AuthRealm:
        raise InvalidCompanionInput
    return REALM_POLICIES[realm].missing_or_wrong_authority
