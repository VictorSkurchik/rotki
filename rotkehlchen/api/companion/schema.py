from __future__ import annotations

from typing import TYPE_CHECKING, Final

from rotkehlchen.api.companion.types import (
    DEVICE_PUBLIC_KEY_ALGORITHM,
    ControlStoreUnavailable,
    InvalidControlStoreInput,
    validate_device_label,
    validate_device_session_id,
    validate_optional_timestamp,
    validate_platform,
    validate_profile_id,
    validate_public_key,
    validate_public_key_algorithm,
    validate_timestamp,
)

if TYPE_CHECKING:
    import rsqlite

CONTROL_DB_NAME: Final = 'control.db'
CONTROL_DB_APPLICATION_ID: Final = 0x524B4344  # ASCII ``RKCD``.
CONTROL_DB_VERSION: Final = 1

DEVICE_SESSIONS_TABLE: Final = 'device_sessions'
DEVICE_SESSIONS_PROFILE_INDEX: Final = 'idx_device_sessions_profile_order'
DEVICE_SESSIONS_REVOKED_UPDATE_TRIGGER: Final = 'device_sessions_prevent_revoked_update'

CREATE_DEVICE_SESSIONS_TABLE: Final = f"""CREATE TABLE {DEVICE_SESSIONS_TABLE} (
    device_session_id BLOB NOT NULL PRIMARY KEY
        CHECK(typeof(device_session_id) = 'blob' AND length(device_session_id) = 32),
    profile_id BLOB NOT NULL
        CHECK(typeof(profile_id) = 'blob' AND length(profile_id) = 32),
    device_label TEXT NOT NULL
        CHECK(
            typeof(device_label) = 'text' AND
            length(device_label) BETWEEN 1 AND 64 AND
            length(CAST(device_label AS BLOB)) <= 256
        ),
    platform TEXT NOT NULL
        CHECK(typeof(platform) = 'text' AND platform IN ('android', 'ios')),
    paired_at INTEGER NOT NULL
        CHECK(typeof(paired_at) = 'integer' AND paired_at >= 0),
    last_seen_at INTEGER
        CHECK(
            last_seen_at IS NULL OR
            (typeof(last_seen_at) = 'integer' AND last_seen_at >= paired_at)
        ),
    revoked_at INTEGER
        CHECK(
            revoked_at IS NULL OR
            (
                typeof(revoked_at) = 'integer' AND
                revoked_at >= paired_at AND
                (last_seen_at IS NULL OR revoked_at >= last_seen_at)
            )
        ),
    public_key_algorithm TEXT,
    public_key BLOB,
    CHECK(
        (
            revoked_at IS NULL AND
            typeof(public_key_algorithm) = 'text' AND
            public_key_algorithm = '{DEVICE_PUBLIC_KEY_ALGORITHM}' AND
            typeof(public_key) = 'blob' AND
            length(public_key) = 65 AND
            substr(public_key, 1, 1) = x'04'
        ) OR (
            revoked_at IS NOT NULL AND
            public_key_algorithm IS NULL AND
            public_key IS NULL
        )
    )
) WITHOUT ROWID"""

CREATE_DEVICE_SESSIONS_PROFILE_INDEX: Final = (
    f'CREATE INDEX {DEVICE_SESSIONS_PROFILE_INDEX} ON {DEVICE_SESSIONS_TABLE}'
    '(profile_id, paired_at DESC, device_session_id ASC)'
)

CREATE_DEVICE_SESSIONS_REVOKED_UPDATE_TRIGGER: Final = f"""CREATE TRIGGER {
    DEVICE_SESSIONS_REVOKED_UPDATE_TRIGGER
}
BEFORE UPDATE ON {DEVICE_SESSIONS_TABLE}
WHEN OLD.revoked_at IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'revoked Device Session is immutable');
END"""

CONTROL_DB_SCHEMA_STATEMENTS: Final = (
    CREATE_DEVICE_SESSIONS_TABLE,
    CREATE_DEVICE_SESSIONS_PROFILE_INDEX,
    CREATE_DEVICE_SESSIONS_REVOKED_UPDATE_TRIGGER,
)

_EXPECTED_SCHEMA_OBJECTS: Final = {
    ('table', DEVICE_SESSIONS_TABLE): CREATE_DEVICE_SESSIONS_TABLE,
    ('index', DEVICE_SESSIONS_PROFILE_INDEX): CREATE_DEVICE_SESSIONS_PROFILE_INDEX,
    ('trigger', DEVICE_SESSIONS_REVOKED_UPDATE_TRIGGER): (
        CREATE_DEVICE_SESSIONS_REVOKED_UPDATE_TRIGGER
    ),
}


