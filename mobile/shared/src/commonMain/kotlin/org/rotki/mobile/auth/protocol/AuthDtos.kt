package org.rotki.mobile.auth.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rotki.mobile.core.protocol.AccessSessionCredential
import org.rotki.mobile.core.protocol.ChallengeId
import org.rotki.mobile.core.protocol.ChallengeNonce
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.PairingId
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.StrictJsonBooleanSerializer
import org.rotki.mobile.core.protocol.StrictJsonLongSerializer
import org.rotki.mobile.core.protocol.X963PublicKey
import org.rotki.mobile.core.protocol.generated.CompanionPlatform
import org.rotki.mobile.core.protocol.generated.DEVICE_PROOF_ALGORITHM
import org.rotki.mobile.core.protocol.generated.DeviceSessionState

@Serializable
internal class RegisterDeviceSessionRequestDto(
    @SerialName("pairing_id")
    internal val pairingId: String,
    @SerialName("device_label")
    internal val deviceLabel: String,
    @SerialName("platform")
    internal val platform: String,
    @SerialName("public_key_algorithm")
    internal val publicKeyAlgorithm: String,
    @SerialName("public_key")
    internal val publicKey: String,
) {
    internal companion object {
        internal fun create(
            pairingId: PairingId,
            deviceLabel: DeviceLabel,
            platform: CompanionPlatform,
            publicKey: X963PublicKey,
        ): RegisterDeviceSessionRequestDto = RegisterDeviceSessionRequestDto(
            pairingId = pairingId.encoded,
            deviceLabel = deviceLabel.value,
            platform = platform.wireValue,
            publicKeyAlgorithm = DEVICE_PROOF_ALGORITHM,
            publicKey = publicKey.encoded,
        )
    }
}

@Serializable
internal class RenameCurrentDeviceSessionRequestDto(
    @SerialName("device_label")
    internal val deviceLabel: String,
)

@Serializable
internal class DeviceSessionResultDto(
    @SerialName("device_session")
    internal val deviceSession: DeviceSessionDto,
)

@Serializable
internal class RevokedResultDto(
    @SerialName("revoked")
    internal val revoked: @Serializable(with = StrictJsonBooleanSerializer::class) Boolean,
)

@Serializable
internal class DeviceSessionDto(
    @SerialName("device_session_id")
    internal val deviceSessionId: String,
    @SerialName("device_label")
    internal val deviceLabel: String,
    @SerialName("platform")
    internal val platform: String,
    @SerialName("state")
    internal val state: String,
    @SerialName("paired_at")
    internal val pairedAt: @Serializable(with = StrictJsonLongSerializer::class) Long,
    @SerialName("last_seen_at")
    internal val lastSeenAt: @Serializable(with = StrictJsonLongSerializer::class) Long?,
    @SerialName("revoked_at")
    internal val revokedAt: @Serializable(with = StrictJsonLongSerializer::class) Long?,
)

internal class DeviceSession(
    internal val id: DeviceSessionId,
    internal val label: DeviceLabel,
    internal val platform: CompanionPlatform,
    internal val state: DeviceSessionState,
    internal val pairedAtEpochSeconds: Long,
    internal val lastSeenAtEpochSeconds: Long?,
    internal val revokedAtEpochSeconds: Long?,
)

internal fun DeviceSession.matchesRegistration(
    requestedLabel: DeviceLabel,
    requestedPlatform: CompanionPlatform,
): Boolean =
    state == DeviceSessionState.Authorized &&
        label.value == requestedLabel.value &&
        platform == requestedPlatform &&
        lastSeenAtEpochSeconds == null &&
        revokedAtEpochSeconds == null

internal fun DeviceSessionDto.toDomain(): AuthContractOutcome<DeviceSession> {
    val parsedId = DeviceSessionId.parse(deviceSessionId)
    val parsedLabel = DeviceLabel.parse(deviceLabel)
    val parsedPlatform = CompanionPlatform.entries.firstOrNull { value -> value.wireValue == platform }
    val parsedState = DeviceSessionState.entries.firstOrNull { value -> value.wireValue == state }
    if (parsedId !is ProtocolValueParseOutcome.Accepted ||
        parsedLabel !is DeviceLabelParseOutcome.Accepted ||
        parsedPlatform == null ||
        parsedState == null ||
        pairedAt < 0 ||
        lastSeenAt?.let { value -> value < pairedAt } == true ||
        revokedAt?.let { value -> value < pairedAt } == true ||
        (parsedState == DeviceSessionState.Authorized && revokedAt != null) ||
        (parsedState == DeviceSessionState.Revoked && revokedAt == null)
    ) {
        return AuthContractOutcome.ContractFailure
    }
    return AuthContractOutcome.Accepted(
        DeviceSession(
            id = parsedId.value,
            label = parsedLabel.value,
            platform = parsedPlatform,
            state = parsedState,
            pairedAtEpochSeconds = pairedAt,
            lastSeenAtEpochSeconds = lastSeenAt,
            revokedAtEpochSeconds = revokedAt,
        ),
    )
}

@Serializable
internal class ChallengeRequestDto(
    @SerialName("device_session_id")
    internal val deviceSessionId: String,
) {
    internal companion object {
        internal fun create(deviceSessionId: DeviceSessionId): ChallengeRequestDto =
            ChallengeRequestDto(deviceSessionId.encoded)
    }
}

@Serializable
internal class ChallengeResultDto(
    @SerialName("challenge_id")
    internal val challengeId: String,
    @SerialName("nonce")
    internal val nonce: String,
    @SerialName("expires_at")
    internal val expiresAt: @Serializable(with = StrictJsonLongSerializer::class) Long,
)

