#!/usr/bin/env python3
"""Generate and verify the P0.2 exact-decimal characterization vectors."""

import argparse
import json
import re
import sys
from decimal import (
    ROUND_HALF_EVEN,
    DivisionByZero,
    Inexact,
    getcontext,
    localcontext,
)
from operator import itemgetter
from pathlib import Path
from typing import Any, Final

SPIKE_DIR: Final = Path(__file__).resolve().parents[1]
REPOSITORY_ROOT: Final = Path(__file__).resolve().parents[4]
VECTORS_PATH: Final = SPIKE_DIR / 'vectors' / 'exact-decimal-v1.json'
KOTLIN_PATH: Final = (
    SPIKE_DIR
    / 'src/commonTest/kotlin/org/rotki/mobile/core/decimal/GeneratedExactDecimalVectors.kt'
)

sys.path.insert(0, str(REPOSITORY_ROOT))

from rotkehlchen.fval import FVal  # noqa: E402

PRECISION: Final = 78
MAX_ABSOLUTE_EXPONENT: Final = 1024
MAX_INPUT_LENGTH: Final = 2048
MAX_CANONICAL_LENGTH: Final = 2048
DECIMAL_PATTERN: Final = re.compile(
    r'-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?',
    re.ASCII,
)
NON_FINITE_PATTERN: Final = re.compile(
    r'[+-]?(?:nan|snan|inf|infinity)',
    re.ASCII | re.IGNORECASE,
)
UINT256_MAX: Final = (
    '115792089237316195423570985008687907853269984665640564039457584007913129639935'
)
HALF_EVEN_TIE_DOWN: Final = (
    '200000000000000000000000000000000000000000000000000000000000000000000000000001'
)
HALF_EVEN_TIE_UP: Final = (
    '200000000000000000000000000000000000000000000000000000000000000000000000000003'
)
OVER_PRECISION: Final = (
    '1234567890123456789012345678901234567890123456789012345678901234567890123456789'
)
PLAIN_TEN_TO_78: Final = '1' + ('0' * 78)
OVER_INPUT_LENGTH: Final = '0.' + ('0' * 2047)

Case = dict[str, Any]


