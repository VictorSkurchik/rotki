"""Focused tests for disposable Companion Pairing state."""

from __future__ import annotations

import json
import math
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from typing import TYPE_CHECKING

import pytest

from rotkehlchen.api.companion.codec import (
    EngineOrigin,
    decode_idempotency_key,
    decode_pairing_credential,
    decode_pairing_id,
)
from rotkehlchen.api.companion.pairing_store import (
    BROWSER_SESSION_HASH_DOMAIN,
    PAIRING_CREDENTIAL_HASH_DOMAIN,
    PAIRING_LIFETIME_SECONDS,
    InvalidPairingStoreInput,
    PairingCapacityError,
    PairingIdempotencyConflict,
    PairingStore,
    PairingStoreUnavailable,
)
from rotkehlchen.db.profile import ProfileID

if TYPE_CHECKING:
    from collections.abc import Callable


PROFILE_A = ProfileID(b'\x11' * 32)
PROFILE_B = ProfileID(b'\x22' * 32)
BROWSER_SESSION_A = 'a' * 32
BROWSER_SESSION_B = 'b' * 32
IDEMPOTENCY_KEY_A = bytes(range(0x70, 0x80))
IDEMPOTENCY_KEY_B = bytes(range(0x80, 0x90))
ORIGIN_A = EngineOrigin.parse('https://rotki.example')
ORIGIN_B = EngineOrigin.parse('https://other.example')
REPO_ROOT = Path(__file__).resolve().parents[4]
GOLDEN_VECTORS = json.loads(
    (REPO_ROOT / 'mobile' / 'protocol' / 'v1' / 'golden_vectors.json').read_text(
        encoding='utf-8',
    ),
)
PAIRING_GOLDEN = next(
    example for example in GOLDEN_VECTORS['success_examples']
    if example['id'] == 'create_pairing'
)


@dataclass
class FakeClock:
    wall: float = 1_786_550_280.0
    monotonic: float = 0.0

    def wall_time(self) -> float:
        return self.wall

    def monotonic_time(self) -> float:
        return self.monotonic


class FakeTimer:
    def __init__(self, delay: float, callback: Callable[[], None]) -> None:
        self.delay = delay
        self.callback = callback
        self.cancelled = False
        self.fired = False

    def cancel(self) -> None:
        self.cancelled = True

    def fire(self) -> None:
        if self.cancelled is False:
            self.fired = True
            self.callback()


class FakeTimerFactory:
    def __init__(self) -> None:
        self.timers: list[FakeTimer] = []

    def __call__(self, delay: float, callback: Callable[[], None]) -> FakeTimer:
        timer = FakeTimer(delay=delay, callback=callback)
        self.timers.append(timer)
        return timer

    @property
    def latest(self) -> FakeTimer:
        return self.timers[-1]


class SequenceRandom:
    def __init__(self, values: list[bytes]) -> None:
        self.values = values
        self.calls: list[int] = []

    def __call__(self, length: int) -> bytes:
        self.calls.append(length)
        return self.values.pop(0)


class CounterRandom:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._counter = 0

    def __call__(self, length: int) -> bytes:
        with self._lock:
            self._counter += 1
            return self._counter.to_bytes(length, byteorder='big')


def _make_store(
        *,
        clock: FakeClock | None = None,
        random_values: list[bytes] | None = None,
        max_active_pairings: int = 32,
        generation_attempts: int = 8,
) -> tuple[PairingStore, FakeClock, SequenceRandom, FakeTimerFactory]:
    selected_clock = clock or FakeClock()
    selected_random = SequenceRandom(random_values or [b'\x01' * 16, b'\x02' * 32])
    timer_factory = FakeTimerFactory()
    return (
        PairingStore(
            max_active_pairings=max_active_pairings,
            generation_attempts=generation_attempts,
            wall_clock=selected_clock.wall_time,
            monotonic_clock=selected_clock.monotonic_time,
            random_bytes=selected_random,
            timer_factory=timer_factory,
        ),
        selected_clock,
        selected_random,
        timer_factory,
    )


