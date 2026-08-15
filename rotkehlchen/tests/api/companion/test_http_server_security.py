import json
import logging
from http import HTTPStatus
from typing import TYPE_CHECKING
from unittest.mock import Mock, patch

import pytest
import requests
from marshmallow.exceptions import ValidationError
from werkzeug.exceptions import HTTPException

from rotkehlchen.api.server import handle_request_parsing_error
from rotkehlchen.api.session_token import SESSION_COOKIE_NAME

if TYPE_CHECKING:
    from rotkehlchen.api.server import APIServer


SESSION_KEY = b'companion-http-boundary-test-key'


def _companion_url(api_server: APIServer, path: str) -> str:
    server_name = api_server.flask_app.config['SERVER_NAME']
    return f'http://{server_name}{api_server._api_prefix}/companion{path}'


def _assert_companion_error(
        response: requests.Response,
        *,
        status: HTTPStatus,
        code: str,
) -> None:
    assert response.status_code == status
    payload = response.json()
    assert set(payload) == {'result', 'message', 'error'}
    assert payload['result'] is None
    assert payload['message'] == {
        'resource_not_found': 'Resource not found',
        'unexpected_engine_error': 'Unexpected Engine error',
    }[code]
    assert payload['error'] == {
        'code': code,
        'retryable': False,
        'action': 'none',
    }
    assert response.headers['Cache-Control'] == 'no-store'
    assert 'Allow' not in response.headers
    assert 'Location' not in response.headers


@pytest.mark.parametrize(('method', 'path'), [
    ('GET', '/not-a-resource'),
    ('PUT', '/protocol'),
    ('GET', '/challenges'),
    ('POST', '/challenges'),  # Classified, but deliberately still unadvertised in this tranche.
    ('GET', '/protocol/'),
    ('DELETE', '/device-sessions/current/'),
    ('OPTIONS', '/access-sessions'),
])
def test_unadvertised_and_unlisted_companion_requests_are_404_before_cookie_or_mcp(
        rotkehlchen_api_server: APIServer,
        method: str,
        path: str,
) -> None:
    rest_api = rotkehlchen_api_server.rest_api
    previous_key = rest_api.session_key
    previous_store = rest_api.session_store
    rest_api.session_key = SESSION_KEY
    rest_api.session_store = Mock()
    try:
        with (
            patch(
                'rotkehlchen.api.server.read_session_token',
                side_effect=AssertionError('cookie authority must not run'),
            ) as read_cookie,
            patch(
                'rotkehlchen.api.server._read_internal_mcp_token',
                side_effect=AssertionError('MCP authority must not run'),
            ) as read_mcp,
        ):
            response = requests.request(
                method,
                _companion_url(rotkehlchen_api_server, path),
                headers={
                    'Authorization': 'Bearer irrelevant-foreign-credential',
                    'Cookie': f'{SESSION_COOKIE_NAME}=irrelevant-browser-cookie',
                },
                timeout=10,
            )

        _assert_companion_error(
            response,
            status=HTTPStatus.NOT_FOUND,
            code='resource_not_found',
        )
        read_cookie.assert_not_called()
        read_mcp.assert_not_called()
        assert rest_api.session_store.mock_calls == []
    finally:
        rest_api.session_key = previous_key
        rest_api.session_store = previous_store


def test_head_companion_request_is_404_before_cookie_or_mcp_without_response_metadata(
        rotkehlchen_api_server: APIServer,
) -> None:
    rest_api = rotkehlchen_api_server.rest_api
    previous_key = rest_api.session_key
    previous_store = rest_api.session_store
    rest_api.session_key = SESSION_KEY
    rest_api.session_store = Mock()
    try:
        with (
            patch(
                'rotkehlchen.api.server.read_session_token',
                side_effect=AssertionError('cookie authority must not run'),
            ) as read_cookie,
            patch(
                'rotkehlchen.api.server._read_internal_mcp_token',
                side_effect=AssertionError('MCP authority must not run'),
            ) as read_mcp,
        ):
            response = requests.head(
                _companion_url(rotkehlchen_api_server, '/protocol'),
                headers={
                    'Authorization': 'Bearer irrelevant-foreign-credential',
                    'Cookie': f'{SESSION_COOKIE_NAME}=irrelevant-browser-cookie',
                },
                timeout=10,
            )

        assert response.status_code == HTTPStatus.NOT_FOUND
        assert response.headers['Cache-Control'] == 'no-store'
        assert 'Allow' not in response.headers
        assert 'Location' not in response.headers
        read_cookie.assert_not_called()
        read_mcp.assert_not_called()
        assert rest_api.session_store.mock_calls == []
    finally:
        rest_api.session_key = previous_key
        rest_api.session_store = previous_store


