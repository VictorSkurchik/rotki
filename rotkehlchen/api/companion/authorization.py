from enum import StrEnum
from types import MappingProxyType
from typing import Final

COMPANION_API_PREFIX: Final = '/api/1/companion'


class CompanionRealm(StrEnum):
    """The sole authority selected by an exact Companion method/rule pair."""

    PUBLIC = 'public'
    FULL_CLIENT = 'full_client'
    PAIRING = 'pairing'
    DEVICE_PROOF = 'device_proof'
    ACCESS_SESSION = 'access_session'


_COMPANION_ROUTE_REALMS: Final = MappingProxyType({
    (f'{COMPANION_API_PREFIX}/protocol', 'GET'): CompanionRealm.PUBLIC,
    (f'{COMPANION_API_PREFIX}/pairings', 'POST'): CompanionRealm.FULL_CLIENT,
    (
        f'{COMPANION_API_PREFIX}/pairings/<string:pairing_id>',
        'DELETE',
    ): CompanionRealm.FULL_CLIENT,
    (f'{COMPANION_API_PREFIX}/device-sessions', 'POST'): CompanionRealm.PAIRING,
    (f'{COMPANION_API_PREFIX}/device-sessions', 'GET'): CompanionRealm.FULL_CLIENT,
    (
        f'{COMPANION_API_PREFIX}/device-sessions/<string:device_session_id>',
        'PATCH',
    ): CompanionRealm.FULL_CLIENT,
    (
        f'{COMPANION_API_PREFIX}/device-sessions/<string:device_session_id>',
        'DELETE',
    ): CompanionRealm.FULL_CLIENT,
    (
        f'{COMPANION_API_PREFIX}/device-sessions/current',
        'PATCH',
    ): CompanionRealm.ACCESS_SESSION,
    (
        f'{COMPANION_API_PREFIX}/device-sessions/current',
        'DELETE',
    ): CompanionRealm.ACCESS_SESSION,
    (f'{COMPANION_API_PREFIX}/challenges', 'POST'): CompanionRealm.DEVICE_PROOF,
    (f'{COMPANION_API_PREFIX}/access-sessions', 'POST'): CompanionRealm.DEVICE_PROOF,
    (f'{COMPANION_API_PREFIX}/snapshot', 'GET'): CompanionRealm.ACCESS_SESSION,
    (f'{COMPANION_API_PREFIX}/history', 'GET'): CompanionRealm.ACCESS_SESSION,
    (
        f'{COMPANION_API_PREFIX}/refresh-operations',
        'POST',
    ): CompanionRealm.ACCESS_SESSION,
    (
        f'{COMPANION_API_PREFIX}/refresh-operations',
        'GET',
    ): CompanionRealm.ACCESS_SESSION,
    (
        f'{COMPANION_API_PREFIX}/refresh-operations/<string:operation_id>',
        'GET',
    ): CompanionRealm.ACCESS_SESSION,
})


def classify_companion_route(rule: str | None, method: str) -> CompanionRealm | None:
    """Select exactly one realm, or deny an unlisted method/rule without fallback."""
    if rule is None:
        return None
    return _COMPANION_ROUTE_REALMS.get((rule, method))