CASES: Final[list[Case]] = [
    {'id': 'parse.zero', 'op': 'parse', 'args': {'value': '0'}},
    {'id': 'parse.negative_zero', 'op': 'parse', 'args': {'value': '-0.000'}},
    {'id': 'parse.scale_normalized', 'op': 'parse', 'args': {'value': '123.45000'}},
    {'id': 'parse.negative', 'op': 'parse', 'args': {'value': '-987654321.00001'}},
    {'id': 'parse.scientific_positive', 'op': 'parse', 'args': {'value': '1.49298E+12'}},
    {'id': 'parse.scientific_negative', 'op': 'parse', 'args': {'value': '7.5e-9'}},
    {'id': 'parse.scientific_zero', 'op': 'parse', 'args': {'value': '-0e+20'}},
    {
        'id': 'parse.uint256_max',
        'op': 'parse',
        'args': {'value': UINT256_MAX},
    },
    {'id': 'parse.tiny', 'op': 'parse', 'args': {'value': '1e-100'}},
    {'id': 'inspect.negative', 'op': 'inspect', 'args': {'value': '-120.3400'}},
    {'id': 'inspect.zero', 'op': 'inspect', 'args': {'value': '-0.000'}},
    {'id': 'compare.less', 'op': 'compare', 'args': {'lhs': '-0.1', 'rhs': '0'}},
    {'id': 'compare.equal_scale', 'op': 'compare', 'args': {'lhs': '1.0', 'rhs': '1.00'}},
    {'id': 'compare.greater', 'op': 'compare', 'args': {'lhs': '1000', 'rhs': '999.999'}},
    {'id': 'add.fractions', 'op': 'add', 'args': {'lhs': '0.1', 'rhs': '0.2'}},
    {
        'id': 'add.uint256_carry',
        'op': 'add',
        'args': {
            'lhs': UINT256_MAX,
            'rhs': '1',
        },
    },
    {'id': 'add.cancellation', 'op': 'add', 'args': {'lhs': '123.456', 'rhs': '-123.456'}},
    {'id': 'add.precision_exceeded', 'op': 'add', 'args': {'lhs': '1e78', 'rhs': '1'}},
    {'id': 'subtract.fractions', 'op': 'subtract', 'args': {'lhs': '1000', 'rhs': '0.001'}},
    {'id': 'subtract.negative', 'op': 'subtract', 'args': {'lhs': '-5', 'rhs': '12.25'}},
    {'id': 'multiply.fractions', 'op': 'multiply', 'args': {'lhs': '123.45', 'rhs': '0.006'}},
    {'id': 'multiply.sign', 'op': 'multiply', 'args': {'lhs': '-2.5', 'rhs': '-4'}},
    {
        'id': 'multiply.precision_exceeded',
        'op': 'multiply',
        'args': {
            'lhs': '9999999999999999999999999999999999999999',
            'rhs': '9999999999999999999999999999999999999999',
        },
    },
    {
        'id': 'multiply.range_exceeded',
        'op': 'multiply',
        'args': {'lhs': '1e1024', 'rhs': '1e1024'},
    },
    {'id': 'divide.terminating', 'op': 'divide', 'args': {'lhs': '10', 'rhs': '4'}},
    {'id': 'divide.repeating_thirds', 'op': 'divide', 'args': {'lhs': '1', 'rhs': '3'}},
    {'id': 'divide.repeating_round_up', 'op': 'divide', 'args': {'lhs': '2', 'rhs': '3'}},
    {'id': 'divide.negative', 'op': 'divide', 'args': {'lhs': '-1', 'rhs': '8'}},
    {'id': 'divide.zero', 'op': 'divide', 'args': {'lhs': '-0', 'rhs': '3'}},
    {'id': 'divide.mixed_scale', 'op': 'divide', 'args': {'lhs': '123.45', 'rhs': '0.0067'}},
    {'id': 'divide.scientific_scale', 'op': 'divide', 'args': {'lhs': '1e-100', 'rhs': '3e20'}},
    {'id': 'divide.uint256_max', 'op': 'divide', 'args': {'lhs': UINT256_MAX, 'rhs': '7'}},
    {
        'id': 'divide.small_by_large',
        'op': 'divide',
        'args': {'lhs': '0.00000001', 'rhs': '999999'},
    },
    {
        'id': 'divide.large_by_small',
        'op': 'divide',
        'args': {'lhs': '999999', 'rhs': '0.00000001'},
    },
    {
        'id': 'divide.range_exceeded',
        'op': 'divide',
        'args': {'lhs': '1e1024', 'rhs': '1e-1024'},
    },
    {
        'id': 'divide.plain_power_of_ten',
        'op': 'divide',
        'args': {'lhs': PLAIN_TEN_TO_78, 'rhs': '1'},
    },
    {
        'id': 'divide.scientific_power_of_ten',
        'op': 'divide',
        'args': {'lhs': '1e78', 'rhs': '1'},
    },
    {
        'id': 'divide.trailing_zero_denominator',
        'op': 'divide',
        'args': {'lhs': '1', 'rhs': PLAIN_TEN_TO_78},
    },
    {
        'id': 'divide.half_even_tie_down',
        'op': 'divide',
        'args': {
            'lhs': HALF_EVEN_TIE_DOWN,
            'rhs': '2',
        },
    },
    {
        'id': 'divide.half_even_tie_up',
        'op': 'divide',
        'args': {
            'lhs': HALF_EVEN_TIE_UP,
            'rhs': '2',
        },
    },
    {'id': 'divide.by_zero', 'op': 'divide', 'args': {'lhs': '1', 'rhs': '-0.00'}},
    {
        'id': 'round.half_even_down',
        'op': 'round',
        'args': {'value': '2.345', 'fraction_digits': 2},
    },
    {'id': 'round.half_even_up', 'op': 'round', 'args': {'value': '2.355', 'fraction_digits': 2}},
    {
        'id': 'round.negative_half_even',
        'op': 'round',
        'args': {'value': '-2.345', 'fraction_digits': 2},
    },
    {'id': 'round.carry', 'op': 'round', 'args': {'value': '999.995', 'fraction_digits': 2}},
    {'id': 'round.zero_padding', 'op': 'round', 'args': {'value': '12', 'fraction_digits': 4}},
    {'id': 'round.integer', 'op': 'round', 'args': {'value': '2.5', 'fraction_digits': 0}},
    {'id': 'round.invalid_scale', 'op': 'round', 'args': {'value': '1', 'fraction_digits': -1}},
    {
        'id': 'sort.stable_numeric',
        'op': 'sort',
        'args': {
            'values': [
                {'id': 'positive', 'value': '10'},
                {'id': 'equal_a', 'value': '1.0'},
                {'id': 'negative', 'value': '-2'},
                {'id': 'equal_b', 'value': '1.00'},
                {'id': 'zero', 'value': '-0'},
            ],
        },
    },
    {
        'id': 'sum.ordered_exact',
        'op': 'sum',
        'args': {'values': ['0.1', '0.2', '-0.05', '1000000000000000000000000000000']},
    },
    {
        'id': 'sum.cancellation',
        'op': 'sum',
        'args': {'values': ['1000000000000000000000000', '1', '-1000000000000000000000000']},
    },
    {
        'id': 'sum.precision_exceeded',
        'op': 'sum',
        'args': {'values': ['1e78', '1', '-1e78']},
    },
    {'id': 'serialize.string', 'op': 'serialize', 'args': {'value': '123.4500'}},
    {'id': 'reject.nan', 'op': 'parse', 'args': {'value': 'NaN'}},
    {'id': 'reject.negative_infinity', 'op': 'parse', 'args': {'value': '-Infinity'}},
    {'id': 'reject.snan', 'op': 'parse', 'args': {'value': 'sNaN'}},
    {'id': 'reject.empty', 'op': 'parse', 'args': {'value': ''}},
    {'id': 'reject.whitespace', 'op': 'parse', 'args': {'value': ' 1.0'}},
    {'id': 'reject.leading_plus', 'op': 'parse', 'args': {'value': '+1'}},
    {'id': 'reject.leading_zero', 'op': 'parse', 'args': {'value': '01'}},
    {'id': 'reject.no_integer', 'op': 'parse', 'args': {'value': '.5'}},
    {'id': 'reject.no_fraction', 'op': 'parse', 'args': {'value': '5.'}},
    {'id': 'reject.underscore', 'op': 'parse', 'args': {'value': '1_000'}},
    {'id': 'reject.locale_comma', 'op': 'parse', 'args': {'value': '1,5'}},
    {'id': 'reject.unicode_digit', 'op': 'parse', 'args': {'value': '\u0661.\u0665'}},
    {'id': 'reject.incomplete_exponent', 'op': 'parse', 'args': {'value': '1e'}},
    {'id': 'reject.json_number', 'op': 'parse', 'args': {'value': 1}},
    {'id': 'reject.json_boolean', 'op': 'parse', 'args': {'value': True}},
    {'id': 'reject.json_null', 'op': 'parse', 'args': {'value': None}},
    {'id': 'reject.exponent_range', 'op': 'parse', 'args': {'value': '1e1025'}},
    {'id': 'reject.input_length', 'op': 'parse', 'args': {'value': OVER_INPUT_LENGTH}},
    {
        'id': 'reject.precision',
        'op': 'parse',
        'args': {'value': OVER_PRECISION},
    },
]


