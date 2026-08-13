import base64
import logging
from concurrent.futures import ThreadPoolExecutor
from typing import TYPE_CHECKING, Any
from unittest.mock import patch

import pytest
import rsqlite
from cryptography.hazmat.primitives.asymmetric import ec

from rotkehlchen.api.companion.control_store import ControlStore
from rotkehlchen.api.companion.types import (
    ControlStoreUnavailable,
    DevicePlatform,
    DeviceSessionID,
    DeviceSessionRecord,
    DeviceSessionState,
    InvalidControlStoreInput,
)
from rotkehlchen.db.profile import ProfileID

if TYPE_CHECKING:
    from pathlib import Path

PROFILE_ID_A = ProfileID(bytes(range(32)))
PROFILE_ID_B = ProfileID(bytes(reversed(range(32))))


def _register(
        store: ControlStore,
        public_key: bytes,
        *,
        profile_id: ProfileID = PROFILE_ID_A,
        label: str = 'Pixel 9',
        platform: DevicePlatform = DevicePlatform.ANDROID,
        paired_at: int = 1_000,
) -> DeviceSessionRecord:
    return store.register_device_session(
        profile_id=profile_id,
        device_label=label,
        platform=platform,
        public_key=public_key,
        paired_at=paired_at,
    )


def test_register_reopen_and_list_device_session(
        control_db_path: Path,
        p256_public_key: bytes,
) -> None:
    store = ControlStore(control_db_path)
    registered = _register(store, p256_public_key)

    assert registered.device_label == 'Pixel 9'
    assert registered.platform == DevicePlatform.ANDROID
    assert registered.state == DeviceSessionState.AUTHORIZED
    assert registered.paired_at == 1_000
    assert registered.last_seen_at is None
    assert registered.revoked_at is None
    store.close()

    reopened = ControlStore(control_db_path)
    try:
        assert reopened.list_device_sessions(PROFILE_ID_A) == [registered]
        binding = reopened.get_authorized_binding(registered.device_session_id)
        assert binding is not None
        assert binding.device_session_id == registered.device_session_id
        assert binding.profile_id == PROFILE_ID_A
        assert binding.public_key == p256_public_key
        assert binding.public_key_algorithm == 'ecdsa-p256-sha256-p1363'
    finally:
        reopened.close()


