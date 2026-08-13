"""Strict, redaction-safe wire codecs for the Companion Protocol."""

from __future__ import annotations

import base64
import binascii
import ipaddress
import json
import math
import re
from dataclasses import dataclass
from hashlib import sha256
from typing import Any, Final
from urllib.parse import urlsplit

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

from rotkehlchen.api.companion.generated_protocol import (
    DECODED_LENGTHS,
    ENCODED_LENGTHS,
)

BASE64URL_PATTERN: Final = re.compile(r'^[A-Za-z0-9_-]+$')
DEVICE_PROOF_DOMAIN: Final = b'rotki-companion-device-proof/v1'
MAX_JSON_BYTES: Final = 65_536
MAX_JSON_DEPTH: Final = 64
MAX_ENGINE_ORIGIN_LENGTH: Final = 2_048
P256_ORDER: Final = int(
    'FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551',
    16,
)
MAX_TIMESTAMP: Final = 2**63 - 1


class InvalidCompanionInput(ValueError):
    """A wire value does not satisfy the Companion Protocol contract."""

    def __init__(self) -> None:
        super().__init__('Invalid Companion Protocol input')


def encode_base64url(value: bytes) -> str:
    """Encode exact bytes as canonical unpadded Base64URL."""
    if type(value) is not bytes:
        raise InvalidCompanionInput
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')


def decode_base64url(
        value: object,
        *,
        decoded_length: int,
        encoded_length: int | None = None,
) -> bytes:
    """Decode one canonical unpadded Base64URL value of an exact length."""
    if type(decoded_length) is not int or decoded_length < 1:
        raise InvalidCompanionInput
    expected_encoded_length = (decoded_length * 8 + 5) // 6
    if encoded_length is not None and encoded_length != expected_encoded_length:
        raise InvalidCompanionInput
    if (
            type(value) is not str or
            len(value) != expected_encoded_length or
            BASE64URL_PATTERN.fullmatch(value) is None
    ):
        raise InvalidCompanionInput
    try:
        decoded = base64.b64decode(
            f'{value}{"=" * (-len(value) % 4)}',
            altchars=b'-_',
            validate=True,
        )
    except (binascii.Error, ValueError):
        raise InvalidCompanionInput from None
    if len(decoded) != decoded_length or encode_base64url(decoded) != value:
        raise InvalidCompanionInput
    return decoded


def _decode_named(value: object, name: str) -> bytes:
    return decode_base64url(
        value,
        decoded_length=DECODED_LENGTHS[name],
        encoded_length=ENCODED_LENGTHS[name],
    )


def decode_pairing_id(value: object) -> bytes:
    return _decode_named(value, 'pairing_id')


def decode_pairing_credential(value: object) -> bytes:
    return _decode_named(value, 'pairing_credential')


def decode_device_session_id(value: object) -> bytes:
    return _decode_named(value, 'device_session_id')


def decode_challenge_id(value: object) -> bytes:
    return _decode_named(value, 'challenge_id')


def decode_nonce(value: object) -> bytes:
    return _decode_named(value, 'nonce')


def decode_access_session_credential(value: object) -> bytes:
    return _decode_named(value, 'access_session_credential')


def decode_idempotency_key(value: object) -> bytes:
    return _decode_named(value, 'idempotency_key')


def decode_public_key(value: object) -> bytes:
    """Decode and validate an uncompressed X9.63 P-256 public key."""
    decoded = _decode_named(value, 'p256_public_key')
    if decoded[0] != 0x04:
        raise InvalidCompanionInput
    try:
        ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), decoded)
    except ValueError:
        raise InvalidCompanionInput from None
    return decoded


def validate_p1363_signature(value: object) -> bytes:
    """Validate exact 64-byte P-256 IEEE P1363 signature scalars."""
    if type(value) is not bytes or len(value) != DECODED_LENGTHS['p1363_signature']:
        raise InvalidCompanionInput
    r = int.from_bytes(value[:32], byteorder='big')
    s = int.from_bytes(value[32:], byteorder='big')
    if not 1 <= r < P256_ORDER or not 1 <= s < P256_ORDER:
        raise InvalidCompanionInput
    return value


def decode_p1363_signature(value: object) -> bytes:
    return validate_p1363_signature(_decode_named(value, 'p1363_signature'))


def p1363_signature_to_der(value: object) -> bytes:
    signature = validate_p1363_signature(value)
    return encode_dss_signature(
        int.from_bytes(signature[:32], byteorder='big'),
        int.from_bytes(signature[32:], byteorder='big'),
    )


