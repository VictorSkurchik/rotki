import json
from pathlib import Path

import pytest

from rotkehlchen.api.companion.authorization import CompanionRealm, classify_companion_route

REPO_ROOT = Path(__file__).resolve().parents[4]
ROUTE_MATRIX = json.loads(
    (REPO_ROOT / 'mobile' / 'protocol' / 'v1' / 'p0_1_cases.json').read_text(
        encoding='utf-8',
    ),
)['route_matrix']
COMPANION_PREFIX = '/api/1/companion'
HTTP_METHODS = ('GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS')
FLASK_VARIABLES = {
    '{pairing_id}': '<string:pairing_id>',
    '{device_session_id}': '<string:device_session_id>',
    '{operation_id}': '<string:operation_id>',
}


def _flask_rule(protocol_path: str) -> str:
    rule = f'{COMPANION_PREFIX}{protocol_path}'
    for protocol_variable, flask_variable in FLASK_VARIABLES.items():
        rule = rule.replace(protocol_variable, flask_variable)
    return rule


EXPECTED_ROUTES = {
    (_flask_rule(route['path']), route['method']): CompanionRealm(route['realm'])
    for route in ROUTE_MATRIX
}


def test_protocol_route_matrix_classifies_all_16_exact_pairs() -> None:
    assert len(ROUTE_MATRIX) == 16
    assert len(EXPECTED_ROUTES) == 16
    for (rule, method), realm in EXPECTED_ROUTES.items():
        assert classify_companion_route(rule, method) is realm


def test_every_unlisted_method_for_a_known_rule_is_not_classified() -> None:
    for route_key in EXPECTED_ROUTES:
        rule = route_key[0]
        for method in HTTP_METHODS:
            expected = EXPECTED_ROUTES.get((rule, method))
            assert classify_companion_route(rule, method) is expected, (rule, method)


@pytest.mark.parametrize(('rule', 'method'), [
    (None, 'GET'),
    (f'{COMPANION_PREFIX}/protocol', 'get'),
    (f'{COMPANION_PREFIX}/protocol', ' GET'),
    (f'{COMPANION_PREFIX}/protocol/', 'GET'),
    (f'{COMPANION_PREFIX}//protocol', 'GET'),
    (f'{COMPANION_PREFIX}/./protocol', 'GET'),
    (f'{COMPANION_PREFIX}/device-sessions/current/', 'DELETE'),
    (f'{COMPANION_PREFIX}/device-sessions/<string:device_session_id>', 'POST'),
    (f'{COMPANION_PREFIX}/device-sessions/<path:device_session_id>', 'DELETE'),
    (f'{COMPANION_PREFIX}/pairings/<string:pairing_id>;parameter', 'DELETE'),
    (f'{COMPANION_PREFIX}/refresh-operations/<string:operation_id>/child', 'GET'),
    ('/api/1/companionish/protocol', 'GET'),
    ('/api/10/companion/protocol', 'GET'),
])
def test_classifier_fails_closed_for_near_or_malformed_rules(
        rule: str | None,
        method: str,
) -> None:
    assert classify_companion_route(rule, method) is None


def test_current_literal_and_device_id_template_select_only_their_declared_realms() -> None:
    current_rule = f'{COMPANION_PREFIX}/device-sessions/current'
    identifier_rule = f'{COMPANION_PREFIX}/device-sessions/<string:device_session_id>'

    assert classify_companion_route(current_rule, 'PATCH') is CompanionRealm.ACCESS_SESSION
    assert classify_companion_route(current_rule, 'DELETE') is CompanionRealm.ACCESS_SESSION
    assert classify_companion_route(identifier_rule, 'PATCH') is CompanionRealm.FULL_CLIENT
    assert classify_companion_route(identifier_rule, 'DELETE') is CompanionRealm.FULL_CLIENT
    assert classify_companion_route(current_rule, 'GET') is None
    assert classify_companion_route(identifier_rule, 'GET') is None