def test_profile_scoping_for_list_rename_and_revoke(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    first = _register(control_store, p256_public_key)
    second = _register(
        control_store,
        p256_public_key,
        profile_id=PROFILE_ID_B,
        label='iPhone',
        platform=DevicePlatform.IOS,
        paired_at=2_000,
    )

    assert control_store.list_device_sessions(PROFILE_ID_A) == [first]
    assert control_store.list_device_sessions(PROFILE_ID_B) == [second]
    assert control_store.rename_device_session(
        PROFILE_ID_B,
        first.device_session_id,
        'Wrong profile',
    ) is None
    assert control_store.revoke_device_session(
        PROFILE_ID_B,
        first.device_session_id,
        2_001,
    ) is None
    assert control_store.list_device_sessions(PROFILE_ID_A) == [first]


def test_listing_uses_paired_time_then_raw_id_order(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    raw_first = b'\x00' * 32
    raw_second = b'\xfb' + b'\x00' * 31
    assert base64.urlsafe_b64encode(raw_second) < base64.urlsafe_b64encode(raw_first)

    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        side_effect=(raw_second, raw_first, b'\x80' * 32),
    ):
        second = _register(control_store, p256_public_key, label='Second raw ID')
        first = _register(control_store, p256_public_key, label='First raw ID')
        newest = _register(
            control_store,
            p256_public_key,
            label='Newest',
            paired_at=1_001,
        )

    assert control_store.list_device_sessions(PROFILE_ID_A) == [newest, first, second]


def test_device_session_id_collision_retries_without_overwrite(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    colliding_id = b'\x11' * 32
    replacement_id = b'\x22' * 32
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        side_effect=(colliding_id, colliding_id, replacement_id),
    ):
        original = _register(control_store, p256_public_key, label='Original')
        replacement = _register(control_store, p256_public_key, label='Replacement')

    assert original.device_session_id == colliding_id
    assert replacement.device_session_id == replacement_id
    labels = [
        record.device_label for record in control_store.list_device_sessions(PROFILE_ID_A)
    ]
    assert labels == [
        'Original',
        'Replacement',
    ]


def test_device_session_id_collision_exhaustion_is_fail_closed(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    colliding_id = b'\x33' * 32
    with patch(
        'rotkehlchen.api.companion.control_store.secrets.token_bytes',
        return_value=colliding_id,
    ):
        original = _register(control_store, p256_public_key, label='Original')
        with pytest.raises(ControlStoreUnavailable):
            _register(control_store, p256_public_key, label='Must not overwrite')

    assert control_store.list_device_sessions(PROFILE_ID_A) == [original]


def test_rename_preserves_other_device_session_fields(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    registered = _register(control_store, p256_public_key)
    renamed = control_store.rename_device_session(
        PROFILE_ID_A,
        registered.device_session_id,
        'My phone',
    )

    assert renamed is not None
    assert renamed.device_label == 'My phone'
    assert renamed.device_session_id == registered.device_session_id
    assert renamed.paired_at == registered.paired_at
    assert control_store.get_authorized_binding(registered.device_session_id) is not None


def test_revoke_erases_key_and_keeps_immutable_tombstone(
        control_store: ControlStore,
        control_db_path: Path,
        p256_public_key: bytes,
) -> None:
    registered = _register(control_store, p256_public_key)
    assert control_store.mark_successful_proof(registered.device_session_id, 1_100) is True

    revoked = control_store.revoke_device_session(
        PROFILE_ID_A,
        registered.device_session_id,
        1_200,
    )
    repeated = control_store.revoke_device_session(
        PROFILE_ID_A,
        registered.device_session_id,
        1_300,
    )

    assert revoked is not None
    assert repeated == revoked
    assert revoked.state == DeviceSessionState.REVOKED
    assert revoked.last_seen_at == 1_100
    assert revoked.revoked_at == 1_200
    assert control_store.get_authorized_binding(registered.device_session_id) is None
    assert control_store.rename_device_session(
        PROFILE_ID_A,
        registered.device_session_id,
        'Cannot rename',
    ) is None
    assert control_store.mark_successful_proof(registered.device_session_id, 1_400) is False

    with rsqlite.connect(control_db_path) as connection:
        row = connection.execute(
            'SELECT public_key_algorithm, public_key, revoked_at '
            'FROM device_sessions WHERE device_session_id=?',
            (registered.device_session_id,),
        ).fetchone()
        assert row == (None, None, 1_200)
        with pytest.raises(rsqlite.IntegrityError):
            connection.execute(
                'UPDATE device_sessions SET device_label=? WHERE device_session_id=?',
                ('Bypass', registered.device_session_id),
            )

    control_store.close()
    reopened = ControlStore(control_db_path)
    try:
        assert reopened.list_device_sessions(PROFILE_ID_A) == [revoked]
        assert reopened.get_authorized_binding(registered.device_session_id) is None
    finally:
        reopened.close()


def test_unknown_and_revoked_have_same_authorization_lookup(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    registered = _register(control_store, p256_public_key)
    unknown = DeviceSessionID(b'\xff' * 32)
    assert control_store.get_authorized_binding(unknown) is None

    control_store.revoke_device_session(PROFILE_ID_A, registered.device_session_id, 1_001)
    assert control_store.get_authorized_binding(registered.device_session_id) is None


def test_last_seen_is_monotonic_and_authorized_only(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    registered = _register(control_store, p256_public_key)
    assert control_store.mark_successful_proof(registered.device_session_id, 1_200) is True
    assert control_store.mark_successful_proof(registered.device_session_id, 1_100) is True
    assert control_store.mark_successful_proof(registered.device_session_id, 1_200) is True
    assert control_store.list_device_sessions(PROFILE_ID_A)[0].last_seen_at == 1_200

    assert control_store.mark_successful_proof(DeviceSessionID(b'\xee' * 32), 1_300) is False
    control_store.revoke_device_session(PROFILE_ID_A, registered.device_session_id, 1_300)
    assert control_store.mark_successful_proof(registered.device_session_id, 1_400) is False
    assert control_store.list_device_sessions(PROFILE_ID_A)[0].last_seen_at == 1_200


def test_clock_regression_never_prevents_proof_tracking_or_revocation(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    registered = _register(control_store, p256_public_key, paired_at=2_000)
    assert control_store.mark_successful_proof(registered.device_session_id, 1_900) is True
    assert control_store.list_device_sessions(PROFILE_ID_A)[0].last_seen_at == 2_000

    revoked = control_store.revoke_device_session(
        PROFILE_ID_A,
        registered.device_session_id,
        1_800,
    )
    assert revoked is not None
    assert revoked.revoked_at == 2_000
    assert control_store.get_authorized_binding(registered.device_session_id) is None


@pytest.mark.parametrize(
    'label',
    [
        '',
        ' ',
        ' leading',
        'trailing ',
        'a' * 65,
        'nul\x00label',
        'format\u200blabel',
        'line\u2028separator',
        'paragraph\u2029separator',
        'surrogate\ud800',
    ],
)
def test_rejects_invalid_device_labels(
        control_store: ControlStore,
        p256_public_key: bytes,
        label: str,
) -> None:
    with pytest.raises(InvalidControlStoreInput):
        _register(control_store, p256_public_key, label=label)


def test_device_label_preserves_unicode_without_normalization(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    label = f'Cafe\u0301 {"🙂" * 58}'
    assert len(label) == 64
    registered = _register(control_store, p256_public_key, label=label)
    assert registered.device_label == label
    assert control_store.list_device_sessions(PROFILE_ID_A)[0].device_label == label


@pytest.mark.parametrize(
    'public_key',
    [
        b'',
        b'\x04' + b'\x00' * 64,
        b'\x02' + b'\x00' * 32,
        b'\x05' + b'\x01' * 64,
    ],
)
def test_rejects_malformed_or_non_uncompressed_p256_keys(
        control_store: ControlStore,
        public_key: bytes,
) -> None:
    with pytest.raises(InvalidControlStoreInput):
        _register(control_store, public_key)


def test_rejects_wrong_runtime_types(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    public_numbers = ec.derive_private_key(2, ec.SECP256R1()).public_key().public_numbers()
    compressed_key = bytes([2 | (public_numbers.y & 1)]) + public_numbers.x.to_bytes(32, 'big')
    invalid_registrations: tuple[dict[str, Any], ...] = (
        {'profile_id': b'\x00' * 31},
        {'profile_id': bytearray(PROFILE_ID_A)},
        {'platform': 'web'},
        {'public_key': bytearray(p256_public_key)},
        {'public_key': compressed_key},
        {'paired_at': True},
        {'paired_at': -1},
        {'paired_at': 2**63},
        {'paired_at': 1.5},
    )
    for overrides in invalid_registrations:
        arguments = {
            'profile_id': PROFILE_ID_A,
            'device_label': 'Pixel 9',
            'platform': DevicePlatform.ANDROID,
            'public_key': p256_public_key,
            'paired_at': 1_000,
        }
        arguments.update(overrides)
        with pytest.raises(InvalidControlStoreInput):
            control_store.register_device_session(**arguments)  # type: ignore[arg-type]

    registered = _register(control_store, p256_public_key)
    invalid_device_session_ids: tuple[Any, ...] = (
        b'\x00' * 31,
        bytearray(registered.device_session_id),
    )
    for device_session_id in invalid_device_session_ids:
        with pytest.raises(InvalidControlStoreInput):
            control_store.get_authorized_binding(device_session_id)

    with pytest.raises(InvalidControlStoreInput):
        control_store.list_device_sessions(ProfileID(b'\x00' * 31))
    with pytest.raises(InvalidControlStoreInput):
        control_store.rename_device_session(
            PROFILE_ID_A,
            registered.device_session_id,
            'trailing ',
        )
    with pytest.raises(InvalidControlStoreInput):
        control_store.revoke_device_session(
            PROFILE_ID_A,
            registered.device_session_id,
            True,
        )
    with pytest.raises(InvalidControlStoreInput):
        control_store.revoke_device_session(
            PROFILE_ID_A,
            registered.device_session_id,
            2**63,
        )
    with pytest.raises(InvalidControlStoreInput):
        control_store.mark_successful_proof(registered.device_session_id, -1)
    with pytest.raises(InvalidControlStoreInput):
        control_store.mark_successful_proof(registered.device_session_id, 2**63)


def test_concurrent_writes_serialize_without_lost_records(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    labels = [f'Device {idx}' for idx in range(16)]
    with ThreadPoolExecutor(max_workers=8) as executor:
        records = list(executor.map(
            lambda label: _register(control_store, p256_public_key, label=label),
            labels,
            timeout=10,
        ))

    assert len({record.device_session_id for record in records}) == len(labels)
    stored_labels = {
        record.device_label for record in control_store.list_device_sessions(PROFILE_ID_A)
    }
    assert stored_labels == set(labels)


def test_concurrent_revoke_and_last_seen_leave_valid_tombstone(
        control_store: ControlStore,
        p256_public_key: bytes,
) -> None:
    registered = _register(control_store, p256_public_key)
    with ThreadPoolExecutor(max_workers=2) as executor:
        proof = executor.submit(
            control_store.mark_successful_proof,
            registered.device_session_id,
            1_100,
        )
        revoke = executor.submit(
            control_store.revoke_device_session,
            PROFILE_ID_A,
            registered.device_session_id,
            1_200,
        )
        proof_result = proof.result(timeout=10)
        revoked = revoke.result(timeout=10)

    assert proof_result in (True, False)
    assert revoked is not None
    assert revoked.state == DeviceSessionState.REVOKED
    assert revoked.revoked_at == 1_200
    assert revoked.last_seen_at in (None, 1_100)
    assert control_store.get_authorized_binding(registered.device_session_id) is None


def test_records_bindings_and_logs_redact_identifiers_and_keys(
        control_store: ControlStore,
        p256_public_key: bytes,
        caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.DEBUG)
    registered = _register(control_store, p256_public_key, label='Safe label')
    binding = control_store.get_authorized_binding(registered.device_session_id)
    assert binding is not None
    assert not hasattr(registered, 'profile_id')
    assert not hasattr(registered, 'public_key')
    control_store.rename_device_session(
        PROFILE_ID_A,
        registered.device_session_id,
        'Renamed safely',
    )

    output = '\n'.join((repr(registered), repr(binding), caplog.text))
    sensitive_values = (
        registered.device_session_id.hex(),
        base64.urlsafe_b64encode(registered.device_session_id).rstrip(b'=').decode(),
        bytes(PROFILE_ID_A).hex(),
        base64.urlsafe_b64encode(PROFILE_ID_A).rstrip(b'=').decode(),
        p256_public_key.hex(),
        base64.urlsafe_b64encode(p256_public_key).rstrip(b'=').decode(),
        repr(registered.device_session_id),
        repr(PROFILE_ID_A),
        repr(p256_public_key),
        'Safe label',
        'Renamed safely',
    )
    assert all(value not in output for value in sensitive_values)


def test_close_is_idempotent(control_db_path: Path) -> None:
    store = ControlStore(control_db_path)
    store.close()
    store.close()
