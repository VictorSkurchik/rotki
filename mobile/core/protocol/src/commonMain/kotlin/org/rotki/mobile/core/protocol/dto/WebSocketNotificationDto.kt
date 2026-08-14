@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.core.protocol.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.rotki.mobile.core.protocol.CompanionJsonCodec
import org.rotki.mobile.core.protocol.StrictJsonBooleanSerializer
import org.rotki.mobile.core.protocol.StrictJsonIntSerializer
import org.rotki.mobile.core.protocol.StrictJsonLongSerializer
import org.rotki.mobile.core.protocol.generated.OperationErrorCode
import org.rotki.mobile.core.protocol.generated.ProtocolClientInputLimits
import org.rotki.mobile.core.protocol.generated.ProtocolEncodedLengths
import org.rotki.mobile.core.protocol.generated.ProtocolErrorAction
import org.rotki.mobile.core.protocol.generated.SourceErrorCode
import org.rotki.mobile.core.protocol.generated.WebSocketEventType
import org.rotki.mobile.core.protocol.hasDuplicateJsonMember
import org.rotki.mobile.core.protocol.hasValidJsonSyntax
import org.rotki.mobile.core.protocol.isCanonicalFixedBase64Url
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public object WebSocketNotificationDecoder {
    public fun decode(text: String): WebSocketNotificationDecodeOutcome {
        if (text.encodeToByteArray().size > ProtocolClientInputLimits.MaximumWebSocketEventBytes) {
            return WebSocketNotificationDecodeOutcome.ContractFailure
        }
        if (hasDuplicateJsonMember(text)) {
            return WebSocketNotificationDecodeOutcome.ContractFailure
        }
        if (!hasValidJsonSyntax(text)) {
            return WebSocketNotificationDecodeOutcome.ContractFailure
        }
        val envelope =
            try {
                CompanionJsonCodec.parseToJsonElement(text) as? JsonObject
                    ?: return WebSocketNotificationDecodeOutcome.ContractFailure
            } catch (_: SerializationException) {
                return WebSocketNotificationDecodeOutcome.ContractFailure
            } catch (_: IllegalArgumentException) {
                return WebSocketNotificationDecodeOutcome.ContractFailure
            }
        val type =
            envelope["type"] as? JsonPrimitive
                ?: return WebSocketNotificationDecodeOutcome.ContractFailure
        if (!type.isString) return WebSocketNotificationDecodeOutcome.ContractFailure
        return when (type.content) {
            WebSocketEventType.CompanionSnapshotRevision.wireValue -> {
                decodeSnapshot(envelope["data"] as? JsonObject)
            }

            WebSocketEventType.CompanionRefreshOperation.wireValue -> {
                decodeRefresh(envelope["data"] as? JsonObject)
            }

            else -> {
                WebSocketNotificationDecodeOutcome.IgnoredUnknownEvent
            }
        }
    }

    private fun decodeSnapshot(data: JsonObject?): WebSocketNotificationDecodeOutcome {
        if (data == null) return WebSocketNotificationDecodeOutcome.ContractFailure
        val dto =
            try {
                CompanionJsonCodec.decodeFromJsonElement(SnapshotRevisionDataDto.serializer(), data)
            } catch (_: SerializationException) {
                return WebSocketNotificationDecodeOutcome.ContractFailure
            }
        if (!isCanonicalFixedBase64Url(dto.revision, SNAPSHOT_REVISION_LENGTH, 32)) {
            return WebSocketNotificationDecodeOutcome.ContractFailure
        }
        return WebSocketNotificationDecodeOutcome.Decoded(
            CompanionNotification.SnapshotRevisionAvailable(dto.revision),
        )
    }

    private fun decodeRefresh(data: JsonObject?): WebSocketNotificationDecodeOutcome {
        if (data == null) return WebSocketNotificationDecodeOutcome.ContractFailure
        val dto =
            try {
                CompanionJsonCodec.decodeFromJsonElement(RefreshOperationDataDto.serializer(), data)
            } catch (_: SerializationException) {
                return WebSocketNotificationDecodeOutcome.ContractFailure
            }
        val operation = dto.toDomain() ?: return WebSocketNotificationDecodeOutcome.ContractFailure
        return WebSocketNotificationDecodeOutcome.Decoded(
            CompanionNotification.RefreshOperationChanged(operation),
        )
    }
}