def parse_bearer_authorization_header(
        values: list[str] | tuple[str, ...],
        *,
        credential_name: str,
) -> bytes:
    """Parse one Bearer header without accepting folding, padding, or ambiguity."""
    if type(values) not in (list, tuple) or len(values) != 1 or type(values[0]) is not str:
        raise InvalidCompanionInput
    value = values[0]
    if ',' in value or value != value.strip() or value.count(' ') != 1:
        raise InvalidCompanionInput
    scheme, credential = value.split(' ', maxsplit=1)
    if scheme.lower() != 'bearer' or credential == '':
        raise InvalidCompanionInput
    if credential_name not in {'pairing_credential', 'access_session_credential'}:
        raise InvalidCompanionInput
    return _decode_named(credential, credential_name)


def parse_idempotency_key_header(values: list[str] | tuple[str, ...]) -> bytes:
    if type(values) not in (list, tuple) or len(values) != 1:
        raise InvalidCompanionInput
    return decode_idempotency_key(values[0])


def _reject_constant(_value: str) -> Any:
    raise InvalidCompanionInput


def _object_from_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise InvalidCompanionInput
        result[key] = value
    return result


def _container_depth(value: object, maximum: int) -> None:
    stack: list[tuple[object, int]] = [(value, 1)]
    while stack:
        current, depth = stack.pop()
        if not isinstance(current, (dict, list)):
            continue
        if depth > maximum:
            raise InvalidCompanionInput
        if isinstance(current, dict):
            for key in current:
                try:
                    key.encode('utf-8', errors='strict')
                except UnicodeEncodeError:
                    raise InvalidCompanionInput from None
            stack.extend((child, depth + 1) for child in current.values())
        else:
            stack.extend((child, depth + 1) for child in current)

        for scalar in current.values() if isinstance(current, dict) else current:
            if isinstance(scalar, str):
                try:
                    scalar.encode('utf-8', errors='strict')
                except UnicodeEncodeError:
                    raise InvalidCompanionInput from None
            elif isinstance(scalar, float) and not math.isfinite(scalar):
                raise InvalidCompanionInput


def parse_json_object(
        raw: object,
        *,
        max_bytes: int = MAX_JSON_BYTES,
        max_depth: int = MAX_JSON_DEPTH,
) -> dict[str, Any]:
    """Parse a bounded UTF-8 JSON object, rejecting duplicate members at every depth."""
    if (
            type(raw) is not bytes or
            type(max_bytes) is not int or
            type(max_depth) is not int or
            max_bytes < 1 or
            max_depth < 1 or
            not 1 <= len(raw) <= max_bytes
    ):
        raise InvalidCompanionInput
    try:
        text = raw.decode('utf-8', errors='strict')
        value = json.loads(
            text,
            object_pairs_hook=_object_from_pairs,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, ValueError, RecursionError):
        raise InvalidCompanionInput from None
    if type(value) is not dict:
        raise InvalidCompanionInput
    _container_depth(value, max_depth)
    return value


@dataclass(frozen=True, slots=True, repr=False)
class EngineOrigin:
    """An exact canonical HTTPS origin shared with the mobile implementation."""

    canonical: str

    def __post_init__(self) -> None:
        if _parse_engine_origin(self.canonical) != self.canonical:
            raise InvalidCompanionInput

    @classmethod
    def parse(cls, value: object) -> EngineOrigin:
        if type(value) is not str:
            raise InvalidCompanionInput
        return cls(_parse_engine_origin(value))

    def __str__(self) -> str:
        return 'EngineOrigin(redacted)'

    def __repr__(self) -> str:
        return 'EngineOrigin(redacted)'


def _explicit_port(authority: str) -> tuple[int, str] | None:
    if authority.startswith('['):
        closing_bracket = authority.find(']')
        if closing_bracket < 0:
            raise InvalidCompanionInput
        suffix = authority[closing_bracket + 1:]
        if suffix == '':
            return None
        if not suffix.startswith(':'):
            raise InvalidCompanionInput
        source = suffix[1:]
    else:
        colon_count = authority.count(':')
        if colon_count == 0:
            return None
        if colon_count != 1:
            raise InvalidCompanionInput
        source = authority.rsplit(':', maxsplit=1)[1]
    if not source.isascii() or not source.isdecimal():
        raise InvalidCompanionInput
    port = int(source)
    if not 1 <= port <= 65_535 or source != str(port) or port == 443:
        raise InvalidCompanionInput
    return port, source


