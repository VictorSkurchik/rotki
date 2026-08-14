package org.rotki.mobile.auth.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("org.rotki.mobile.core.protocol.dto.DeviceSessionEnvelopeDto")
internal class DeviceSessionEnvelopeDto(
    @SerialName("result")
    internal val result: DeviceSessionResultDto,
    @SerialName("message")
    internal val message: String,
)