@HiddenFromObjC
public sealed interface WebSocketNotificationDecodeOutcome {
    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class Decoded internal constructor(
        public val notification: CompanionNotification,
    ) : WebSocketNotificationDecodeOutcome {
        public override fun toString(): String = "Decoded(redacted)"
    }

    @HiddenFromObjC
    public data object IgnoredUnknownEvent : WebSocketNotificationDecodeOutcome

    @HiddenFromObjC
    public data object ContractFailure : WebSocketNotificationDecodeOutcome
}

@HiddenFromObjC
public sealed interface CompanionNotification {
    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class SnapshotRevisionAvailable internal constructor(
        public val revision: String,
    ) : CompanionNotification {
        public override fun toString(): String = "SnapshotRevisionAvailable(redacted)"
    }

    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class RefreshOperationChanged internal constructor(
        public val operation: RefreshOperationNotification,
    ) : CompanionNotification {
        public override fun toString(): String = "RefreshOperationChanged(redacted)"
    }
}

@HiddenFromObjC
@ConsistentCopyVisibility
public data class RefreshOperationNotification internal constructor(
    public val operationId: String,
    public val version: Int,
    public val createdAtEpochSeconds: Long,
    public val startedAtEpochSeconds: Long?,
    public val finishedAtEpochSeconds: Long?,
    public val target: RefreshTarget,
    public val state: RefreshOperationState,
    public val progress: RefreshProgress?,
    public val resultSnapshotRevision: String?,
    public val error: RefreshOperationError?,
) {
    public override fun toString(): String = "RefreshOperationNotification(redacted)"
}

@HiddenFromObjC
public sealed interface RefreshTarget {
    @HiddenFromObjC
    public data object Global : RefreshTarget

    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class Source internal constructor(
        public val sourceId: String,
    ) : RefreshTarget {
        public override fun toString(): String = "Source(redacted)"
    }
}

@HiddenFromObjC
public enum class RefreshOperationState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

@HiddenFromObjC
@ConsistentCopyVisibility
public data class RefreshProgress internal constructor(
    public val completed: Int,
    public val total: Int,
)

@HiddenFromObjC
public sealed interface RefreshOperationError {
    public val retryable: Boolean
    public val action: ProtocolErrorAction

    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class Operation internal constructor(
        public val code: OperationErrorCode,
        public override val retryable: Boolean,
        public override val action: ProtocolErrorAction,
    ) : RefreshOperationError

    @HiddenFromObjC
    @ConsistentCopyVisibility
    public data class Source internal constructor(
        public val code: SourceErrorCode,
        public override val retryable: Boolean,
        public override val action: ProtocolErrorAction,
    ) : RefreshOperationError

    @HiddenFromObjC
    public data object UnexpectedOperation : RefreshOperationError {
        public override val retryable: Boolean = false
        public override val action: ProtocolErrorAction = ProtocolErrorAction.None
    }
}

@Serializable
private class SnapshotRevisionDataDto(
    @SerialName("revision")
    val revision: String,
)

@Serializable
private class RefreshOperationDataDto(
    @SerialName("operation_id")
    val operationId: String,
    @SerialName("version")
    val version:
        @Serializable(with = StrictJsonIntSerializer::class)
        Int,
    @SerialName("created_at")
    val createdAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long,
    @SerialName("started_at")
    val startedAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long?,
    @SerialName("finished_at")
    val finishedAt:
        @Serializable(with = StrictJsonLongSerializer::class)
        Long?,
    @SerialName("target")
    val target: JsonObject,
    @SerialName("state")
    val state: String,
    @SerialName("progress")
    val progress: RefreshProgressDto?,
    @SerialName("result_snapshot_revision")
    val resultSnapshotRevision: String?,
    @SerialName("error")
    val error: RefreshOperationErrorDto?,
)

