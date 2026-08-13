import os
import stat
from typing import TYPE_CHECKING
from unittest.mock import MagicMock, patch

import pytest
import rsqlite

from rotkehlchen.api.companion.control_store import ControlStore
from rotkehlchen.api.companion.schema import (
    CONTROL_DB_APPLICATION_ID,
    CONTROL_DB_SCHEMA_STATEMENTS,
    CONTROL_DB_VERSION,
)
from rotkehlchen.api.companion.types import ControlStoreUnavailable
from rotkehlchen.api.companion.upgrades import manager as control_upgrades
from rotkehlchen.api.companion.upgrades.manager import (
    ControlStoreUpgrade,
    initialize_or_upgrade_control_store,
)
from rotkehlchen.api.rest import RestAPI
from rotkehlchen.db.profile import ProfileID

if TYPE_CHECKING:
    from pathlib import Path

PROFILE_ID = ProfileID(bytes(range(32)))
EXPECTED_SCHEMA_OBJECTS = {
    ('index', 'idx_device_sessions_profile_order'),
    ('table', 'device_sessions'),
    ('trigger', 'device_sessions_prevent_revoked_update'),
}
EXPECTED_COLUMNS = [
    'device_session_id',
    'profile_id',
    'device_label',
    'platform',
    'paired_at',
    'last_seen_at',
    'revoked_at',
    'public_key_algorithm',
    'public_key',
]


def _set_database_identity(
        connection: rsqlite.Connection,
        *,
        application_id: int,
        version: int,
) -> None:
    connection.execute(f'PRAGMA application_id = {application_id}')
    connection.execute(f'PRAGMA user_version = {version}')


def _assert_generic_unavailable(
        error: pytest.ExceptionInfo[ControlStoreUnavailable],
        path: Path,
) -> None:
    message = str(error.value)
    assert 'unavailable' in message.lower()
    assert str(path) not in message


def test_fresh_schema_identity_objects_and_permissions(control_db_path: Path) -> None:
    store = ControlStore(control_db_path)
    connection = store._require_connection()
    assert connection.execute('PRAGMA foreign_keys').fetchone() == (1,)
    assert connection.execute('PRAGMA synchronous').fetchone() == (2,)
    assert connection.execute('PRAGMA trusted_schema').fetchone() == (0,)
    assert connection.execute('PRAGMA busy_timeout').fetchone() == (5_000,)
    store.close()

    with rsqlite.connect(control_db_path) as connection:
        assert connection.execute('PRAGMA application_id').fetchone() == (
            CONTROL_DB_APPLICATION_ID,
        )
        assert connection.execute('PRAGMA user_version').fetchone() == (CONTROL_DB_VERSION,)
        assert connection.execute('PRAGMA journal_mode').fetchone() == ('wal',)
        assert connection.execute('PRAGMA integrity_check').fetchall() == [('ok',)]
        assert connection.execute('PRAGMA foreign_key_check').fetchall() == []
        objects = {
            (object_type, name)
            for object_type, name in connection.execute(
                "SELECT type, name FROM sqlite_schema "
                "WHERE substr(name, 1, 7) != 'sqlite_'",
            )
        }
        assert objects == EXPECTED_SCHEMA_OBJECTS
        columns = [
            row[1] for row in connection.execute('PRAGMA table_info(device_sessions)')
        ]
        assert columns == EXPECTED_COLUMNS
        table_sql = connection.execute(
            "SELECT sql FROM sqlite_schema WHERE type='table' AND name='device_sessions'",
        ).fetchone()
        assert table_sql is not None
        assert 'WITHOUT ROWID' in table_sql[0].upper()

    if os.name == 'posix':
        assert stat.S_IMODE(control_db_path.stat().st_mode) == 0o600


def test_existing_empty_file_is_initialized(control_db_path: Path) -> None:
    control_db_path.touch(mode=0o600)
    store = ControlStore(control_db_path)
    store.close()

    with rsqlite.connect(control_db_path) as connection:
        assert connection.execute('PRAGMA application_id').fetchone() == (
            CONTROL_DB_APPLICATION_ID,
        )
        assert connection.execute('PRAGMA user_version').fetchone() == (CONTROL_DB_VERSION,)