internal class AuthorizationChallenge(
    internal val id: ChallengeId,
    internal val nonce: ChallengeNonce,
    internal val expiresAtEpochSeconds: Long,
)

internal fun ChallengeResultDto.toDomain(): AuthContractOutcome<AuthorizationChallenge> {
    val parsedId = ChallengeId.parse(challengeId)
    val parsedNonce = ChallengeNonce.parse(nonce)
    if (parsedId !is ProtocolValueParseOutcome.Accepted ||
        parsedNonce !is ProtocolValueParseOutcome.Accepted ||
        expiresAt < 0
    ) {
        return AuthContractOutcome.ContractFailure
    }
    return AuthContractOutcome.Accepted(
        AuthorizationChallenge(parsedId.value, parsedNonce.value, expiresAt),
    )
}

@Serializable
internal class AccessSessionRequestDto(
    @SerialName("device_session_id")
    internal val deviceSessionId: String,
    @SerialName("challenge_id")
    internal val challengeId: String,
    @SerialName("signature")
    internal val signature: String,
) {
    internal companion object {
        internal fun create(
            deviceSessionId: DeviceSessionId,
            challengeId: ChallengeId,
            signature: P1363Signature,
        ): AccessSessionRequestDto = AccessSessionRequestDto(
            deviceSessionId = deviceSessionId.encoded,
            challengeId = challengeId.encoded,
            signature = signature.encoded,
        )
    }
}

@Serializable
internal class AccessSessionResultDto(
    @SerialName("access_session_credential")
    internal val accessSessionCredential: String,
    @SerialName("expires_at")
    internal val expiresAt: @Serializable(with = StrictJsonLongSerializer::class) Long,
)

internal class AccessSession(
    internal val credential: AccessSessionCredential,
    internal val expiresAtEpochSeconds: Long,
)

internal fun AccessSessionResultDto.toDomain(): AuthContractOutcome<AccessSession> {
    val parsedCredential = AccessSessionCredential.parse(accessSessionCredential)
    if (parsedCredential !is ProtocolValueParseOutcome.Accepted || expiresAt < 0) {
        return AuthContractOutcome.ContractFailure
    }
    return AuthContractOutcome.Accepted(AccessSession(parsedCredential.value, expiresAt))
}

internal sealed interface AuthContractOutcome<out T> {
    data class Accepted<T>(internal val value: T) : AuthContractOutcome<T>

    data object ContractFailure : AuthContractOutcome<Nothing>
}

internal class DeviceLabel private constructor(internal val value: String) {
    internal companion object {
        internal fun parse(candidate: String): DeviceLabelParseOutcome {
            val scalarCount = candidate.unicodeScalarCountOrNull()
                ?: return DeviceLabelParseOutcome.Rejected
            val isInvalid = scalarCount !in 1..64 ||
                candidate.encodeToByteArray().size > 256 ||
                candidate != candidate.trim() ||
                candidate.hasForbiddenLabelScalar()
            return if (isInvalid) {
                DeviceLabelParseOutcome.Rejected
            } else {
                DeviceLabelParseOutcome.Accepted(DeviceLabel(candidate))
            }
        }
    }
}

/** Cross-platform scalar-aware validator for native device-label providers. */
public object DeviceLabelValidator {
    public fun isValid(candidate: String): Boolean =
        DeviceLabel.parse(candidate) is DeviceLabelParseOutcome.Accepted
}

internal sealed interface DeviceLabelParseOutcome {
    data class Accepted(internal val value: DeviceLabel) : DeviceLabelParseOutcome

    data object Rejected : DeviceLabelParseOutcome
}

private fun String.unicodeScalarCountOrNull(): Int? {
    var count = 0
    var index = 0
    while (index < length) {
        val first = this[index]
        index += when {
            first.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> 2
            first.isHighSurrogate() || first.isLowSurrogate() -> return null
            else -> 1
        }
        count += 1
    }
    return count
}

private fun String.hasForbiddenLabelScalar(): Boolean {
    var index = 0
    while (index < length) {
        val first = this[index]
        val codePoint = if (first.isHighSurrogate()) {
            val second = this[index + 1]
            index += 2
            SUPPLEMENTARY_PLANE_OFFSET +
                ((first.code - HIGH_SURROGATE_START) shl SURROGATE_SHIFT) +
                (second.code - LOW_SURROGATE_START)
        } else {
            index += 1
            first.code
        }
        if (codePoint.isForbiddenLabelCodePoint()) return true
    }
    return false
}

private fun Int.isForbiddenLabelCodePoint(): Boolean = when (this) {
    in 0x0000..0x001F,
    in 0x007F..0x009F,
    0x00AD,
    in 0x0600..0x0605,
    0x061C,
    0x06DD,
    0x070F,
    in 0x0890..0x0891,
    0x08E2,
    0x180E,
    in 0x200B..0x200F,
    in 0x2028..0x202E,
    in 0x2060..0x2064,
    in 0x2066..0x206F,
    0xFEFF,
    in 0xFFF9..0xFFFB,
    0x110BD,
    0x110CD,
    in 0x13430..0x1343F,
    in 0x1BCA0..0x1BCAF,
    in 0x1D173..0x1D17A,
    0xE0001,
    in 0xE0020..0xE007F,
    -> true
    else -> false
}

private const val HIGH_SURROGATE_START: Int = 0xD800
private const val LOW_SURROGATE_START: Int = 0xDC00
private const val SURROGATE_SHIFT: Int = 10
private const val SUPPLEMENTARY_PLANE_OFFSET: Int = 0x10000