@Serializable
private class RefreshProgressDto(
    @SerialName("completed")
    val completed:
        @Serializable(with = StrictJsonIntSerializer::class)
        Int,
    @SerialName("total")
    val total:
        @Serializable(with = StrictJsonIntSerializer::class)
        Int,
)

@Serializable
private class RefreshOperationErrorDto(
    @SerialName("code")
    val code: String,
    @SerialName("retryable")
    val retryable:
        @Serializable(with = StrictJsonBooleanSerializer::class)
        Boolean,
    @SerialName("action")
    val action: String,
)

private fun RefreshOperationDataDto.toDomain(): RefreshOperationNotification? {
    if (!isCanonicalFixedBase64Url(operationId, ProtocolEncodedLengths.PairingId, 16) ||
        version < 1 ||
        createdAt < 0 ||
        startedAt?.let { value -> value < createdAt } == true ||
        finishedAt?.let { value -> value < (startedAt ?: createdAt) } == true ||
        resultSnapshotRevision?.let { value ->
            !isCanonicalFixedBase64Url(value, SNAPSHOT_REVISION_LENGTH, 32)
        } == true
    ) {
        return null
    }
    val domainTarget = target.toRefreshTarget() ?: return null
    val domainState =
        when (state) {
            "queued" -> RefreshOperationState.QUEUED
            "running" -> RefreshOperationState.RUNNING
            "succeeded" -> RefreshOperationState.SUCCEEDED
            "failed" -> RefreshOperationState.FAILED
            else -> return null
        }
    val domainProgress = progress?.toDomain() ?: if (progress == null) null else return null
    val domainError = error?.toDomain(domainTarget) ?: if (error == null) null else return null
    if ((domainTarget is RefreshTarget.Global && domainProgress == null) ||
        (domainTarget is RefreshTarget.Source && domainProgress != null)
    ) {
        return null
    }
    val stateIsValid =
        when (domainState) {
            RefreshOperationState.QUEUED -> {
                startedAt == null && finishedAt == null && domainError == null &&
                    resultSnapshotRevision == null &&
                    (
                        domainTarget is RefreshTarget.Source ||
                            domainProgress?.completed == 0
                    )
            }

            RefreshOperationState.RUNNING -> {
                startedAt != null && finishedAt == null && domainError == null &&
                    resultSnapshotRevision == null
            }

            RefreshOperationState.SUCCEEDED -> {
                startedAt != null && finishedAt != null && domainError == null &&
                    (domainProgress == null || domainProgress.completed == domainProgress.total)
            }

            RefreshOperationState.FAILED -> {
                finishedAt != null && domainError != null &&
                    failedOutcomeIsValid(
                        progress = domainProgress,
                        error = domainError,
                        resultSnapshotRevision = resultSnapshotRevision,
                    )
            }
        }
    if (!stateIsValid) {
        return null
    }
    return RefreshOperationNotification(
        operationId = operationId,
        version = version,
        createdAtEpochSeconds = createdAt,
        startedAtEpochSeconds = startedAt,
        finishedAtEpochSeconds = finishedAt,
        target = domainTarget,
        state = domainState,
        progress = domainProgress,
        resultSnapshotRevision = resultSnapshotRevision,
        error = domainError,
    )
}