def _create(
        store: PairingStore,
        *,
        profile_id: ProfileID = PROFILE_A,
        browser_session_id: str = BROWSER_SESSION_A,
        idempotency_key: bytes = IDEMPOTENCY_KEY_A,
        engine_origin: EngineOrigin = ORIGIN_A,
):
    return store.create(
        profile_id=profile_id,
        browser_session_id=browser_session_id,
        idempotency_key=idempotency_key,
        engine_origin=engine_origin,
    )


def test_pairing_creation_matches_golden_qr_bytes() -> None:
    """The Engine produces the fixed compact field order and exact two-minute expiry."""
    golden_result = PAIRING_GOLDEN['response']['result']
    golden_qr = json.loads(golden_result['qr_payload'])
    store, _clock, random_source, timers = _make_store(
        random_values=[
            decode_pairing_id(golden_result['pairing_id']),
            decode_pairing_credential(golden_qr['pairing_credential']),
        ],
    )

    creation = store.create(
        profile_id=PROFILE_A,
        browser_session_id=BROWSER_SESSION_A,
        idempotency_key=decode_idempotency_key(
            PAIRING_GOLDEN['request']['headers']['Idempotency-Key'],
        ),
        engine_origin=EngineOrigin.parse(golden_qr['engine_origin']),
    )

    assert creation.as_result() == golden_result
    assert creation.qr_payload.encode('utf-8').endswith(b'}')
    assert b'\n' not in creation.qr_payload.encode('utf-8')
    assert random_source.calls == [16, 32]
    assert timers.latest.delay == PAIRING_LIFETIME_SECONDS


def test_exact_idempotency_replay_uses_no_randomness() -> None:
    """An exact retained key returns the same secret-bearing object without new entropy."""
    store, _clock, random_source, _timers = _make_store()
    first = _create(store)

    replay = _create(store)

    assert replay is first
    assert replay.as_result() == first.as_result()
    assert random_source.calls == [16, 32]
    assert store.active_count() == 1


def test_idempotency_conflict_and_scope_boundaries() -> None:
    """Origin is semantic input while Profile and browser session partition each key."""
    store, _clock, random_source, _timers = _make_store(random_values=[
        b'\x01' * 16,
        b'\x11' * 32,
        b'\x02' * 16,
        b'\x22' * 32,
        b'\x03' * 16,
        b'\x33' * 32,
    ])
    _create(store)

    with pytest.raises(PairingIdempotencyConflict):
        _create(store, engine_origin=ORIGIN_B)
    from_other_browser = _create(store, browser_session_id=BROWSER_SESSION_B)
    from_other_profile = _create(store, profile_id=PROFILE_B)

    assert from_other_browser.pairing_id != from_other_profile.pairing_id
    assert store.active_count() == 3
    assert random_source.calls == [16, 32] * 3


def test_active_timer_purges_secret_replay_at_exact_monotonic_expiry() -> None:
    """Expiry is traffic-independent, exact at 120 seconds, and robust to an early timer."""
    store, clock, random_source, timers = _make_store(random_values=[
        b'\x01' * 16,
        b'\x11' * 32,
        b'\x02' * 16,
        b'\x22' * 32,
    ])
    first = _create(store)
    first_timer = timers.latest

    clock.monotonic = 119.0
    first_timer.fire()
    assert len(store._records) == 1  # pylint: disable=protected-access
    assert timers.latest.delay == 1.0

    clock.monotonic = 120.0
    timers.latest.fire()
    assert len(store._records) == 0  # pylint: disable=protected-access
    assert len(store._creation_replays) == 0  # pylint: disable=protected-access
    assert len(store._pairing_by_credential_hash) == 0  # pylint: disable=protected-access

    replacement = _create(store)
    assert replacement is not first
    assert replacement.pairing_id != first.pairing_id
    assert random_source.calls == [16, 32] * 2