def test_companion_namespace_redacts_every_seeded_request_surface(
        rotkehlchen_api_server: APIServer,
        caplog: pytest.LogCaptureFixture,
) -> None:
    secrets = {
        'path': 'seeded-path-device-session-id',
        'query': 'seeded-query-pairing-credential',
        'body': 'seeded-body-profile-marker',
        'authorization': 'seeded-access-bearer',
        'cookie': 'seeded-browser-cookie',
        'origin': 'https://seeded-engine-origin.example',
        'source': '198.51.100.231',
    }
    caplog.set_level(logging.DEBUG, logger='rotkehlchen.api.server')

    response = requests.post(
        _companion_url(
            rotkehlchen_api_server,
            f'/{secrets["path"]}?value={secrets["query"]}',
        ),
        json={'device_session_id': secrets['body']},
        headers={
            'Authorization': f'Bearer {secrets["authorization"]}',
            'Cookie': f'{SESSION_COOKIE_NAME}={secrets["cookie"]}',
            'X-Rotki-Companion-Origin': secrets['origin'],
            'X-Rotki-Companion-Source': secrets['source'],
        },
        timeout=10,
    )

    _assert_companion_error(
        response,
        status=HTTPStatus.NOT_FOUND,
        code='resource_not_found',
    )
    observed = '\n'.join((
        caplog.text,
        response.text,
        repr(dict(response.headers)),
    ))
    for surface, secret in secrets.items():
        assert secret not in observed, f'{surface} leaked from the Companion namespace'


def test_companion_dispatch_failure_returns_fixed_redacted_500(
        rotkehlchen_api_server: APIServer,
        caplog: pytest.LogCaptureFixture,
) -> None:
    secrets = {
        'path': 'seeded-exception-path-identifier',
        'query': 'seeded-exception-query-token',
        'body': 'seeded-exception-body-profile',
        'authorization': 'seeded-exception-access-bearer',
        'exception': 'seeded-exception-message-and-traceback',
    }
    caplog.set_level(logging.DEBUG, logger='rotkehlchen.api.server')

    with patch(
        'rotkehlchen.api.server.classify_companion_route',
        side_effect=RuntimeError(secrets['exception']),
    ):
        response = requests.patch(
            _companion_url(
                rotkehlchen_api_server,
                f'/{secrets["path"]}?value={secrets["query"]}',
            ),
            json={'device_session_id': secrets['body']},
            headers={
                'Authorization': f'Bearer {secrets["authorization"]}',
                'Referer': f'https://example.invalid/{secrets["exception"]}',
                'User-Agent': secrets['exception'],
            },
            timeout=10,
        )

    _assert_companion_error(
        response,
        status=HTTPStatus.INTERNAL_SERVER_ERROR,
        code='unexpected_engine_error',
    )
    observed = '\n'.join((
        caplog.text,
        response.text,
        repr(dict(response.headers)),
    ))
    for surface, secret in secrets.items():
        assert secret not in observed, f'{surface} leaked from a Companion failure'
    assert 'Traceback (most recent call last)' not in caplog.text
    assert 'Unhandled exception when processing Companion endpoint' in caplog.text


def test_companion_parser_failure_returns_fixed_redacted_400(
        rotkehlchen_api_server: APIServer,
        caplog: pytest.LogCaptureFixture,
) -> None:
    secret = 'seeded-parser-validation-message-and-request-body'
    caplog.set_level(logging.DEBUG, logger='rotkehlchen.api.server')
    app = rotkehlchen_api_server.flask_app
    with app.test_request_context(
        f'{rotkehlchen_api_server._api_prefix}/companion/challenges?token={secret}',
        method='POST',
        data=secret,
        headers={
            'Authorization': f'Bearer {secret}',
            'Referer': f'https://example.invalid/{secret}',
            'User-Agent': secret,
        },
    ):
        with pytest.raises(HTTPException) as error:
            handle_request_parsing_error(
                ValidationError({'json': [secret]}),
                Mock(),
                Mock(),
                None,
                None,
            )
        response = error.value.get_response()

    assert response.status_code == HTTPStatus.BAD_REQUEST
    assert json.loads(response.get_data(as_text=True)) == {
        'result': None,
        'message': 'Invalid request',
        'error': {
            'code': 'invalid_request',
            'retryable': False,
            'action': 'none',
        },
    }
    assert response.headers['Cache-Control'] == 'no-store'
    assert 'Allow' not in response.headers
    assert 'Location' not in response.headers
    observed = '\n'.join((
        caplog.text,
        response.get_data(as_text=True),
        repr(dict(response.headers)),
    ))
    assert secret not in observed


def test_non_companion_parser_failure_keeps_the_legacy_envelope(
        rotkehlchen_api_server: APIServer,
) -> None:
    message = 'legacy-validation-message'
    app = rotkehlchen_api_server.flask_app
    with app.test_request_context(
        f'{rotkehlchen_api_server._api_prefix}/ping',
        method='POST',
    ):
        with pytest.raises(HTTPException) as error:
            handle_request_parsing_error(
                ValidationError({'json': [message]}),
                Mock(),
                Mock(),
                None,
                None,
            )
        response = error.value.get_response()

    assert response.status_code == HTTPStatus.BAD_REQUEST
    assert json.loads(response.get_data(as_text=True)) == {
        'result': None,
        'message': f'["{message}"]',
    }
