from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import threading
import time
from dataclasses import dataclass
from enum import StrEnum
from typing import TYPE_CHECKING, Final

from rotkehlchen.api.companion.types import (
    DEVICE_PUBLIC_KEY_ALGORITHM,
    CanonicalEngineOrigin,
    ControlStoreUnavailable,
    DevicePlatform,
    IdempotencyKey,
    InvalidControlStoreInput,
    PairingCredential,
    PairingID,
    validate_device_label,
    validate_idempotency_key,
    validate_pairing_credential,
    validate_pairing_id,
    validate_profile_id,
    validate_public_key,
    validate_timestamp,
)

if TYPE_CHECKING:
    from collections.abc import Callable

    from rotkehlchen.api.companion.control_store import ControlStore
    from rotkehlchen.api.companion.types import DeviceSessionRecord
    from rotkehlchen.db.profile import ProfileID


PAIRING_LIFETIME_SECONDS: Final = 120
REGISTRATION_REPLAY_LIFETIME_SECONDS: Final = 300
PAIRING_RANDOM_GENERATION_ATTEMPTS: Final = 8
PAIRING_RECORD_CAPACITY: Final = 256
ACTIVE_PAIRING_CAPACITY: Final = 64
ACTIVE_PAIRING_PER_PROFILE_CAPACITY: Final = 8
PAIRING_QR_KIND: Final = 'rotki_companion_pairing'
PAIRING_QR_FORMAT_VERSION: Final = 1

_PROFILE_BINDING_DOMAIN: Final = b'rotki-companion-pairing-profile/v1\x00'
_ORIGIN_BINDING_DOMAIN: Final = b'rotki-companion-pairing-origin/v1\x00'
_CREDENTIAL_HASH_DOMAIN: Final = b'rotki-companion-pairing-credential/v1\x00'
_CREATE_FINGERPRINT_DOMAIN: Final = b'rotki-companion-pairing-create/v1\x00'
_REGISTRATION_FINGERPRINT_DOMAIN: Final = b'rotki-companion-registration/v1\x00'
_PUBLIC_KEY_ALGORITHM: Final = DEVICE_PUBLIC_KEY_ALGORITHM.encode('ascii')


class PairingStoreFailure(StrEnum):
    INVALID_REQUEST = 'invalid_request'
    IDEMPOTENCY_CONFLICT = 'idempotency_conflict'
    PAIRING_UNAVAILABLE = 'pairing_unavailable'
    STORE_UNAVAILABLE = 'store_unavailable'


class PairingCancellationOutcome(StrEnum):
    CANCELLED = 'cancelled'


@dataclass(frozen=True, slots=True, repr=False)
class PairingCreated:
    response_bytes: bytes

    def __repr__(self) -> str:
        return '<PairingCreated redacted>'


@dataclass(frozen=True, slots=True, repr=False)
class PairingRegistered:
    response_bytes: bytes

    def __repr__(self) -> str:
        return '<PairingRegistered redacted>'


PairingCreationOutcome = PairingCreated | PairingStoreFailure
PairingRegistrationOutcome = PairingRegistered | PairingStoreFailure


@dataclass(slots=True, repr=False)
class _ActivePairing:
    pairing_id: PairingID
    profile_binding: bytes
    origin_binding: bytes
    protocol_version: int
    credential_hash: bytes
    idempotency_key: IdempotencyKey
    request_fingerprint: bytes
    expires_at: int
    response_bytes: bytearray


@dataclass(frozen=True, slots=True, repr=False)
class _RegistrationReplay:
    profile_binding: bytes
    origin_binding: bytes
    protocol_version: int
    credential_hash: bytes
    idempotency_key: IdempotencyKey
    request_fingerprint: bytes
    expires_at: int
    response_bytes: bytes | None


