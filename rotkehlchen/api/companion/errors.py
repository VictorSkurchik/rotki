"""Fixed, redacted HTTP envelopes for Companion resources."""

from __future__ import annotations

from http import HTTPStatus
from typing import TYPE_CHECKING, Any, Final

from rotkehlchen.api.companion.generated_protocol import (
    HTTP_ERROR_SPECS,
    SUPPORTED_PROTOCOL_VERSIONS,
    HttpErrorCode,
)
from rotkehlchen.api.rest import api_response

if TYPE_CHECKING:
    from collections.abc import Mapping

    from flask import Response

ERROR_MESSAGES: Final = {
    HttpErrorCode.INVALID_REQUEST: 'The request is invalid',
    HttpErrorCode.ACCESS_SESSION_UNAVAILABLE: 'The Access Session is unavailable',
    HttpErrorCode.FULL_CLIENT_AUTH_REQUIRED: 'Full Client authentication is required',
    HttpErrorCode.NOT_AUTHORIZED: 'The Device Session is not authorized',
    HttpErrorCode.SCOPE_DENIED: 'The Companion scope does not allow this request',
    HttpErrorCode.RESOURCE_NOT_FOUND: 'The resource was not found',
    HttpErrorCode.HISTORY_CHANGED: 'The history changed',
    HttpErrorCode.IDEMPOTENCY_CONFLICT: (
        'The idempotency key was already used for another request'
    ),
    HttpErrorCode.NO_REFRESHABLE_SOURCES: 'No portfolio source can be refreshed',
    HttpErrorCode.PROFILE_MISMATCH: 'The bound Profile is not open',
    HttpErrorCode.REFRESH_CONFLICT: 'A conflicting refresh is already active',
    HttpErrorCode.SOURCE_DISABLED: 'The portfolio source is disabled',
    HttpErrorCode.CHALLENGE_UNAVAILABLE: 'The challenge is unavailable',
    HttpErrorCode.HISTORY_CURSOR_UNAVAILABLE: 'The history cursor is unavailable',
    HttpErrorCode.PAIRING_UNAVAILABLE: 'The Pairing is unavailable',
    HttpErrorCode.LOCKED_ENGINE: 'The Engine is locked',
    HttpErrorCode.INCOMPATIBLE_PROTOCOL: 'The Companion Protocol is incompatible',
    HttpErrorCode.RATE_LIMITED: 'The request was rate limited',
    HttpErrorCode.UNEXPECTED_ENGINE_ERROR: 'An unexpected Engine error occurred',
    HttpErrorCode.SNAPSHOT_UNAVAILABLE: 'The portfolio Snapshot is unavailable',
}
_SAFE_EXTRA_ERROR_KEYS: Final = frozenset({'supported_protocol_versions'})


def _safe_extra_error(extra_error: Mapping[str, object] | None) -> dict[str, object]:
    if extra_error is None:
        return {}
    if type(extra_error) is not dict:
        raise ValueError('Unsafe Companion error metadata')
    if not extra_error:
        return {}
    if set(extra_error) - _SAFE_EXTRA_ERROR_KEYS:
        raise ValueError('Unsafe Companion error metadata')
    versions = extra_error.get('supported_protocol_versions')
    if (
            type(versions) not in (list, tuple) or
            not versions or
            any(type(version) is not int or version < 1 for version in versions) or
            tuple(versions) != tuple(sorted(set(versions)))
    ):
        raise ValueError('Invalid supported Companion Protocol versions')
    return {'supported_protocol_versions': list(versions)}


def companion_success_payload(result: object) -> dict[str, Any]:
    return {'result': result, 'message': ''}


def companion_error_payload(
        code: HttpErrorCode,
        *,
        extra_error: Mapping[str, object] | None = None,
) -> dict[str, Any]:
    """Build a typed error payload from closed, non-sensitive inputs only."""
    if type(code) is not HttpErrorCode:
        raise ValueError('Unknown Companion error code')
    spec = HTTP_ERROR_SPECS[code]
    safe_extra_error = _safe_extra_error(extra_error)
    if safe_extra_error and code is not HttpErrorCode.INCOMPATIBLE_PROTOCOL:
        raise ValueError('Companion error metadata is invalid for this code')
    typed_error: dict[str, object] = {
        'code': code.value,
        'retryable': spec.retryable,
        'action': spec.action.value,
    }
    typed_error.update(safe_extra_error)
    return {
        'result': None,
        'message': ERROR_MESSAGES[code],
        'error': typed_error,
    }


def _apply_response_policy(response: Response, *, no_store: bool) -> Response:
    if type(no_store) is not bool:
        raise ValueError('Invalid Companion response cache policy')
    if no_store:
        response.headers['Cache-Control'] = 'no-store'
    return response


def companion_success_response(
        result: object,
        *,
        status_code: HTTPStatus | int = HTTPStatus.OK,
        no_store: bool = True,
) -> Response:
    """Return the existing rotki envelope with response-body logging disabled."""
    try:
        normalized_status = HTTPStatus(status_code)
    except (TypeError, ValueError):
        raise ValueError('Invalid Companion success status') from None
    if not 200 <= normalized_status < 300:
        raise ValueError('Invalid Companion success status')
    return _apply_response_policy(
        api_response(
            companion_success_payload(result),
            status_code=normalized_status,
            log_result=False,
        ),
        no_store=no_store,
    )


def companion_error_response(
        code: HttpErrorCode,
        *,
        extra_error: Mapping[str, object] | None = None,
        retry_after_seconds: int | None = None,
        no_store: bool = True,
) -> Response:
    """Return the fixture-defined status/error tuple without accepting raw diagnostics."""
    if type(code) is not HttpErrorCode:
        raise ValueError('Unknown Companion error code')
    if code is HttpErrorCode.INCOMPATIBLE_PROTOCOL and extra_error is None:
        extra_error = {'supported_protocol_versions': SUPPORTED_PROTOCOL_VERSIONS}
    if code is HttpErrorCode.RATE_LIMITED:
        if type(retry_after_seconds) is not int or retry_after_seconds < 0:
            raise ValueError('Rate-limited responses require Retry-After seconds')
    elif retry_after_seconds is not None:
        raise ValueError('Retry-After is only valid for rate-limited responses')

    spec = HTTP_ERROR_SPECS[code]
    response = api_response(
        companion_error_payload(code, extra_error=extra_error),
        status_code=HTTPStatus(spec.status),
        log_result=False,
    )
    if retry_after_seconds is not None:
        response.headers['Retry-After'] = str(retry_after_seconds)
    return _apply_response_policy(response, no_store=no_store)
