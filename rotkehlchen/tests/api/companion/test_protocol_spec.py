import base64
import hashlib
import json
import re
from operator import itemgetter
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_ROOT = REPO_ROOT / 'mobile' / 'protocol' / 'v1'
TOKEN_PATTERN = re.compile(r'^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$')
ALGORITHM_PATTERN = re.compile(r'^[a-z0-9]+(?:-[a-z0-9]+)*$')
BASE64URL_PATTERN = re.compile(r'^[A-Za-z0-9_-]+$')
PAIRING_QR_REQUIRED_KEYS = {
    'kind',
    'format_version',
    'engine_origin',
    'pairing_id',
    'pairing_credential',
    'expires_at',
}
ROOT_STATES = {
    'unpaired',
    'device_locked',
    'connecting',
    'online',
    'refreshing',
    'degraded',
    'unreachable',
    'engine_locked',
    'profile_mismatch',
    'incompatible',
    'revoked',
}
EXPECTED_CAPABILITIES = {
    'device_sessions': 1,
    'history_pagination': 1,
    'portfolio_snapshot': 1,
    'refresh_operations': 1,
    'websocket_notifications': 1,
}
EXPECTED_ROUTE_IDS = {
    'cancel_pairing',
    'create_access_session',
    'create_challenge',
    'create_pairing',
    'create_refresh_operation',
    'fetch_snapshot',
    'get_protocol',
    'get_refresh_operation',
    'list_device_sessions',
    'list_refresh_operations',
    'page_history',
    'register_device_session',
    'rename_current_device_session',
    'rename_device_session',
    'revoke_current_device_session',
    'revoke_device_session',
}
EXPECTED_THREAT_IDS = {
    'background_purges_access_bearer',
    'challenge_equivalence_class',
    'concurrent_proof_single_winner',
    'declare_threat_model_exclusions',
    'deny_companion_bearer_outside_namespace',
    'deny_cross_realm_credentials',
    'deny_missing_companion_scope',
    'deny_unlisted_companion_route',
    'derive_current_device_from_access_session',
    'engine_restart_invalidates_access_bearer',
    'expired_access_bearer',
    'filter_websocket_events_server_side',
    'hide_cross_profile_resource',
    'ignore_credentials_on_protocol_discovery',
    'invalid_signature_consumes_challenge',
    'rate_limit_before_signature',
    'redact_seeded_sensitive_values',
    'reject_altered_proof_origin',
    'reject_changed_consumed_registration',
    'reject_expired_pairing',
    'reject_non_https_origin',
    'reject_pairing_idempotency_conflict',
    'reject_replayed_pairing',
    'reject_untrusted_tls',
    'reject_websocket_bearer_in_url_or_subprotocol',
    'reject_websocket_cookie_and_bearer',
    'replay_consumed_registration',
    'replay_pairing_creation',
    'revoked_device_rejects_access_bearer',
    'unknown_device_allocates_no_bucket',
    'unknown_revoked_device_equivalence',
    'websocket_1008_requires_http_proof',
}
HTTP_EFFECTS = {'delete', 'keep', 'not_established', 'unchanged'}
HTTP_ERROR_KEYS = {
    'status',
    'code',
    'retryable',
    'action',
    'device_session_effect',
    'offline_snapshot_effect',
}