class PairingStore:
    """Process-local Pairing authority and registration replay state.

    This store is intentionally wire-inaccessible until the complete ``device_sessions:1``
    capability is implemented. It accepts a structurally validated origin only after a future
    trusted boundary adapter has established that authority.
    """

    __slots__ = (
        '_active',
        '_active_capacity',
        '_clock',
        '_control_store',
        '_creation_replays',
        '_hash_key',
        '_last_now',
        '_lock',
        '_per_profile_active_capacity',
        '_random_bytes',
        '_record_capacity',
        '_registration_replays',
    )

    def __init__(
            self,
            control_store: ControlStore,
            *,
            random_bytes: Callable[[int], bytes] = secrets.token_bytes,
            clock: Callable[[], int] = lambda: int(time.time()),
            hash_key: bytes | None = None,
            record_capacity: int = PAIRING_RECORD_CAPACITY,
            active_capacity: int = ACTIVE_PAIRING_CAPACITY,
            per_profile_active_capacity: int = ACTIVE_PAIRING_PER_PROFILE_CAPACITY,
    ) -> None:
        if (
                type(record_capacity) is not int or
                type(active_capacity) is not int or
                type(per_profile_active_capacity) is not int or
                record_capacity < 1 or
                active_capacity < 1 or
                per_profile_active_capacity < 1 or
                active_capacity > record_capacity or
                per_profile_active_capacity > active_capacity
        ):
            raise ValueError('Invalid Pairing Store capacity')
        if hash_key is None:
            hash_key = random_bytes(32)
        if type(hash_key) is not bytes or len(hash_key) != 32:
            raise ValueError('Invalid Pairing Store hash key')

        self._control_store = control_store
        self._random_bytes = random_bytes
        self._clock = clock
        self._hash_key = hash_key
        self._record_capacity = record_capacity
        self._active_capacity = active_capacity
        self._per_profile_active_capacity = per_profile_active_capacity
        self._lock = threading.RLock()
        self._active: dict[PairingID, _ActivePairing] = {}
        self._creation_replays: dict[tuple[bytes, IdempotencyKey], PairingID] = {}
        self._registration_replays: dict[PairingID, _RegistrationReplay] = {}
        self._last_now: int | None = None

    def issue(
            self,
            *,
            profile_id: ProfileID,
            engine_origin: CanonicalEngineOrigin,
            protocol_version: int,
            idempotency_key: IdempotencyKey,
            request_body: object,
    ) -> PairingCreationOutcome:
        validated_profile_id = validate_profile_id(profile_id)
        validated_origin = _validate_origin(engine_origin)
        validated_protocol = _validate_protocol_version(protocol_version)
        validated_key = validate_idempotency_key(idempotency_key)
        request_fingerprint = _fingerprint_creation_request(request_body)
        profile_binding = self._bind(_PROFILE_BINDING_DOMAIN, validated_profile_id)
        origin_binding = self._bind(_ORIGIN_BINDING_DOMAIN, validated_origin.value.encode('ascii'))

        with self._lock:
            validated_now = self._read_now(PAIRING_LIFETIME_SECONDS)
            self._purge_expired(validated_now)
            replay_key = (profile_binding, validated_key)
            if (existing_id := self._creation_replays.get(replay_key)) is not None:
                existing = self._active.get(existing_id)
                if existing is None:  # Defensive repair of an impossible partial index.
                    self._creation_replays.pop(replay_key, None)
                elif (
                        existing.protocol_version == validated_protocol and
                        hmac.compare_digest(existing.origin_binding, origin_binding) and
                        hmac.compare_digest(
                            existing.request_fingerprint,
                            request_fingerprint,
                        )
                ):
                    return PairingCreated(bytes(existing.response_bytes))
                else:
                    return PairingStoreFailure.IDEMPOTENCY_CONFLICT

            if type(request_body) is not dict or request_body != {}:
                return PairingStoreFailure.INVALID_REQUEST
            if self._has_no_capacity(profile_binding):
                return PairingStoreFailure.STORE_UNAVAILABLE

            generated = self._generate_pairing_authority()
            if generated is None:
                return PairingStoreFailure.STORE_UNAVAILABLE
            pairing_id, credential, credential_hash = generated
            expires_at = validated_now + PAIRING_LIFETIME_SECONDS
            response_bytes = bytearray(_encode_pairing_response(
                pairing_id=pairing_id,
                credential=credential,
                engine_origin=validated_origin,
                expires_at=expires_at,
            ))
            active = _ActivePairing(
                pairing_id=pairing_id,
                profile_binding=profile_binding,
                origin_binding=origin_binding,
                protocol_version=validated_protocol,
                credential_hash=credential_hash,
                idempotency_key=validated_key,
                request_fingerprint=request_fingerprint,
                expires_at=expires_at,
                response_bytes=response_bytes,
            )
            self._active[pairing_id] = active
            self._creation_replays[replay_key] = pairing_id
            return PairingCreated(bytes(response_bytes))

    def cancel(
            self,
            *,
            profile_id: ProfileID,
            pairing_id: PairingID,
    ) -> PairingCancellationOutcome:
        profile_binding = self._bind(_PROFILE_BINDING_DOMAIN, validate_profile_id(profile_id))
        validated_id = validate_pairing_id(pairing_id)
        with self._lock:
            validated_now = self._read_now(0)
            self._purge_expired(validated_now)
            if (
                    (active := self._active.get(validated_id)) is not None and
                    hmac.compare_digest(active.profile_binding, profile_binding)
            ):
                self._remove_active(active)
        return PairingCancellationOutcome.CANCELLED

    def register(
            self,
            *,
            pairing_id: PairingID,
            credential: PairingCredential,
            request_origin: CanonicalEngineOrigin,
            open_profile_id: ProfileID,
            protocol_version: int,
            idempotency_key: IdempotencyKey,
            device_label: str,
            platform: DevicePlatform,
            public_key: bytes,
    ) -> PairingRegistrationOutcome:
        validated_id = validate_pairing_id(pairing_id)
        validated_credential = validate_pairing_credential(credential)
        validated_origin = _validate_origin(request_origin)
        validated_profile_id = validate_profile_id(open_profile_id)
        validated_protocol = _validate_protocol_version(protocol_version)
        validated_key = validate_idempotency_key(idempotency_key)

        profile_binding = self._bind(_PROFILE_BINDING_DOMAIN, validated_profile_id)
        origin_binding = self._bind(_ORIGIN_BINDING_DOMAIN, validated_origin.value.encode('ascii'))
        credential_hash = self._bind(_CREDENTIAL_HASH_DOMAIN, validated_credential)
        with self._lock:
            validated_now = self._read_now(REGISTRATION_REPLAY_LIFETIME_SECONDS)
            self._purge_expired(validated_now)
            if (active := self._active.get(validated_id)) is None:
                return self._replay_registration(
                    pairing_id=validated_id,
                    profile_binding=profile_binding,
                    origin_binding=origin_binding,
                    protocol_version=validated_protocol,
                    credential_hash=credential_hash,
                    idempotency_key=validated_key,
                    device_label=device_label,
                    platform=platform,
                    public_key=public_key,
                )
            if not _authority_matches(
                    profile_binding=profile_binding,
                    origin_binding=origin_binding,
                    protocol_version=validated_protocol,
                    credential_hash=credential_hash,
                    record=active,
            ):
                return PairingStoreFailure.PAIRING_UNAVAILABLE

            validated_label, validated_platform, validated_public_key, request_fingerprint = (
                _validate_registration_semantics(
                    pairing_id=validated_id,
                    device_label=device_label,
                    platform=platform,
                    public_key=public_key,
                )
            )

            try:
                device_session = self._control_store.register_device_session(
                    profile_id=validated_profile_id,
                    device_label=validated_label,
                    platform=validated_platform,
                    public_key=validated_public_key,
                    paired_at=validated_now,
                )
            except ControlStoreUnavailable:
                return PairingStoreFailure.STORE_UNAVAILABLE

            self._registration_replays[validated_id] = _RegistrationReplay(
                profile_binding=profile_binding,
                origin_binding=origin_binding,
                protocol_version=validated_protocol,
                credential_hash=credential_hash,
                idempotency_key=validated_key,
                request_fingerprint=request_fingerprint,
                expires_at=validated_now + REGISTRATION_REPLAY_LIFETIME_SECONDS,
                response_bytes=None,
            )
            self._remove_active(active)
            response_bytes = _encode_registration_response(device_session)
            self._registration_replays[validated_id] = _RegistrationReplay(
                profile_binding=profile_binding,
                origin_binding=origin_binding,
                protocol_version=validated_protocol,
                credential_hash=credential_hash,
                idempotency_key=validated_key,
                request_fingerprint=request_fingerprint,
                expires_at=validated_now + REGISTRATION_REPLAY_LIFETIME_SECONDS,
                response_bytes=response_bytes,
            )
            return PairingRegistered(response_bytes)

    def clear_profile(self, profile_id: ProfileID) -> None:
        profile_binding = self._bind(_PROFILE_BINDING_DOMAIN, validate_profile_id(profile_id))
        with self._lock:
            for active in tuple(self._active.values()):
                if hmac.compare_digest(active.profile_binding, profile_binding):
                    self._remove_active(active)
            for pairing_id, replay in tuple(self._registration_replays.items()):
                if hmac.compare_digest(replay.profile_binding, profile_binding):
                    self._registration_replays.pop(pairing_id, None)

    def clear(self) -> None:
        with self._lock:
            self._clear_locked()
            self._last_now = None

    def __repr__(self) -> str:
        return '<PairingStore redacted>'

    def _bind(self, domain: bytes, value: bytes) -> bytes:
        return hmac.digest(self._hash_key, domain + value, 'sha256')

    def _read_now(self, lifetime: int) -> int:
        return _validate_now_with_lifetime(self._clock(), lifetime)

    def _has_no_capacity(self, profile_binding: bytes) -> bool:
        if (
                len(self._active) + len(self._registration_replays) >= self._record_capacity or
                len(self._active) >= self._active_capacity
        ):
            return True
        return sum(
            hmac.compare_digest(active.profile_binding, profile_binding)
            for active in self._active.values()
        ) >= self._per_profile_active_capacity

    def _generate_pairing_authority(
            self,
    ) -> tuple[PairingID, PairingCredential, bytes] | None:
        for _ in range(PAIRING_RANDOM_GENERATION_ATTEMPTS):
            try:
                pairing_id = validate_pairing_id(self._random_bytes(16))
                credential = validate_pairing_credential(self._random_bytes(32))
            except (TypeError, ValueError):
                return None
            credential_hash = self._bind(_CREDENTIAL_HASH_DOMAIN, credential)
            if (
                    pairing_id not in self._active and
                    pairing_id not in self._registration_replays and
                    all(
                        not hmac.compare_digest(record.credential_hash, credential_hash)
                        for record in self._active.values()
                    ) and
                    all(
                        not hmac.compare_digest(record.credential_hash, credential_hash)
                        for record in self._registration_replays.values()
                    )
            ):
                return pairing_id, credential, credential_hash
        return None

    def _purge_expired(self, now: int) -> None:
        if self._last_now is not None and now < self._last_now:
            self._clear_locked()
        self._last_now = now
        for active in tuple(self._active.values()):
            if now >= active.expires_at:
                self._remove_active(active)
        for pairing_id, replay in tuple(self._registration_replays.items()):
            if now >= replay.expires_at:
                self._registration_replays.pop(pairing_id, None)

    def _clear_locked(self) -> None:
        for active in tuple(self._active.values()):
            active.response_bytes[:] = b'\x00' * len(active.response_bytes)
        self._active.clear()
        self._creation_replays.clear()
        self._registration_replays.clear()

    def _remove_active(self, active: _ActivePairing) -> None:
        self._active.pop(active.pairing_id, None)
        self._creation_replays.pop((active.profile_binding, active.idempotency_key), None)
        active.response_bytes[:] = b'\x00' * len(active.response_bytes)

    def _replay_registration(
            self,
            *,
            pairing_id: PairingID,
            profile_binding: bytes,
            origin_binding: bytes,
            protocol_version: int,
            credential_hash: bytes,
            idempotency_key: IdempotencyKey,
            device_label: object,
            platform: object,
            public_key: object,
    ) -> PairingRegistrationOutcome:
        replay = self._registration_replays.get(pairing_id)
        if replay is None or not _authority_matches(
                profile_binding=profile_binding,
                origin_binding=origin_binding,
                protocol_version=protocol_version,
                credential_hash=credential_hash,
                record=replay,
        ):
            return PairingStoreFailure.PAIRING_UNAVAILABLE
        if not hmac.compare_digest(replay.idempotency_key, idempotency_key):
            return PairingStoreFailure.PAIRING_UNAVAILABLE
        _, _, _, request_fingerprint = _validate_registration_semantics(
            pairing_id=pairing_id,
            device_label=device_label,
            platform=platform,
            public_key=public_key,
        )
        if not hmac.compare_digest(replay.request_fingerprint, request_fingerprint):
            return PairingStoreFailure.IDEMPOTENCY_CONFLICT
        if replay.response_bytes is None:
            return PairingStoreFailure.STORE_UNAVAILABLE
        return PairingRegistered(replay.response_bytes)


