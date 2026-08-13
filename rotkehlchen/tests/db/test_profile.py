from contextlib import suppress
from unittest.mock import patch

import pytest
from sqlcipher3 import dbapi2 as sqlcipher

from rotkehlchen.constants.misc import DEFAULT_SQL_VM_INSTRUCTIONS_CB
from rotkehlchen.db.dbhandler import DBHandler
from rotkehlchen.db.drivers.sqlite import DBConnection, DBConnectionType
from rotkehlchen.db.profile import PROFILE_ID_LENGTH, generate_profile_id
from rotkehlchen.db.schema import build_fresh_db_script
from rotkehlchen.db.settings import ROTKEHLCHEN_DB_VERSION
from rotkehlchen.db.utils import unlock_database
from rotkehlchen.errors.misc import DBSchemaError
from rotkehlchen.user_messages import MessagesAggregator


class _RollbackProfileMutation(Exception):
    pass


def _read_profile_id(database: DBHandler) -> bytes:
    with database.conn.read_ctx() as cursor:
        return database.get_profile_id(cursor)


def test_profile_id_is_stable_profile_scoped_and_backed_up(tmp_path, globaldb) -> None:  # pylint: disable=unused-argument
    """A fresh Profile gets one identity that survives ordinary persistence paths."""
    first_dir = tmp_path / 'first-profile'
    second_dir = tmp_path / 'second-profile'
    first_dir.mkdir()
    second_dir.mkdir()
    messages_aggregator = MessagesAggregator()

    first_db = DBHandler(
        user_data_dir=first_dir,
        password='123',
        msg_aggregator=messages_aggregator,
        initial_settings=None,
        sql_vm_instructions_cb=DEFAULT_SQL_VM_INSTRUCTIONS_CB,
        resume_from_backup=False,
    )
    try:
        first_profile_id = _read_profile_id(first_db)
        assert _read_profile_id(first_db) == first_profile_id
        backup_path = first_db.create_db_backup()
        sqlcipher_version = first_db.sqlcipher_version
    finally:
        first_db.logout()

    backup_connection = DBConnection(
        path=backup_path,
        connection_type=DBConnectionType.USER,
        sql_vm_instructions_cb=DEFAULT_SQL_VM_INSTRUCTIONS_CB,
    )
    try:
        unlock_database(
            db_connection=backup_connection,
            password='123',
            sqlcipher_version=sqlcipher_version,
            apply_optimizations=False,
        )
        with backup_connection.read_ctx() as cursor:
            assert cursor.execute(
                'SELECT profile_id FROM profile_metadata WHERE singleton=1',
            ).fetchone() == (first_profile_id,)
    finally:
        backup_connection.close()

    reopened_db = DBHandler(
        user_data_dir=first_dir,
        password='123',
        msg_aggregator=messages_aggregator,
        initial_settings=None,
        sql_vm_instructions_cb=DEFAULT_SQL_VM_INSTRUCTIONS_CB,
        resume_from_backup=False,
    )
    try:
        assert _read_profile_id(reopened_db) == first_profile_id
    finally:
        reopened_db.logout()

    second_db = DBHandler(
        user_data_dir=second_dir,
        password='123',
        msg_aggregator=messages_aggregator,
        initial_settings=None,
        sql_vm_instructions_cb=DEFAULT_SQL_VM_INSTRUCTIONS_CB,
        resume_from_backup=False,
    )
    try:
        second_profile_id = _read_profile_id(second_db)
    finally:
        second_db.logout()

    assert len(first_profile_id) == PROFILE_ID_LENGTH
    assert len(second_profile_id) == PROFILE_ID_LENGTH
    assert second_profile_id != first_profile_id


def test_profile_id_getter_fails_closed(database: DBHandler) -> None:
    original_profile_id = _read_profile_id(database)

    with suppress(_RollbackProfileMutation), database.conn.write_ctx() as write_cursor:
        write_cursor.execute('DELETE FROM profile_metadata')
        with pytest.raises(DBSchemaError, match='exactly one row'):
            database.get_profile_id(write_cursor)
        raise _RollbackProfileMutation

    for malformed_profile_id in (b'\x00' * (PROFILE_ID_LENGTH - 1), 'x' * PROFILE_ID_LENGTH):
        with suppress(_RollbackProfileMutation), database.conn.write_ctx() as write_cursor:
            write_cursor.execute('DROP TABLE profile_metadata')
            write_cursor.execute(
                'CREATE TABLE profile_metadata(singleton INTEGER, profile_id BLOB)',
            )
            write_cursor.execute(
                'INSERT INTO profile_metadata(singleton, profile_id) VALUES(1, ?)',
                (malformed_profile_id,),
            )
            with pytest.raises(DBSchemaError, match='malformed Profile ID'):
                database.get_profile_id(write_cursor)
            raise _RollbackProfileMutation

    assert _read_profile_id(database) == original_profile_id


def test_generate_profile_id_uses_32_random_bytes() -> None:
    assert len(generate_profile_id()) == PROFILE_ID_LENGTH

    expected_profile_id = bytes(range(PROFILE_ID_LENGTH))
    with patch(
        'rotkehlchen.db.profile.secrets.token_bytes',
        return_value=expected_profile_id,
    ) as mock_token_bytes:
        assert generate_profile_id() == expected_profile_id

    mock_token_bytes.assert_called_once_with(PROFILE_ID_LENGTH)


def test_fresh_profile_creation_is_atomic(tmp_path) -> None:
    """A failure before the sole commit rolls back schema, identity, and version together."""
    database_path = tmp_path / 'interrupted-profile.db'
    connection = DBConnection(
        path=database_path,
        connection_type=DBConnectionType.USER,
        sql_vm_instructions_cb=DEFAULT_SQL_VM_INSTRUCTIONS_CB,
    )
    expected_profile_id = bytes(reversed(range(PROFILE_ID_LENGTH)))
    valid_script = build_fresh_db_script(
        profile_id=expected_profile_id,
        version=ROTKEHLCHEN_DB_VERSION,
    )
    interrupted_script = valid_script.replace(
        'INSERT INTO profile_metadata(singleton, profile_id)',
        'INSERT INTO missing_profile_metadata(singleton, profile_id)',
        1,
    )
    assert interrupted_script != valid_script

    try:
        unlock_database(
            db_connection=connection,
            password='123',
            sqlcipher_version=4,
            apply_optimizations=False,
        )
        with (
            pytest.raises(sqlcipher.OperationalError),  # pylint: disable=no-member
            connection.write_ctx() as write_cursor,
        ):
            write_cursor.executescript(interrupted_script)

        with connection.read_ctx() as cursor:
            assert cursor.execute(
                "SELECT name FROM sqlite_master WHERE type='table'",
            ).fetchall() == []

        with connection.write_ctx() as write_cursor:
            write_cursor.executescript(valid_script)
        with connection.read_ctx() as cursor:
            assert cursor.execute(
                'SELECT profile_id FROM profile_metadata WHERE singleton=1',
            ).fetchone() == (expected_profile_id,)
            assert cursor.execute(
                "SELECT value FROM settings WHERE name='version'",
            ).fetchone() == (str(ROTKEHLCHEN_DB_VERSION),)
    finally:
        connection.close()
