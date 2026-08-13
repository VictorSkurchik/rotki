"""Bounded disposable state for Companion Pairing creation."""

from __future__ import annotations

import json
import math
import re
import secrets
import threading
import time
from collections.abc import Callable
from contextlib import suppress
from dataclasses import dataclass
from hashlib import sha256
from typing import Final, Protocol, cast

from rotkehlchen.api.companion.codec import EngineOrigin, encode_base64url
from rotkehlchen.api.companion.generated_protocol import (
    DECODED_LENGTHS,
    LIFETIMES_SECONDS,
)
from rotkehlchen.api.companion.types import (
    InvalidControlStoreInput,
    validate_profile_id,
)
from rotkehlchen.db.profile import ProfileID

PAIRING_LIFETIME_SECONDS: Final = LIFETIMES_SECONDS['pairing']
MAX_ACTIVE_PAIRINGS: Final = 32
PAIRING_GENERATION_ATTEMPTS: Final = 8
PAIRING_CREDENTIAL_HASH_DOMAIN: Final = b'rotki-companion-pairing-credential/v1'
PAIRING_CREATION_FINGERPRINT_DOMAIN: Final = b'rotki-companion-pairing-creation/v1'
BROWSER_SESSION_HASH_DOMAIN: Final = b'rotki-companion-browser-session/v1'
PAIRING_QR_KIND: Final = 'rotki_companion_pairing'
PAIRING_QR_FORMAT_VERSION: Final = 1
MAX_TIMESTAMP: Final = 2**63 - 1
_BROWSER_SESSION_ID_PATTERN: Final = re.compile(r'^[0-9a-f]{32}$')


class _TimerHandle(Protocol):
    def cancel(self) -> None:
        """Prevent the callback when it has not started yet."""


TimerFactory = Callable[[float, Callable[[], None]], _TimerHandle]


class InvalidPairingStoreInput(ValueError):
    """A caller value cannot be represented by the Pairing contract."""

    def __init__(self) -> None:
        super().__init__('Invalid Companion Pairing input')

    def __repr__(self) -> str:
        return '<InvalidPairingStoreInput redacted>'


class PairingStoreUnavailable(RuntimeError):
    """The Pairing store cannot safely create disposable authority."""

    def __init__(self) -> None:
        super().__init__('Companion Pairing is unavailable')

    def __repr__(self) -> str:
        return '<PairingStoreUnavailable redacted>'


class PairingIdempotencyConflict(RuntimeError):
    """One retained creation key was reused with different semantics."""

    def __init__(self) -> None:
        super().__init__('Companion Pairing idempotency conflict')

    def __repr__(self) -> str:
        return '<PairingIdempotencyConflict redacted>'


class PairingCapacityError(RuntimeError):
    """The bounded Pairing store has no slot until its earliest expiry."""

    def __init__(self, retry_after_seconds: int) -> None:
        if type(retry_after_seconds) is not int or retry_after_seconds < 1:
            raise InvalidPairingStoreInput
        self.retry_after_seconds = retry_after_seconds
        super().__init__('Companion Pairing capacity is exhausted')

    def __repr__(self) -> str:
        return (
            '<PairingCapacityError redacted '
            f'retry_after_seconds={self.retry_after_seconds}>'
        )


@dataclass(frozen=True, slots=True, repr=False)
class PairingCreation:
    """The exact secret-bearing creation result retained for replay."""

    pairing_id: str
    expires_at: int
    qr_payload: str

    def as_result(self) -> dict[str, object]:
        """Build the ordered success result expected by the HTTP envelope."""
        return {
            'pairing_id': self.pairing_id,
            'expires_at': self.expires_at,
            'qr_payload': self.qr_payload,
        }

    def __repr__(self) -> str:
        return '<PairingCreation redacted>'

    def __str__(self) -> str:
        return 'PairingCreation(redacted)'


type _IdempotencyScope = tuple[ProfileID, bytes, bytes]


@dataclass(frozen=True, slots=True, repr=False)
class _PairingRecord:
    profile_id: ProfileID
    engine_origin: EngineOrigin
    credential_hash: bytes
    expires_monotonic: float
    idempotency_scope: _IdempotencyScope
    creation: PairingCreation

    def __repr__(self) -> str:
        return '<_PairingRecord redacted>'