def test_pairing_id_and_credential_collisions_are_bounded() -> None:
    """Both random namespaces retry together and exhaustion leaves no partial record."""
    pairing_id_a = b'\x01' * 16
    credential_a = b'\x11' * 32
    pairing_id_b = b'\x02' * 16
    pairing_id_c = b'\x03' * 16
    credential_c = b'\x33' * 32
    store, _clock, random_source, _timers = _make_store(
        generation_attempts=3,
        random_values=[
            pairing_id_a,
            credential_a,
            pairing_id_a,
            b'\x22' * 32,
            pairing_id_b,
            credential_a,
            pairing_id_c,
            credential_c,
        ],
    )
    _create(store)

    second = _create(store, idempotency_key=IDEMPOTENCY_KEY_B)

    assert decode_pairing_id(second.pairing_id) == pairing_id_c
    assert random_source.calls == [16, 32] * 4
    assert store.active_count() == 2

    random_source.values.extend([
        pairing_id_a,
        credential_a,
        pairing_id_a,
        credential_a,
        pairing_id_a,
        credential_a,
    ])
    with pytest.raises(PairingStoreUnavailable):
        _create(store, idempotency_key=b'\x90' * 16)
    assert store.active_count() == 2


def test_credential_index_uses_a_domain_separated_hash() -> None:
    """The verifier index never uses a raw or generic SHA-256 credential value."""
    credential = b'credential-marker'.ljust(32, b'!')
    store, _clock, _random_source, _timers = _make_store(
        random_values=[b'\x01' * 16, credential],
    )
    _create(store)
    expected = sha256(b''.join((PAIRING_CREDENTIAL_HASH_DOMAIN, b'\x00', credential))).digest()

    assert set(store._pairing_by_credential_hash) == {  # pylint: disable=protected-access
        expected,
    }
    assert sha256(credential).digest() not in store._pairing_by_credential_hash  # pylint: disable=protected-access


def test_capacity_reports_earliest_integer_retry_after() -> None:
    """The bounded store admits replay at capacity and rejects new work before RNG."""
    store, clock, random_source, _timers = _make_store(
        max_active_pairings=2,
        random_values=[
            b'\x01' * 16,
            b'\x11' * 32,
            b'\x02' * 16,
            b'\x22' * 32,
        ],
    )
    first = _create(store)
    _create(store, idempotency_key=IDEMPOTENCY_KEY_B)
    assert _create(store) is first

    with pytest.raises(PairingCapacityError) as error:
        _create(store, idempotency_key=b'\x90' * 16)
    assert error.value.retry_after_seconds == 120

    clock.monotonic = 1.25
    with pytest.raises(PairingCapacityError) as later_error:
        _create(store, idempotency_key=b'\x91' * 16)
    assert later_error.value.retry_after_seconds == 119
    assert random_source.calls == [16, 32] * 2


def test_cancellation_hides_unknown_and_cross_profile_pairings() -> None:
    """Every valid cancellation succeeds while only the owner Profile mutates state."""
    store, _clock, _random_source, timers = _make_store()
    creation = _create(store)
    pairing_id = decode_pairing_id(creation.pairing_id)
    scheduled_timer = timers.latest

    assert store.cancel(profile_id=PROFILE_B, pairing_id=pairing_id) is True
    assert store.cancel(profile_id=PROFILE_A, pairing_id=b'\xff' * 16) is True
    assert store.active_count() == 1
    assert scheduled_timer.cancelled is False

    assert store.cancel(profile_id=PROFILE_A, pairing_id=pairing_id) is True
    assert store.cancel(profile_id=PROFILE_A, pairing_id=pairing_id) is True
    assert store.active_count() == 0
    assert scheduled_timer.cancelled is True


def test_clear_cancels_timer_and_discards_creation_replay() -> None:
    """Lifecycle cleanup removes all secret-bearing state and makes old timers inert."""
    store, _clock, random_source, timers = _make_store(random_values=[
        b'\x01' * 16,
        b'\x11' * 32,
        b'\x02' * 16,
        b'\x22' * 32,
    ])
    first = _create(store)
    timer = timers.latest

    store.clear()

    assert timer.cancelled is True
    assert store.active_count() == 0
    second = _create(store)
    assert second is not first
    assert random_source.calls == [16, 32] * 2


