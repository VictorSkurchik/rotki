"""Tests for the fail-closed Companion service lifecycle boundary."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event, Lock, Thread
from typing import TYPE_CHECKING, cast

import pytest

from rotkehlchen.api.companion.service import CompanionService
from rotkehlchen.api.companion.types import (
    ControlStoreUnavailable,
    InvalidControlStoreInput,
)
from rotkehlchen.db.profile import ProfileID

if TYPE_CHECKING:
    from rotkehlchen.api.companion.control_store import ControlStore


PROFILE_ID_A = ProfileID(b'\x11' * 32)
PROFILE_ID_B = ProfileID(b'\x22' * 32)


class StubControlStore:  # pylint: disable=too-few-public-methods
    """Minimal close-observable Control Store test double."""

    def __init__(self, close_error: BaseException | None = None) -> None:
        self.close_calls = 0
        self.close_error = close_error

    def close(self) -> None:
        """Count and optionally fail the best-effort close."""
        self.close_calls += 1
        if self.close_error is not None:
            raise self.close_error


def _service_with_stub(
        close_error: BaseException | None = None,
) -> tuple[CompanionService, StubControlStore]:
    """Build a service around the typed test double."""
    store = StubControlStore(close_error=close_error)
    return CompanionService(cast('ControlStore', store)), store


def test_startup_unavailable_is_fail_closed() -> None:
    """A missing startup store never advertises or runs an operation."""
    service = CompanionService(control_store=None)
    operation_called = False
    cleanup_calls = 0

    def operation(_store: ControlStore) -> None:
        nonlocal operation_called
        operation_called = True

    def cleanup() -> None:
        nonlocal cleanup_calls
        cleanup_calls += 1

    assert service.health_snapshot().available is False
    assert service.health_snapshot().auth_ready is False
    service.profile_opened(PROFILE_ID_A)
    assert service.health_snapshot().profile_id is None
    assert not service.capabilities()
    assert service.mark_auth_ready() is False
    with pytest.raises(ControlStoreUnavailable):
        service.with_control_store(operation)
    service.register_disposable_cleanup(cleanup)
    service.shutdown()

    assert operation_called is False
    assert cleanup_calls == 1


def test_capability_requires_healthy_store_and_auth_readiness() -> None:
    """Auth readiness is explicit, one-way, and health-gated."""
    service, store = _service_with_stub()

    assert service.health_snapshot().available is True
    assert service.health_snapshot().auth_ready is False
    assert not service.capabilities()
    assert service.mark_auth_ready() is True
    assert service.mark_auth_ready() is True
    assert service.health_snapshot().auth_ready is True
    assert service.capabilities() == {'device_sessions': 1}

    mutated = service.capabilities()
    mutated['unimplemented'] = 99
    assert service.capabilities() == {'device_sessions': 1}

    service.shutdown()
    assert store.close_calls == 1


def test_profile_snapshot_hooks_are_validated_and_mismatch_safe() -> None:
    """A stale close hook cannot clear a newer open Profile."""
    service, _store = _service_with_stub()

    service.profile_opened(PROFILE_ID_A)
    first = service.health_snapshot()
    assert first.profile_id == PROFILE_ID_A
    assert '11' * 32 not in repr(first)

    assert service.profile_closing(expected_profile_id=PROFILE_ID_B) is False
    assert service.health_snapshot().profile_id == PROFILE_ID_A
    assert service.profile_closing(expected_profile_id=PROFILE_ID_A) is True
    assert service.health_snapshot().profile_id is None
    assert service.profile_closing() is False

    service.profile_opened(PROFILE_ID_B)
    assert service.profile_closing() is True
    with pytest.raises(InvalidControlStoreInput):
        service.profile_opened(ProfileID(b'\x00' * 31))
    with pytest.raises(InvalidControlStoreInput):
        service.profile_closing(ProfileID(b'\x00' * 31))


def test_invalid_input_does_not_poison_service() -> None:
    """Caller validation failures do not trip the storage circuit breaker."""
    service, store = _service_with_stub()
    cleanup_calls = 0

    def cleanup() -> None:
        nonlocal cleanup_calls
        cleanup_calls += 1

    def invalid_operation(_store: ControlStore) -> None:
        raise InvalidControlStoreInput

    service.register_disposable_cleanup(cleanup)
    with pytest.raises(InvalidControlStoreInput):
        service.with_control_store(invalid_operation)

    assert service.health_snapshot().available is True
    assert service.with_control_store(lambda _store: 'still healthy') == 'still healthy'
    assert cleanup_calls == 0
    assert store.close_calls == 0


def test_poison_runs_each_cleanup_and_best_effort_close_once() -> None:
    """Store failure clears disposable state despite arbitrary cleanup failures."""
    service, store = _service_with_stub(close_error=RuntimeError('close failed'))
    cleanup_calls: list[str] = []

    def failing_cleanup() -> None:
        cleanup_calls.append('failing')
        raise RuntimeError('cleanup failed')

    def successful_cleanup() -> None:
        cleanup_calls.append('successful')

    service.register_disposable_cleanup(failing_cleanup)
    service.register_disposable_cleanup(successful_cleanup)
    service.register_disposable_cleanup(successful_cleanup)
    service.profile_opened(PROFILE_ID_A)
    assert service.mark_auth_ready() is True

    with pytest.raises(ControlStoreUnavailable):
        service.with_control_store(
            lambda _store: (_ for _ in ()).throw(ControlStoreUnavailable()),
        )

    health = service.health_snapshot()
    assert health.available is False
    assert health.auth_ready is False
    assert health.profile_id is None
    assert not service.capabilities()
    assert cleanup_calls == ['failing', 'successful']
    assert store.close_calls == 1

    with pytest.raises(ControlStoreUnavailable):
        service.with_control_store(lambda _store: None)
    service.shutdown()
    assert cleanup_calls == ['failing', 'successful']
    assert store.close_calls == 1


def test_concurrent_store_failure_poison_has_one_winner() -> None:
    """Concurrent failing calls execute only one operation and disposal."""
    service, store = _service_with_stub()
    worker_count = 8
    start = Barrier(worker_count)
    counter_lock = Lock()
    operation_calls = 0
    cleanup_calls = 0

    def cleanup() -> None:
        nonlocal cleanup_calls
        with counter_lock:
            cleanup_calls += 1

    def failing_operation(_store: ControlStore) -> None:
        nonlocal operation_calls
        with counter_lock:
            operation_calls += 1
        raise ControlStoreUnavailable

    def worker() -> type[ControlStoreUnavailable] | None:
        start.wait()
        try:
            service.with_control_store(failing_operation)
        except ControlStoreUnavailable:
            return ControlStoreUnavailable
        return None

    service.register_disposable_cleanup(cleanup)
    with ThreadPoolExecutor(max_workers=worker_count) as executor:
        outcomes = list(executor.map(lambda _index: worker(), range(worker_count)))

    assert outcomes == [ControlStoreUnavailable] * worker_count
    assert operation_calls == 1
    assert cleanup_calls == 1
    assert store.close_calls == 1


def test_shutdown_clears_profile_and_authority_once() -> None:
    """Shutdown clears all state and is idempotent even if close is interrupted."""
    service, store = _service_with_stub(close_error=KeyboardInterrupt())
    cleanup_calls = 0

    def cleanup() -> None:
        nonlocal cleanup_calls
        cleanup_calls += 1

    service.profile_opened(PROFILE_ID_A)
    service.register_disposable_cleanup(cleanup)
    assert service.mark_auth_ready() is True
    service.shutdown()
    service.shutdown()

    assert service.health_snapshot().available is False
    assert service.health_snapshot().auth_ready is False
    assert service.health_snapshot().profile_id is None
    assert not service.capabilities()
    assert service.mark_auth_ready() is False
    assert cleanup_calls == 1
    assert store.close_calls == 1


def test_shutdown_waits_for_concurrent_poison_disposal() -> None:
    """Shutdown is a barrier for cleanup even when another request won poisoning."""
    service, store = _service_with_stub()
    cleanup_started = Event()
    release_cleanup = Event()
    poison_finished = Event()
    shutdown_finished = Event()

    def cleanup() -> None:
        cleanup_started.set()
        assert release_cleanup.wait(timeout=5)

    def poison() -> None:
        with pytest.raises(ControlStoreUnavailable):
            service.with_control_store(
                lambda _store: (_ for _ in ()).throw(ControlStoreUnavailable()),
            )
        poison_finished.set()

    service.register_disposable_cleanup(cleanup)
    poison_thread = Thread(target=poison)
    poison_thread.start()
    assert cleanup_started.wait(timeout=5)

    def shutdown() -> None:
        service.shutdown()
        shutdown_finished.set()

    shutdown_thread = Thread(target=shutdown)
    shutdown_thread.start()
    assert shutdown_finished.wait(timeout=0.05) is False
    assert store.close_calls == 0

    release_cleanup.set()
    poison_thread.join(timeout=5)
    shutdown_thread.join(timeout=5)
    assert poison_finished.is_set()
    assert shutdown_finished.is_set()
    assert store.close_calls == 1


def test_cleanup_can_reenter_shutdown_without_deadlock() -> None:
    """The disposal owner may reenter shutdown while executing its own cleanup."""
    service, store = _service_with_stub()
    cleanup_finished = Event()

    def cleanup() -> None:
        service.shutdown()
        cleanup_finished.set()

    service.register_disposable_cleanup(cleanup)
    shutdown_thread = Thread(target=service.shutdown, daemon=True)
    shutdown_thread.start()
    shutdown_thread.join(timeout=5)

    assert shutdown_thread.is_alive() is False
    assert cleanup_finished.is_set()
    assert store.close_calls == 1


def test_late_cleanup_registration_runs_immediately_once() -> None:
    """Disposable state cannot be installed after authority is unavailable."""
    service = CompanionService(control_store=None)
    cleanup_calls = 0

    def cleanup() -> None:
        nonlocal cleanup_calls
        cleanup_calls += 1

    service.register_disposable_cleanup(cleanup)
    assert cleanup_calls == 1
