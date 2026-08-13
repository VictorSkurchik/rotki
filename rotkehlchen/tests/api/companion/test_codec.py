import base64
import hashlib
import json
from pathlib import Path

import pytest

from rotkehlchen.api.companion.codec import (
    EngineOrigin,
    InvalidCompanionInput,
    build_device_proof_transcript,
    decode_base64url,
    decode_challenge_id,
    decode_device_session_id,
    decode_nonce,
    decode_p1363_signature,
    decode_public_key,
    derive_engine_origin,
    encode_base64url,
    parse_bearer_authorization_header,
    parse_idempotency_key_header,
    parse_json_object,
    validate_p1363_signature,
    verify_device_proof,
)

REPO_ROOT = Path(__file__).resolve().parents[4]
GOLDEN_VECTORS = json.loads(
    (REPO_ROOT / 'mobile' / 'protocol' / 'v1' / 'golden_vectors.json').read_text(
        encoding='utf-8',
    ),
)


def test_base64url_codec_is_exact_and_canonical() -> None:
    raw = bytes(range(32))
    encoded = 'AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8'
    assert encode_base64url(raw) == encoded
    assert decode_base64url(encoded, decoded_length=32) == raw

    for rejected in (
        f'{encoded}=',
        encoded[:-1],
        f'{encoded[:-1]}+',
        b'not text',
    ):
        with pytest.raises(InvalidCompanionInput):
            decode_base64url(rejected, decoded_length=32)


def test_header_codecs_reject_duplicates_folding_and_wrong_realms() -> None:
    pairing = 'EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8'
    assert parse_bearer_authorization_header(
        [f'bEaReR {pairing}'],
        credential_name='pairing_credential',
    ) == bytes(range(16, 48))
    idempotency = 'cHFyc3R1dnd4eXp7fH1-fw'
    assert parse_idempotency_key_header((idempotency,)) == bytes(range(112, 128))

    for values in ([], [f'Bearer {pairing}', f'Bearer {pairing}'], [f'Bearer {pairing},x']):
        with pytest.raises(InvalidCompanionInput):
            parse_bearer_authorization_header(
                values,
                credential_name='pairing_credential',
            )
    with pytest.raises(InvalidCompanionInput):
        parse_bearer_authorization_header(
            [f'Bearer  {pairing}'],
            credential_name='pairing_credential',
        )


def test_json_parser_rejects_invalid_utf8_duplicates_constants_size_and_depth() -> None:
    assert parse_json_object(b'{"outer":{"value":1},"items":[true,null]}') == {
        'outer': {'value': 1},
        'items': [True, None],
    }
    rejected_values = (
        b'{"a":1,"a":2}',
        b'{"outer":{"a":1,"a":2}}',
        b'{"value":NaN}',
        b'{"value":1e999}',
        b'{"value":"\\ud800"}',
        b'[]',
        b'\xff',
        b'',
    )
    for raw in rejected_values:
        with pytest.raises(InvalidCompanionInput):
            parse_json_object(raw)
    with pytest.raises(InvalidCompanionInput):
        parse_json_object(b'{}', max_bytes=1)

    accepted_depth_64 = b'{"x":' + b'[' * 63 + b'0' + b']' * 63 + b'}'
    rejected_depth_65 = b'{"x":' + b'[' * 64 + b'0' + b']' * 64 + b'}'
    assert parse_json_object(accepted_depth_64)['x'] is not None
    with pytest.raises(InvalidCompanionInput):
        parse_json_object(rejected_depth_65)


def test_engine_origin_matches_mobile_canonical_contract() -> None:
    origin = EngineOrigin.parse('https://rotki.example')
    assert origin.canonical == 'https://rotki.example'
    assert derive_engine_origin('https', '192.0.2.1:8443').canonical == (
        'https://192.0.2.1:8443'
    )
    assert EngineOrigin.parse('https://rotki-node.example').canonical == (
        'https://rotki-node.example'
    )
    assert EngineOrigin.parse('https://xn--rtki-5qa.example').canonical == (
        'https://xn--rtki-5qa.example'
    )
    assert str(origin) == repr(origin) == 'EngineOrigin(redacted)'
    assert 'rotki.example' not in repr(origin)

    rejected = (
        '',
        'http://rotki.example',
        'HTTPS://rotki.example',
        'https://ROTKI.example',
        'https://rotki.example/',
        'https://rotki.example/path',
        'https://user@rotki.example',
        'https://rotki.example?query',
        'https://rotki.example:443',
        'https://rotki.example:0443',
        'https://rotki.example:0',
        'https://rotki.example:65536',
        'https://rotki.example:01',
        'https://rotki.example ',
        'https://rötki.example',
        'https://-rotki.example',
        'https://rotki-.example',
        'https://999.999.999.999',
        'https://[::ffff:c000:201]',
        'https://[fe80::1%25eth0]',
    )
    for value in rejected:
        with pytest.raises(InvalidCompanionInput):
            EngineOrigin.parse(value)


def test_device_proof_helpers_match_and_verify_golden_vector() -> None:
    proof = GOLDEN_VECTORS['device_proof']
    public_key = decode_public_key(proof['public_key'])
    signature = decode_p1363_signature(proof['signature'])
    transcript = build_device_proof_transcript(
        EngineOrigin.parse(proof['canonical_engine_origin']),
        decode_device_session_id(proof['device_session_id']),
        decode_challenge_id(proof['challenge_id']),
        decode_nonce(proof['nonce']),
        proof['expires_at'],
    )

    assert transcript.hex() == proof['transcript_hex']
    assert hashlib.sha256(transcript).hexdigest() == proof['transcript_sha256']
    assert verify_device_proof(public_key, transcript, signature) is True
    assert verify_device_proof(public_key, transcript + b'!', signature) is False
    assert verify_device_proof(public_key, transcript, b'\x00' * 64) is False
    with pytest.raises(InvalidCompanionInput):
        validate_p1363_signature(b'\x00' * 64)


def test_invalid_utf8_pairing_fixture_is_rejected_before_json() -> None:
    case = next(
        item for item in GOLDEN_VECTORS['pairing_qr_cases']
        if item['id'] == 'invalid_utf8'
    )
    with pytest.raises(InvalidCompanionInput):
        parse_json_object(base64.b64decode(case['wire_base64'], validate=True))