def test_concurrent_creation_never_exceeds_capacity() -> None:
    """The lock covers idempotency, capacity, entropy allocation, and timer replacement."""
    clock = FakeClock()
    timers = FakeTimerFactory()
    store = PairingStore(
        max_active_pairings=8,
        wall_clock=clock.wall_time,
        monotonic_clock=clock.monotonic_time,
        random_bytes=CounterRandom(),
        timer_factory=timers,
    )

    def create(index: int) -> str:
        try:
            return _create(store, idempotency_key=index.to_bytes(16, byteorder='big')).pairing_id
        except PairingCapacityError:
            return 'capacity'

    with ThreadPoolExecutor(max_workers=32) as executor:
        outcomes = list(executor.map(create, range(32)))

    successes = [outcome for outcome in outcomes if outcome != 'capacity']
    assert len(successes) == 8
    assert len(set(successes)) == 8
    assert outcomes.count('capacity') == 24
    assert store.active_count() == 8
    assert sum(timer.cancelled is False for timer in timers.timers) == 1


@pytest.mark.parametrize('constructor_argument', [0, -1, True])
def test_constructor_rejects_invalid_bounds(constructor_argument: object) -> None:
    """Capacity and retry bounds cannot silently become unbounded or Boolean."""
    with pytest.raises(InvalidPairingStoreInput):
        PairingStore(max_active_pairings=constructor_argument)  # type: ignore[arg-type]
    with pytest.raises(InvalidPairingStoreInput):
        PairingStore(generation_attempts=constructor_argument)  # type: ignore[arg-type]


@pytest.mark.parametrize(
    ('field', 'value'),
    [
        ('profile_id', ProfileID(b'\x01' * 31)),
        ('browser_session_id', 'not-a-session'),
        ('browser_session_id', 'A' * 32),
        ('idempotency_key', b'\x01' * 15),
        ('engine_origin', 'https://rotki.example'),
    ],
)
def test_create_rejects_invalid_caller_inputs(field: str, value: object) -> None:
    """Store boundaries reject coercion before creating disposable authority."""
    store, _clock, random_source, timers = _make_store()
    arguments: dict[str, object] = {
        'profile_id': PROFILE_A,
        'browser_session_id': BROWSER_SESSION_A,
        'idempotency_key': IDEMPOTENCY_KEY_A,
        'engine_origin': ORIGIN_A,
    }
    arguments[field] = value

    with pytest.raises(InvalidPairingStoreInput):
        store.create(**arguments)  # type: ignore[arg-type]
    assert len(random_source.calls) == 0
    assert len(timers.timers) == 0


@pytest.mark.parametrize('invalid_clock', [math.inf, math.nan, -1.0])
def test_invalid_clock_or_random_source_fails_closed(invalid_clock: float) -> None:
    """Broken injected authority cannot create a Pairing or leave a timer behind."""
    clock = FakeClock(monotonic=invalid_clock)
    store, _clock, _random_source, timers = _make_store(clock=clock)
    with pytest.raises(PairingStoreUnavailable):
        _create(store)
    assert len(timers.timers) == 0

    valid_clock = FakeClock()
    invalid_random = SequenceRandom([b'too short'])
    invalid_random_store = PairingStore(
        wall_clock=valid_clock.wall_time,
        monotonic_clock=valid_clock.monotonic_time,
        random_bytes=invalid_random,
        timer_factory=timers,
    )
    with pytest.raises(PairingStoreUnavailable):
        _create(invalid_random_store)


@pytest.mark.parametrize('clock_name', ['wall_clock', 'monotonic_clock'])
def test_clock_os_error_is_redacted(clock_name: str) -> None:
    """A platform clock failure maps to one fixed error without retaining authority."""
    clock = FakeClock()
    timers = FakeTimerFactory()

    def failing_clock() -> float:
        raise OSError('sensitive clock marker')

    store = PairingStore(
        wall_clock=failing_clock if clock_name == 'wall_clock' else clock.wall_time,
        monotonic_clock=(
            failing_clock if clock_name == 'monotonic_clock' else clock.monotonic_time
        ),
        random_bytes=SequenceRandom([b'\x01' * 16, b'\x02' * 32]),
        timer_factory=timers,
    )

    with pytest.raises(PairingStoreUnavailable) as error:
        _create(store)
    assert 'marker' not in str(error.value)
    assert len(store._records) == 0  # pylint: disable=protected-access
    assert len(timers.timers) == 0