def _validate_origin(value: object) -> CanonicalEngineOrigin:
    if type(value) is not CanonicalEngineOrigin:
        raise ValueError('Invalid canonical Engine origin')
    return value


def _validate_protocol_version(value: object) -> int:
    if type(value) is not int or value < 1:
        raise ValueError('Invalid Companion Protocol version')
    return value


def _validate_now_with_lifetime(now: object, lifetime: int) -> int:
    validated = validate_timestamp(now)
    if validated > (2**63 - 1) - lifetime:
        raise ValueError('Invalid Companion timestamp')
    return validated


def _authority_matches(
        *,
        profile_binding: bytes,
        origin_binding: bytes,
        protocol_version: int,
        credential_hash: bytes,
        record: _ActivePairing | _RegistrationReplay,
) -> bool:
    return (
        hmac.compare_digest(record.profile_binding, profile_binding) and
        hmac.compare_digest(record.origin_binding, origin_binding) and
        record.protocol_version == protocol_version and
        hmac.compare_digest(record.credential_hash, credential_hash)
    )


def _fingerprint_creation_request(request_body: object) -> bytes:
    try:
        canonical_body = json.dumps(
            request_body,
            ensure_ascii=False,
            allow_nan=False,
            separators=(',', ':'),
            sort_keys=True,
        ).encode()
    except (TypeError, ValueError, UnicodeError):
        canonical_body = b'<invalid>'
    return hashlib.sha256(_CREATE_FINGERPRINT_DOMAIN + canonical_body).digest()