def _parse_engine_origin(value: str) -> str:
    if (
            value == '' or
            len(value) > MAX_ENGINE_ORIGIN_LENGTH or
            any(not 0x21 <= ord(character) <= 0x7E for character in value) or
            not value.startswith('https://') or
            value != value.lower()
    ):
        raise InvalidCompanionInput
    authority = value.removeprefix('https://')
    if any(character in authority for character in '/?#@%'):
        raise InvalidCompanionInput
    explicit_port = _explicit_port(authority)
    try:
        parsed = urlsplit(value)
        parsed_port = parsed.port
    except ValueError:
        raise InvalidCompanionInput from None
    host = parsed.hostname
    if (
            parsed.scheme != 'https' or
            host in (None, '') or
            parsed.username is not None or
            parsed.password is not None or
            parsed.path != '' or
            parsed.query != '' or
            parsed.fragment != '' or
            parsed_port == 443
    ):
        raise InvalidCompanionInput
    if explicit_port is None and parsed_port is not None:
        raise InvalidCompanionInput
    if explicit_port is not None and parsed_port != explicit_port[0]:
        raise InvalidCompanionInput

    assert host is not None  # established above without exposing the candidate
    if authority.startswith('['):
        try:
            ipv6_host = ipaddress.IPv6Address(host)
        except ipaddress.AddressValueError:
            raise InvalidCompanionInput from None
        if ipv6_host.ipv4_mapped is not None:
            raise InvalidCompanionInput
        canonical_host = ipv6_host.compressed
        host_and_brackets = f'[{canonical_host}]'
    else:
        try:
            ipv4_host = ipaddress.IPv4Address(host)
        except ipaddress.AddressValueError:
            if all(character in '0123456789.' for character in host) and '.' in host:
                raise InvalidCompanionInput from None
            if len(host) > 253:
                raise InvalidCompanionInput from None
            labels = host.split('.')
            if any(
                    label == '' or
                    len(label) > 63 or
                    not label[0].isalnum() or
                    not label[-1].isalnum() or
                    any(not (character.isascii() and (character.isalnum() or character == '-'))
                        for character in label)
                    for label in labels
            ):
                raise InvalidCompanionInput from None
            host_and_brackets = host
        else:
            host_and_brackets = str(ipv4_host)
    canonical = f'https://{host_and_brackets}'
    if explicit_port is not None:
        canonical = f'{canonical}:{explicit_port[0]}'
    if canonical != value:
        raise InvalidCompanionInput
    return canonical


def derive_engine_origin(scheme: object, authority: object) -> EngineOrigin:
    """Build an origin only from already trusted, single-valued ingress metadata."""
    if type(scheme) is not str or scheme != 'https' or type(authority) is not str:
        raise InvalidCompanionInput
    return EngineOrigin.parse(f'https://{authority}')


def build_device_proof_transcript(
        engine_origin: EngineOrigin,
        device_session_id: object,
        challenge_id: object,
        nonce: object,
        expires_at: object,
) -> bytes:
    """Build the exact version-1 proof transcript shared with Kotlin and Swift."""
    if type(engine_origin) is not EngineOrigin:
        raise InvalidCompanionInput
    device_bytes = _require_length(device_session_id, DECODED_LENGTHS['device_session_id'])
    challenge_bytes = _require_length(challenge_id, DECODED_LENGTHS['challenge_id'])
    nonce_bytes = _require_length(nonce, DECODED_LENGTHS['nonce'])
    if type(expires_at) is not int or not 0 <= expires_at <= MAX_TIMESTAMP:
        raise InvalidCompanionInput
    origin_bytes = engine_origin.canonical.encode('ascii')
    if len(origin_bytes) > 65_535:
        raise InvalidCompanionInput
    return b''.join((
        DEVICE_PROOF_DOMAIN,
        b'\x00',
        len(origin_bytes).to_bytes(2, byteorder='big'),
        origin_bytes,
        device_bytes,
        challenge_bytes,
        nonce_bytes,
        expires_at.to_bytes(8, byteorder='big'),
    ))


def _require_length(value: object, expected_length: int) -> bytes:
    if type(value) is not bytes or len(value) != expected_length:
        raise InvalidCompanionInput
    return value


def device_proof_transcript_sha256(transcript: object) -> bytes:
    if type(transcript) is not bytes:
        raise InvalidCompanionInput
    return sha256(transcript).digest()


def verify_device_proof(
        public_key: object,
        transcript: object,
        signature: object,
) -> bool:
    """Verify a raw-transcript ECDSA/SHA-256 proof using an IEEE P1363 signature."""
    try:
        key_bytes = _require_length(public_key, DECODED_LENGTHS['p256_public_key'])
        if key_bytes[0] != 0x04 or type(transcript) is not bytes:
            return False
        key = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), key_bytes)
        key.verify(
            p1363_signature_to_der(signature),
            transcript,
            ec.ECDSA(hashes.SHA256()),
        )
    except (InvalidCompanionInput, InvalidSignature, ValueError):
        return False
    return True
