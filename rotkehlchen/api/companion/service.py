"""Lifecycle and fail-closed health boundary for Engine Companion services."""

from __future__ import annotations

import threading
from contextlib import suppress
from dataclasses import dataclass
from typing import TYPE_CHECKING

from rotkehlchen.api.companion.generated_protocol import CAPABILITIES, Capability
from rotkehlchen.api.companion.types import (
    ControlStoreUnavailable,
    validate_profile_id,
)

if TYPE_CHECKING:
    from collections.abc import Callable

    from rotkehlchen.api.companion.control_store import ControlStore
    from rotkehlchen.db.profile import ProfileID


@dataclass(frozen=True, slots=True, repr=False)
class CompanionHealth:
    """Immutable internal snapshot without a diagnostic Profile identifier leak."""

    available: bool
    auth_ready: bool
    profile_id: ProfileID | None

    def __repr__(self) -> str:
        return (
            '<CompanionHealth '
            f'available={self.available} '
            f'auth_ready={self.auth_ready} '
            f'profile_open={self.profile_id is not None}>'
        )


class CompanionService:
    """Thread-safe lifecycle boundary around durable Companion authority.

    A ``ControlStoreUnavailable`` raised by any operation permanently opens the
    circuit breaker. Disposable authentication state is cleared and the store is
    closed best-effort exactly once. Invalid caller input and unrelated defects
    are deliberately not translated into storage failure.
    """

    def __init__(self, control_store: ControlStore | None) -> None:
        self._lock = threading.RLock()
        self._control_store = control_store
        self._auth_ready = False
        self._current_profile_id: ProfileID | None = None
        self._disposable_cleanup_callbacks: list[Callable[[], object]] = []
        self._shutdown = False
        self._disposal_owner_ident: int | None = None
        self._disposal_complete = threading.Event()
        if control_store is None:
            self._disposal_complete.set()

    def health_snapshot(self) -> CompanionHealth:
        """Return one coherent point-in-time service state."""
        with self._lock:
            return CompanionHealth(
                available=self._control_store is not None and self._shutdown is False,
                auth_ready=self._auth_ready,
                profile_id=self._current_profile_id,
            )

    def capabilities(self) -> dict[str, int]:
        """Advertise only implemented authorization backed by healthy authority."""
        with self._lock:
            if (
                    self._control_store is None or
                    self._shutdown or
                    self._auth_ready is False
            ):
                return {}
            return {
                Capability.DEVICE_SESSIONS.value: CAPABILITIES[Capability.DEVICE_SESSIONS],
            }

    def mark_auth_ready(self) -> bool:
        """Enable the implemented auth capability once; failure cannot be reversed."""
        with self._lock:
            if self._control_store is None or self._shutdown:
                return False
            self._auth_ready = True
            return True

    def profile_opened(self, profile_id: ProfileID) -> None:
        """Publish the currently open Profile ID as one atomic snapshot update."""
        validated_profile_id = validate_profile_id(profile_id)
        with self._lock:
            if self._control_store is not None and self._shutdown is False:
                self._current_profile_id = validated_profile_id

    def profile_closing(self, expected_profile_id: ProfileID | None = None) -> bool:
        """Clear the open Profile only if the optional expected identity still matches."""
        validated_expected = (
            None if expected_profile_id is None else validate_profile_id(expected_profile_id)
        )
        with self._lock:
            if self._current_profile_id is None:
                return False
            if (
                    validated_expected is not None and
                    validated_expected != self._current_profile_id
            ):
                return False
            self._current_profile_id = None
            return True

    def register_disposable_cleanup(self, callback: Callable[[], object]) -> None:
        """Register one in-memory authority cleanup, or run it if already unavailable."""
        run_immediately = False
        with self._lock:
            if self._control_store is None or self._shutdown:
                run_immediately = True
            elif not any(
                registered is callback for registered in self._disposable_cleanup_callbacks
            ):
                self._disposable_cleanup_callbacks.append(callback)
        if run_immediately:
            with suppress(BaseException):
                callback()

    def with_control_store[T](self, operation: Callable[[ControlStore], T]) -> T:
        """Serialize a store operation and permanently fail closed on store unavailability."""
        disposal: tuple[ControlStore | None, tuple[Callable[[], object], ...]] | None = None
        with self._lock:
            if (control_store := self._control_store) is None or self._shutdown:
                raise ControlStoreUnavailable
            try:
                return operation(control_store)
            except ControlStoreUnavailable:
                disposal = self._poison_locked()

        assert disposal is not None
        self._complete_disposal(*disposal)
        raise ControlStoreUnavailable from None

    def _poison_locked(
            self,
    ) -> tuple[ControlStore | None, tuple[Callable[[], object], ...]]:
        """Detach all authority while holding the lifecycle lock."""
        control_store = self._control_store
        self._control_store = None
        self._auth_ready = False
        self._current_profile_id = None
        self._disposal_owner_ident = threading.get_ident()
        callbacks = tuple(self._disposable_cleanup_callbacks)
        self._disposable_cleanup_callbacks.clear()
        return control_store, callbacks

    @staticmethod
    def _dispose(
            control_store: ControlStore | None,
            callbacks: tuple[Callable[[], object], ...],
    ) -> None:
        for callback in callbacks:
            with suppress(BaseException):
                callback()
        if control_store is not None:
            with suppress(BaseException):
                control_store.close()

    def _complete_disposal(
            self,
            control_store: ControlStore | None,
            callbacks: tuple[Callable[[], object], ...],
    ) -> None:
        try:
            self._dispose(control_store, callbacks)
        finally:
            with self._lock:
                self._disposal_owner_ident = None
                self._disposal_complete.set()

    def shutdown(self) -> None:
        """Permanently stop Companion authority; repeated shutdown is harmless."""
        disposal: tuple[ControlStore | None, tuple[Callable[[], object], ...]] | None = None
        with self._lock:
            if self._shutdown is False:
                self._shutdown = True
                self._current_profile_id = None
                if self._control_store is not None:
                    disposal = self._poison_locked()
            wait_for_disposal = self._disposal_owner_ident != threading.get_ident()
        if disposal is not None:
            self._complete_disposal(*disposal)
        elif wait_for_disposal:
            self._disposal_complete.wait()


__all__ = ['CompanionHealth', 'CompanionService']