private fun JsonObject.toRefreshTarget(): RefreshTarget? {
    val kind = this["kind"] as? JsonPrimitive ?: return null
    if (!kind.isString) return null
    return when (kind.content) {
        "global" -> {
            if ("source_id" !in this) RefreshTarget.Global else null
        }

        "source" -> {
            val sourceId = this["source_id"] as? JsonPrimitive ?: return null
            sourceId
                .takeIf { value ->
                    value.isString &&
                        isCanonicalFixedBase64Url(
                            value.content,
                            ProtocolEncodedLengths.PairingId,
                            16,
                        )
                }?.let { value -> RefreshTarget.Source(value.content) }
        }

        else -> {
            null
        }
    }
}

private fun RefreshProgressDto.toDomain(): RefreshProgress? =
    if (completed >= 0 && total > 0 && completed <= total) {
        RefreshProgress(completed, total)
    } else {
        null
    }

private fun RefreshOperationErrorDto.toDomain(target: RefreshTarget): RefreshOperationError? {
    val operationCode =
        OperationErrorCode.entries.firstOrNull { candidate ->
            candidate.wireValue == code
        }
    if (operationCode != null) {
        val knownAction =
            ProtocolErrorAction.entries.firstOrNull { candidate ->
                candidate.wireValue == action
            } ?: return null
        val targetIsValid =
            when (operationCode) {
                OperationErrorCode.OperationInterrupted -> true
                OperationErrorCode.SourceRefreshFailed -> target is RefreshTarget.Global
            }
        if (!targetIsValid ||
            !retryable ||
            knownAction != ProtocolErrorAction.Retry
        ) {
            return null
        }
        return RefreshOperationError.Operation(operationCode, retryable, knownAction)
    }
    if (target !is RefreshTarget.Source) {
        if (SourceErrorCode.entries.any { candidate -> candidate.wireValue == code }) {
            return null
        }
        return RefreshOperationError.UnexpectedOperation
    }
    val sourceCode =
        SourceErrorCode.entries.firstOrNull { candidate -> candidate.wireValue == code }
            ?: return RefreshOperationError.Source(
                SourceErrorCode.SourceUnexpectedError,
                retryable = false,
                action = ProtocolErrorAction.None,
            )
    val knownAction =
        ProtocolErrorAction.entries.firstOrNull { candidate ->
            candidate.wireValue == action
        } ?: return null
    val expected = SOURCE_ERROR_TUPLES[sourceCode] ?: return null
    if (retryable != expected.retryable || knownAction != expected.action) return null
    return RefreshOperationError.Source(sourceCode, retryable, knownAction)
}

private fun failedOutcomeIsValid(
    progress: RefreshProgress?,
    error: RefreshOperationError,
    resultSnapshotRevision: String?,
): Boolean =
    when (error) {
        is RefreshOperationError.Source -> {
            progress == null
        }

        RefreshOperationError.UnexpectedOperation -> {
            progress != null
        }

        is RefreshOperationError.Operation -> {
            when (error.code) {
                OperationErrorCode.OperationInterrupted -> {
                    true
                }

                OperationErrorCode.SourceRefreshFailed -> {
                    progress != null && progress.completed == progress.total &&
                        resultSnapshotRevision != null
                }
            }
        }
    }

private const val SNAPSHOT_REVISION_LENGTH: Int = 43

private data class ErrorTuple(
    val retryable: Boolean,
    val action: ProtocolErrorAction,
)

private val SOURCE_ERROR_TUPLES: Map<SourceErrorCode, ErrorTuple> =
    mapOf(
        SourceErrorCode.SourceUnreachable to ErrorTuple(true, ProtocolErrorAction.Retry),
        SourceErrorCode.SourceRateLimited to ErrorTuple(true, ProtocolErrorAction.Retry),
        SourceErrorCode.SourceAuthenticationFailed to
            ErrorTuple(false, ProtocolErrorAction.UseFullClient),
        SourceErrorCode.SourceConfigurationChanged to
            ErrorTuple(false, ProtocolErrorAction.UseFullClient),
        SourceErrorCode.SourceUnexpectedError to ErrorTuple(false, ProtocolErrorAction.None),
    )
