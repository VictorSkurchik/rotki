from __future__ import annotations

import json
import logging
from contextlib import contextmanager
from http import HTTPStatus
from http.client import HTTPConnection
from typing import TYPE_CHECKING, Any
from urllib.parse import urlsplit

import pytest
import requests

import rotkehlchen.api.server as server_module
from rotkehlchen.api.companion.authorization import (
    COMPANION_PATH_PREFIX,
    ROUTE_POLICIES,
    RoutePolicy,
)
from rotkehlchen.api.companion.generated_protocol import PROTOCOL_HEADER, HttpErrorCode
from rotkehlchen.api.server import APIServer
from rotkehlchen.api.session_store import SESSION_DB_NAME, SessionStore
from rotkehlchen.api.session_token import MCP_BACKEND_PROOF_HEADER, SESSION_COOKIE_NAME
from rotkehlchen.constants.misc import GLOBALDIR_NAME
from rotkehlchen.tests.utils.api import api_url_for

if TYPE_CHECKING:
    from collections.abc import Iterator

    from _pytest.logging import LogCaptureFixture
    from _pytest.monkeypatch import MonkeyPatch


_TEST_SESSION_KEY = b'companion-cookie-boundary-test-key'
_NON_PUBLIC_POLICIES: tuple[RoutePolicy, ...] = tuple(
    policy for policy in ROUTE_POLICIES if policy.route_id != 'get_protocol'
)
_PATH_ARGUMENTS: dict[str, str] = {
    'pairing_id': 'pairing-route-value',
    'device_session_id': 'device-session-route-value',
    'operation_id': 'operation-route-value',
}
_PROTOCOL_HEADER_CASES: tuple[tuple[str, dict[str, str]], ...] = (
    ('supported', {PROTOCOL_HEADER: '1'}),
    ('missing', {}),
    ('unsupported', {PROTOCOL_HEADER: '0'}),
    ('folded', {PROTOCOL_HEADER: '1, 1'}),
)
_UNLISTED_ROUTE_CASES: tuple[tuple[str, str], ...] = (
    ('GET', ''),
    ('GET', '/unknown-resource'),
    ('GET', '/protocol/'),
    ('POST', '/protocol'),
    ('PUT', '/protocol'),
    ('PATCH', '/protocol'),
    ('DELETE', '/protocol'),
    ('OPTIONS', '/protocol'),
)


@pytest.fixture(name='use_clean_caching_directory')
def fixture_use_clean_caching_directory() -> bool:
    """Keep this live-server suite isolated from parallel API agents and local caches."""
    return True


def _companion_url(api_server: APIServer, relative_path: str) -> str:
    protocol_url = api_url_for(api_server, 'companionprotocolresource')
    return f'{protocol_url.removesuffix("/protocol")}{relative_path}'


def _concrete_path(policy: RoutePolicy) -> str:
    return policy.path.format_map(_PATH_ARGUMENTS)


def _assert_typed_error(
        response: requests.Response,
        *,
        status: HTTPStatus,
        code: HttpErrorCode,
        message: str,
        action: str,
        extra_error: dict[str, Any] | None = None,
) -> None:
    expected_error: dict[str, Any] = {
        'code': code.value,
        'retryable': False,
        'action': action,
    }
    if extra_error is not None:
        expected_error.update(extra_error)

    assert response.status_code == status
    assert response.headers['Content-Type'] == 'application/json'
    assert response.headers['Cache-Control'] == 'no-store'
    assert 'rotki-log-result' not in response.headers
    assert response.json() == {
        'result': None,
        'message': message,
        'error': expected_error,
    }


def _assert_incompatible_protocol(response: requests.Response) -> None:
    _assert_typed_error(
        response,
        status=HTTPStatus.UPGRADE_REQUIRED,
        code=HttpErrorCode.INCOMPATIBLE_PROTOCOL,
        message='The Companion Protocol is incompatible',
        action='upgrade_engine',
        extra_error={'supported_protocol_versions': [1]},
    )


def _assert_resource_not_found(response: requests.Response) -> None:
    _assert_typed_error(
        response,
        status=HTTPStatus.NOT_FOUND,
        code=HttpErrorCode.RESOURCE_NOT_FOUND,
        message='The resource was not found',
        action='none',
    )
    assert 'Allow' not in response.headers
    assert 'Location' not in response.headers


