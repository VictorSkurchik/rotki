"""HTTP adapters for the versioned Companion namespace."""

from __future__ import annotations

from typing import TYPE_CHECKING, ClassVar

from rotkehlchen.api.companion.errors import (
    companion_error_response,
    companion_success_response,
)
from rotkehlchen.api.companion.generated_protocol import (
    SUPPORTED_PROTOCOL_VERSIONS,
    HttpErrorCode,
)
from rotkehlchen.api.v1.common_resources import BaseMethodView

if TYPE_CHECKING:
    from flask import Response


class CompanionProtocolResource(BaseMethodView):
    """Public, credential-independent Companion capability discovery."""

    def get(self) -> Response:
        return companion_success_response({
            'supported_protocol_versions': list(SUPPORTED_PROTOCOL_VERSIONS),
            'capabilities': self.rest_api.companion_service.capabilities(),
        }, no_store=True)

    @staticmethod
    def head() -> Response:
        return companion_error_response(HttpErrorCode.RESOURCE_NOT_FOUND)

    @staticmethod
    def options() -> Response:
        return companion_error_response(HttpErrorCode.RESOURCE_NOT_FOUND)


class CompanionUnavailableResource(BaseMethodView):
    """Fail-closed adapter for recognized routes not implemented in this slice.

    Defining every verb keeps Flask from producing a redirect, 405, or ``Allow``
    header before the closed Companion route matrix can classify the request.
    """

    methods: ClassVar[frozenset[str]] = frozenset({
        'DELETE',
        'GET',
        'HEAD',
        'OPTIONS',
        'PATCH',
        'POST',
        'PUT',
    })

    def dispatch_request(self, **_kwargs: str) -> Response:
        # The raw-path dispatcher in ``before_request_callback`` classifies and
        # short-circuits every non-public Companion route. Never rematch Flask's
        # already percent-decoded path here: this defensive fallback stays closed
        # if that dispatcher is ever rearranged.
        return companion_error_response(HttpErrorCode.RESOURCE_NOT_FOUND)


__all__ = ['CompanionProtocolResource', 'CompanionUnavailableResource']
