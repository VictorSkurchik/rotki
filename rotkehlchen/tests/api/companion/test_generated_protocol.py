import json
from dataclasses import asdict
from pathlib import Path

from rotkehlchen.api.companion.generated_protocol import (
    CAPABILITIES,
    ENCODED_LENGTHS,
    HTTP_ERROR_SPECS,
    IDEMPOTENCY_KEY_HEADER,
    LIFETIMES_SECONDS,
    PROTOCOL_HEADER,
    PROTOCOL_VERSIONS,
    REALM_POLICY_ROWS,
    ROUTE_POLICY_ROWS,
    X_ROTKI_CLIENT_IP_HEADER,
    X_ROTKI_ENGINE_ORIGIN_HEADER,
    HttpErrorCode,
)

REPO_ROOT = Path(__file__).resolve().parents[4]
CONTRACT_ROOT = REPO_ROOT / 'mobile' / 'protocol' / 'v1'


def _load_contract(name: str) -> dict:
    return json.loads((CONTRACT_ROOT / name).read_text(encoding='utf-8'))


def test_generated_vocabulary_matches_canonical_fixture() -> None:
    vocabulary = _load_contract('vocabulary.json')

    assert list(PROTOCOL_VERSIONS) == vocabulary['protocol_versions']
    assert vocabulary['headers']['protocol'] == PROTOCOL_HEADER
    assert vocabulary['headers']['idempotency_key'] == IDEMPOTENCY_KEY_HEADER
    assert {key.value: value for key, value in CAPABILITIES.items()} == (
        vocabulary['capabilities']
    )
    assert vocabulary['lifetimes_seconds'] == LIFETIMES_SECONDS
    assert vocabulary['encoded_lengths'] == ENCODED_LENGTHS
    assert {code.value for code in HttpErrorCode} == set(vocabulary['http_error_codes'])
    assert X_ROTKI_ENGINE_ORIGIN_HEADER == 'X-Rotki-Engine-Origin'
    assert X_ROTKI_CLIENT_IP_HEADER == 'X-Rotki-Client-IP'


def test_generated_p0_error_and_policy_rows_match_fixture() -> None:
    cases = _load_contract('p0_1_cases.json')
    generated_errors = [
        {
            'status': HTTP_ERROR_SPECS[HttpErrorCode(case['code'])].status,
            'code': case['code'],
            'retryable': HTTP_ERROR_SPECS[HttpErrorCode(case['code'])].retryable,
            'action': HTTP_ERROR_SPECS[HttpErrorCode(case['code'])].action.value,
            'device_session_effect': (
                HTTP_ERROR_SPECS[HttpErrorCode(case['code'])].device_session_effect
            ),
            'offline_snapshot_effect': (
                HTTP_ERROR_SPECS[HttpErrorCode(case['code'])].offline_snapshot_effect
            ),
        }
        for case in cases['error_cases']
    ]
    generated_routes = [
        {
            'id': row.route_id,
            'method': row.method,
            'path': row.path,
            'realm': row.realm.value,
            'authority': row.authority.value,
            'requires_protocol_header': row.requires_protocol_header,
            'scope': row.scope,
            **(
                {'identity_source': row.identity_source}
                if row.identity_source is not None
                else {}
            ),
            'success_status': row.success_status,
        }
        for row in ROUTE_POLICY_ROWS
    ]
    generated_realms = [
        {
            **asdict(row),
            'realm': row.realm.value,
            'missing_or_wrong_authority': (
                row.missing_or_wrong_authority.value
                if row.missing_or_wrong_authority is not None
                else None
            ),
        }
        for row in REALM_POLICY_ROWS
    ]

    assert generated_errors == cases['error_cases']
    assert generated_routes == cases['route_matrix']
    assert generated_realms == cases['realm_policies']
