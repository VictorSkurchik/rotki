from __future__ import annotations

import os
import secrets
import threading
from contextlib import contextmanager, suppress
from pathlib import Path
from typing import TYPE_CHECKING, Final

import rsqlite

from rotkehlchen.api.companion.schema import (
    CONTROL_DB_NAME,
    validate_control_store_schema,
)
from rotkehlchen.api.companion.types import (
    DEVICE_PUBLIC_KEY_ALGORITHM,
    AuthorizedDeviceBinding,
    ControlStoreUnavailable,
    DevicePlatform,
    DeviceSessionID,
    DeviceSessionRecord,
    DeviceSessionState,
    InvalidControlStoreInput,
    validate_device_label,
    validate_device_session_id,
    validate_optional_timestamp,
    validate_platform,
    validate_profile_id,
    validate_public_key,
    validate_timestamp,
)
from rotkehlchen.api.companion.upgrades.manager import initialize_or_upgrade_control_store
from rotkehlchen.constants.misc import GLOBALDIR_NAME

if TYPE_CHECKING:
    from collections.abc import Iterator, Sequence

    from rotkehlchen.db.profile import ProfileID


DEVICE_SESSION_ID_GENERATION_ATTEMPTS: Final = 8
CONTROL_DB_BUSY_TIMEOUT_MILLISECONDS: Final = 5_000