def test_canonical_store_lives_below_global_directory(tmp_path: Path) -> None:
    store = ControlStore.at_data_directory(tmp_path)
    store.close()

    assert (tmp_path / 'global' / 'control.db').is_file()


def test_fresh_creation_rolls_back_schema_identity_and_version_together(
        control_db_path: Path,
) -> None:
    def fail_after_partial_schema(cursor: rsqlite.Cursor) -> None:
        cursor.execute('CREATE TABLE partial_schema(value INTEGER)')
        raise RuntimeError('injected creation failure')

    connection = rsqlite.connect(control_db_path, isolation_level=None)
    try:
        with (
            patch(
                'rotkehlchen.api.companion.upgrades.manager.create_control_store_schema',
                side_effect=fail_after_partial_schema,
            ),
            pytest.raises(RuntimeError, match='injected creation failure'),
        ):
            initialize_or_upgrade_control_store(connection)

        assert connection.execute('PRAGMA application_id').fetchone() == (0,)
        assert connection.execute('PRAGMA user_version').fetchone() == (0,)
        assert connection.execute(
            "SELECT name FROM sqlite_schema WHERE substr(name, 1, 7) != 'sqlite_'",
        ).fetchall() == []
    finally:
        connection.close()
    ControlStore(control_db_path).close()


def test_future_upgrade_rolls_back_schema_and_version_together(
        control_db_path: Path,
) -> None:
    store = ControlStore(control_db_path)
    store.close()

    def fail_after_partial_upgrade(cursor: rsqlite.Cursor) -> None:
        cursor.execute('CREATE TABLE partial_upgrade(value INTEGER)')
        raise RuntimeError('injected upgrade failure')

    connection = rsqlite.connect(control_db_path, isolation_level=None)
    try:
        with (
            patch.object(control_upgrades, 'CONTROL_DB_VERSION', 2),
            patch.object(
                control_upgrades,
                'CONTROL_STORE_UPGRADES',
                (ControlStoreUpgrade(1, 2, fail_after_partial_upgrade),),
            ),
            pytest.raises(RuntimeError, match='injected upgrade failure'),
        ):
            initialize_or_upgrade_control_store(connection)

        assert connection.execute('PRAGMA user_version').fetchone() == (1,)
        assert connection.execute(
            "SELECT name FROM sqlite_schema WHERE name='partial_upgrade'",
        ).fetchall() == []
    finally:
        connection.close()
    ControlStore(control_db_path).close()


def test_future_schema_is_rejected_without_rewriting(control_db_path: Path) -> None:
    store = ControlStore(control_db_path)
    store.close()
    with rsqlite.connect(control_db_path) as connection:
        connection.execute(f'PRAGMA user_version = {CONTROL_DB_VERSION + 1}')
    original = control_db_path.read_bytes()

    with pytest.raises(ControlStoreUnavailable) as error:
        ControlStore(control_db_path)

    _assert_generic_unavailable(error, control_db_path)
    assert control_db_path.read_bytes() == original
    with rsqlite.connect(control_db_path) as connection:
        assert connection.execute('PRAGMA user_version').fetchone() == (CONTROL_DB_VERSION + 1,)


def test_nonempty_v0_database_is_rejected_without_rewriting(control_db_path: Path) -> None:
    with rsqlite.connect(control_db_path) as connection:
        connection.execute('CREATE TABLE sentinel(value TEXT NOT NULL)')
        connection.execute("INSERT INTO sentinel(value) VALUES('preserve me')")
    original = control_db_path.read_bytes()

    with pytest.raises(ControlStoreUnavailable) as error:
        ControlStore(control_db_path)

    _assert_generic_unavailable(error, control_db_path)
    assert control_db_path.read_bytes() == original
    with rsqlite.connect(control_db_path) as connection:
        assert connection.execute('SELECT value FROM sentinel').fetchone() == ('preserve me',)
        assert connection.execute('PRAGMA user_version').fetchone() == (0,)


