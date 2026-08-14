#!/usr/bin/env python3
"""Generate Kotlin Protocol vocabulary and cross-platform contract fixtures."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Final

REPOSITORY_ROOT: Final = Path(__file__).resolve().parents[3]
CORE_PROTOCOL_DIR: Final = REPOSITORY_ROOT / 'mobile' / 'core' / 'protocol'
CORE_TESTING_DIR: Final = REPOSITORY_ROOT / 'mobile' / 'core' / 'testing'
PROTOCOL_DIR: Final = REPOSITORY_ROOT / 'mobile' / 'protocol' / 'v1'
VOCABULARY_PATH: Final = PROTOCOL_DIR / 'vocabulary.json'
NAMES_PATH: Final = PROTOCOL_DIR / 'generated_names.json'
CASES_PATH: Final = PROTOCOL_DIR / 'p0_1_cases.json'
GOLDEN_PATH: Final = PROTOCOL_DIR / 'golden_vectors.json'
CLIENT_POLICY_PATH: Final = PROTOCOL_DIR / 'client_policy_cases.json'
VOCABULARY_KOTLIN_PATH: Final = (
    CORE_PROTOCOL_DIR
    / 'src/commonMain/kotlin/org/rotki/mobile/core/protocol/generated/'
    / 'GeneratedProtocolVocabulary.kt'
)
FIXTURES_KOTLIN_PATH: Final = (
    CORE_TESTING_DIR
    / 'src/commonMain/kotlin/org/rotki/mobile/core/protocol/generated/'
    / 'GeneratedProtocolFixtures.kt'
)

ENUM_DOMAINS: Final = (
    ('ProtocolAuthRealm', 'auth_realms'),
    ('ProtocolAuthority', 'authorities'),
    ('DeviceSessionState', 'device_session_states'),
    ('ProtocolErrorAction', 'error_actions'),
    ('HttpErrorCode', 'http_error_codes'),
    ('OperationErrorCode', 'operation_error_codes'),
    ('CompanionPlatform', 'platforms'),
    ('ProtocolRootState', 'root_states'),
    ('SourceErrorCode', 'source_error_codes'),
    ('WebSocketEventType', 'websocket_event_types'),
)


def reject_duplicate_members(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON member: {key}')
        result[key] = value
    return result


def load_canonical_json(path: Path) -> tuple[str, dict[str, Any]]:
    raw = path.read_text(encoding='utf-8')
    parsed = json.loads(raw, object_pairs_hook=reject_duplicate_members)
    if not isinstance(parsed, dict):
        raise ValueError(f'{path} must contain one JSON object')
    canonical = f'{json.dumps(parsed, indent=2, ensure_ascii=False)}\n'
    if raw != canonical:
        raise ValueError(f'{path} is not canonical JSON')
    if parsed.get('schema_version') != 1:
        raise ValueError(f'{path} has an unsupported schema_version')
    return raw, parsed


def kotlin_raw_string(raw: str) -> str:
    if '"""' in raw:
        raise ValueError('Protocol fixture cannot be embedded in a Kotlin raw string')
    return raw.replace('$', "${'$'}")


def generated_enum(
        type_name: str,
        domain_name: str,
        values: list[str],
        names: dict[str, Any],
) -> str:
    entries = []
    for wire_value in values:
        kotlin_name = names[domain_name][wire_value]['kotlin']
        entries.append(f'    {kotlin_name}("{wire_value}"),')
    return '\n'.join((
        '@HiddenFromObjC',
        f'public enum class {type_name}(',
        '    public val wireValue: String,',
        ') {',
        *entries,
        '}',
    ))