def create_control_store_schema(cursor: rsqlite.Cursor) -> None:  # pylint: disable=no-member
    """Create a fresh v1 schema inside the caller's explicit transaction."""
    for statement in CONTROL_DB_SCHEMA_STATEMENTS:
        cursor.execute(statement)
    cursor.execute(f'PRAGMA application_id = {CONTROL_DB_APPLICATION_ID}')
    # Set the version last so interruption can never advertise an incomplete schema.
    cursor.execute(f'PRAGMA user_version = {CONTROL_DB_VERSION}')


def _read_pragma_integer(connection: rsqlite.Connection, pragma: str) -> int:  # pylint: disable=no-member
    row = connection.execute(f'PRAGMA {pragma}').fetchone()
    if row is None or len(row) != 1 or type(row[0]) is not int:
        raise ControlStoreUnavailable
    return row[0]


def _validate_schema_objects(connection: rsqlite.Connection) -> None:  # pylint: disable=no-member
    rows = connection.execute(
        "SELECT type, name, sql FROM sqlite_schema WHERE substr(name, 1, 7) != 'sqlite_'",
    ).fetchall()
    if len(rows) != len(_EXPECTED_SCHEMA_OBJECTS):
        raise ControlStoreUnavailable

    actual_objects: dict[tuple[str, str], str] = {}
    for object_type, name, sql in rows:
        if type(object_type) is not str or type(name) is not str or type(sql) is not str:
            raise ControlStoreUnavailable
        actual_objects[object_type, name] = sql
    if set(actual_objects) != set(_EXPECTED_SCHEMA_OBJECTS):
        raise ControlStoreUnavailable
    for identity, expected_sql in _EXPECTED_SCHEMA_OBJECTS.items():
        if actual_objects[identity] != expected_sql:
            raise ControlStoreUnavailable


def _validate_database_integrity(connection: rsqlite.Connection) -> None:  # pylint: disable=no-member
    if connection.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
        raise ControlStoreUnavailable
    if connection.execute('PRAGMA foreign_key_check').fetchall() != []:
        raise ControlStoreUnavailable


def _validate_device_session_rows(connection: rsqlite.Connection) -> None:  # pylint: disable=no-member
    rows = connection.execute(
        'SELECT device_session_id, profile_id, device_label, platform, paired_at, '
        'last_seen_at, revoked_at, public_key_algorithm, public_key FROM device_sessions',
    ).fetchall()
    try:
        for (
                device_session_id,
                profile_id,
                device_label,
                platform,
                paired_at,
                last_seen_at,
                revoked_at,
                public_key_algorithm,
                public_key,
        ) in rows:
            validate_device_session_id(device_session_id)
            validate_profile_id(profile_id)
            validate_device_label(device_label)
            validate_platform(platform)
            validated_paired_at = validate_timestamp(paired_at)
            validated_last_seen_at = validate_optional_timestamp(last_seen_at)
            validated_revoked_at = validate_optional_timestamp(revoked_at)
            if (
                    (validated_last_seen_at is not None and
                     validated_last_seen_at < validated_paired_at) or
                    (validated_revoked_at is not None and
                     validated_revoked_at < validated_paired_at) or
                    (validated_last_seen_at is not None and
                     validated_revoked_at is not None and
                     validated_revoked_at < validated_last_seen_at)
            ):
                raise InvalidControlStoreInput

            if validated_revoked_at is None:
                validate_public_key_algorithm(public_key_algorithm)
                validate_public_key(public_key)
            elif public_key_algorithm is not None or public_key is not None:
                raise InvalidControlStoreInput
    except (InvalidControlStoreInput, TypeError, ValueError):
        raise ControlStoreUnavailable from None


def validate_control_store_schema(connection: rsqlite.Connection) -> None:  # pylint: disable=no-member
    """Fail closed unless metadata, SQL objects, integrity, and every row match v1."""
    if (
            _read_pragma_integer(connection, 'application_id') != CONTROL_DB_APPLICATION_ID or
            _read_pragma_integer(connection, 'user_version') != CONTROL_DB_VERSION
    ):
        raise ControlStoreUnavailable
    _validate_schema_objects(connection)
    _validate_database_integrity(connection)
    _validate_device_session_rows(connection)
