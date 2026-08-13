import pytest

from rotkehlchen.api.companion.authorization import (
    ROUTE_POLICIES,
    InvalidProtocolHeader,
    extract_asgi_header_values,
    get_route_policy,
    is_companion_path,
    match_route_policy,
    validate_client_ip_header,
    validate_engine_origin_header,
    validate_protocol_header,
)
from rotkehlchen.api.companion.codec import InvalidCompanionInput
from rotkehlchen.api.companion.generated_protocol import PROTOCOL_HEADER, AuthRealm, Capability


def test_matches_concrete_full_and_relative_paths_with_exact_route_precedence() -> None:
    current = get_route_policy('PATCH', '/api/1/companion/device-sessions/current')
    concrete = match_route_policy('PATCH', '/device-sessions/abc_123')
    pairing = match_route_policy('DELETE', '/api/1/companion/pairings/abc_123')

    assert current is not None
    assert current.route_id == 'rename_current_device_session'
    assert current.realm is AuthRealm.ACCESS_SESSION
    assert concrete is not None
    assert concrete.route_id == 'rename_device_session'
    assert concrete.realm is AuthRealm.FULL_CLIENT
    assert pairing is not None
    assert pairing.route_id == 'cancel_pairing'
    assert pairing.required_capability is Capability.DEVICE_SESSIONS
    assert all(
        policy.required_capability is not None
        for policy in ROUTE_POLICIES
        if policy.route_id != 'get_protocol'
    )


@pytest.mark.parametrize(('method', 'path'), [
    ('HEAD', '/api/1/companion/protocol'),
    ('OPTIONS', '/api/1/companion/protocol'),
    ('get', '/api/1/companion/protocol'),
    ('POST', '/api/1/companion/protocol'),
    ('GET', '/api/1/companion/protocol/'),
    ('DELETE', '/api/1/companion/pairings/'),
    ('DELETE', '/api/1/companion/pairings//value'),
    ('DELETE', '/api/1/companion/pairings/a%2Fb'),
    ('DELETE', '/api/1/companion/pairings/..'),
    ('GET', '/api/1/companionevil/protocol'),
])
def test_closed_route_matcher_rejects_non_matrix_requests(method: str, path: str) -> None:
    assert match_route_policy(method, path) is None


def test_companion_namespace_check_has_segment_boundary() -> None:
    assert is_companion_path('/api/1/companion') is True
    assert is_companion_path('/api/1/companion/protocol') is True
    assert is_companion_path('/api/1/companionevil') is False
    assert is_companion_path('/protocol') is False


def test_protocol_header_accepts_only_one_exact_supported_decimal() -> None:
    assert validate_protocol_header(['1']) == 1
    for values in ([], ['1', '1'], ['1, 1'], ['01'], [' 1'], ['2']):
        with pytest.raises(InvalidProtocolHeader) as error:
            validate_protocol_header(values)
        assert error.value.supported_protocol_versions == (1,)
        assert '1, 1' not in repr(error.value)


def test_asgi_header_extraction_rejects_wsgi_aliases_and_malformed_heads() -> None:
    target = PROTOCOL_HEADER
    assert extract_asgi_header_values({
        'headers': [(b'Rotki-Companion-Protocol', b'1')],
    }, target) == ('1',)
    assert extract_asgi_header_values({
        'headers': [(b'rotki-companion-protocol', b'1'), (b'other', b'value')],
    }, target) == ('1',)
    assert extract_asgi_header_values({
        'headers': [(b'rotki-companion-protocol', b'1'), (b'rotki-companion-protocol', b'2')],
    }, target) == ('1', '2')

    for scope in (
        {'headers': [(b'rotki_companion_protocol', b'1')]},
        {'headers': [
            (b'rotki-companion-protocol', b'1'),
            (b'rotki_companion_protocol', b'1'),
        ]},
        {'headers': [(b'rotki-companion-protocol', b'\xff')]},
        {'headers': [(b'rotki-companion-protocol',)]},
        {'headers': 'not-a-header-list'},
        {},
    ):
        with pytest.raises(InvalidCompanionInput):
            extract_asgi_header_values(scope, target)


def test_trusted_origin_and_client_ip_headers_are_strict() -> None:
    origin = validate_engine_origin_header(['https://rotki.example'])
    assert origin is not None
    assert origin.canonical == 'https://rotki.example'
    assert validate_engine_origin_header([], required=False) is None
    assert validate_client_ip_header(['192.0.2.1']) == '192.0.2.1'
    assert validate_client_ip_header(['2001:db8::1']) == '2001:db8::1'
    assert validate_client_ip_header([], required=False) is None

    for values in ([], ['https://rotki.example', 'https://other.example'], ['bad,origin']):
        with pytest.raises(InvalidCompanionInput):
            validate_engine_origin_header(values)
    for values in (
        ['192.0.2.1, 192.0.2.2'],
        ['2001:0db8::1'],
        ['::ffff:c000:201'],
        ['192.0.2.1:80'],
    ):
        with pytest.raises(InvalidCompanionInput):
            validate_client_ip_header(values)