@contextmanager
def _enabled_session_gate(api_server: APIServer) -> Iterator[None]:
    """Enable the Docker cookie gate on the already-running test server."""
    rest_api = api_server.rest_api
    previous_key = rest_api.session_key
    previous_store = rest_api.session_store
    assert previous_key is None
    assert previous_store is None

    store = SessionStore(
        db_path=rest_api.rotkehlchen.data_dir / GLOBALDIR_NAME / SESSION_DB_NAME,
        session_key=_TEST_SESSION_KEY,
    )
    rest_api.session_key = _TEST_SESSION_KEY
    rest_api.session_store = store
    store.on_sessions_changed = rest_api._disconnect_revoked_websockets
    try:
        yield
    finally:
        store.close()
        rest_api.session_key = previous_key
        rest_api.session_store = previous_store


def test_companion_routes_are_not_added_to_legacy_cookie_allowlist() -> None:
    assert all(
        not rule.startswith(f'{COMPANION_PATH_PREFIX}/')
        for rule, _method in APIServer._cookie_less_rules  # pylint: disable=protected-access
    )


def test_protocol_discovery_is_public_and_does_not_touch_control_store(
        rotkehlchen_api_server: APIServer,
        monkeypatch: MonkeyPatch,
) -> None:
    service = rotkehlchen_api_server.rest_api.companion_service

    def fail_on_store_lookup(*_args: object, **_kwargs: object) -> None:
        raise AssertionError('public discovery consulted the Companion Control Store')

    monkeypatch.setattr(service, 'with_control_store', fail_on_store_lookup)
    headers = {
        PROTOCOL_HEADER: 'invalid-public-header',
        'Authorization': 'Bearer public-authorization-secret',
        MCP_BACKEND_PROOF_HEADER: 'public-mcp-proof-secret',
        'Cookie': f'{SESSION_COOKIE_NAME}=public-cookie-secret',
    }
    with _enabled_session_gate(rotkehlchen_api_server):
        response = requests.get(
            _companion_url(rotkehlchen_api_server, '/protocol'),
            headers=headers,
        )

    assert response.status_code == HTTPStatus.OK
    assert response.headers['Content-Type'] == 'application/json'
    assert response.headers['Cache-Control'] == 'no-store'
    assert 'rotki-log-result' not in response.headers
    assert response.json() == {
        'result': {
            'supported_protocol_versions': [1],
            'capabilities': {},
        },
        'message': '',
    }


def test_recognized_unavailable_routes_fail_as_incompatible_before_legacy_auth(
        rotkehlchen_api_server: APIServer,
) -> None:
    with _enabled_session_gate(rotkehlchen_api_server):
        for policy in _NON_PUBLIC_POLICIES:
            for header_case, headers in _PROTOCOL_HEADER_CASES:
                response = requests.request(
                    method=policy.method,
                    url=_companion_url(rotkehlchen_api_server, _concrete_path(policy)),
                    headers={
                        'Authorization': 'Bearer wrong-realm-secret',
                        MCP_BACKEND_PROOF_HEADER: 'wrong-mcp-proof-secret',
                        'Content-Type': 'application/json',
                        **headers,
                    },
                    json={'request_secret': 'unavailable-route-body-secret'},
                    allow_redirects=False,
                )
                try:
                    _assert_incompatible_protocol(response)
                except AssertionError as error:
                    raise AssertionError(
                        f'Failed {policy.route_id} with {header_case} protocol header',
                    ) from error


def test_unlisted_routes_methods_and_trailing_slash_are_typed_not_found(
        rotkehlchen_api_server: APIServer,
) -> None:
    with _enabled_session_gate(rotkehlchen_api_server):
        for method, path in _UNLISTED_ROUTE_CASES:
            response = requests.request(
                method=method,
                url=_companion_url(rotkehlchen_api_server, path),
                headers={
                    PROTOCOL_HEADER: '1',
                    'Authorization': 'Bearer unlisted-route-secret',
                },
                allow_redirects=False,
            )
            try:
                _assert_resource_not_found(response)
            except AssertionError as error:
                raise AssertionError(f'Failed unlisted route {method} {path}') from error


