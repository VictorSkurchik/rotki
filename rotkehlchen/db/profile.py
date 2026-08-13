import secrets
from typing import TYPE_CHECKING, Final, NewType

from rotkehlchen.errors.misc import DBSchemaError

if TYPE_CHECKING:
    from rotkehlchen.db.drivers.sqlite import DBCursor


PROFILE_ID_LENGTH: Final = 32
PROFILE_METADATA_SINGLETON: Final = 1
ProfileID = NewType('ProfileID', bytes)


def _deserialize_profile_id(rows: list[tuple[int, object]]) -> ProfileID:
    """Validate and deserialize the single encrypted Profile metadata row."""
    if len(rows) != 1:
        raise DBSchemaError('Profile metadata must contain exactly one row')

    singleton, value = rows[0]
    if (
            singleton != PROFILE_METADATA_SINGLETON or
            not isinstance(value, bytes) or
            len(value) != PROFILE_ID_LENGTH
    ):
        raise DBSchemaError('Profile metadata contains a malformed Profile ID')

    return ProfileID(value)


def generate_profile_id() -> ProfileID:
    """Generate a cryptographically random opaque Profile identifier."""
    return ProfileID(secrets.token_bytes(PROFILE_ID_LENGTH))


def read_profile_id(cursor: DBCursor) -> ProfileID:
    """Read the stable Profile ID, failing closed instead of creating or rotating it."""
    return _deserialize_profile_id(cursor.execute(
        'SELECT singleton, profile_id FROM profile_metadata',
    ).fetchall())