def test_sqlite_like_wildcard_cannot_hide_nonempty_v0_schema(control_db_path: Path) -> None:
    with rsqlite.connect(control_db_path) as connection:
        connection.execute('CREATE TABLE sqliteXhidden(value TEXT NOT NULL)')
        connection.execute("INSERT INTO sqliteXhidden(value) VALUES('preserve me')")
    original = control_db_path.read_bytes()

    with pytest.raises(ControlStoreUnavailable):
        ControlStore(control_db_path)

    assert control_db_path.read_bytes() == original
    with rsqlite.connect(control_db_path) as connection:
        assert connection.execute('SELECT value FROM sqliteXhidden').fetchone() == ('preserve me',)


def test_wrong_application_id_is_rejected_without_rewriting(control_db_path: Path) -> None:
    store = ControlStore(control_db_path)
    store.close()
    with rsqlite.connect(control_db_path) as connection:
        connection.execute(f'PRAGMA application_id = {CONTROL_DB_APPLICATION_ID + 1}')
    original = control_db_path.read_bytes()

    with pytest.raises(ControlStoreUnavailable) as error:
        ControlStore(control_db_path)

    _assert_generic_unavailable(error, control_db_path)
    assert control_db_path.read_bytes() == original
    with rsqlite.connect(control_db_path) as connection:
        assert connection.execute('PRAGMA application_id').fetchone() == (
            CONTROL_DB_APPLICATION_ID + 1,
        )


def test_current_version_with_malformed_schema_is_not_recreated(control_db_path: Path) -> None:
    with rsqlite.connect(control_db_path) as connection:
        _set_database_identity(
            connection,
            application_id=CONTROL_DB_APPLICATION_ID,
            version=CONTROL_DB_VERSION,
        )
        connection.execute('CREATE TABLE device_sessions(untrusted_value TEXT NOT NULL)')
        connection.execute("INSERT INTO device_sessions VALUES('preserve me')")
    original = control_db_path.read_bytes()

    with pytest.raises(ControlStoreUnavailable) as error:
        ControlStore(control_db_path)

    _assert_generic_unavailable(error, control_db_path)
    assert control_db_path.read_bytes() == original
    with rsqlite.connect(control_db_path) as connection:
        assert connection.execute('SELECT untrusted_value FROM device_sessions').fetchone() == (
            'preserve me',
        )


def test_schema_comparison_does_not_casefold_sql_literals(control_db_path: Path) -> None:
    with rsqlite.connect(control_db_path) as connection:
        for statement in CONTROL_DB_SCHEMA_STATEMENTS:
            connection.execute(statement.replace("'android'", "'ANDROID'", 1))
        _set_database_identity(
            connection,
            application_id=CONTROL_DB_APPLICATION_ID,
            version=CONTROL_DB_VERSION,
        )
    original = control_db_path.read_bytes()

    with pytest.raises(ControlStoreUnavailable):
        ControlStore(control_db_path)

    assert control_db_path.read_bytes() == original


def test_current_schema_rejects_sqlite_like_wildcard_hidden_object(
        control_db_path: Path,
) -> None:
    store = ControlStore(control_db_path)
    store.close()
    with rsqlite.connect(control_db_path) as connection:
        connection.execute('CREATE TABLE sqliteXhidden(value INTEGER)')
    original = control_db_path.read_bytes()

    with pytest.raises(ControlStoreUnavailable):
        ControlStore(control_db_path)

    assert control_db_path.read_bytes() == original


