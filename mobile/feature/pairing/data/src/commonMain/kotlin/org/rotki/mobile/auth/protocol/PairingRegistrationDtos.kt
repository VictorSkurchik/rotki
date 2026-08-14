@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.auth.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.PairingId
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.StrictJsonLongSerializer
import org.rotki.mobile.core.protocol.X963PublicKey
import org.rotki.mobile.core.protocol.generated.CompanionPlatform
import org.rotki.mobile.core.protocol.generated.DEVICE_PROOF_ALGORITHM
import org.rotki.mobile.core.protocol.generated.DeviceSessionState
import kotlin.native.HiddenFromObjC

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
        ): RegisterDeviceSessionRequestDto =
            RegisterDeviceSessionRequestDto(
                pairingId = pairingId.encoded,
                deviceLabel = deviceLabel.value,
                platform = platform.wireValue,
                publicKeyAlgorithm = DEVICE_PROOF_ALGORITHM,
                publicKey = publicKey.encoded,
            )
    }
}

@Serializable
internal class DeviceSessionResultDto(
    @SerialName("device_session")
    internal val deviceSession: DeviceSessionDto,
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
    internal val pairedAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long,
    @SerialName("last_seen_at")
    internal val lastSeenAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long?,
    @SerialName("revoked_at")
    internal val revokedAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long?,
)

internal class DeviceSession(
    internal val id: DeviceSessionId,
    internal val label: DeviceLabel,
    internal val platform: CompanionPlatform,
    internal val state: DeviceSessionState,
    internal val pairedAtEpochSeconds: Long,
    internal val lastSeenAtEpochSeconds: Long?,
    internal val revokedAtEpochSeconds: Long?,
) {
    override fun toString(): String = "DeviceSession(redacted)"
}

internal fun DeviceSession.matchesRegistration(
    requestedLabel: DeviceLabel,
    requestedPlatform: CompanionPlatform,
): Boolean =
    state == DeviceSessionState.Authorized &&
        label.value == requestedLabel.value &&
        platform == requestedPlatform &&
        lastSeenAtEpochSeconds == null &&
        revokedAtEpochSeconds == null

internal fun DeviceSessionDto.toDomainOrNull(): DeviceSession? {
    val parsedId = DeviceSessionId.parse(deviceSessionId)
    val parsedLabel = DeviceLabel.parse(deviceLabel)
    val parsedPlatform = CompanionPlatform.entries.firstOrNull { value -> value.wireValue == platform }
    val parsedState = DeviceSessionState.entries.firstOrNull { value -> value.wireValue == state }
    if (parsedId !is ProtocolValueParseOutcome.Accepted ||
        parsedLabel == null ||
        parsedPlatform == null ||
        parsedState == null ||
        pairedAt < 0 ||
        lastSeenAt?.let { value -> value < pairedAt } == true ||
        revokedAt?.let { value -> value < pairedAt } == true ||
        (parsedState == DeviceSessionState.Authorized && revokedAt != null) ||
        (parsedState == DeviceSessionState.Revoked && revokedAt == null)
    ) {
        return null
    }
    return DeviceSession(
        id = parsedId.value,
        label = parsedLabel,
        platform = parsedPlatform,
        state = parsedState,
        pairedAtEpochSeconds = pairedAt,
        lastSeenAtEpochSeconds = lastSeenAt,
        revokedAtEpochSeconds = revokedAt,
    )
}

@HiddenFromObjC
public class DeviceLabel internal constructor(
    internal val value: String,
) {
    public override fun toString(): String = "DeviceLabel(redacted)"

    internal companion object {
        internal fun parse(candidate: String): DeviceLabel? {
            val scalarCount = candidate.unicodeScalarCountOrNull() ?: return null
            val isInvalid =
                scalarCount !in 1..64 ||
                    candidate.encodeToByteArray().size > 256 ||
                    candidate != candidate.trim() ||
                    candidate.hasForbiddenLabelScalar()
            return if (isInvalid) null else DeviceLabel(candidate)
        }
    }
}

@HiddenFromObjC
public fun parsePairingDeviceLabel(candidate: String): DeviceLabel? = DeviceLabel.parse(candidate)

private fun String.unicodeScalarCountOrNull(): Int? {
    var count = 0
    var index = 0
    while (index < length) {
        val first = this[index]
        index +=
            when {
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
        val codePoint =
            if (first.isHighSurrogate()) {
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

private fun Int.isForbiddenLabelCodePoint(): Boolean =
    when (this) {
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