@dataclass(frozen=True, slots=True, repr=False)
class _CreationReplay:
    request_fingerprint: bytes
    pairing_id: bytes
    creation: PairingCreation

    def __repr__(self) -> str:
        return '<_CreationReplay redacted>'


def _threading_timer_factory(
        delay_seconds: float,
        callback: Callable[[], None],
) -> _TimerHandle:
    timer = threading.Timer(interval=delay_seconds, function=callback)
    timer.daemon = True
    timer.start()
    return timer


def _domain_hash(domain: bytes, value: bytes) -> bytes:
    return sha256(b''.join((domain, b'\x00', value))).digest()


def _validate_idempotency_key(value: object) -> bytes:
    if type(value) is not bytes or len(value) != DECODED_LENGTHS['idempotency_key']:
        raise InvalidPairingStoreInput
    return value


def _validate_pairing_id(value: object) -> bytes:
    if type(value) is not bytes or len(value) != DECODED_LENGTHS['pairing_id']:
        raise InvalidPairingStoreInput
    return value


def _validate_browser_session_id(value: object) -> str:
    if type(value) is not str or _BROWSER_SESSION_ID_PATTERN.fullmatch(value) is None:
        raise InvalidPairingStoreInput
    return value


def _validate_clock_value(value: object) -> int | float:
    if type(value) not in (float, int):
        raise PairingStoreUnavailable
    numeric_value = cast('int | float', value)
    if not math.isfinite(numeric_value) or numeric_value < 0:
        raise PairingStoreUnavailable
    return numeric_value


