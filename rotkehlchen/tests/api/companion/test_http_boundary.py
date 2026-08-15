from ipaddress import ip_address

import pytest
from werkzeug.datastructures import Headers

from rotkehlchen.api.companion.request_boundary import (
    COMPANION_ORIGIN_HEADER,
    COMPANION_SOURCE_HEADER,
    InvalidTrustedCompanionRequestContext,
    is_companion_request_path,
    parse_trusted_companion_request_context,
)
from rotkehlchen.api.companion.types import CanonicalEngineOrigin

ORIGIN = 'https://rotki.example'
SOURCE = '203.0.113.9'


def _headers(origin: str = ORIGIN, source: str = SOURCE) -> Headers:
    return Headers([
        (COMPANION_ORIGIN_HEADER, origin),
        (COMPANION_SOURCE_HEADER, source),
    ])


@pytest.mark.parametrize('origin', [
    'https://rotki.example',
    'https://192.168.1.10:8443',
    'https://[2001:db8::1]:8443',
])
def test_trusted_request_context_accepts_canonical_fixed_origins(origin: str) -> None:
    context = parse_trusted_companion_request_context(_headers(origin=origin))
    assert context is not None
    assert context.engine_origin == CanonicalEngineOrigin(origin)
    assert context.source_address == ip_address(SOURCE)


@pytest.mark.parametrize('source', [
    '203.0.113.9',
    '192.168.1.10',
    '2001:db8::1',
    '::1',
])
def test_trusted_request_context_accepts_only_canonical_ip_source_values(source: str) -> None:
    context = parse_trusted_companion_request_context(_headers(source=source))
    assert context is not None
    assert context.source_address == ip_address(source)


def test_trusted_request_context_is_absent_only_when_both_internal_headers_are_absent() -> None:
    assert parse_trusted_companion_request_context(Headers()) is None
    # Ordinary forwarding and authority headers never substitute for Starling's
    # private pair, even when their values look individually valid.
    assert parse_trusted_companion_request_context(Headers([
        ('Host', 'rotki.example'),
        ('X-Forwarded-Host', 'rotki.example'),
        ('X-Forwarded-Proto', 'https'),
        ('X-Forwarded-For', SOURCE),
        ('X-Real-IP', SOURCE),
    ])) is None


@pytest.mark.parametrize('headers', [
    Headers([(COMPANION_ORIGIN_HEADER, ORIGIN)]),
    Headers([(COMPANION_SOURCE_HEADER, SOURCE)]),
    Headers([
        (COMPANION_ORIGIN_HEADER, ''),
        (COMPANION_SOURCE_HEADER, SOURCE),
    ]),
    Headers([
        (COMPANION_ORIGIN_HEADER, ORIGIN),
        (COMPANION_SOURCE_HEADER, ''),
    ]),
    Headers([
        (COMPANION_ORIGIN_HEADER, ORIGIN),
        (COMPANION_ORIGIN_HEADER, ORIGIN),
        (COMPANION_SOURCE_HEADER, SOURCE),
    ]),
    Headers([
        (COMPANION_ORIGIN_HEADER, ORIGIN),
        (COMPANION_ORIGIN_HEADER, 'https://other.example'),
        (COMPANION_SOURCE_HEADER, SOURCE),
    ]),
    Headers([
        (COMPANION_ORIGIN_HEADER, ORIGIN),
        (COMPANION_SOURCE_HEADER, SOURCE),
        (COMPANION_SOURCE_HEADER, '198.51.100.7'),
    ]),
    Headers([
        (COMPANION_ORIGIN_HEADER, f'{ORIGIN}, https://other.example'),
        (COMPANION_SOURCE_HEADER, SOURCE),
    ]),
    Headers([
        (COMPANION_ORIGIN_HEADER, ORIGIN),
        (COMPANION_SOURCE_HEADER, f'{SOURCE}, 198.51.100.7'),
    ]),
])
def test_trusted_request_context_rejects_incomplete_duplicate_or_combined_headers(
        headers: Headers,
) -> None:
    with pytest.raises(InvalidTrustedCompanionRequestContext) as error:
        parse_trusted_companion_request_context(headers)
    assert str(error.value) == 'Invalid trusted Companion request context'


@pytest.mark.parametrize('origin', [
    'http://rotki.example',
    'HTTPS://rotki.example',
    'https://rötki.example',
    'https://bad_host.example',
    'https://rotki.example.',
    'https://user@rotki.example',
    'https://rotki.example/path',
    'https://rotki.example?query',
    'https://rotki.example#fragment',
    'https://127.000.000.001',
    'https://[not-an-ip]',
    'https://[2001:0db8::1]',
    'https://[2001:db8::1]opaque',
    'https://[fe80::1%25eth0]',
    'https://rotki.example:443',
    'https://rotki.example:0443',
    ' https://rotki.example',
    'https://rotki.example ',
])
def test_trusted_request_context_wraps_every_noncanonical_origin_as_fixed_error(
        origin: str,
) -> None:
    with pytest.raises(InvalidTrustedCompanionRequestContext) as error:
        parse_trusted_companion_request_context(_headers(origin=origin))
    assert str(error.value) == 'Invalid trusted Companion request context'
    assert origin not in str(error.value)
    assert origin not in repr(error.value)


@pytest.mark.parametrize('source', [
    '203.0.113.9:443',
    '203.0.113.009',
    '[2001:db8::1]',
    '2001:0db8::1',
    '2001:DB8::1',
    'fe80::1%eth0',
    '::ffff:192.0.2.1',
    '203.0.113.9, 198.51.100.7',
    ' 203.0.113.9',
    '203.0.113.9 ',
    'not-an-address',
])
def test_trusted_request_context_rejects_noncanonical_or_chained_source_values(
        source: str,
) -> None:
    with pytest.raises(InvalidTrustedCompanionRequestContext) as error:
        parse_trusted_companion_request_context(_headers(source=source))
    assert str(error.value) == 'Invalid trusted Companion request context'
    assert source not in str(error.value)
    assert source not in repr(error.value)


def test_trusted_request_context_has_one_fixed_redacted_representation() -> None:
    first = parse_trusted_companion_request_context(_headers())
    second = parse_trusted_companion_request_context(_headers(
        origin='https://other.example:8443',
        source='2001:db8::2',
    ))
    assert first is not None and second is not None
    assert repr(first) == '<TrustedCompanionRequestContext redacted>'
    assert repr(second) == repr(first)
    for secret in (ORIGIN, SOURCE, 'https://other.example:8443', '2001:db8::2'):
        assert secret not in repr(first)
        assert secret not in repr(second)


@pytest.mark.parametrize('path', [
    '/api/1/companion',
    '/api/1/companion/',
    '/api/1/companion/protocol',
    '/api/1/companion/device-sessions/current',
])
def test_companion_namespace_path_matches_only_the_exact_segment(path: str) -> None:
    assert is_companion_request_path(path) is True


@pytest.mark.parametrize('path', [
    '',
    '/',
    '/api/1/companionish',
    '/api/1/companionish/protocol',
    '/api/10/companion/protocol',
    '/api/1/Companion/protocol',
    '/api/1/not-companion',
])
def test_non_companion_paths_do_not_enter_the_special_boundary(path: str) -> None:
    assert is_companion_request_path(path) is False
