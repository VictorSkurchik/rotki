package org.rotki.mobile.auth.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rotki.mobile.core.protocol.StrictJsonBooleanSerializer

@Serializable
internal class RenameCurrentDeviceSessionRequestDto(
    @SerialName("device_label")
    internal val deviceLabel: String,
)

@Serializable
internal class RevokedResultDto(
    @SerialName("revoked")
    internal val revoked:
        @Serializable(with = StrictJsonBooleanSerializer::class)
        Boolean,
)

/** Cross-platform scalar-aware validator for native device-label providers. */
public object DeviceLabelValidator {
    public fun isValid(candidate: String): Boolean = parsePairingDeviceLabel(candidate) != null
}