class PairingStore:
    """Thread-safe, bounded, in-memory Pairing authority.

    The only retained plaintext credential is inside the cached HTTP result required for
    exact idempotent replay. The live verifier index stores a purpose-domain hash. One
    timer follows the earliest monotonic deadline so expiry does not depend on traffic.
    """

    def __init__(
            self,
            *,
            max_active_pairings: int = MAX_ACTIVE_PAIRINGS,
            generation_attempts: int = PAIRING_GENERATION_ATTEMPTS,
            wall_clock: Callable[[], float] = time.time,
            monotonic_clock: Callable[[], float] = time.monotonic,
            random_bytes: Callable[[int], bytes] = secrets.token_bytes,
            timer_factory: TimerFactory = _threading_timer_factory,
    ) -> None:
        if (
                type(max_active_pairings) is not int or
                max_active_pairings < 1 or
                type(generation_attempts) is not int or
                generation_attempts < 1 or
                not callable(wall_clock) or
                not callable(monotonic_clock) or
                not callable(random_bytes) or
                not callable(timer_factory)
        ):
            raise InvalidPairingStoreInput
        self._max_active_pairings = max_active_pairings
        self._generation_attempts = generation_attempts
        self._wall_clock = wall_clock
        self._monotonic_clock = monotonic_clock
        self._random_bytes = random_bytes
        self._timer_factory = timer_factory
        self._lock = threading.RLock()
        self._records: dict[bytes, _PairingRecord] = {}
        self._pairing_by_credential_hash: dict[bytes, bytes] = {}
        self._creation_replays: dict[_IdempotencyScope, _CreationReplay] = {}
        self._expiry_timer: _TimerHandle | None = None
        self._timer_generation = 0

    def __repr__(self) -> str:
        return '<PairingStore redacted>'

    def create(
            self,
            *,
            profile_id: ProfileID,
            browser_session_id: str,
            idempotency_key: bytes,
            engine_origin: EngineOrigin,
    ) -> PairingCreation:
        """Create or replay one Pairing for an authenticated Full Client caller."""
        try:
            validated_profile_id = validate_profile_id(profile_id)
        except InvalidControlStoreInput:
            raise InvalidPairingStoreInput from None
        validated_session_id = _validate_browser_session_id(browser_session_id)
        validated_idempotency_key = _validate_idempotency_key(idempotency_key)
        if type(engine_origin) is not EngineOrigin:
            raise InvalidPairingStoreInput

        session_hash = _domain_hash(
            BROWSER_SESSION_HASH_DOMAIN,
            validated_session_id.encode('ascii'),
        )
        scope = (validated_profile_id, session_hash, validated_idempotency_key)
        request_fingerprint = _domain_hash(
            PAIRING_CREATION_FINGERPRINT_DOMAIN,
            engine_origin.canonical.encode('ascii'),
        )

        with self._lock:
            now_monotonic = self._read_monotonic()
            self._purge_and_reschedule_locked(now_monotonic)
            if (replay := self._creation_replays.get(scope)) is not None:
                if replay.request_fingerprint != request_fingerprint:
                    raise PairingIdempotencyConflict
                return replay.creation
            if len(self._records) >= self._max_active_pairings:
                earliest_expiry = min(
                    record.expires_monotonic for record in self._records.values()
                )
                raise PairingCapacityError(max(1, math.ceil(earliest_expiry - now_monotonic)))

            pairing_id, credential, credential_hash = self._generate_unique_pairing_locked()
            expires_at = self._read_expiry_wall_time()
            expires_monotonic = now_monotonic + PAIRING_LIFETIME_SECONDS
            pairing_id_encoded = encode_base64url(pairing_id)
            creation = PairingCreation(
                pairing_id=pairing_id_encoded,
                expires_at=expires_at,
                qr_payload=self._build_qr_payload(
                    engine_origin=engine_origin,
                    pairing_id=pairing_id_encoded,
                    pairing_credential=encode_base64url(credential),
                    expires_at=expires_at,
                ),
            )
            record = _PairingRecord(
                profile_id=validated_profile_id,
                engine_origin=engine_origin,
                credential_hash=credential_hash,
                expires_monotonic=expires_monotonic,
                idempotency_scope=scope,
                creation=creation,
            )
            self._records[pairing_id] = record
            self._pairing_by_credential_hash[credential_hash] = pairing_id
            self._creation_replays[scope] = _CreationReplay(
                request_fingerprint=request_fingerprint,
                pairing_id=pairing_id,
                creation=creation,
            )
            try:
                self._reschedule_expiry_locked(now_monotonic)
            except RuntimeError:
                self._clear_locked()
                raise PairingStoreUnavailable from None
            return creation

    def cancel(self, *, profile_id: ProfileID, pairing_id: bytes) -> bool:
        """Cancel only the caller Profile's Pairing while hiding existence and ownership."""
        try:
            validated_profile_id = validate_profile_id(profile_id)
        except InvalidControlStoreInput:
            raise InvalidPairingStoreInput from None
        validated_pairing_id = _validate_pairing_id(pairing_id)
        with self._lock:
            now_monotonic = self._read_monotonic()
            self._purge_and_reschedule_locked(now_monotonic)
            record = self._records.get(validated_pairing_id)
            if record is not None and record.profile_id == validated_profile_id:
                self._remove_record_locked(validated_pairing_id, record)
                try:
                    self._reschedule_expiry_locked(now_monotonic)
                except RuntimeError:
                    self._clear_locked()
                    raise PairingStoreUnavailable from None
        return True

    def clear(self) -> None:
        """Cancel the active timer and discard every Pairing and creation replay."""
        with self._lock:
            self._clear_locked()

    def active_count(self) -> int:
        """Return a non-sensitive count after enforcing the current expiry boundary."""
        with self._lock:
            now_monotonic = self._read_monotonic()
            self._purge_and_reschedule_locked(now_monotonic)
            return len(self._records)

    def _read_monotonic(self) -> float:
        return float(self._read_clock(self._monotonic_clock))

    def _read_expiry_wall_time(self) -> int:
        current = math.floor(self._read_clock(self._wall_clock))
        if current > MAX_TIMESTAMP - PAIRING_LIFETIME_SECONDS:
            raise PairingStoreUnavailable
        return current + PAIRING_LIFETIME_SECONDS

    @staticmethod
    def _read_clock(clock: Callable[[], float]) -> int | float:
        """Read a platform clock without exposing an OS diagnostic at the auth boundary."""
        try:
            value = clock()
        except OSError:
            raise PairingStoreUnavailable from None
        return _validate_clock_value(value)

    def _generate_unique_pairing_locked(self) -> tuple[bytes, bytes, bytes]:
        for _ in range(self._generation_attempts):
            pairing_id = self._generate_random(DECODED_LENGTHS['pairing_id'])
            credential = self._generate_random(DECODED_LENGTHS['pairing_credential'])
            credential_hash = _domain_hash(PAIRING_CREDENTIAL_HASH_DOMAIN, credential)
            if (
                    pairing_id not in self._records and
                    credential_hash not in self._pairing_by_credential_hash
            ):
                return pairing_id, credential, credential_hash
        raise PairingStoreUnavailable

    def _generate_random(self, length: int) -> bytes:
        try:
            value = self._random_bytes(length)
        except Exception:  # pylint: disable=broad-exception-caught
            # The injected entropy boundary is arbitrary application code in tests and
            # a platform primitive in production. Never let its diagnostic cross into an
            # HTTP error, while still allowing process-control exceptions to propagate.
            raise PairingStoreUnavailable from None
        if type(value) is not bytes or len(value) != length:
            raise PairingStoreUnavailable
        return value

    @staticmethod
    def _build_qr_payload(
            *,
            engine_origin: EngineOrigin,
            pairing_id: str,
            pairing_credential: str,
            expires_at: int,
    ) -> str:
        return json.dumps(
            {
                'kind': PAIRING_QR_KIND,
                'format_version': PAIRING_QR_FORMAT_VERSION,
                'engine_origin': engine_origin.canonical,
                'pairing_id': pairing_id,
                'pairing_credential': pairing_credential,
                'expires_at': expires_at,
            },
            ensure_ascii=False,
            separators=(',', ':'),
        )

    def _purge_expired_locked(self, now_monotonic: float) -> bool:
        expired = [
            (pairing_id, record)
            for pairing_id, record in self._records.items()
            if now_monotonic >= record.expires_monotonic
        ]
        for pairing_id, record in expired:
            self._remove_record_locked(pairing_id, record)
        return bool(expired)

    def _remove_record_locked(self, pairing_id: bytes, record: _PairingRecord) -> None:
        self._records.pop(pairing_id, None)
        self._pairing_by_credential_hash.pop(record.credential_hash, None)
        replay = self._creation_replays.get(record.idempotency_scope)
        if replay is not None and replay.pairing_id == pairing_id:
            self._creation_replays.pop(record.idempotency_scope, None)

    def _purge_and_reschedule_locked(self, now_monotonic: float) -> None:
        if self._purge_expired_locked(now_monotonic) is False:
            return
        try:
            self._reschedule_expiry_locked(now_monotonic)
        except RuntimeError:
            self._clear_locked()
            raise PairingStoreUnavailable from None

    def _reschedule_expiry_locked(self, now_monotonic: float) -> None:
        self._cancel_timer_locked()
        if len(self._records) == 0:
            return
        earliest_expiry = min(
            record.expires_monotonic for record in self._records.values()
        )
        self._timer_generation += 1
        timer_generation = self._timer_generation

        def expire() -> None:
            self._expire_from_timer(timer_generation)

        try:
            timer = self._timer_factory(
                max(0.0, earliest_expiry - now_monotonic),
                expire,
            )
        except Exception:  # pylint: disable=broad-exception-caught
            raise RuntimeError from None
        if not callable(getattr(timer, 'cancel', None)):
            raise RuntimeError
        self._expiry_timer = timer

    def _expire_from_timer(self, timer_generation: int) -> None:
        with self._lock:
            if timer_generation != self._timer_generation:
                return
            self._expiry_timer = None
            try:
                now_monotonic = self._read_monotonic()
            except PairingStoreUnavailable:
                self._clear_locked()
                return
            self._purge_expired_locked(now_monotonic)
            try:
                self._reschedule_expiry_locked(now_monotonic)
            except RuntimeError:
                self._clear_locked()

    def _cancel_timer_locked(self) -> None:
        self._timer_generation += 1
        if self._expiry_timer is not None:
            with suppress(BaseException):
                self._expiry_timer.cancel()
            self._expiry_timer = None

    def _clear_locked(self) -> None:
        self._cancel_timer_locked()
        self._records.clear()
        self._pairing_by_credential_hash.clear()
        self._creation_replays.clear()


__all__ = [
    'InvalidPairingStoreInput',
    'PairingCapacityError',
    'PairingCreation',
    'PairingIdempotencyConflict',
    'PairingStore',
    'PairingStoreUnavailable',
]
