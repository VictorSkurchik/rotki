package org.rotki.mobile.auth.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("org.rotki.mobile.core.protocol.dto.ChallengeEnvelopeDto")
internal class ChallengeEnvelopeDto(
    @SerialName("result")
    internal val result: ChallengeResultDto,
    @SerialName("message")
    internal val message: String,
)

@Serializable
@SerialName("org.rotki.mobile.core.protocol.dto.AccessSessionEnvelopeDto")
internal class AccessSessionEnvelopeDto(
    @SerialName("result")
    internal val result: AccessSessionResultDto,
    @SerialName("message")
    internal val message: String,
)