class ControlStore:
    """Durable, non-secret authority for Companion Device Sessions.

    The store owns one deliberately serialized plaintext SQLite connection. It never logs
    statements or bindings: opaque identifiers, public keys, and labels are non-secret but
    remain privacy-sensitive diagnostic data.
    """

    def __init__(self, db_path: Path) -> None:
        self._lock = threading.RLock()
        self._connection: rsqlite.Connection | None = None  # pylint: disable=no-member
        self._db_path = db_path
        try:
            self._create_private_file_if_missing()
            connection = rsqlite.connect(
                database=db_path,
                check_same_thread=False,
                isolation_level=None,
            )
            self._connection = connection
            connection.execute('PRAGMA foreign_keys = ON')
            connection.execute('PRAGMA trusted_schema = OFF')
            connection.execute(f'PRAGMA busy_timeout = {CONTROL_DB_BUSY_TIMEOUT_MILLISECONDS}')

            # Inspect and migrate before enabling WAL: an unsupported or corrupt store must
            # not be rewritten merely by attempting to open it.
            initialize_or_upgrade_control_store(connection)
            journal_mode = connection.execute('PRAGMA journal_mode = WAL').fetchone()
            if journal_mode != ('wal',):
                raise ControlStoreUnavailable
            connection.execute('PRAGMA synchronous = FULL')
            validate_control_store_schema(connection)
            self._harden_database_permissions()
        except ControlStoreUnavailable:
            self._close_after_failed_initialization()
            raise
        except (OSError, rsqlite.Error):  # pylint: disable=no-member
            self._close_after_failed_initialization()
            raise ControlStoreUnavailable from None

    @classmethod
    def at_data_directory(cls, data_directory: Path) -> ControlStore:
        """Open the canonical store below ``<data_dir>/global``."""
        return cls(data_directory / GLOBALDIR_NAME / CONTROL_DB_NAME)

    def _create_private_file_if_missing(self) -> None:
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        try:
            descriptor = os.open(
                self._db_path,
                os.O_CREAT | os.O_EXCL | os.O_RDWR,
                0o600,
            )
        except FileExistsError:
            return
        os.close(descriptor)

    def _harden_database_permissions(self) -> None:
        if os.name == 'posix':
            for path in (
                self._db_path,
                Path(f'{self._db_path}-wal'),
                Path(f'{self._db_path}-shm'),
            ):
                if path.exists():
                    path.chmod(0o600)

    def _close_after_failed_initialization(self) -> None:
        if self._connection is None:
            return
        with suppress(rsqlite.Error):  # pylint: disable=no-member
            self._connection.close()
        self._connection = None

    def _require_connection(self) -> rsqlite.Connection:  # pylint: disable=no-member
        if self._connection is None:
            raise ControlStoreUnavailable
        return self._connection

    def _mark_unavailable(self) -> ControlStoreUnavailable:
        """Permanently fail this instance closed after a SQLite runtime failure."""
        connection = self._connection
        self._connection = None
        if connection is not None:
            with suppress(rsqlite.Error):  # pylint: disable=no-member
                connection.close()
        return ControlStoreUnavailable()

    @contextmanager
    def _write_cursor(self) -> Iterator[rsqlite.Cursor]:  # pylint: disable=no-member
        connection = self._require_connection()
        cursor = connection.cursor()
        try:
            cursor.execute('BEGIN IMMEDIATE')
            yield cursor
            connection.commit()
        except BaseException:
            with suppress(rsqlite.Error):  # pylint: disable=no-member
                connection.rollback()
            raise
        finally:
            with suppress(rsqlite.Error):  # pylint: disable=no-member
                cursor.close()

    @staticmethod
    def _validate_public_platform(value: object) -> DevicePlatform:
        if type(value) is not DevicePlatform:
            raise InvalidControlStoreInput
        return value

    def _deserialize_record(self, row: Sequence[object]) -> DeviceSessionRecord:
        try:
            if len(row) != 6:
                raise InvalidControlStoreInput
            (
                device_session_id,
                device_label,
                platform,
                paired_at,
                last_seen_at,
                revoked_at,
            ) = row
            validated_platform = validate_platform(platform)
            validated_paired_at = validate_timestamp(paired_at)
            validated_last_seen_at = validate_optional_timestamp(last_seen_at)
            validated_revoked_at = validate_optional_timestamp(revoked_at)
            return DeviceSessionRecord(
                device_session_id=validate_device_session_id(device_session_id),
                device_label=validate_device_label(device_label),
                platform=validated_platform,
                state=(
                    DeviceSessionState.AUTHORIZED
                    if validated_revoked_at is None
                    else DeviceSessionState.REVOKED
                ),
                paired_at=validated_paired_at,
                last_seen_at=validated_last_seen_at,
                revoked_at=validated_revoked_at,
            )
        except (InvalidControlStoreInput, TypeError, ValueError):
            raise self._mark_unavailable() from None

    def register_device_session(
            self,
            *,
            profile_id: ProfileID,
            device_label: str,
            platform: DevicePlatform,
            public_key: bytes,
            paired_at: int,
    ) -> DeviceSessionRecord:
        """Create one authorized Device Session without overwriting any existing ID."""
        validated_profile_id = validate_profile_id(profile_id)
        validated_label = validate_device_label(device_label)
        validated_platform = self._validate_public_platform(platform)
        validated_public_key = validate_public_key(public_key)
        validated_paired_at = validate_timestamp(paired_at)

        with self._lock:
            try:
                with self._write_cursor() as cursor:
                    for _ in range(DEVICE_SESSION_ID_GENERATION_ATTEMPTS):
                        device_session_id = validate_device_session_id(secrets.token_bytes(32))
                        if cursor.execute(
                                'SELECT 1 FROM device_sessions WHERE device_session_id=?',
                                (device_session_id,),
                        ).fetchone() is None:
                            break
                    else:
                        raise ControlStoreUnavailable

                    cursor.execute(
                        'INSERT INTO device_sessions('
                        'device_session_id, profile_id, device_label, platform, paired_at, '
                        'public_key_algorithm, public_key) VALUES(?, ?, ?, ?, ?, ?, ?)',
                        (
                            device_session_id,
                            validated_profile_id,
                            validated_label,
                            validated_platform.value,
                            validated_paired_at,
                            DEVICE_PUBLIC_KEY_ALGORITHM,
                            validated_public_key,
                        ),
                    )
            except (ControlStoreUnavailable, InvalidControlStoreInput):
                raise
            except rsqlite.Error:  # pylint: disable=no-member
                raise self._mark_unavailable() from None

        return DeviceSessionRecord(
            device_session_id=device_session_id,
            device_label=validated_label,
            platform=validated_platform,
            state=DeviceSessionState.AUTHORIZED,
            paired_at=validated_paired_at,
            last_seen_at=None,
            revoked_at=None,
        )

    def get_authorized_binding(
            self,
            device_session_id: DeviceSessionID,
    ) -> AuthorizedDeviceBinding | None:
        """Return proof-verification material; unknown and revoked IDs are equivalent."""
        validated_id = validate_device_session_id(device_session_id)
        with self._lock:
            try:
                row = self._require_connection().execute(
                    'SELECT device_session_id, profile_id, public_key_algorithm, public_key '
                    'FROM device_sessions WHERE device_session_id=? AND revoked_at IS NULL',
                    (validated_id,),
                ).fetchone()
            except rsqlite.Error:  # pylint: disable=no-member
                raise self._mark_unavailable() from None
        if row is None:
            return None
        try:
            return AuthorizedDeviceBinding(*row)
        except (InvalidControlStoreInput, TypeError, ValueError):
            raise self._mark_unavailable() from None

    def list_device_sessions(self, profile_id: ProfileID) -> list[DeviceSessionRecord]:
        """List one Profile Lineage's live and revoked audit records in wire order."""
        validated_profile_id = validate_profile_id(profile_id)
        with self._lock:
            try:
                rows = self._require_connection().execute(
                    'SELECT device_session_id, device_label, platform, paired_at, '
                    'last_seen_at, revoked_at FROM device_sessions WHERE profile_id=? '
                    'ORDER BY paired_at DESC, device_session_id ASC',
                    (validated_profile_id,),
                ).fetchall()
            except rsqlite.Error:  # pylint: disable=no-member
                raise self._mark_unavailable() from None
        return [self._deserialize_record(row) for row in rows]

    def rename_device_session(
            self,
            profile_id: ProfileID,
            device_session_id: DeviceSessionID,
            device_label: str,
    ) -> DeviceSessionRecord | None:
        """Rename an authorized Profile-scoped record; never disclose cross-Profile rows."""
        validated_profile_id = validate_profile_id(profile_id)
        validated_id = validate_device_session_id(device_session_id)
        validated_label = validate_device_label(device_label)
        with self._lock:
            try:
                with self._write_cursor() as cursor:
                    cursor.execute(
                        'UPDATE device_sessions SET device_label=? '
                        'WHERE profile_id=? AND device_session_id=? AND revoked_at IS NULL',
                        (validated_label, validated_profile_id, validated_id),
                    )
                    row = cursor.execute(
                        'SELECT device_session_id, device_label, platform, paired_at, '
                        'last_seen_at, revoked_at FROM device_sessions '
                        'WHERE profile_id=? AND device_session_id=? AND revoked_at IS NULL',
                        (validated_profile_id, validated_id),
                    ).fetchone()
            except rsqlite.Error:  # pylint: disable=no-member
                raise self._mark_unavailable() from None
        return None if row is None else self._deserialize_record(row)

    def revoke_device_session(
            self,
            profile_id: ProfileID,
            device_session_id: DeviceSessionID,
            revoked_at: int,
    ) -> DeviceSessionRecord | None:
        """Remove authorization while retaining one immutable Profile-scoped audit row."""
        validated_profile_id = validate_profile_id(profile_id)
        validated_id = validate_device_session_id(device_session_id)
        validated_revoked_at = validate_timestamp(revoked_at)
        with self._lock:
            try:
                with self._write_cursor() as cursor:
                    row = cursor.execute(
                        'SELECT device_session_id, device_label, platform, paired_at, '
                        'last_seen_at, revoked_at FROM device_sessions '
                        'WHERE profile_id=? AND device_session_id=?',
                        (validated_profile_id, validated_id),
                    ).fetchone()
                    if row is None:
                        return None
                    record = self._deserialize_record(row)
                    if record.state is DeviceSessionState.REVOKED:
                        return record
                    # Revocation must never fail merely because the wall clock moved
                    # backwards. Clamp to the durable timeline while still deleting the
                    # authorization key in this transaction.
                    effective_revoked_at = max(
                        validated_revoked_at,
                        record.paired_at,
                        record.last_seen_at or record.paired_at,
                    )

                    cursor.execute(
                        'UPDATE device_sessions SET revoked_at=?, '
                        'public_key_algorithm=NULL, public_key=NULL '
                        'WHERE profile_id=? AND device_session_id=? AND revoked_at IS NULL',
                        (effective_revoked_at, validated_profile_id, validated_id),
                    )
                    row = cursor.execute(
                        'SELECT device_session_id, device_label, platform, paired_at, '
                        'last_seen_at, revoked_at FROM device_sessions '
                        'WHERE profile_id=? AND device_session_id=?',
                        (validated_profile_id, validated_id),
                    ).fetchone()
            except (ControlStoreUnavailable, InvalidControlStoreInput):
                raise
            except rsqlite.Error:  # pylint: disable=no-member
                raise self._mark_unavailable() from None
        if row is None:
            raise ControlStoreUnavailable
        return self._deserialize_record(row)

    def mark_successful_proof(
            self,
            device_session_id: DeviceSessionID,
            seen_at: int,
    ) -> bool:
        """Advance last-seen monotonically for an authorized Device Session only."""
        validated_id = validate_device_session_id(device_session_id)
        validated_seen_at = validate_timestamp(seen_at)
        with self._lock:
            try:
                with self._write_cursor() as cursor:
                    row = cursor.execute(
                        'SELECT paired_at, revoked_at FROM device_sessions '
                        'WHERE device_session_id=?',
                        (validated_id,),
                    ).fetchone()
                    if row is None or row[1] is not None:
                        return False
                    try:
                        paired_at = validate_timestamp(row[0])
                    except InvalidControlStoreInput:
                        raise ControlStoreUnavailable from None
                    effective_seen_at = max(validated_seen_at, paired_at)
                    cursor.execute(
                        'UPDATE device_sessions SET last_seen_at = CASE '
                        'WHEN last_seen_at IS NULL OR last_seen_at < ? THEN ? '
                        'ELSE last_seen_at END '
                        'WHERE device_session_id=? AND revoked_at IS NULL',
                        (effective_seen_at, effective_seen_at, validated_id),
                    )
            except (ControlStoreUnavailable, InvalidControlStoreInput):
                raise
            except rsqlite.Error:  # pylint: disable=no-member
                raise self._mark_unavailable() from None
        return True

    def close(self) -> None:
        """Checkpoint and close once; repeated shutdown is harmless."""
        with self._lock:
            if self._connection is None:
                return
            connection = self._connection
            failure = False
            try:
                connection.execute('PRAGMA wal_checkpoint(TRUNCATE)')
            except (OSError, rsqlite.Error):  # pylint: disable=no-member
                failure = True
            try:
                connection.close()
            except rsqlite.Error:  # pylint: disable=no-member
                failure = True
            self._connection = None
            try:
                self._harden_database_permissions()
            except OSError:
                failure = True
            if failure:
                raise ControlStoreUnavailable from None


__all__ = ['ControlStore']