def generate_vocabulary() -> str:
    _, vocabulary = load_canonical_json(VOCABULARY_PATH)
    _, generated_names = load_canonical_json(NAMES_PATH)
    _, client_policy = load_canonical_json(CLIENT_POLICY_PATH)
    names = generated_names.get('domains')
    if not isinstance(names, dict):
        raise ValueError('generated_names.json must contain a domains object')

    enum_blocks = []
    for type_name, domain_name in ENUM_DOMAINS:
        values = vocabulary[domain_name]
        if not isinstance(values, list) or not all(isinstance(value, str) for value in values):
            raise ValueError(f'vocabulary domain {domain_name} must be a string list')
        enum_blocks.append(generated_enum(type_name, domain_name, values, names))

    capability_entries = []
    for wire_value, version in sorted(vocabulary['capabilities'].items()):
        kotlin_name = names['capabilities'][wire_value]['kotlin']
        capability_entries.append(f'    {kotlin_name}("{wire_value}", {version}),')
    capability_block = '\n'.join((
        '@HiddenFromObjC',
        'public enum class ProtocolCapability(',
        '    public val wireValue: String,',
        '    public val minimumVersion: Int,',
        ') {',
        *capability_entries,
        '}',
    ))

    versions = ', '.join(str(value) for value in vocabulary['protocol_versions'])
    close_codes = ', '.join(str(value) for value in vocabulary['websocket_close_codes'])
    headers = vocabulary['headers']
    lifetimes = vocabulary['lifetimes_seconds']
    lengths = vocabulary['encoded_lengths']
    input_limits = client_policy['input_limits']
    retry_policy = client_policy['retry_policy']
    retry_attempt_limits = retry_policy['attempt_limits']
    retryable_statuses = ', '.join(
        str(status) for status in retry_policy['retryable_http_statuses']
    )
    algorithms = vocabulary['public_key_algorithms']
    if algorithms != ['ecdsa-p256-sha256-p1363']:
        raise ValueError('Unexpected Protocol v1 public-key algorithm registry')

    return '\n\n'.join((
        '// Generated by shared/tools/generate_companion_protocol_fixtures.py. Do not edit.\n'
        '@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)\n\n'
        'package org.rotki.mobile.core.protocol.generated\n\n'
        'import kotlin.native.HiddenFromObjC',
        '\n'.join((
            '@HiddenFromObjC',
            f'public const val PROTOCOL_VOCABULARY_SCHEMA_VERSION: Int = {vocabulary["schema_version"]}',
            '@HiddenFromObjC',
            f'public val SUPPORTED_PROTOCOL_VERSIONS: Set<Int> = setOf({versions})',
            '@HiddenFromObjC',
            f'public val WEBSOCKET_CLOSE_CODES: Set<Int> = setOf({close_codes})',
            '@HiddenFromObjC',
            f'public const val DEVICE_PROOF_ALGORITHM: String = "{algorithms[0]}"',
        )),
        '\n'.join((
            '@HiddenFromObjC',
            'public object ProtocolHeaders {',
            f'    public const val IdempotencyKey: String = "{headers["idempotency_key"]}"',
            f'    public const val Protocol: String = "{headers["protocol"]}"',
            '}',
        )),
        '\n'.join((
            '@HiddenFromObjC',
            'public object ProtocolLifetimesSeconds {',
            f'    public const val AccessSession: Long = {lifetimes["access_session"]}L',
            f'    public const val Challenge: Long = {lifetimes["challenge"]}L',
            f'    public const val Pairing: Long = {lifetimes["pairing"]}L',
            '    public const val ProactiveRenewalWindow: Long = '
            f'{lifetimes["proactive_renewal_window"]}L',
            f'    public const val RegistrationReplay: Long = {lifetimes["registration_replay"]}L',
            '}',
        )),
        '\n'.join((
            '@HiddenFromObjC',
            'public object ProtocolEncodedLengths {',
            f'    public const val AccessSessionCredential: Int = {lengths["access_session_credential"]}',
            f'    public const val ChallengeId: Int = {lengths["challenge_id"]}',
            f'    public const val DeviceSessionId: Int = {lengths["device_session_id"]}',
            f'    public const val IdempotencyKey: Int = {lengths["idempotency_key"]}',
            f'    public const val Nonce: Int = {lengths["nonce"]}',
            f'    public const val PairingCredential: Int = {lengths["pairing_credential"]}',
            f'    public const val PairingId: Int = {lengths["pairing_id"]}',
            f'    public const val P1363Signature: Int = {lengths["p1363_signature"]}',
            f'    public const val P256PublicKey: Int = {lengths["p256_public_key"]}',
            '}',
        )),
        '\n'.join((
            '@HiddenFromObjC',
            'public object ProtocolClientInputLimits {',
            '    public const val MaximumControlResponseBytes: Int = '
            f'{input_limits["maximum_control_response_bytes"]}',
            '    public const val MaximumJsonNestingDepth: Int = '
            f'{input_limits["maximum_json_nesting_depth"]}',
            '    public const val MaximumWebSocketBufferedFrames: Int = '
            f'{input_limits["maximum_websocket_buffered_frames"]}',
            '    public const val MaximumWebSocketEventBytes: Long = '
            f'{input_limits["maximum_websocket_event_bytes"]}L',
            '}',
        )),
        '\n'.join((
            '@HiddenFromObjC',
            'public object ProtocolRetryPolicy {',
            '    public const val SafeGetMaximumAttempts: Int = '
            f'{retry_attempt_limits["safe_get"]}',
            '    public const val IdempotentWriteMaximumAttempts: Int = '
            f'{retry_attempt_limits["idempotent_write"]}',
            '    public const val ChallengeOrProofMaximumAttempts: Int = '
            f'{retry_attempt_limits["challenge_or_proof"]}',
            '    public val RetryableHttpStatuses: Set<Int> = '
            f'setOf({retryable_statuses})',
            '    public const val BaseDelayMilliseconds: Long = '
            f'{retry_policy["base_delay_milliseconds"]}L',
            '    public const val MaximumDelayMilliseconds: Long = '
            f'{retry_policy["maximum_delay_milliseconds"]}L',
            '    public const val MaximumRetryAfterSeconds: Long = '
            f'{retry_policy["maximum_retry_after_seconds"]}L',
            '}',
        )),
        capability_block,
        *enum_blocks,
    )) + '\n'