class ContractError(ValueError):
    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


def normalized_significant_digits(raw: str) -> int:
    mantissa = re.split(r'[eE]', raw, maxsplit=1)[0]
    digits = mantissa.lstrip('-').replace('.', '').strip('0')
    return max(1, len(digits))


def strict_fval(raw: object) -> FVal:
    if not isinstance(raw, str):
        raise ContractError('invalid_type')
    if len(raw) > MAX_INPUT_LENGTH:
        raise ContractError('range_exceeded')
    if NON_FINITE_PATTERN.fullmatch(raw):
        raise ContractError('non_finite')
    if DECIMAL_PATTERN.fullmatch(raw) is None:
        raise ContractError('invalid_syntax')
    exponent_match = re.search(r'[eE]([+-]?[0-9]+)$', raw, re.ASCII)
    if exponent_match is not None and abs(int(exponent_match.group(1))) > MAX_ABSOLUTE_EXPONENT:
        raise ContractError('range_exceeded')
    if normalized_significant_digits(raw) > PRECISION:
        raise ContractError('precision_exceeded')

    value = FVal(raw)
    canonical = str(value)
    if len(canonical) > MAX_CANONICAL_LENGTH:
        raise ContractError('range_exceeded')
    return value


def validate_result(value: FVal) -> FVal:
    if len(str(value)) > MAX_CANONICAL_LENGTH:
        raise ContractError('range_exceeded')
    return value


def success(**values: Any) -> dict[str, Any]:
    return {'status': 'ok', **values}


def failure(code: str) -> dict[str, str]:
    return {'status': 'error', 'code': code}


def exact_binary(operation: str, lhs: object, rhs: object) -> FVal:
    left = strict_fval(lhs)
    right = strict_fval(rhs)
    with localcontext() as context:
        context.prec = PRECISION
        context.rounding = ROUND_HALF_EVEN
        context.clear_flags()
        if operation == 'add':
            result = left + right
        elif operation == 'subtract':
            result = left - right
        elif operation == 'multiply':
            result = left * right
        else:
            raise AssertionError(f'Unknown exact operation: {operation}')
        if context.flags[Inexact]:
            raise ContractError('precision_exceeded')
    return validate_result(result)


