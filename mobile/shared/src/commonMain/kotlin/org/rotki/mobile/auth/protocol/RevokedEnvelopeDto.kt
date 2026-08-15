package org.rotki.mobile.auth.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("org.rotki.mobile.core.protocol.dto.RevokedEnvelopeDto")
internal class RevokedEnvelopeDto(
    @SerialName("result")
    internal val result: RevokedResultDto,
    @SerialName("message")
    internal val message: String,
)