def test_protocol_head_is_typed_not_found_without_allow_or_redirect(
        rotkehlchen_api_server: APIServer,
) -> None:
    """HEAD has no response body on the wire, so only its typed response metadata is visible."""
    with _enabled_session_gate(rotkehlchen_api_server):
        response = requests.head(
            _companion_url(rotkehlchen_api_server, '/protocol'),
            headers={PROTOCOL_HEADER: '1'},
            allow_redirects=False,
        )

    assert response.status_code == HTTPStatus.NOT_FOUND
    assert response.headers['Content-Type'] == 'application/json'
    assert response.headers['Cache-Control'] == 'no-store'
    assert 'Allow' not in response.headers
    assert 'Location' not in response.headers
    assert 'rotki-log-result' not in response.headers


def test_encoded_companion_paths_fail_closed_before_flask_normalization(
        rotkehlchen_api_server: APIServer,
) -> None:
    """Send exact request-target bytes; requests normalizes unreserved percent escapes."""
    parsed = urlsplit(_companion_url(rotkehlchen_api_server, '/protocol'))
    assert parsed.hostname is not None and parsed.port is not None
    connection = HTTPConnection(parsed.hostname, parsed.port)
    try:
        for method, raw_path in (
            ('GET', '/api/1/companion%2fprotocol'),
            ('GET', '/api/1/companion/%70rotocol'),
            ('DELETE', '/api/1/companion/pairings/encoded%2fsegment'),
            ('GET', '/api//1/companion/protocol'),
            ('GET', '/api/1//companion/protocol'),
            ('GET', '/api/1/companion//protocol'),
        ):
            connection.request(method, raw_path, headers={PROTOCOL_HEADER: '1'})
            response = connection.getresponse()
            payload = json.loads(response.read())
            assert response.status == HTTPStatus.NOT_FOUND, raw_path
            assert response.getheader('Allow') is None
            assert response.getheader('Location') is None
            assert payload['error']['code'] == HttpErrorCode.RESOURCE_NOT_FOUND.value
    finally:
        connection.close()


def test_protocol_header_wsgi_alias_is_rejected_at_the_asgi_boundary(
        rotkehlchen_api_server: APIServer,
        monkeypatch: MonkeyPatch,
) -> None:
    observed_values: list[tuple[str, ...]] = []
    original_validator = server_module.validate_protocol_header

    def record_validation(values: tuple[str, ...]) -> int:
        observed_values.append(values)
        return original_validator(values)

    monkeypatch.setattr(server_module, 'validate_protocol_header', record_validation)
    parsed = urlsplit(_companion_url(rotkehlchen_api_server, '/pairings/value'))
    assert parsed.hostname is not None and parsed.port is not None
    connection = HTTPConnection(parsed.hostname, parsed.port)
    try:
        connection.request('DELETE', parsed.path, headers={PROTOCOL_HEADER: '1'})
        canonical_response = connection.getresponse()
        canonical_payload = json.loads(canonical_response.read())
        connection.request('DELETE', parsed.path, headers={
            'Rotki_Companion_Protocol': '1',
        })
        alias_response = connection.getresponse()
        alias_payload = json.loads(alias_response.read())
    finally:
        connection.close()

    assert canonical_response.status == alias_response.status == HTTPStatus.UPGRADE_REQUIRED
    assert canonical_payload['error']['code'] == alias_payload['error']['code'] == (
        HttpErrorCode.INCOMPATIBLE_PROTOCOL.value
    )
    assert observed_values == [('1',)]


