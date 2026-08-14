package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rotki.mobile.auth.protocol.AccessSessionResultDto
import org.rotki.mobile.auth.protocol.ChallengeResultDto
import org.rotki.mobile.auth.protocol.DeviceSessionResultDto
import org.rotki.mobile.auth.protocol.RevokedResultDto
import org.rotki.mobile.core.protocol.StrictJsonBooleanSerializer

@Serializable
internal class CompanionErrorDto(
    @SerialName("code")
    internal val code: String,
    @SerialName("retryable")
    internal val retryable:
        @Serializable(with = StrictJsonBooleanSerializer::class)
        Boolean,
    @SerialName("action")
    internal val action: String,
)

@Serializable
internal class ProtocolDiscoveryEnvelopeDto(
    @SerialName("result")
    internal val result: ProtocolDiscoveryResultDto,
    @SerialName("message")
    internal val message: String,
)

@Serializable
internal class DeviceSessionEnvelopeDto(
    @SerialName("result")
    internal val result: DeviceSessionResultDto,
    @SerialName("message")
    internal val message: String,
)

@Serializable
internal class RevokedEnvelopeDto(
    @SerialName("result")
    internal val result: RevokedResultDto,
    @SerialName("message")
    internal val message: String,
)

@Serializable
internal class ChallengeEnvelopeDto(
    @SerialName("result")
    internal val result: ChallengeResultDto,
    @SerialName("message")
    internal val message: String,
)

@Serializable
internal class AccessSessionEnvelopeDto(
    @SerialName("result")
    internal val result: AccessSessionResultDto,
    @SerialName("message")
    internal val message: String,
)

@Serializable
internal class CompanionFailureEnvelopeDto(
    @SerialName("result")
    internal val result: Nothing?,
    @SerialName("message")
    internal val message: String,
    @SerialName("error")
    internal val error: CompanionErrorDto,
)
