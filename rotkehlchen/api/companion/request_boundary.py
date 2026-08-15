from dataclasses import dataclass
from ipaddress import IPv4Address, IPv6Address, ip_address
from typing import TYPE_CHECKING, Final

from rotkehlchen.api.companion.types import CanonicalEngineOrigin

if TYPE_CHECKING:
    from werkzeug.datastructures import Headers

COMPANION_API_PREFIX: Final = '/api/1/companion'
COMPANION_ORIGIN_HEADER: Final = 'X-Rotki-Companion-Origin'
COMPANION_SOURCE_HEADER: Final = 'X-Rotki-Companion-Source'


class InvalidTrustedCompanionRequestContext(ValueError):
    """Starling's private origin/source pair is absent in part or malformed."""

    def __init__(self) -> None:
        super().__init__('Invalid trusted Companion request context')


@dataclass(frozen=True, slots=True, repr=False)
class TrustedCompanionRequestContext:
    """Redacted system-trusted metadata supplied as one complete private pair."""

    engine_origin: CanonicalEngineOrigin
    source_address: IPv4Address | IPv6Address

    def __post_init__(self) -> None:
        if (
                type(self.engine_origin) is not CanonicalEngineOrigin or
                type(self.source_address) not in (IPv4Address, IPv6Address)
        ):
            raise InvalidTrustedCompanionRequestContext

    def __repr__(self) -> str:
        return '<TrustedCompanionRequestContext redacted>'


def parse_trusted_companion_request_context(
        headers: Headers,
) -> TrustedCompanionRequestContext | None:
    """Parse Starling's exact private pair; return ``None`` only when both are absent.

    Duplicate, partial, empty, and non-canonical values fail as one fixed error
    without reflecting either header. Authority trust is established by Starling;
    this adapter only validates the private representation handed to core.
    """
    origin_values = headers.getlist(COMPANION_ORIGIN_HEADER)
    source_values = headers.getlist(COMPANION_SOURCE_HEADER)
    if len(origin_values) == 0 and len(source_values) == 0:
        return None
    if len(origin_values) != 1 or len(source_values) != 1:
        raise InvalidTrustedCompanionRequestContext

    try:
        engine_origin = CanonicalEngineOrigin(origin_values[0])
        source_address = ip_address(source_values[0])
    except ValueError:
        raise InvalidTrustedCompanionRequestContext from None
    if (
            str(source_address) != source_values[0] or
            (
                type(source_address) is IPv6Address and
                (
                    source_address.ipv4_mapped is not None or
                    source_address.scope_id is not None
                )
            )
    ):
        raise InvalidTrustedCompanionRequestContext

    return TrustedCompanionRequestContext(
        engine_origin=engine_origin,
        source_address=source_address,
    )


def is_companion_request_path(path: str) -> bool:
    """Return whether Flask's decoded path is inside the exact Companion namespace."""
    return path == COMPANION_API_PREFIX or path.startswith(f'{COMPANION_API_PREFIX}/')