def test_current_schema_with_semantically_invalid_row_is_not_recreated(
        control_db_path: Path,
) -> None:
    store = ControlStore(control_db_path)
    store.close()
    with rsqlite.connect(control_db_path) as connection:
        connection.execute(
            'INSERT INTO device_sessions('
            'device_session_id, profile_id, device_label, platform, paired_at, '
            'public_key_algorithm, public_key) VALUES(?, ?, ?, ?, ?, ?, ?)',
            (
                b'\x01' * 32,
                PROFILE_ID,
                'Invalid point',
                'android',
                1_000,
                'ecdsa-p256-sha256-p1363',
                b'\x04' + b'\x00' * 64,
            ),
        )
    original = control_db_path.read_bytes()

    with pytest.raises(ControlStoreUnavailable) as error:
        ControlStore(control_db_path)

    _assert_generic_unavailable(error, control_db_path)
    assert control_db_path.read_bytes() == original
    with rsqlite.connect(control_db_path) as connection:
        assert connection.execute('SELECT COUNT(*) FROM device_sessions').fetchone() == (1,)


def test_corrupt_database_is_not_recreated(control_db_path: Path) -> None:
    corrupt_bytes = b'not a sqlite database\x00control store corruption'
    control_db_path.write_bytes(corrupt_bytes)

    with pytest.raises(ControlStoreUnavailable) as error:
        ControlStore(control_db_path)

    _assert_generic_unavailable(error, control_db_path)
    assert control_db_path.read_bytes() == corrupt_bytes


def test_truncated_database_is_not_recreated(control_db_path: Path) -> None:
    store = ControlStore(control_db_path)
    store.close()
    truncated = control_db_path.read_bytes()[:200]
    control_db_path.write_bytes(truncated)

    with pytest.raises(ControlStoreUnavailable) as error:
        ControlStore(control_db_path)

    _assert_generic_unavailable(error, control_db_path)
    assert control_db_path.read_bytes() == truncated


def test_closed_store_rejects_operations_with_generic_error(control_db_path: Path) -> None:
    store = ControlStore(control_db_path)
    store.close()

    with pytest.raises(ControlStoreUnavailable) as error:
        store.list_device_sessions(PROFILE_ID)

    _assert_generic_unavailable(error, control_db_path)


def test_sqlite_runtime_failure_permanently_fails_instance_closed(
        control_store: ControlStore,
) -> None:
    with (
        patch.object(
            control_store,
            '_require_connection',
            side_effect=rsqlite.OperationalError('seeded private detail'),
        ),
        pytest.raises(ControlStoreUnavailable, match='Control Store is unavailable'),
    ):
        control_store.list_device_sessions(PROFILE_ID)

    with pytest.raises(ControlStoreUnavailable, match='Control Store is unavailable'):
        control_store.list_device_sessions(PROFILE_ID)


def test_rest_api_disables_companion_when_control_store_is_unavailable(
        tmp_path: Path,
        caplog: pytest.LogCaptureFixture,
) -> None:
    rotki = MagicMock()
    rotki.data_dir = tmp_path
    rotki.api_tasks = []
    with patch.object(
        ControlStore,
        'at_data_directory',
        side_effect=ControlStoreUnavailable,
    ):
        rest_api = RestAPI(rotkehlchen=rotki)

    assert rest_api.companion_service.health_snapshot().available is False
    rotki.start.assert_called_once_with()
    assert 'Companion Control Store is unavailable; Companion is disabled' in caplog.text
    assert str(tmp_path) not in caplog.text


def test_rest_api_closes_control_store_before_global_cleanup(tmp_path: Path) -> None:
    rotki = MagicMock()
    rotki.data_dir = tmp_path
    rotki.api_tasks = []
    store = MagicMock(spec=ControlStore)
    events = MagicMock()
    store.close.side_effect = lambda: events('control_store_closed')
    global_db = MagicMock()
    global_db.cleanup.side_effect = lambda: events('global_db_cleaned')

    with (
        patch.object(ControlStore, 'at_data_directory', return_value=store),
        patch('rotkehlchen.api.rest.GlobalDBHandler', return_value=global_db),
        patch('rotkehlchen.api.rest.logging.shutdown'),
    ):
        rest_api = RestAPI(rotkehlchen=rotki)
        rest_api.stop()

    assert rest_api.companion_service.health_snapshot().available is False
    assert events.call_args_list == [
        (('control_store_closed',), {}),
        (('global_db_cleaned',), {}),
    ]
