from __future__ import annotations

from contextlib import suppress
from dataclasses import dataclass
from typing import TYPE_CHECKING, Final

import rsqlite

from rotkehlchen.api.companion.schema import (
    CONTROL_DB_APPLICATION_ID,
    CONTROL_DB_VERSION,
    create_control_store_schema,
    validate_control_store_schema,
)
from rotkehlchen.api.companion.types import ControlStoreUnavailable

if TYPE_CHECKING:
    from collections.abc import Callable


@dataclass(frozen=True, slots=True)
class ControlStoreUpgrade:
    """One structural transition; its version bump is owned by this manager."""

    from_version: int
    to_version: int
    apply: Callable[[rsqlite.Cursor], None]  # pylint: disable=no-member


# Version zero is reserved for a truly empty database and is initialized directly. Future
# releases append real structural transitions here; the store never drops unknown schemas.
CONTROL_STORE_UPGRADES: Final[tuple[ControlStoreUpgrade, ...]] = ()


def _read_pragma_integer(connection: rsqlite.Connection, pragma: str) -> int:  # pylint: disable=no-member
    row = connection.execute(f'PRAGMA {pragma}').fetchone()
    if row is None or len(row) != 1 or type(row[0]) is not int:
        raise ControlStoreUnavailable
    return row[0]


def _has_application_objects(connection: rsqlite.Connection) -> bool:  # pylint: disable=no-member
    return connection.execute(
        "SELECT 1 FROM sqlite_schema WHERE substr(name, 1, 7) != 'sqlite_' LIMIT 1",
    ).fetchone() is not None


def _run_transaction(
        connection: rsqlite.Connection,  # pylint: disable=no-member
        operation: Callable[[rsqlite.Cursor], None],  # pylint: disable=no-member
) -> None:
    cursor = connection.cursor()
    try:
        cursor.execute('BEGIN IMMEDIATE')
        operation(cursor)
        connection.commit()
    except BaseException:
        with suppress(rsqlite.Error):  # pylint: disable=no-member
            connection.rollback()
        raise
    finally:
        with suppress(rsqlite.Error):  # pylint: disable=no-member
            cursor.close()


def _initialize_fresh_control_store(connection: rsqlite.Connection) -> None:  # pylint: disable=no-member
    _run_transaction(connection, create_control_store_schema)


def _upgrade_control_store(
        connection: rsqlite.Connection,  # pylint: disable=no-member
        current_version: int,
) -> None:
    upgrades_by_source = {upgrade.from_version: upgrade for upgrade in CONTROL_STORE_UPGRADES}
    while current_version < CONTROL_DB_VERSION:
        upgrade = upgrades_by_source.get(current_version)
        if upgrade is None or upgrade.to_version != current_version + 1:
            raise ControlStoreUnavailable

        def apply_and_bump(
                cursor: rsqlite.Cursor,  # pylint: disable=no-member
                selected_upgrade: ControlStoreUpgrade = upgrade,
        ) -> None:
            selected_upgrade.apply(cursor)
            cursor.execute(f'PRAGMA user_version = {selected_upgrade.to_version}')

        _run_transaction(connection, apply_and_bump)
        current_version = upgrade.to_version


def initialize_or_upgrade_control_store(connection: rsqlite.Connection) -> None:  # pylint: disable=no-member
    """Initialize only an empty v0 store, otherwise migrate known versions forward.

    Future versions, foreign application IDs, malformed current schemas, and nonempty v0
    databases are rejected without mutation. Every real migration and its version bump share
    one explicit transaction.
    """
    try:
        application_id = _read_pragma_integer(connection, 'application_id')
        current_version = _read_pragma_integer(connection, 'user_version')
        has_application_objects = _has_application_objects(connection)

        if current_version == 0:
            if application_id != 0 or has_application_objects:
                raise ControlStoreUnavailable
            _initialize_fresh_control_store(connection)
        else:
            if (
                    application_id != CONTROL_DB_APPLICATION_ID or
                    current_version > CONTROL_DB_VERSION
            ):
                raise ControlStoreUnavailable
            _upgrade_control_store(connection, current_version)

        validate_control_store_schema(connection)
    except ControlStoreUnavailable:
        raise
    except rsqlite.Error:  # pylint: disable=no-member
        raise ControlStoreUnavailable from None
