from typing import TYPE_CHECKING

import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from rotkehlchen.api.companion.control_store import ControlStore

if TYPE_CHECKING:
    from collections.abc import Iterator
    from pathlib import Path


@pytest.fixture(name='control_db_path')
def fixture_control_db_path(tmp_path: Path) -> Path:
    return tmp_path / 'control.db'


@pytest.fixture(name='control_store')
def fixture_control_store(control_db_path: Path) -> Iterator[ControlStore]:
    store = ControlStore(control_db_path)
    yield store
    store.close()


@pytest.fixture
def p256_public_key() -> bytes:
    numbers = ec.derive_private_key(1, ec.SECP256R1()).public_key().public_numbers()
    return b'\x04' + numbers.x.to_bytes(32, 'big') + numbers.y.to_bytes(32, 'big')