def _fingerprint_registration_request(
        *,
        pairing_id: PairingID,
        device_label: str,
        platform: DevicePlatform,
        public_key: bytes,
) -> bytes:
    encoded_label = device_label.encode()
    encoded_platform = platform.value.encode('ascii')
    framed = b''.join((
        bytes(pairing_id),
        len(encoded_label).to_bytes(2, 'big'),
        encoded_label,
        len(encoded_platform).to_bytes(1, 'big'),
        encoded_platform,
        len(_PUBLIC_KEY_ALGORITHM).to_bytes(1, 'big'),
        _PUBLIC_KEY_ALGORITHM,
        bytes(public_key),
    ))
    return hashlib.sha256(_REGISTRATION_FINGERPRINT_DOMAIN + framed).digest()


def _validate_registration_semantics(
        *,
        pairing_id: PairingID,
        device_label: object,
        platform: object,
        public_key: object,
) -> tuple[str, DevicePlatform, bytes, bytes]:
    validated_label = validate_device_label(device_label)
    if type(platform) is not DevicePlatform:
        raise InvalidControlStoreInput
    validated_public_key = validate_public_key(public_key)
    return (
        validated_label,
        platform,
        validated_public_key,
        _fingerprint_registration_request(
            pairing_id=pairing_id,
            device_label=validated_label,
            platform=platform,
            public_key=validated_public_key,
        ),
    )