def evaluate(case: Case) -> dict[str, Any]:
    operation = case['op']
    args = case['args']
    try:
        if operation == 'parse':
            return success(canonical=str(strict_fval(args['value'])))
        if operation == 'inspect':
            value = strict_fval(args['value'])
            canonical = str(value)
            sign = (
                'zero'
                if value.num.is_zero()
                else ('negative' if value.num.is_signed() else 'positive')
            )
            return success(
                canonical=canonical,
                sign=sign,
                is_zero=value.num.is_zero(),
                magnitude=str(abs(value)),
                canonical_scale=len(canonical.partition('.')[2]),
            )
        if operation == 'compare':
            lhs = strict_fval(args['lhs'])
            rhs = strict_fval(args['rhs'])
            ordering = 'less' if lhs < rhs else ('greater' if lhs > rhs else 'equal')
            return success(ordering=ordering)
        if operation in {'add', 'subtract', 'multiply'}:
            return success(canonical=str(exact_binary(operation, args['lhs'], args['rhs'])))
        if operation == 'divide':
            lhs = strict_fval(args['lhs'])
            rhs = strict_fval(args['rhs'])
            if rhs.num.is_zero():
                raise ContractError('division_by_zero')
            with localcontext() as context:
                context.prec = PRECISION
                context.rounding = ROUND_HALF_EVEN
                try:
                    result = lhs / rhs
                except DivisionByZero as error:
                    raise ContractError('division_by_zero') from error
            return success(canonical=str(validate_result(result)))
        if operation == 'round':
            fraction_digits = args['fraction_digits']
            if not isinstance(fraction_digits, int) or isinstance(fraction_digits, bool):
                raise ContractError('invalid_scale')
            if fraction_digits not in range(31):
                raise ContractError('invalid_scale')
            value = strict_fval(args['value'])
            with localcontext() as context:
                context.prec = PRECISION
                context.rounding = ROUND_HALF_EVEN
                rounded = round(value, fraction_digits)
            return success(text=format(rounded.num, f'.{fraction_digits}f'))
        if operation == 'sort':
            parsed = [(item['id'], strict_fval(item['value'])) for item in args['values']]
            return success(ordered_ids=[item[0] for item in sorted(parsed, key=itemgetter(1))])
        if operation == 'sum':
            total = FVal(0)
            for raw in args['values']:
                total = exact_binary('add', str(total), raw)
            return success(canonical=str(total))
        if operation == 'serialize':
            value = strict_fval(args['value'])
            return success(json=json.dumps(str(value), ensure_ascii=True, separators=(',', ':')))
        raise AssertionError(f'Unknown operation: {operation}')
    except ContractError as error:
        return failure(error.code)


def payload() -> dict[str, Any]:
    assert getcontext().prec == PRECISION
    assert getcontext().rounding == ROUND_HALF_EVEN
    return {
        'schema_version': 1,
        'contract': {
            'input_type': 'json_string',
            'parse_grammar': r'-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?',
            'max_significant_digits': PRECISION,
            'max_absolute_exponent': MAX_ABSOLUTE_EXPONENT,
            'max_input_length': MAX_INPUT_LENGTH,
            'max_canonical_length': MAX_CANONICAL_LENGTH,
            'canonical_form': 'minimal_plain_ascii',
            'zero_sign': 'collapsed',
            'add_subtract_multiply': 'exact_or_precision_exceeded',
            'division': {'precision_significant_digits': PRECISION, 'rounding': 'half_even'},
            'display_rounding': 'half_even',
            'sum': 'ordered_left_fold_from_zero',
            'sort': 'numeric_stable',
        },
        'cases': [{**case, 'expect': evaluate(case)} for case in CASES],
    }


def render_json(data: dict[str, Any]) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2) + '\n'


def render_kotlin(vectors_json: str) -> str:
    if '$' in vectors_json or '"""' in vectors_json:
        raise AssertionError('Vector JSON cannot be embedded safely in a Kotlin raw string')
    return f'''// Generated by python/generate_exact_decimal_vectors.py. Do not edit.
package org.rotki.mobile.core.decimal

internal val EXACT_DECIMAL_VECTORS_JSON: String = """
{vectors_json.rstrip()}
""".trimIndent()
'''


def write_or_check(write: bool) -> None:
    vectors_json = render_json(payload())
    expected = {
        VECTORS_PATH: vectors_json,
        KOTLIN_PATH: render_kotlin(vectors_json),
    }
    stale: list[Path] = []
    for path, content in expected.items():
        if write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        elif not path.exists() or path.read_text(encoding='utf-8') != content:
            stale.append(path)
    if stale:
        joined = ', '.join(str(path.relative_to(REPOSITORY_ROOT)) for path in stale)
        raise SystemExit(f'Generated exact-decimal files are stale: {joined}')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true', help='Regenerate checked-in artifacts')
    args = parser.parse_args()
    write_or_check(write=args.write)
    action = 'generated' if args.write else 'verified'
    sys.stdout.write(f'{action} {len(CASES)} exact-decimal vectors against Python FVal\n')


if __name__ == '__main__':
    main()