def test_timer_clock_os_error_clears_secret_state() -> None:
    """An asynchronous clock failure clears Pairing plaintext without escaping its callback."""
    clock = FakeClock()
    timers = FakeTimerFactory()
    monotonic_reads = iter((clock.monotonic, OSError('sensitive timer clock marker')))

    def monotonic_clock() -> float:
        if isinstance(value := next(monotonic_reads), OSError):
            raise value
        return value

    store = PairingStore(
        wall_clock=clock.wall_time,
        monotonic_clock=monotonic_clock,
        random_bytes=SequenceRandom([b'\x01' * 16, b'\x02' * 32]),
        timer_factory=timers,
    )
    _create(store)

    timers.latest.fire()

    assert len(store._records) == 0  # pylint: disable=protected-access
    assert len(store._creation_replays) == 0  # pylint: disable=protected-access
    assert len(store._pairing_by_credential_hash) == 0  # pylint: disable=protected-access


def test_timer_start_failure_rolls_back_all_secret_state() -> None:
    """A Pairing is never returned when traffic-independent expiry cannot be armed."""
    clock = FakeClock()

    def failing_timer(_delay: float, _callback: Callable[[], None]) -> FakeTimer:
        raise RuntimeError('timer marker must remain redacted')

    store = PairingStore(
        wall_clock=clock.wall_time,
        monotonic_clock=clock.monotonic_time,
        random_bytes=SequenceRandom([b'\x01' * 16, b'\x02' * 32]),
        timer_factory=failing_timer,
    )

    with pytest.raises(PairingStoreUnavailable) as error:
        _create(store)
    assert 'marker' not in str(error.value)
    assert store.active_count() == 0


def test_invalid_timer_handle_rolls_back_all_secret_state() -> None:
    """A timer without cancellation authority cannot retain a secret Pairing."""
    clock = FakeClock()

    def invalid_timer(_delay: float, _callback: Callable[[], None]) -> object:
        return object()

    store = PairingStore(
        wall_clock=clock.wall_time,
        monotonic_clock=clock.monotonic_time,
        random_bytes=SequenceRandom([b'\x01' * 16, b'\x02' * 32]),
        timer_factory=invalid_timer,  # type: ignore[arg-type]
    )

    with pytest.raises(PairingStoreUnavailable):
        _create(store)
    assert store.active_count() == 0


def test_random_source_exception_is_redacted() -> None:
    """Any ordinary entropy-seam failure maps to one fixed unavailable error."""
    clock = FakeClock()

    def failing_random(_length: int) -> bytes:
        raise ValueError('sensitive random source marker')

    store = PairingStore(
        wall_clock=clock.wall_time,
        monotonic_clock=clock.monotonic_time,
        random_bytes=failing_random,
        timer_factory=FakeTimerFactory(),
    )

    with pytest.raises(PairingStoreUnavailable) as error:
        _create(store)
    assert 'marker' not in str(error.value)


def test_representations_and_errors_are_redacted() -> None:
    """Secrets, origins, Profile IDs, and caller IDs have no diagnostic representation."""
    credential = b'sensitive-credential-marker!!!'.ljust(32, b'!')
    store, _clock, _random_source, _timers = _make_store(
        max_active_pairings=1,
        random_values=[b'\xdd' * 16, credential],
    )
    creation = _create(store)
    with pytest.raises(PairingCapacityError) as capacity_error:
        _create(store, idempotency_key=IDEMPOTENCY_KEY_B)
    with pytest.raises(PairingIdempotencyConflict) as conflict_error:
        _create(store, engine_origin=ORIGIN_B)

    representations = (
        repr(store),
        repr(creation),
        str(creation),
        repr(next(iter(store._records.values()))),  # pylint: disable=protected-access
        repr(next(iter(store._creation_replays.values()))),  # pylint: disable=protected-access
        repr(capacity_error.value),
        str(capacity_error.value),
        repr(conflict_error.value),
        str(conflict_error.value),
    )
    forbidden = (
        'sensitive-credential-marker',
        'rotki.example',
        '11' * 32,
        BROWSER_SESSION_A,
        creation.pairing_id,
    )
    assert all(
        marker not in representation
        for marker in forbidden
        for representation in representations
    )
    assert BROWSER_SESSION_HASH_DOMAIN != PAIRING_CREDENTIAL_HASH_DOMAIN