def _reject_duplicate_members(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON member: {key}')
        result[key] = value
    return result


def _load_json(name: str) -> dict[str, Any]:
    raw = (CONTRACT_ROOT / name).read_text(encoding='utf-8')
    parsed = json.loads(raw, object_pairs_hook=_reject_duplicate_members)
    assert isinstance(parsed, dict)
    assert raw == f'{json.dumps(parsed, indent=2, ensure_ascii=False)}\n'
    return parsed


VOCABULARY = _load_json('vocabulary.json')
CASES = _load_json('p0_1_cases.json')
GOLDEN_VECTORS = _load_json('golden_vectors.json')
GENERATED_NAMES = _load_json('generated_names.json')


def _decode_base64url(value: str, encoded_length_key: str) -> bytes:
    assert len(value) == VOCABULARY['encoded_lengths'][encoded_length_key]
    assert BASE64URL_PATTERN.fullmatch(value) is not None
    assert '=' not in value
    decoded = base64.urlsafe_b64decode(f'{value}{"=" * (-len(value) % 4)}')
    assert base64.urlsafe_b64encode(decoded).rstrip(b'=').decode() == value
    return decoded


def _decode_pairing_qr(case: dict[str, Any]) -> bytes:
    if 'wire_utf8' in case:
        return case['wire_utf8'].encode()
    if 'wire_base64' in case:
        return base64.b64decode(case['wire_base64'], validate=True)

    builder = case['wire_builder']
    return (
        builder['prefix'] +
        builder['repeat_ascii'] * builder['repeat_count'] +
        builder['suffix']
    ).encode()


def _validate_pairing_qr(raw: bytes, now: int) -> bool:
    if len(raw) > 2048:
        return False

    try:
        text = raw.decode('utf-8', errors='strict')
        value = json.loads(text, object_pairs_hook=_reject_duplicate_members)
    except (UnicodeDecodeError, ValueError, json.JSONDecodeError):
        return False

    if not isinstance(value, dict) or not value.keys() >= PAIRING_QR_REQUIRED_KEYS:
        return False
    if value['kind'] != 'rotki_companion_pairing' or type(value['format_version']) is not int:
        return False
    if value['format_version'] != 1 or type(value['expires_at']) is not int:
        return False
    if value['expires_at'] <= now:
        return False
    if not all(isinstance(value[key], str) for key in (
            'engine_origin', 'pairing_id', 'pairing_credential')):
        return False

    origin = urlsplit(value['engine_origin'])
    if (
            origin.scheme != 'https' or
            origin.username is not None or
            origin.password is not None or
            not origin.hostname or
            origin.path or
            origin.query or
            origin.fragment or
            value['engine_origin'].endswith('/')):
        return False

    try:
        _decode_base64url(value['pairing_id'], 'pairing_id')
        _decode_base64url(value['pairing_credential'], 'pairing_credential')
    except (AssertionError, ValueError):
        return False
    return True


def test_vocabulary_is_canonical_and_namespaced() -> None:
    assert VOCABULARY['schema_version'] == 1
    assert VOCABULARY['protocol_versions'] == [1]
    assert VOCABULARY['capabilities'] == EXPECTED_CAPABILITIES
    assert set(VOCABULARY['root_states']) == ROOT_STATES
    assert VOCABULARY['websocket_close_codes'] == [1000, 1006, 1008, 1012]

    token_collections = (
        'auth_realms',
        'authorities',
        'platforms',
        'root_states',
        'http_error_codes',
        'source_error_codes',
        'operation_error_codes',
        'error_actions',
        'websocket_event_types',
        'device_session_states',
    )
    for collection in token_collections:
        values = VOCABULARY[collection]
        assert values == sorted(set(values))
        assert all(TOKEN_PATTERN.fullmatch(value) is not None for value in values)

    algorithms = VOCABULARY['public_key_algorithms']
    assert algorithms == sorted(set(algorithms))
    assert all(ALGORITHM_PATTERN.fullmatch(value) is not None for value in algorithms)

    error_domains = (
        set(VOCABULARY['http_error_codes']),
        set(VOCABULARY['source_error_codes']),
        set(VOCABULARY['operation_error_codes']),
    )
    assert not error_domains[0] & error_domains[1]
    assert not error_domains[0] & error_domains[2]
    assert not error_domains[1] & error_domains[2]
    assert VOCABULARY['lifetimes_seconds'] == {
        'access_session': 900,
        'challenge': 60,
        'pairing': 120,
        'proactive_renewal_window': 300,
        'registration_replay': 300,
    }


def test_generated_language_names_are_current() -> None:
    token_collections = (
        'auth_realms',
        'authorities',
        'device_session_states',
        'error_actions',
        'http_error_codes',
        'operation_error_codes',
        'platforms',
        'root_states',
        'source_error_codes',
        'websocket_event_types',
    )
    collections = {
        **{name: VOCABULARY[name] for name in token_collections},
        'capabilities': sorted(VOCABULARY['capabilities']),
    }
    expected_domains: dict[str, dict[str, dict[str, str]]] = {}
    for domain, values in sorted(collections.items()):
        expected_domains[domain] = {}
        for wire_value in values:
            pascal = ''.join(word.capitalize() for word in wire_value.split('_'))
            expected_domains[domain][wire_value] = {
                'python': wire_value.upper(),
                'kotlin': pascal,
                'swift': f'{pascal[0].lower()}{pascal[1:]}',
            }

    assert {'schema_version': 1, 'domains': expected_domains} == GENERATED_NAMES


@pytest.mark.parametrize('case', CASES['error_cases'], ids=itemgetter('code'))
def test_http_error_matrix(case: dict[str, Any]) -> None:
    assert set(case) == HTTP_ERROR_KEYS
    assert type(case['status']) is int and 400 <= case['status'] <= 599
    assert type(case['retryable']) is bool
    assert case['code'] in VOCABULARY['http_error_codes']
    assert case['action'] in VOCABULARY['error_actions']
    assert case['device_session_effect'] in HTTP_EFFECTS
    assert case['offline_snapshot_effect'] in HTTP_EFFECTS


def test_http_error_matrix_is_complete_and_fail_closed() -> None:
    errors = CASES['error_cases']
    assert len(errors) == len(VOCABULARY['http_error_codes']) == 20
    assert {case['code'] for case in errors} == set(VOCABULARY['http_error_codes'])
    assert len({(case['status'], case['code']) for case in errors}) == len(errors)
    destructive = [case for case in errors if case['device_session_effect'] == 'delete']
    assert destructive == [next(case for case in errors if case['code'] == 'not_authorized')]
    assert destructive[0]['offline_snapshot_effect'] == 'delete'


def test_route_matrix_is_deny_by_default() -> None:
    routes = CASES['route_matrix']
    assert len(routes) == 16
    assert {route['id'] for route in routes} == EXPECTED_ROUTE_IDS
    assert len({(route['method'], route['path']) for route in routes}) == len(routes)

    for route in routes:
        assert route['realm'] in VOCABULARY['auth_realms']
        assert route['authority'] in VOCABULARY['authorities']
        assert route['success_status'] in (200, 201, 202)
        if route['id'] == 'get_protocol':
            assert route['realm'] == 'public'
            assert route['authority'] == 'none'
            assert route['requires_protocol_header'] is False
        else:
            assert route['realm'] != 'public'
            assert route['requires_protocol_header'] is True

    access_routes = [route for route in routes if route['realm'] == 'access_session']
    assert len(access_routes) == 7
    assert all(route['scope'] is not None for route in access_routes)
    current_routes = [route for route in routes if route['path'] == '/device-sessions/current']
    assert len(current_routes) == 2
    assert all(route['identity_source'] == 'access_session' for route in current_routes)

    policies = {policy['realm']: policy for policy in CASES['realm_policies']}
    assert set(policies) == set(VOCABULARY['auth_realms'])
    assert policies['public']['credential_selection'] == 'ignore_all'
    assert policies['device_proof']['credential_selection'] == 'request_body_stage_only'
    assert policies['pairing']['missing_or_wrong_authority'] == 'pairing_unavailable'
    assert policies['access_session']['missing_or_wrong_authority'] == (
        'access_session_unavailable')


def test_root_state_matrix_is_complete() -> None:
    transitions = CASES['root_transitions']
    assert len({transition['id'] for transition in transitions}) == len(transitions)
    assert all(set(transition['from']) <= ROOT_STATES for transition in transitions)
    assert all(transition['to'] in ROOT_STATES for transition in transitions)

    incoming_states = {transition['to'] for transition in transitions}
    source_states = {
        state
        for transition in transitions
        for state in transition['from']
    }
    assert incoming_states | source_states >= ROOT_STATES
    assert next(
        transition for transition in transitions if transition['id'] == 'websocket_policy_close'
    )['to'] == 'connecting'
    assert next(
        transition for transition in transitions if transition['id'] == 'proof_not_authorized'
    )['to'] == 'revoked'


def test_threat_matrix_is_complete() -> None:
    threats = CASES['threat_cases']
    assert {threat['id'] for threat in threats} == EXPECTED_THREAT_IDS
    assert len({threat['id'] for threat in threats}) == len(threats)

    errors_by_code = {case['code']: case for case in CASES['error_cases']}
    for threat in threats:
        expected = threat['expected']
        if code := expected.get('code'):
            assert code in errors_by_code
            assert expected['status'] == errors_by_code[code]['status']

    challenge_equivalence = next(
        threat for threat in threats if threat['id'] == 'challenge_equivalence_class'
    )
    assert challenge_equivalence['expected'] == {
        'outcome': 'http_error',
        'status': 410,
        'code': 'challenge_unavailable',
        'mutation': 'none',
        'disclosure': 'none',
    }
    rate_limit = next(
        threat for threat in threats if threat['id'] == 'rate_limit_before_signature'
    )
    assert rate_limit['expected']['mutation'] == 'none'


@pytest.mark.parametrize(
    'case',
    GOLDEN_VECTORS['pairing_qr_cases'],
    ids=itemgetter('id'),
)
def test_pairing_qr_vectors(case: dict[str, Any]) -> None:
    assert _validate_pairing_qr(_decode_pairing_qr(case), case['now']) is case['accepted']


def test_success_examples_cover_auth_control_routes() -> None:
    examples = GOLDEN_VECTORS['success_examples']
    expected = EXPECTED_ROUTE_IDS - {
        'create_refresh_operation',
        'fetch_snapshot',
        'get_refresh_operation',
        'list_refresh_operations',
        'page_history',
    }
    assert {example['id'] for example in examples} == expected
    for example in examples:
        response = example['response']
        assert set(response) == {'result', 'message'}
        assert response['message'] == ''
        assert 'error' not in response


def test_device_proof_golden_vector() -> None:
    vector = GOLDEN_VECTORS['device_proof']
    origin = vector['canonical_engine_origin'].encode()
    device_session_id = _decode_base64url(vector['device_session_id'], 'device_session_id')
    challenge_id = _decode_base64url(vector['challenge_id'], 'challenge_id')
    nonce = _decode_base64url(vector['nonce'], 'nonce')
    transcript = b''.join((
        b'rotki-companion-device-proof/v1',
        b'\x00',
        len(origin).to_bytes(2, 'big'),
        origin,
        device_session_id,
        challenge_id,
        nonce,
        vector['expires_at'].to_bytes(8, 'big'),
    ))
    assert transcript.hex() == vector['transcript_hex']
    assert hashlib.sha256(transcript).hexdigest() == vector['transcript_sha256']

    public_key = _decode_base64url(vector['public_key'], 'p256_public_key')
    signature = _decode_base64url(vector['signature'], 'p1363_signature')
    assert len(public_key) == 65 and public_key[0] == 0x04
    assert len(signature) == 64

    verifier = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), public_key)
    r = int.from_bytes(signature[:32], 'big')
    s = int.from_bytes(signature[32:], 'big')
    verifier.verify(encode_dss_signature(r, s), transcript, ec.ECDSA(hashes.SHA256()))


def test_websocket_vocabulary_and_examples_agree() -> None:
    examples = GOLDEN_VECTORS['websocket_examples']
    assert {example['payload']['type'] for example in examples} == set(
        VOCABULARY['websocket_event_types'],
    )
    assert all(set(example['payload']) == {'type', 'data'} for example in examples)
