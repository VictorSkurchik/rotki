@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.rotki.mobile.core.protocol.StrictJsonBooleanSerializer
import kotlin.native.HiddenFromObjC

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
@HiddenFromObjC
public class ProtocolDiscoveryEnvelopeDto internal constructor(
    @SerialName("result")
    internal val result: ProtocolDiscoveryResultDto,
    @SerialName("message")
    internal val message: String,
)

@Serializable
@HiddenFromObjC
public class CompanionFailureEnvelopeDto internal constructor(
    @SerialName("result")
    internal val result: Nothing?,
    @SerialName("message")
    internal val message: String,
    @SerialName("error")
    internal val error: CompanionErrorDto,
)
