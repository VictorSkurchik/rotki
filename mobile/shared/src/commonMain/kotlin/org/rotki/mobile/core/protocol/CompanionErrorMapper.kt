package org.rotki.mobile.core.protocol

import org.rotki.mobile.core.protocol.dto.CompanionErrorDto
import org.rotki.mobile.core.protocol.generated.HttpErrorCode
import org.rotki.mobile.core.protocol.generated.ProtocolErrorAction

internal sealed interface CompanionFailure {
    data class Known(
        internal val statusCode: Int,
        internal val code: HttpErrorCode,
        internal val retryable: Boolean,
        internal val action: ProtocolErrorAction,
        internal val localEffect: CompanionFailureLocalEffect,
    ) : CompanionFailure

    data object UnexpectedEngineError : CompanionFailure
}

internal enum class CompanionFailureLocalEffect {
    KEEP,
    DELETE_PAIRING_AND_SNAPSHOT,
    NOT_ESTABLISHED,
    UNCHANGED,
}

internal fun CompanionErrorDto.toDomain(statusCode: Int): CompanionFailure {
    val knownCode =
        HttpErrorCode.entries.firstOrNull { candidate -> candidate.wireValue == code }
            ?: return CompanionFailure.UnexpectedEngineError
    val knownAction =
        ProtocolErrorAction.entries.firstOrNull { candidate ->
            candidate.wireValue == action
        } ?: return CompanionFailure.UnexpectedEngineError
    val expected = EXPECTED_ERRORS[knownCode] ?: return CompanionFailure.UnexpectedEngineError
    if (expected.statusCode != statusCode ||
        expected.retryable != retryable ||
        expected.action != knownAction
    ) {
        return CompanionFailure.UnexpectedEngineError
    }
    return CompanionFailure.Known(
        statusCode = statusCode,
        code = knownCode,
        retryable = retryable,
        action = knownAction,
        localEffect = expected.localEffect,
    )
}

private data class ExpectedError(
    val statusCode: Int,
    val retryable: Boolean,
    val action: ProtocolErrorAction,
    val localEffect: CompanionFailureLocalEffect,
)

private val EXPECTED_ERRORS: Map<HttpErrorCode, ExpectedError> =
    mapOf(
        HttpErrorCode.InvalidRequest to expected(400, false, ProtocolErrorAction.None),
        HttpErrorCode.AccessSessionUnavailable to expected(401, false, ProtocolErrorAction.ProveDevice),
        HttpErrorCode.FullClientAuthRequired to
            expected(
                401,
                false,
                ProtocolErrorAction.AuthenticateFullClient,
                CompanionFailureLocalEffect.UNCHANGED,
            ),
        HttpErrorCode.NotAuthorized to
            expected(
                401,
                false,
                ProtocolErrorAction.PairAgain,
                CompanionFailureLocalEffect.DELETE_PAIRING_AND_SNAPSHOT,
            ),
        HttpErrorCode.ScopeDenied to expected(403, false, ProtocolErrorAction.UseFullClient),
        HttpErrorCode.ResourceNotFound to expected(404, false, ProtocolErrorAction.None),
        HttpErrorCode.HistoryChanged to expected(409, false, ProtocolErrorAction.FetchSnapshot),
        HttpErrorCode.IdempotencyConflict to expected(409, false, ProtocolErrorAction.NewRequest),
        HttpErrorCode.NoRefreshableSources to expected(409, false, ProtocolErrorAction.UseFullClient),
        HttpErrorCode.ProfileMismatch to expected(409, false, ProtocolErrorAction.OpenBoundProfile),
        HttpErrorCode.RefreshConflict to expected(409, false, ProtocolErrorAction.ObserveActive),
        HttpErrorCode.SourceDisabled to expected(409, false, ProtocolErrorAction.EnableSourceFullClient),
        HttpErrorCode.ChallengeUnavailable to expected(410, false, ProtocolErrorAction.RequestChallenge),
        HttpErrorCode.HistoryCursorUnavailable to expected(410, false, ProtocolErrorAction.RestartHistory),
        HttpErrorCode.PairingUnavailable to
            expected(
                410,
                false,
                ProtocolErrorAction.PairAgain,
                CompanionFailureLocalEffect.NOT_ESTABLISHED,
            ),
        HttpErrorCode.LockedEngine to expected(423, false, ProtocolErrorAction.UnlockFullClient),
        HttpErrorCode.IncompatibleProtocol to expected(426, false, ProtocolErrorAction.UpgradeEngine),
        HttpErrorCode.RateLimited to expected(429, true, ProtocolErrorAction.RetryAfter),
        HttpErrorCode.UnexpectedEngineError to expected(500, false, ProtocolErrorAction.None),
        HttpErrorCode.SnapshotUnavailable to expected(503, true, ProtocolErrorAction.Retry),
    )

private fun expected(
    statusCode: Int,
    retryable: Boolean,
    action: ProtocolErrorAction,
    localEffect: CompanionFailureLocalEffect = CompanionFailureLocalEffect.KEEP,
): ExpectedError = ExpectedError(statusCode, retryable, action, localEffect)