def test_companion_request_and_response_logs_are_redacted(
        rotkehlchen_api_server: APIServer,
        monkeypatch: MonkeyPatch,
        caplog: LogCaptureFixture,
) -> None:
    path_secret = 'path-secret-a8d3bc7e'
    query_secret = 'query-secret-47d9f2ce'
    header_secret = 'header-secret-01dfe6a7'
    body_secret = 'body-secret-6d23ab91'
    response_secret = 'response-secret-c0f4781d'
    method_secret = 'METHOD-SECRET-91D3C5A7'
    server_logger = 'rotkehlchen.api.server'
    caplog.set_level(logging.DEBUG, logger=server_logger)

    response = requests.delete(
        _companion_url(rotkehlchen_api_server, f'/pairings/{path_secret}'),
        params={'private_query': query_secret},
        headers={
            PROTOCOL_HEADER: '1',
            'Authorization': f'Bearer {header_secret}',
        },
        json={'private_body': body_secret},
    )
    _assert_incompatible_protocol(response)

    parsed = urlsplit(_companion_url(rotkehlchen_api_server, '/protocol'))
    assert parsed.hostname is not None and parsed.port is not None
    connection = HTTPConnection(parsed.hostname, parsed.port)
    try:
        for malformed_path in (
            f'/api/1/companion;opaque/{path_secret}',
            f'/api/1/companion.private/{path_secret}',
            f'/API/1/COMPANION/pairings/{path_secret}',
            f'/%41PI/1/%43OMPANION/pairings/{path_secret}',
        ):
            connection.request(
                'POST',
                f'{malformed_path}?private_query={query_secret}',
                body=json.dumps({'private_body': body_secret}),
                headers={
                    'Authorization': f'Bearer {header_secret}',
                    'Content-Type': 'application/json',
                },
            )
            malformed_response = connection.getresponse()
            malformed_payload = json.loads(malformed_response.read())
            assert malformed_response.status == HTTPStatus.NOT_FOUND, malformed_path
            assert malformed_response.getheader('Allow') is None
            assert malformed_response.getheader('Location') is None
            assert malformed_payload['error']['code'] == HttpErrorCode.RESOURCE_NOT_FOUND.value
        connection.request(method_secret, '/api/1/companion/protocol')
        method_response = connection.getresponse()
        method_payload = json.loads(method_response.read())
        assert method_response.status == HTTPStatus.NOT_FOUND
        assert method_payload['error']['code'] == HttpErrorCode.RESOURCE_NOT_FOUND.value
    finally:
        connection.close()

    service = rotkehlchen_api_server.rest_api.companion_service
    monkeypatch.setattr(service, 'capabilities', lambda: {response_secret: 1})
    discovery = requests.get(_companion_url(rotkehlchen_api_server, '/protocol'))
    assert discovery.status_code == HTTPStatus.OK
    assert response_secret in discovery.text

    server_output = '\n'.join(
        record.getMessage() for record in caplog.records if record.name == server_logger
    )
    for secret in (
        path_secret,
        query_secret,
        header_secret,
        body_secret,
        response_secret,
        method_secret,
    ):
        assert secret not in caplog.text
    assert 'method=DELETE' in server_output
    assert 'route=/pairings/{pairing_id}' in server_output
    assert 'status_code=426' in server_output
    assert 'method=GET' in server_output
    assert 'route=/protocol' in server_output
    assert 'status_code=200' in server_output
    assert 'method=UNLISTED' in server_output


def test_companion_exception_is_redacted_from_response_and_logs(
        rotkehlchen_api_server: APIServer,
        monkeypatch: MonkeyPatch,
        caplog: LogCaptureFixture,
) -> None:
    exception_secret = 'exception-secret-f13c75e9'
    server_logger = 'rotkehlchen.api.server'
    caplog.set_level(logging.DEBUG, logger=server_logger)

    def raise_secret_exception() -> dict[str, int]:
        raise RuntimeError(exception_secret)

    monkeypatch.setattr(
        rotkehlchen_api_server.rest_api.companion_service,
        'capabilities',
        raise_secret_exception,
    )
    response = requests.get(_companion_url(rotkehlchen_api_server, '/protocol'))

    _assert_typed_error(
        response,
        status=HTTPStatus.INTERNAL_SERVER_ERROR,
        code=HttpErrorCode.UNEXPECTED_ENGINE_ERROR,
        message='An unexpected Engine error occurred',
        action='none',
    )
    server_output = '\n'.join(
        record.getMessage() for record in caplog.records if record.name == server_logger
    )
    assert exception_secret not in response.text
    assert exception_secret not in caplog.text
    assert 'Traceback' not in caplog.text
    assert 'method=GET' in server_output
    assert 'route=/protocol' in server_output
    assert 'status_code=500' in server_output
