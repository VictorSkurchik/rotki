import base64
import json
import logging
from collections import deque
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Event, Lock
from typing import TYPE_CHECKING, Any
from unittest.mock import patch

import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from rotkehlchen.api.companion.pairing_store import (
    PAIRING_RANDOM_GENERATION_ATTEMPTS,
    PairingCancellationOutcome,
    PairingCreated,
    PairingRegistered,
    PairingStore,
    PairingStoreFailure,
)
from rotkehlchen.api.companion.types import (
    CanonicalEngineOrigin,
    ControlStoreUnavailable,
    DevicePlatform,
    IdempotencyKey,
    InvalidControlStoreInput,
    PairingCredential,
    PairingID,
)
from rotkehlchen.db.profile import ProfileID

if TYPE_CHECKING:
    from collections.abc import Callable, Iterable

    from rotkehlchen.api.companion.control_store import ControlStore
    from rotkehlchen.api.companion.types import DeviceSessionRecord


REPO_ROOT = Path(__file__).resolve().parents[4]
GOLDEN_VECTORS = json.loads(
    (REPO_ROOT / 'mobile' / 'protocol' / 'v1' / 'golden_vectors.json').read_text(
        encoding='utf-8',
    ),
)
CREATE_EXAMPLE = next(
    example for example in GOLDEN_VECTORS['success_examples']
    if example['id'] == 'create_pairing'
)
REGISTER_EXAMPLE = next(
    example for example in GOLDEN_VECTORS['success_examples']
    if example['id'] == 'register_device_session'
)
VALID_QR_VECTOR = next(
    vector for vector in GOLDEN_VECTORS['pairing_qr_cases']
    if vector['id'] == 'valid'
)

PROFILE_A = ProfileID(bytes(range(32)))
PROFILE_B = ProfileID(bytes(reversed(range(32))))
PROFILE_C = ProfileID(b'\x80' * 32)
ORIGIN = CanonicalEngineOrigin('https://rotki.example')
OTHER_ORIGIN = CanonicalEngineOrigin('https://other.rotki.example')
PROTOCOL_VERSION = 1
ISSUED_AT = CREATE_EXAMPLE['response']['result']['expires_at'] - 120
REGISTERED_AT = REGISTER_EXAMPLE['response']['result']['device_session']['paired_at']
SESSION_ID = bytes(range(32))
HASH_KEY = b'pairing-store-test-hash-key-0001'
CHANGED_DEVICE_LABEL = 'Changed device label'
_DEFAULT_REQUEST_BODY = object()


def _p256_public_key(private_value: int) -> bytes:
    numbers = ec.derive_private_key(private_value, ec.SECP256R1()).public_key().public_numbers()
    return b'\x04' + numbers.x.to_bytes(32, 'big') + numbers.y.to_bytes(32, 'big')


OTHER_P256_PUBLIC_KEY = _p256_public_key(2)


