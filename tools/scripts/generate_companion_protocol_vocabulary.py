"""Generate language bindings from the canonical Companion Protocol fixtures."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Final

REPO_ROOT: Final = Path(__file__).resolve().parents[2]
CONTRACT_ROOT: Final = REPO_ROOT / 'mobile' / 'protocol' / 'v1'
VOCABULARY_PATH: Final = CONTRACT_ROOT / 'vocabulary.json'
P0_CASES_PATH: Final = CONTRACT_ROOT / 'p0_1_cases.json'
NAMES_OUTPUT_PATH: Final = CONTRACT_ROOT / 'generated_names.json'
PYTHON_OUTPUT_PATH: Final = (
    REPO_ROOT / 'rotkehlchen' / 'api' / 'companion' / 'generated_protocol.py'
)
TOKEN_COLLECTIONS: Final = (
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
ENUM_CLASS_NAMES: Final = {
    'auth_realms': 'AuthRealm',
    'authorities': 'Authority',
    'capabilities': 'Capability',
    'device_session_states': 'DeviceSessionState',
    'error_actions': 'ErrorAction',
    'http_error_codes': 'HttpErrorCode',
    'operation_error_codes': 'OperationErrorCode',
    'platforms': 'Platform',
    'root_states': 'RootState',
    'source_error_codes': 'SourceErrorCode',
    'websocket_event_types': 'WebsocketEventType',
}


def _pascal_case(value: str) -> str:
    return ''.join(word.capitalize() for word in value.split('_'))


def _load_object(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(value, dict):
        raise TypeError(f'{path.relative_to(REPO_ROOT)} must contain a JSON object')
    return value


def generate_names(vocabulary: dict[str, Any] | None = None) -> str:
    """Generate stable cross-language symbol names."""
    if vocabulary is None:
        vocabulary = _load_object(VOCABULARY_PATH)
    domains: dict[str, dict[str, dict[str, str]]] = {}

    collections = {
        **{name: vocabulary[name] for name in TOKEN_COLLECTIONS},
        'capabilities': sorted(vocabulary['capabilities']),
    }
    for domain, values in sorted(collections.items()):
        domains[domain] = {}
        for wire_value in values:
            pascal = _pascal_case(wire_value)
            domains[domain][wire_value] = {
                'python': wire_value.upper(),
                'kotlin': pascal,
                'swift': f'{pascal[0].lower()}{pascal[1:]}',
            }

    return f'{json.dumps({"schema_version": 1, "domains": domains}, indent=2)}\n'


def _render_enum(class_name: str, values: list[str]) -> list[str]:
    lines = [f'class {class_name}(StrEnum):']
    lines.extend(f"    {value.upper()} = '{value}'" for value in values)
    lines.extend(('', ''))
    return lines


def _render_string_int_dict(name: str, values: dict[str, int]) -> list[str]:
    lines = [f'{name}: Final = {{']
    lines.extend(f"    '{key}': {value}," for key, value in values.items())
    lines.extend(('}', ''))
    return lines


def generate_python(
        vocabulary: dict[str, Any] | None = None,
        p0_cases: dict[str, Any] | None = None,
) -> str:
    """Generate the importable Engine binding without runtime fixture I/O."""
    if vocabulary is None:
        vocabulary = _load_object(VOCABULARY_PATH)
    if p0_cases is None:
        p0_cases = _load_object(P0_CASES_PATH)

    lines = [
        '"""Generated Companion Protocol vocabulary. Do not edit by hand."""',
        '',
        'from dataclasses import dataclass',
        'from enum import StrEnum',
        'from typing import Final',
        '',
        '',
    ]
    enum_values = {
        **{name: vocabulary[name] for name in TOKEN_COLLECTIONS},
        'capabilities': sorted(vocabulary['capabilities']),
    }
    for collection_name, class_name in ENUM_CLASS_NAMES.items():
        lines.extend(_render_enum(class_name, enum_values[collection_name]))

    protocol_versions = ', '.join(str(value) for value in vocabulary['protocol_versions'])
    if len(vocabulary['protocol_versions']) == 1:
        protocol_versions += ','
    lines.extend((
        f'PROTOCOL_VERSIONS: Final = ({protocol_versions})',
        'SUPPORTED_PROTOCOL_VERSIONS: Final = PROTOCOL_VERSIONS',
        f"PROTOCOL_HEADER: Final = {vocabulary['headers']['protocol']!r}",
        f"IDEMPOTENCY_KEY_HEADER: Final = {vocabulary['headers']['idempotency_key']!r}",
        "X_ROTKI_ENGINE_ORIGIN_HEADER: Final = 'X-Rotki-Engine-Origin'",
        "X_ROTKI_CLIENT_IP_HEADER: Final = 'X-Rotki-Client-IP'",
        'ENGINE_ORIGIN_HEADER: Final = X_ROTKI_ENGINE_ORIGIN_HEADER',
        'CLIENT_IP_HEADER: Final = X_ROTKI_CLIENT_IP_HEADER',
        '',
        'CAPABILITIES: Final = {',
    ))
    lines.extend(
        f'    Capability.{key.upper()}: {value},'
        for key, value in vocabulary['capabilities'].items()
    )
    lines.extend(('}', '', ''))
    lines.extend(_render_string_int_dict('LIFETIMES_SECONDS', vocabulary['lifetimes_seconds']))
    lines.extend(_render_string_int_dict('ENCODED_LENGTHS', vocabulary['encoded_lengths']))
    lines.extend((
        'DECODED_LENGTHS: Final = {',
        '    key: encoded_length * 6 // 8',
        '    for key, encoded_length in ENCODED_LENGTHS.items()',
        '}',
        '',
        f'PUBLIC_KEY_ALGORITHMS: Final = {tuple(vocabulary["public_key_algorithms"])!r}',
        f'WEBSOCKET_CLOSE_CODES: Final = {tuple(vocabulary["websocket_close_codes"])!r}',
        '',
        '',
        '@dataclass(frozen=True, slots=True)',
        'class HttpErrorSpec:',
        '    status: int',
        '    retryable: bool',
        '    action: ErrorAction',
        '    device_session_effect: str',
        '    offline_snapshot_effect: str',
        '',
        '',
        'HTTP_ERROR_SPECS: Final = {',
    ))
    for case in p0_cases['error_cases']:
        lines.extend((
            f'    HttpErrorCode.{case["code"].upper()}: HttpErrorSpec(',
            f'        status={case["status"]},',
            f'        retryable={case["retryable"]!r},',
            f'        action=ErrorAction.{case["action"].upper()},',
            f'        device_session_effect={case["device_session_effect"]!r},',
            f'        offline_snapshot_effect={case["offline_snapshot_effect"]!r},',
            '    ),',
        ))
    lines.extend((
        '}',
        '',
        '',
        '@dataclass(frozen=True, slots=True)',
        'class RoutePolicyRow:',
        '    route_id: str',
        '    method: str',
        '    path: str',
        '    realm: AuthRealm',
        '    authority: Authority',
        '    requires_protocol_header: bool',
        '    scope: str | None',
        '    identity_source: str | None',
        '    success_status: int',
        '',
        '',
        'ROUTE_POLICY_ROWS: Final = (',
    ))
    for route in p0_cases['route_matrix']:
        lines.extend((
            '    RoutePolicyRow(',
            f'        route_id={route["id"]!r},',
            f'        method={route["method"]!r},',
            f'        path={route["path"]!r},',
            f'        realm=AuthRealm.{route["realm"].upper()},',
            f'        authority=Authority.{route["authority"].upper()},',
            f'        requires_protocol_header={route["requires_protocol_header"]!r},',
            f'        scope={route.get("scope")!r},',
            f'        identity_source={route.get("identity_source")!r},',
            f'        success_status={route["success_status"]},',
            '    ),',
        ))
    lines.extend((
        ')',
        '',
        '',
        '@dataclass(frozen=True, slots=True)',
        'class RealmPolicyRow:',
        '    realm: AuthRealm',
        '    credential_selection: str',
        '    missing_or_wrong_authority: HttpErrorCode | None',
        '    foreign_credentials: str',
        '',
        '',
        'REALM_POLICY_ROWS: Final = (',
    ))
    for policy in p0_cases['realm_policies']:
        missing = policy['missing_or_wrong_authority']
        missing_expression = (
            'None' if missing is None else f'HttpErrorCode.{missing.upper()}'
        )
        lines.extend((
            '    RealmPolicyRow(',
            f'        realm=AuthRealm.{policy["realm"].upper()},',
            f'        credential_selection={policy["credential_selection"]!r},',
            f'        missing_or_wrong_authority={missing_expression},',
            f'        foreign_credentials={policy["foreign_credentials"]!r},',
            '    ),',
        ))
    lines.extend((')', ''))
    return '\n'.join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--check', action='store_true')
    action.add_argument('--write', action='store_true')
    args = parser.parse_args()

    vocabulary = _load_object(VOCABULARY_PATH)
    outputs = {
        NAMES_OUTPUT_PATH: generate_names(vocabulary),
        PYTHON_OUTPUT_PATH: generate_python(vocabulary, _load_object(P0_CASES_PATH)),
    }
    if args.check:
        stale = [
            path for path, generated in outputs.items()
            if not path.exists() or path.read_text(encoding='utf-8') != generated
        ]
        for path in stale:
            print(f'{path.relative_to(REPO_ROOT)} is stale')
        return int(bool(stale))

    for path, generated in outputs.items():
        path.write_text(generated, encoding='utf-8')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