def _encode_base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')


def _encode_pairing_response(
        *,
        pairing_id: PairingID,
        credential: PairingCredential,
        engine_origin: CanonicalEngineOrigin,
        expires_at: int,
) -> bytes:
    pairing_id_text = _encode_base64url(pairing_id)
    qr_payload = json.dumps(
        {
            'kind': PAIRING_QR_KIND,
            'format_version': PAIRING_QR_FORMAT_VERSION,
            'engine_origin': engine_origin.value,
            'pairing_id': pairing_id_text,
            'pairing_credential': _encode_base64url(credential),
            'expires_at': expires_at,
        },
        ensure_ascii=False,
        separators=(',', ':'),
    )
    return json.dumps(
        {
            'result': {
                'pairing_id': pairing_id_text,
                'expires_at': expires_at,
                'qr_payload': qr_payload,
            },
            'message': '',
        },
        ensure_ascii=False,
        separators=(',', ':'),
    ).encode()


def _encode_registration_response(device_session: DeviceSessionRecord) -> bytes:
    return json.dumps(
        {
            'result': {
                'device_session': {
                    'device_session_id': _encode_base64url(device_session.device_session_id),
                    'device_label': device_session.device_label,
                    'platform': device_session.platform.value,
                    'state': device_session.state.value,
                    'paired_at': device_session.paired_at,
                    'last_seen_at': device_session.last_seen_at,
                    'revoked_at': device_session.revoked_at,
                },
            },
            'message': '',
        },
        ensure_ascii=False,
        separators=(',', ':'),
    ).encode()


__all__ = [
    'PairingCancellationOutcome',
    'PairingCreated',
    'PairingRegistered',
    'PairingStore',
    'PairingStoreFailure',
]
