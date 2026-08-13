import json
from http import HTTPStatus

import pytest
from flask import Flask

from rotkehlchen.api.companion.errors import (
    ERROR_MESSAGES,
    companion_error_payload,
    companion_error_response,
    companion_success_response,
)
from rotkehlchen.api.companion.generated_protocol import HTTP_ERROR_SPECS, HttpErrorCode


def test_every_error_payload_has_exact_generated_tuple_and_fixed_message() -> None:
    for code, spec in HTTP_ERROR_SPECS.items():
        payload = companion_error_payload(code)
        assert payload == {
            'result': None,
            'message': ERROR_MESSAGES[code],
            'error': {
                'code': code.value,
                'retryable': spec.retryable,
                'action': spec.action.value,
            },
        }


def test_error_metadata_is_closed_and_cannot_echo_raw_values() -> None:
    with pytest.raises(ValueError):
        companion_error_payload(
            HttpErrorCode.INVALID_REQUEST,
            extra_error={'diagnostic': 'credential or exception'},
        )
    with pytest.raises(ValueError):
        companion_error_payload(
            HttpErrorCode.INCOMPATIBLE_PROTOCOL,
            extra_error={'supported_protocol_versions': ['1']},
        )


def test_flask_responses_disable_body_logging_and_apply_cache_policy() -> None:
    app = Flask(__name__)
    with app.app_context():
        success = companion_success_response(
            {'secret': 'redacted-by-log-policy'},
            status_code=HTTPStatus.CREATED,
        )
        error = companion_error_response(HttpErrorCode.RESOURCE_NOT_FOUND)
        incompatible = companion_error_response(HttpErrorCode.INCOMPATIBLE_PROTOCOL)
        limited = companion_error_response(
            HttpErrorCode.RATE_LIMITED,
            retry_after_seconds=3,
        )

    assert success.status_code == HTTPStatus.CREATED
    assert json.loads(success.get_data()) == {
        'result': {'secret': 'redacted-by-log-policy'},
        'message': '',
    }
    assert success.headers['rotki-log-result'] == 'False'
    assert success.headers['Cache-Control'] == 'no-store'
    assert error.status_code == HTTPStatus.NOT_FOUND
    assert error.headers['rotki-log-result'] == 'False'
    assert error.headers['Cache-Control'] == 'no-store'
    assert json.loads(incompatible.get_data())['error']['supported_protocol_versions'] == [1]
    assert limited.headers['Retry-After'] == '3'


def test_rate_limit_retry_after_is_required_and_exclusive() -> None:
    app = Flask(__name__)
    with app.app_context():
        with pytest.raises(ValueError):
            companion_error_response(HttpErrorCode.RATE_LIMITED)
        with pytest.raises(ValueError):
            companion_error_response(
                HttpErrorCode.INVALID_REQUEST,
                retry_after_seconds=1,
            )