def _decode_base64url(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


GOLDEN_QR = json.loads(CREATE_EXAMPLE['response']['result']['qr_payload'])
PAIRING_ID = PairingID(_decode_base64url(GOLDEN_QR['pairing_id']))
PAIRING_CREDENTIAL = PairingCredential(_decode_base64url(GOLDEN_QR['pairing_credential']))
IDEMPOTENCY_KEY = IdempotencyKey(_decode_base64url(
    CREATE_EXAMPLE['request']['headers']['Idempotency-Key'],
))
OTHER_IDEMPOTENCY_KEY = IdempotencyKey(b'\xa5' * 16)


def _compact_json(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()


class _AuthorityRandom:
    """Return complete ID/credential candidates in the order the store requests them."""

    def __init__(self, authorities: Iterable[tuple[bytes, bytes]]) -> None:
        self._authorities = deque(authorities)
        self._pending_credential: bytes | None = None
        self.attempts = 0

    def __call__(self, size: int) -> bytes:
        if size == 16:
            assert self._pending_credential is None
            pairing_id, self._pending_credential = self._authorities.popleft()
            self.attempts += 1
            return pairing_id
        if size == 32:
            assert self._pending_credential is not None
            credential = self._pending_credential
            self._pending_credential = None
            return credential
        raise AssertionError(f'unexpected random byte count: {size}')


class _MutableClock:
    def __init__(self, now: int) -> None:
        self.now = now

    def __call__(self) -> int:
        return self.now


_STORE_CLOCKS: dict[PairingStore, _MutableClock] = {}


class _BlockedRegistration:
    def __init__(self, register: Callable[..., DeviceSessionRecord]) -> None:
        self._register = register
        self._calls_lock = Lock()
        self.entered = Event()
        self.release = Event()
        self.calls = 0

    def __call__(self, **kwargs: Any) -> DeviceSessionRecord:
        with self._calls_lock:
            self.calls += 1
        self.entered.set()
        assert self.release.wait(timeout=10)
        return self._register(**kwargs)


def _new_store(
        control_store: ControlStore,
        authorities: Iterable[tuple[bytes, bytes]],
        **capacity_overrides: int,
) -> tuple[PairingStore, _AuthorityRandom]:
    random_bytes = _AuthorityRandom(authorities)
    clock = _MutableClock(ISSUED_AT)
    store = PairingStore(
        control_store,
        random_bytes=random_bytes,
        clock=clock,
        hash_key=HASH_KEY,
        **capacity_overrides,
    )
    _STORE_CLOCKS[store] = clock
    return store, random_bytes


def _set_now(store: PairingStore, now: int) -> None:
    _STORE_CLOCKS[store].now = now


def _issue(
        store: PairingStore,
        *,
        profile_id: ProfileID = PROFILE_A,
        origin: CanonicalEngineOrigin = ORIGIN,
        idempotency_key: IdempotencyKey = IDEMPOTENCY_KEY,
        request_body: object = _DEFAULT_REQUEST_BODY,
        now: int = ISSUED_AT,
):
    _set_now(store, now)
    return store.issue(
        profile_id=profile_id,
        engine_origin=origin,
        protocol_version=PROTOCOL_VERSION,
        idempotency_key=idempotency_key,
        request_body={} if request_body is _DEFAULT_REQUEST_BODY else request_body,
    )


def _authority(created: PairingCreated) -> tuple[PairingID, PairingCredential]:
    response = json.loads(created.response_bytes)
    qr_payload = json.loads(response['result']['qr_payload'])
    return (
        PairingID(_decode_base64url(qr_payload['pairing_id'])),
        PairingCredential(_decode_base64url(qr_payload['pairing_credential'])),
    )


def _register(
        store: PairingStore,
        p256_public_key: bytes,
        *,
        pairing_id: PairingID = PAIRING_ID,
        credential: PairingCredential = PAIRING_CREDENTIAL,
        origin: CanonicalEngineOrigin = ORIGIN,
        profile_id: ProfileID = PROFILE_A,
        idempotency_key: IdempotencyKey = IDEMPOTENCY_KEY,
        device_label: str = REGISTER_EXAMPLE['request']['body']['device_label'],
        platform: DevicePlatform = DevicePlatform.IOS,
        now: int = REGISTERED_AT,
):
    _set_now(store, now)
    return store.register(
        pairing_id=pairing_id,
        credential=credential,
        request_origin=origin,
        open_profile_id=profile_id,
        protocol_version=PROTOCOL_VERSION,
        idempotency_key=idempotency_key,
        device_label=device_label,
        platform=platform,
        public_key=p256_public_key,
    )


def _cancel(
        store: PairingStore,
        *,
        profile_id: ProfileID,
        pairing_id: PairingID,
        now: int,
) -> PairingCancellationOutcome:
    _set_now(store, now)
    return store.cancel(profile_id=profile_id, pairing_id=pairing_id)


@pytest.mark.parametrize('value', [
    'https://rotki.example',
    'https://192.168.1.10:8443',
    'https://[2001:db8::1]:8443',
])
def test_canonical_engine_origin_accepts_canonical_https_authorities(value: str) -> None:
    assert CanonicalEngineOrigin(value).value == value


@pytest.mark.parametrize('value', [
    'https://bad_host.example',
    'https://rotki.example.',
    'https://rotki.example/path',
    'https://user@rotki.example',
    'https://[not-an-ipv6]',
    'https://[2001:0db8::1]',
    'https://127.000.000.001',
    'https://rotki.example:443',
])
def test_canonical_engine_origin_rejects_ambiguous_authorities(value: str) -> None:
    with pytest.raises(InvalidControlStoreInput):
        CanonicalEngineOrigin(value)


def test_issue_matches_golden_pairing_response(control_store: ControlStore) -> None:
    store, random_bytes = _new_store(
        control_store,
        [(PAIRING_ID, PAIRING_CREDENTIAL)],
    )

    created = _issue(store)

    assert isinstance(created, PairingCreated)
    assert created.response_bytes == _compact_json(CREATE_EXAMPLE['response'])
    assert (
        json.loads(created.response_bytes)['result']['qr_payload'] ==
        VALID_QR_VECTOR['wire_utf8']
    )
    assert _authority(created) == (PAIRING_ID, PAIRING_CREDENTIAL)
    assert random_bytes.attempts == 1


def test_issue_retries_identifier_and_credential_collisions(
        control_store: ControlStore,
) -> None:
    second_id = PairingID(b'\x31' * 16)
    third_id = PairingID(b'\x32' * 16)
    second_credential = PairingCredential(b'\x41' * 32)
    third_credential = PairingCredential(b'\x42' * 32)
    store, random_bytes = _new_store(control_store, [
        (PAIRING_ID, PAIRING_CREDENTIAL),
        (PAIRING_ID, second_credential),  # ID collision
        (second_id, PAIRING_CREDENTIAL),  # credential collision
        (third_id, third_credential),
    ])
    first = _issue(store)
    assert isinstance(first, PairingCreated)

    second = _issue(
        store,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    )

    assert isinstance(second, PairingCreated)
    assert _authority(first) == (PAIRING_ID, PAIRING_CREDENTIAL)
    assert _authority(second) == (third_id, third_credential)
    assert random_bytes.attempts == 4


def test_issue_collision_exhaustion_preserves_existing_pairing(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    authorities = [(PAIRING_ID, PAIRING_CREDENTIAL)] + [
        (PAIRING_ID, PairingCredential(bytes([index]) * 32))
        for index in range(1, PAIRING_RANDOM_GENERATION_ATTEMPTS + 1)
    ]
    store, random_bytes = _new_store(control_store, authorities)
    assert isinstance(_issue(store), PairingCreated)

    exhausted = _issue(
        store,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    )

    assert exhausted is PairingStoreFailure.STORE_UNAVAILABLE
    assert random_bytes.attempts == PAIRING_RANDOM_GENERATION_ATTEMPTS + 1
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        assert isinstance(_register(store, p256_public_key), PairingRegistered)


def test_creation_replay_is_exact_scoped_and_conflicting(control_store: ControlStore) -> None:
    profile_b_id = PairingID(b'\x51' * 16)
    profile_b_credential = PairingCredential(b'\x52' * 32)
    store, random_bytes = _new_store(control_store, [
        (PAIRING_ID, PAIRING_CREDENTIAL),
        (profile_b_id, profile_b_credential),
    ])
    first = _issue(store)
    assert isinstance(first, PairingCreated)

    exact = _issue(store, now=ISSUED_AT + 1)
    conflicting_body = _issue(
        store,
        request_body={'changed': True},
        now=ISSUED_AT + 1,
    )
    conflicting_origin = _issue(store, origin=OTHER_ORIGIN, now=ISSUED_AT + 1)
    other_profile = _issue(store, profile_id=PROFILE_B, now=ISSUED_AT + 1)
    invalid_new_request = _issue(
        store,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
        request_body={'changed': True},
        now=ISSUED_AT + 1,
    )

    assert isinstance(exact, PairingCreated)
    assert exact.response_bytes == first.response_bytes
    assert conflicting_body is PairingStoreFailure.IDEMPOTENCY_CONFLICT
    assert conflicting_origin is PairingStoreFailure.IDEMPOTENCY_CONFLICT
    assert invalid_new_request is PairingStoreFailure.INVALID_REQUEST
    assert isinstance(other_profile, PairingCreated)
    assert _authority(other_profile) == (profile_b_id, profile_b_credential)
    assert random_bytes.attempts == 2


def test_pairing_expiry_boundary_is_authoritative(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    before_expiry_id = PairingID(b'\x61' * 16)
    before_expiry_credential = PairingCredential(b'\x62' * 32)
    store, _ = _new_store(control_store, [
        (PAIRING_ID, PAIRING_CREDENTIAL),
        (before_expiry_id, before_expiry_credential),
    ])
    assert isinstance(_issue(store), PairingCreated)
    assert isinstance(_issue(
        store,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ), PairingCreated)
    expires_at = CREATE_EXAMPLE['response']['result']['expires_at']

    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        before_expiry = _register(
            store,
            p256_public_key,
            pairing_id=before_expiry_id,
            credential=before_expiry_credential,
            idempotency_key=OTHER_IDEMPOTENCY_KEY,
            now=expires_at - 1,
        )
    at_expiry = _register(store, p256_public_key, now=expires_at)

    assert isinstance(before_expiry, PairingRegistered)
    assert at_expiry is PairingStoreFailure.PAIRING_UNAVAILABLE
    assert len(control_store.list_device_sessions(PROFILE_A)) == 1


def test_clock_rollback_clears_active_pairing_and_registration_tombstone(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    active_id = PairingID(b'\x6a' * 16)
    active_credential = PairingCredential(b'\x6b' * 32)
    store, _ = _new_store(control_store, [
        (PAIRING_ID, PAIRING_CREDENTIAL),
        (active_id, active_credential),
    ])
    assert isinstance(_issue(store), PairingCreated)
    assert isinstance(_issue(
        store,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ), PairingCreated)
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        assert isinstance(_register(store, p256_public_key), PairingRegistered)

    assert _cancel(
        store,
        profile_id=PROFILE_B,
        pairing_id=active_id,
        now=REGISTERED_AT - 1,
    ) is PairingCancellationOutcome.CANCELLED

    assert _register(store, p256_public_key) is PairingStoreFailure.PAIRING_UNAVAILABLE
    assert _register(
        store,
        p256_public_key,
        pairing_id=active_id,
        credential=active_credential,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ) is PairingStoreFailure.PAIRING_UNAVAILABLE
    assert len(control_store.list_device_sessions(PROFILE_A)) == 1


def test_cancel_and_authority_mismatches_do_not_disclose_or_consume(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    cancelled_id = PairingID(b'\x71' * 16)
    cancelled_credential = PairingCredential(b'\x72' * 32)
    store, _ = _new_store(control_store, [
        (PAIRING_ID, PAIRING_CREDENTIAL),
        (cancelled_id, cancelled_credential),
    ])
    assert isinstance(_issue(store), PairingCreated)
    assert isinstance(_issue(
        store,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ), PairingCreated)

    assert _cancel(
        store,
        profile_id=PROFILE_B,
        pairing_id=PAIRING_ID,
        now=REGISTERED_AT,
    ) is PairingCancellationOutcome.CANCELLED
    mismatches = (
        _register(store, p256_public_key, pairing_id=PairingID(b'\xff' * 16)),
        _register(store, p256_public_key, credential=PairingCredential(b'\xff' * 32)),
        _register(store, p256_public_key, origin=OTHER_ORIGIN),
        _register(store, p256_public_key, profile_id=PROFILE_B),
    )
    assert all(outcome is PairingStoreFailure.PAIRING_UNAVAILABLE for outcome in mismatches)

    assert _cancel(
        store,
        profile_id=PROFILE_A,
        pairing_id=cancelled_id,
        now=REGISTERED_AT,
    ) is PairingCancellationOutcome.CANCELLED
    assert _register(
        store,
        p256_public_key,
        pairing_id=cancelled_id,
        credential=cancelled_credential,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ) is PairingStoreFailure.PAIRING_UNAVAILABLE

    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        assert isinstance(_register(store, p256_public_key), PairingRegistered)


def test_wrong_authority_is_rejected_before_registration_semantics(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    store, _ = _new_store(control_store, [(PAIRING_ID, PAIRING_CREDENTIAL)])
    assert isinstance(_issue(store), PairingCreated)
    wrong_credential = PairingCredential(b'\xfe' * 32)

    with patch(
        'rotkehlchen.api.companion.pairing_store._validate_registration_semantics',
        side_effect=AssertionError('expensive validation must not run'),
    ) as validate_semantics:
        assert _register(
            store,
            p256_public_key,
            credential=wrong_credential,
        ) is PairingStoreFailure.PAIRING_UNAVAILABLE
    validate_semantics.assert_not_called()

    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        assert isinstance(_register(store, p256_public_key), PairingRegistered)
    with patch(
        'rotkehlchen.api.companion.pairing_store._validate_registration_semantics',
        side_effect=AssertionError('replay validation must not run'),
    ) as validate_replay_semantics:
        assert _register(
            store,
            p256_public_key,
            credential=wrong_credential,
        ) is PairingStoreFailure.PAIRING_UNAVAILABLE
    validate_replay_semantics.assert_not_called()


def test_registration_matches_golden_and_persists_binding(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    assert _decode_base64url(REGISTER_EXAMPLE['request']['body']['public_key']) == p256_public_key
    store, _ = _new_store(control_store, [(PAIRING_ID, PAIRING_CREDENTIAL)])
    assert isinstance(_issue(store), PairingCreated)

    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        registered = _register(store, p256_public_key)

    assert isinstance(registered, PairingRegistered)
    assert registered.response_bytes == _compact_json(REGISTER_EXAMPLE['response'])
    records = control_store.list_device_sessions(PROFILE_A)
    assert len(records) == 1
    assert records[0].device_session_id == SESSION_ID
    binding = control_store.get_authorized_binding(records[0].device_session_id)
    assert binding is not None
    assert binding.profile_id == PROFILE_A
    assert binding.public_key == p256_public_key


def test_control_store_failure_does_not_consume_pairing(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    store, _ = _new_store(control_store, [(PAIRING_ID, PAIRING_CREDENTIAL)])
    assert isinstance(_issue(store), PairingCreated)
    with patch.object(
        control_store,
        'register_device_session',
        side_effect=ControlStoreUnavailable,
    ):
        failed = _register(store, p256_public_key)

    assert failed is PairingStoreFailure.STORE_UNAVAILABLE
    assert control_store.list_device_sessions(PROFILE_A) == []
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        retry = _register(store, p256_public_key)
    assert isinstance(retry, PairingRegistered)
    assert len(control_store.list_device_sessions(PROFILE_A)) == 1


def test_postcommit_encoding_failure_cannot_duplicate_registration(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    store, _ = _new_store(control_store, [(PAIRING_ID, PAIRING_CREDENTIAL)])
    assert isinstance(_issue(store), PairingCreated)
    original_register = control_store.register_device_session
    with (
        patch.object(
            control_store,
            'register_device_session',
            wraps=original_register,
        ) as register_device_session,
        patch(
            'rotkehlchen.api.companion.control_store.secrets.token_bytes',
            return_value=SESSION_ID,
        ),
    ):
        with (
            patch(
                'rotkehlchen.api.companion.pairing_store._encode_registration_response',
                side_effect=RuntimeError('seeded postcommit encoding failure'),
            ),
            pytest.raises(RuntimeError, match='seeded postcommit encoding failure'),
        ):
            _register(store, p256_public_key)

        assert len(control_store.list_device_sessions(PROFILE_A)) == 1
        registration_replays: Any = store._registration_replays  # pylint: disable=protected-access
        provisional_tombstone = next(iter(registration_replays.values()))
        assert provisional_tombstone.response_bytes is None
        provisional_byte_fields = [
            bytes(value)
            for slot in provisional_tombstone.__slots__
            if isinstance((value := getattr(provisional_tombstone, slot)), bytes)
        ]
        assert all(
            plaintext not in stored
            for plaintext in (
                bytes(PAIRING_CREDENTIAL),
                bytes(PROFILE_A),
                ORIGIN.value.encode(),
                p256_public_key,
            )
            for stored in provisional_byte_fields
        )
        replay = _register(store, p256_public_key)

    assert replay is PairingStoreFailure.STORE_UNAVAILABLE
    assert register_device_session.call_count == 1
    assert len(control_store.list_device_sessions(PROFILE_A)) == 1


@pytest.mark.parametrize(('second_key', 'second_label', 'expected_second'), [
    (IDEMPOTENCY_KEY, REGISTER_EXAMPLE['request']['body']['device_label'], None),
    (
        IDEMPOTENCY_KEY,
        CHANGED_DEVICE_LABEL,
        PairingStoreFailure.IDEMPOTENCY_CONFLICT,
    ),
    (
        OTHER_IDEMPOTENCY_KEY,
        REGISTER_EXAMPLE['request']['body']['device_label'],
        PairingStoreFailure.PAIRING_UNAVAILABLE,
    ),
], ids=('exact-replay', 'changed-request', 'different-key'))
def test_concurrent_registration_has_exactly_one_durable_mutation(
        control_store: ControlStore,
        p256_public_key: bytes,
        second_key: IdempotencyKey,
        second_label: str,
        expected_second: PairingStoreFailure | None,
) -> None:
    store, _ = _new_store(control_store, [(PAIRING_ID, PAIRING_CREDENTIAL)])
    assert isinstance(_issue(store), PairingCreated)
    original_register = control_store.register_device_session
    blocked = _BlockedRegistration(original_register)
    second_started = Event()

    def register_second():
        second_started.set()
        return _register(
            store,
            p256_public_key,
            idempotency_key=second_key,
            device_label=second_label,
        )

    with (
        patch.object(control_store, 'register_device_session', side_effect=blocked),
        patch(
            'rotkehlchen.api.companion.control_store.secrets.token_bytes',
            return_value=SESSION_ID,
        ),
        ThreadPoolExecutor(max_workers=2) as executor,
    ):
        first_future = executor.submit(_register, store, p256_public_key)
        assert blocked.entered.wait(timeout=10)
        second_future = executor.submit(register_second)
        try:
            assert second_started.wait(timeout=10)
        finally:
            blocked.release.set()
        first = first_future.result(timeout=10)
        second = second_future.result(timeout=10)

    assert isinstance(first, PairingRegistered)
    if expected_second is None:
        assert isinstance(second, PairingRegistered)
        assert second.response_bytes == first.response_bytes
    else:
        assert second is expected_second
    assert blocked.calls == 1
    assert len(control_store.list_device_sessions(PROFILE_A)) == 1


def test_registration_tombstone_replay_conflict_key_and_expiry(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    store, _ = _new_store(control_store, [(PAIRING_ID, PAIRING_CREDENTIAL)])
    assert isinstance(_issue(store), PairingCreated)
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        registered = _register(store, p256_public_key)
    assert isinstance(registered, PairingRegistered)

    exact = _register(store, p256_public_key, now=REGISTERED_AT + 299)
    label_conflict = _register(
        store,
        p256_public_key,
        device_label=CHANGED_DEVICE_LABEL,
        now=REGISTERED_AT + 299,
    )
    platform_conflict = _register(
        store,
        p256_public_key,
        platform=DevicePlatform.ANDROID,
        now=REGISTERED_AT + 299,
    )
    public_key_conflict = _register(
        store,
        OTHER_P256_PUBLIC_KEY,
        now=REGISTERED_AT + 299,
    )
    different_pairing_id = _register(
        store,
        p256_public_key,
        pairing_id=PairingID(b'\xfd' * 16),
        now=REGISTERED_AT + 299,
    )
    different_key = _register(
        store,
        p256_public_key,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
        now=REGISTERED_AT + 299,
    )
    expired = _register(store, p256_public_key, now=REGISTERED_AT + 300)

    assert isinstance(exact, PairingRegistered)
    assert exact.response_bytes == registered.response_bytes
    assert label_conflict is PairingStoreFailure.IDEMPOTENCY_CONFLICT
    assert platform_conflict is PairingStoreFailure.IDEMPOTENCY_CONFLICT
    assert public_key_conflict is PairingStoreFailure.IDEMPOTENCY_CONFLICT
    assert different_pairing_id is PairingStoreFailure.PAIRING_UNAVAILABLE
    assert different_key is PairingStoreFailure.PAIRING_UNAVAILABLE
    assert expired is PairingStoreFailure.PAIRING_UNAVAILABLE
    assert len(control_store.list_device_sessions(PROFILE_A)) == 1


def test_registration_tombstone_contains_no_plaintext_authority_or_public_key(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    store, _ = _new_store(control_store, [(PAIRING_ID, PAIRING_CREDENTIAL)])
    assert isinstance(_issue(store), PairingCreated)
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        assert isinstance(_register(store, p256_public_key), PairingRegistered)

    registration_replays: Any = store._registration_replays  # pylint: disable=protected-access
    assert len(registration_replays) == 1
    tombstone = next(iter(registration_replays.values()))
    stored_byte_fields = [
        bytes(value)
        for slot in tombstone.__slots__
        if isinstance((value := getattr(tombstone, slot)), bytes)
    ]
    forbidden_plaintext = (
        bytes(PAIRING_CREDENTIAL),
        bytes(PROFILE_A),
        ORIGIN.value.encode(),
        p256_public_key,
    )
    assert all(
        plaintext not in stored
        for plaintext in forbidden_plaintext
        for stored in stored_byte_fields
    )


def test_clear_profile_clear_and_restart_discard_only_target_authority(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    profile_b_id = PairingID(b'\x81' * 16)
    profile_b_credential = PairingCredential(b'\x82' * 32)
    later_id = PairingID(b'\x83' * 16)
    later_credential = PairingCredential(b'\x84' * 32)
    store, _ = _new_store(control_store, [
        (PAIRING_ID, PAIRING_CREDENTIAL),
        (profile_b_id, profile_b_credential),
        (later_id, later_credential),
    ])
    assert isinstance(_issue(store), PairingCreated)
    assert isinstance(_issue(store, profile_id=PROFILE_B), PairingCreated)

    store.clear_profile(PROFILE_A)
    assert _register(store, p256_public_key) is PairingStoreFailure.PAIRING_UNAVAILABLE
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        profile_b_registration = _register(
            store,
            p256_public_key,
            pairing_id=profile_b_id,
            credential=profile_b_credential,
            profile_id=PROFILE_B,
        )
    assert isinstance(profile_b_registration, PairingRegistered)
    store.clear_profile(PROFILE_B)
    assert _register(
        store,
        p256_public_key,
        pairing_id=profile_b_id,
        credential=profile_b_credential,
        profile_id=PROFILE_B,
    ) is PairingStoreFailure.PAIRING_UNAVAILABLE

    later = _issue(store, profile_id=PROFILE_C, idempotency_key=OTHER_IDEMPOTENCY_KEY)
    assert isinstance(later, PairingCreated)
    restarted, _ = _new_store(control_store, [])
    assert _register(
        restarted,
        p256_public_key,
        pairing_id=later_id,
        credential=later_credential,
        profile_id=PROFILE_C,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ) is PairingStoreFailure.PAIRING_UNAVAILABLE
    store.clear()
    store.clear()
    assert _register(
        store,
        p256_public_key,
        pairing_id=later_id,
        credential=later_credential,
        profile_id=PROFILE_C,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ) is PairingStoreFailure.PAIRING_UNAVAILABLE


def test_capacity_limits_active_records_and_registration_replays(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    profile_b_id = PairingID(b'\x91' * 16)
    profile_b_credential = PairingCredential(b'\x92' * 32)
    profile_c_id = PairingID(b'\x93' * 16)
    profile_c_credential = PairingCredential(b'\x94' * 32)
    bounded, _ = _new_store(
        control_store,
        [
            (PAIRING_ID, PAIRING_CREDENTIAL),
            (profile_b_id, profile_b_credential),
            (profile_c_id, profile_c_credential),
        ],
        record_capacity=2,
        active_capacity=2,
        per_profile_active_capacity=1,
    )
    assert isinstance(_issue(bounded), PairingCreated)
    assert _issue(
        bounded,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ) is PairingStoreFailure.STORE_UNAVAILABLE
    assert isinstance(_issue(bounded, profile_id=PROFILE_B), PairingCreated)
    assert _issue(
        bounded,
        profile_id=PROFILE_C,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ) is PairingStoreFailure.STORE_UNAVAILABLE
    assert _cancel(
        bounded,
        profile_id=PROFILE_A,
        pairing_id=PAIRING_ID,
        now=ISSUED_AT,
    ) is PairingCancellationOutcome.CANCELLED
    assert isinstance(_issue(
        bounded,
        profile_id=PROFILE_C,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
    ), PairingCreated)

    after_replay_id = PairingID(b'\x95' * 16)
    after_replay_credential = PairingCredential(b'\x96' * 32)
    replay_bounded, _ = _new_store(
        control_store,
        [
            (PAIRING_ID, PAIRING_CREDENTIAL),
            (after_replay_id, after_replay_credential),
        ],
        record_capacity=1,
        active_capacity=1,
        per_profile_active_capacity=1,
    )
    assert isinstance(_issue(replay_bounded), PairingCreated)
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        assert isinstance(_register(replay_bounded, p256_public_key), PairingRegistered)
    assert _issue(
        replay_bounded,
        profile_id=PROFILE_B,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
        now=REGISTERED_AT + 299,
    ) is PairingStoreFailure.STORE_UNAVAILABLE
    after_expiry = _issue(
        replay_bounded,
        profile_id=PROFILE_B,
        idempotency_key=OTHER_IDEMPOTENCY_KEY,
        now=REGISTERED_AT + 300,
    )
    assert isinstance(after_expiry, PairingCreated)
    assert _authority(after_expiry) == (after_replay_id, after_replay_credential)


def test_pairing_values_and_logs_have_fixed_redaction(
        control_store: ControlStore,
        p256_public_key: bytes,
        caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    store, _ = _new_store(control_store, [(PAIRING_ID, PAIRING_CREDENTIAL)])
    created = _issue(store)
    assert isinstance(created, PairingCreated)
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=SESSION_ID,
    ):
        registered = _register(store, p256_public_key)
    assert isinstance(registered, PairingRegistered)

    assert repr(store) == '<PairingStore redacted>'
    assert repr(ORIGIN) == '<CanonicalEngineOrigin redacted>'
    assert repr(created) == '<PairingCreated redacted>'
    assert repr(registered) == '<PairingRegistered redacted>'
    diagnostic_output = '\n'.join((
        repr(store),
        repr(ORIGIN),
        repr(created),
        repr(registered),
        caplog.text,
    ))
    sensitive_values = (
        bytes(PAIRING_ID).hex(),
        GOLDEN_QR['pairing_id'],
        bytes(PAIRING_CREDENTIAL).hex(),
        GOLDEN_QR['pairing_credential'],
        bytes(PROFILE_A).hex(),
        base64.urlsafe_b64encode(PROFILE_A).rstrip(b'=').decode(),
        ORIGIN.value,
        bytes(IDEMPOTENCY_KEY).hex(),
        CREATE_EXAMPLE['request']['headers']['Idempotency-Key'],
        HASH_KEY.hex(),
        p256_public_key.hex(),
        REGISTER_EXAMPLE['request']['body']['public_key'],
        REGISTER_EXAMPLE['request']['body']['device_label'],
    )
    assert all(value not in diagnostic_output for value in sensitive_values)

    registration_response = registered.response_bytes.decode()
    secret_registration_values = (
        GOLDEN_QR['pairing_id'],
        GOLDEN_QR['pairing_credential'],
        bytes(PROFILE_A).hex(),
        ORIGIN.value,
        CREATE_EXAMPLE['request']['headers']['Idempotency-Key'],
        REGISTER_EXAMPLE['request']['body']['public_key'],
    )
    assert all(value not in registration_response for value in secret_registration_values)
