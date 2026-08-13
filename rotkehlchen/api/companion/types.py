import unicodedata
from dataclasses import dataclass
from enum import StrEnum
from typing import Final, NewType

from cryptography.hazmat.primitives.asymmetric import ec

from rotkehlchen.db.profile import PROFILE_ID_LENGTH, ProfileID

DEVICE_SESSION_ID_LENGTH: Final = 32
DEVICE_LABEL_MAX_SCALARS: Final = 64
DEVICE_LABEL_MAX_UTF8_BYTES: Final = 256
P256_UNCOMPRESSED_PUBLIC_KEY_LENGTH: Final = 65
DEVICE_PUBLIC_KEY_ALGORITHM: Final = 'ecdsa-p256-sha256-p1363'
SQLITE_MAX_INTEGER: Final = 2**63 - 1

DeviceSessionID = NewType('DeviceSessionID', bytes)


class ControlStoreUnavailable(RuntimeError):
    """The durable Companion authority cannot be opened or trusted."""

    def __init__(self) -> None:
        super().__init__('Companion Control Store is unavailable')


class InvalidControlStoreInput(ValueError):
    """A value cannot be represented by the Control Store contract."""

    def __init__(self) -> None:
        super().__init__('Invalid Companion Control Store input')


class DevicePlatform(StrEnum):
    ANDROID = 'android'
    IOS = 'ios'


class DeviceSessionState(StrEnum):
    AUTHORIZED = 'authorized'
    REVOKED = 'revoked'


def validate_device_session_id(value: object) -> DeviceSessionID:
    """Return an exact 32-byte Device Session ID without accepting bytes-like coercions."""
    if type(value) is not bytes or len(value) != DEVICE_SESSION_ID_LENGTH:
        raise InvalidControlStoreInput
    return DeviceSessionID(value)


def validate_profile_id(value: object) -> ProfileID:
    """Return an exact 32-byte Profile ID without accepting bytes-like coercions."""
    if type(value) is not bytes or len(value) != PROFILE_ID_LENGTH:
        raise InvalidControlStoreInput
    return ProfileID(value)


def validate_device_label(value: object) -> str:
    """Validate the byte-preserving version-1 Device Label contract."""
    if type(value) is not str or value != value.strip():
        raise InvalidControlStoreInput

    scalar_count = len(value)
    if scalar_count < 1 or scalar_count > DEVICE_LABEL_MAX_SCALARS:
        raise InvalidControlStoreInput
    if any(unicodedata.category(character) in {'Cc', 'Cf', 'Zl', 'Zp'} for character in value):
        raise InvalidControlStoreInput

    try:
        encoded_value = value.encode('utf-8', errors='strict')
    except UnicodeEncodeError:
        raise InvalidControlStoreInput from None
    if len(encoded_value) > DEVICE_LABEL_MAX_UTF8_BYTES:
        raise InvalidControlStoreInput
    return value


def validate_platform(value: object) -> DevicePlatform:
    """Validate and deserialize the closed version-1 platform enum."""
    if type(value) is not str:
        raise InvalidControlStoreInput
    try:
        return DevicePlatform(value)
    except ValueError:
        raise InvalidControlStoreInput from None


def validate_public_key_algorithm(value: object) -> str:
    """Validate the sole version-1 Device Key algorithm identifier."""
    if type(value) is not str or value != DEVICE_PUBLIC_KEY_ALGORITHM:
        raise InvalidControlStoreInput
    return value


def validate_public_key(value: object) -> bytes:
    """Validate an uncompressed X9.63 point on P-256, not just its wire shape."""
    if (
            type(value) is not bytes or
            len(value) != P256_UNCOMPRESSED_PUBLIC_KEY_LENGTH or
            value[0] != 0x04
    ):
        raise InvalidControlStoreInput
    try:
        ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), value)
    except ValueError:
        raise InvalidControlStoreInput from None
    return value


def validate_timestamp(value: object) -> int:
    """Validate a non-negative integer Unix epoch second (with booleans excluded)."""
    if type(value) is not int or value < 0 or value > SQLITE_MAX_INTEGER:
        raise InvalidControlStoreInput
    return value


def validate_optional_timestamp(value: object) -> int | None:
    if value is None:
        return None
    return validate_timestamp(value)


@dataclass(frozen=True, slots=True, repr=False)
class DeviceSessionRecord:
    """Public audit representation; never contains a Profile ID or public key."""

    device_session_id: DeviceSessionID
    device_label: str
    platform: DevicePlatform
    state: DeviceSessionState
    paired_at: int
    last_seen_at: int | None
    revoked_at: int | None

    def __post_init__(self) -> None:
        object.__setattr__(
            self,
            'device_session_id',
            validate_device_session_id(self.device_session_id),
        )
        object.__setattr__(self, 'device_label', validate_device_label(self.device_label))
        if type(self.platform) is not DevicePlatform or type(self.state) is not DeviceSessionState:
            raise InvalidControlStoreInput

        paired_at = validate_timestamp(self.paired_at)
        last_seen_at = validate_optional_timestamp(self.last_seen_at)
        revoked_at = validate_optional_timestamp(self.revoked_at)
        if (
                (last_seen_at is not None and last_seen_at < paired_at) or
                (revoked_at is not None and revoked_at < paired_at) or
                (last_seen_at is not None and
                 revoked_at is not None and
                 revoked_at < last_seen_at) or
                (self.state is DeviceSessionState.AUTHORIZED and revoked_at is not None) or
                (self.state is DeviceSessionState.REVOKED and revoked_at is None)
        ):
            raise InvalidControlStoreInput

    def __repr__(self) -> str:
        return '<DeviceSessionRecord redacted>'


@dataclass(frozen=True, slots=True, repr=False)
class AuthorizedDeviceBinding:
    """Proof-verification material for an authorized Device Session."""

    device_session_id: DeviceSessionID
    profile_id: ProfileID
    public_key_algorithm: str
    public_key: bytes

    def __post_init__(self) -> None:
        object.__setattr__(
            self,
            'device_session_id',
            validate_device_session_id(self.device_session_id),
        )
        object.__setattr__(self, 'profile_id', validate_profile_id(self.profile_id))
        object.__setattr__(
            self,
            'public_key_algorithm',
            validate_public_key_algorithm(self.public_key_algorithm),
        )
        object.__setattr__(self, 'public_key', validate_public_key(self.public_key))

    def __repr__(self) -> str:
        return '<AuthorizedDeviceBinding redacted>'
