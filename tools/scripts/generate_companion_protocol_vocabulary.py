"""Generate cross-language names from the Companion Protocol vocabulary."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Final

REPO_ROOT: Final = Path(__file__).resolve().parents[2]
VOCABULARY_PATH: Final = REPO_ROOT / 'mobile' / 'protocol' / 'v1' / 'vocabulary.json'
OUTPUT_PATH: Final = REPO_ROOT / 'mobile' / 'protocol' / 'v1' / 'generated_names.json'
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


def _pascal_case(value: str) -> str:
    return ''.join(word.capitalize() for word in value.split('_'))


def generate_names() -> str:
    vocabulary = json.loads(VOCABULARY_PATH.read_text(encoding='utf-8'))
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


def main() -> int:
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--check', action='store_true')
    action.add_argument('--write', action='store_true')
    args = parser.parse_args()
    generated = generate_names()

    if args.check:
        if not OUTPUT_PATH.exists() or OUTPUT_PATH.read_text(encoding='utf-8') != generated:
            print(f'{OUTPUT_PATH.relative_to(REPO_ROOT)} is stale')
            return 1
        return 0

    OUTPUT_PATH.write_text(generated, encoding='utf-8')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