def generate_fixtures() -> str:
    fixture_sources = (
        ('PROTOCOL_VOCABULARY_JSON', VOCABULARY_PATH),
        ('PROTOCOL_GENERATED_NAMES_JSON', NAMES_PATH),
        ('PROTOCOL_P0_1_CASES_JSON', CASES_PATH),
        ('PROTOCOL_GOLDEN_VECTORS_JSON', GOLDEN_PATH),
        ('PROTOCOL_CLIENT_POLICY_CASES_JSON', CLIENT_POLICY_PATH),
    )
    constants = []
    for constant_name, path in fixture_sources:
        raw, _ = load_canonical_json(path)
        constants.append(
            f'public const val {constant_name}: String = """{kotlin_raw_string(raw)}"""',
        )
    return '\n\n'.join((
        '// Generated by shared/tools/generate_companion_protocol_fixtures.py. Do not edit.\n'
        'package org.rotki.mobile.core.protocol.generated',
        *constants,
    )) + '\n'


def write_or_check(path: Path, generated: str, write: bool) -> bool:
    if write:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(generated, encoding='utf-8')
        return True
    if not path.exists() or path.read_text(encoding='utf-8') != generated:
        print(f'{path.relative_to(REPOSITORY_ROOT)} is stale')
        return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--check', action='store_true')
    action.add_argument('--write', action='store_true')
    args = parser.parse_args()

    outputs = (
        (VOCABULARY_KOTLIN_PATH, generate_vocabulary()),
        (FIXTURES_KOTLIN_PATH, generate_fixtures()),
    )
    success = all(write_or_check(path, generated, args.write) for path, generated in outputs)
    return 0 if success else 1


if __name__ == '__main__':
    raise SystemExit(main())
