package org.rotki.mobile.feature.pairing.presentation

import org.rotki.mobile.feature.pairing.domain.PairingSubmissionOutcome
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionRejection

public enum class PairingScreenState {
    INTRO,
    SCANNING,
    CAMERA_DENIED,
    SCANNER_UNAVAILABLE,
    INVALID_QR,
    EXPIRED_QR,
    CONNECTING,
}

public enum class PairingFailureCategory {
    MALFORMED,
    UNSUPPORTED,
    EXPIRED,
}

/** Complete, immutable state for the platform-neutral Pairing presentation. */
public data class PairingState(
    public val screen: PairingScreenState,
    public val failure: PairingFailureCategory?,
) {
    init {
        require(hasValidFailureForScreen(screen, failure)) {
            "Pairing failure must match its screen"
        }
    }
}

/** Typed input to the synchronous Pairing state reducer. */
public sealed interface PairingAction {
    public data object StartScanning : PairingAction

    public data object CameraPermissionDenied : PairingAction

    public data object ScannerUnavailable : PairingAction

    /** Result of a submission that the state owner already authorized while scanning. */
    public data class SubmissionCompleted(
        public val outcome: PairingSubmissionOutcome,
    ) : PairingAction

    public data object RetryScanning : PairingAction

    public data class Reset(
        public val sessionIsUnpaired: Boolean,
    ) : PairingAction
}

/** Pure unidirectional transition function for the Pairing presentation state. */
public object PairingReducer {
    public fun initial(sessionIsConnecting: Boolean): PairingState =
        if (sessionIsConnecting) CONNECTING_STATE else INTRO_STATE

    public fun acceptsSubmission(state: PairingState): Boolean = state.screen == PairingScreenState.SCANNING

    public fun reduce(
        state: PairingState,
        action: PairingAction,
    ): PairingState =
        when (action) {
            PairingAction.StartScanning -> {
                if (state.screen == PairingScreenState.CONNECTING) state else SCANNING_STATE
            }

            PairingAction.CameraPermissionDenied -> {
                if (acceptsSubmission(state)) CAMERA_DENIED_STATE else state
            }

            PairingAction.ScannerUnavailable -> {
                if (acceptsSubmission(state)) SCANNER_UNAVAILABLE_STATE else state
            }

            is PairingAction.SubmissionCompleted -> {
                reduceSubmission(state, action.outcome)
            }

            PairingAction.RetryScanning -> {
                retryScanning(state)
            }

            is PairingAction.Reset -> {
                if (state.screen != PairingScreenState.CONNECTING || action.sessionIsUnpaired) {
                    INTRO_STATE
                } else {
                    state
                }
            }
        }

    private fun reduceSubmission(
        state: PairingState,
        outcome: PairingSubmissionOutcome,
    ): PairingState =
        when (outcome) {
            PairingSubmissionOutcome.Accepted -> {
                CONNECTING_STATE
            }

            PairingSubmissionOutcome.Ignored -> {
                state
            }

            is PairingSubmissionOutcome.Rejected -> {
                when (outcome.reason) {
                    PairingSubmissionRejection.MALFORMED -> MALFORMED_STATE
                    PairingSubmissionRejection.UNSUPPORTED -> UNSUPPORTED_STATE
                    PairingSubmissionRejection.EXPIRED -> EXPIRED_STATE
                }
            }
        }

    private fun retryScanning(state: PairingState): PairingState =
        when (state.screen) {
            PairingScreenState.CAMERA_DENIED,
            PairingScreenState.EXPIRED_QR,
            PairingScreenState.INVALID_QR,
            PairingScreenState.SCANNER_UNAVAILABLE,
            -> SCANNING_STATE

            PairingScreenState.CONNECTING,
            PairingScreenState.INTRO,
            PairingScreenState.SCANNING,
            -> state
        }
}

private fun hasValidFailureForScreen(
    screen: PairingScreenState,
    failure: PairingFailureCategory?,
): Boolean =
    when (screen) {
        PairingScreenState.INVALID_QR -> {
            failure == PairingFailureCategory.MALFORMED ||
                failure == PairingFailureCategory.UNSUPPORTED
        }

        PairingScreenState.EXPIRED_QR -> {
            failure == PairingFailureCategory.EXPIRED
        }

        PairingScreenState.CAMERA_DENIED,
        PairingScreenState.CONNECTING,
        PairingScreenState.INTRO,
        PairingScreenState.SCANNER_UNAVAILABLE,
        PairingScreenState.SCANNING,
        -> {
            failure == null
        }
    }

private val INTRO_STATE: PairingState =
    PairingState(
        screen = PairingScreenState.INTRO,
        failure = null,
    )
private val SCANNING_STATE: PairingState =
    PairingState(
        screen = PairingScreenState.SCANNING,
        failure = null,
    )
private val CAMERA_DENIED_STATE: PairingState =
    PairingState(
        screen = PairingScreenState.CAMERA_DENIED,
        failure = null,
    )
private val SCANNER_UNAVAILABLE_STATE: PairingState =
    PairingState(
        screen = PairingScreenState.SCANNER_UNAVAILABLE,
        failure = null,
    )
private val MALFORMED_STATE: PairingState =
    PairingState(
        screen = PairingScreenState.INVALID_QR,
        failure = PairingFailureCategory.MALFORMED,
    )
private val UNSUPPORTED_STATE: PairingState =
    PairingState(
        screen = PairingScreenState.INVALID_QR,
        failure = PairingFailureCategory.UNSUPPORTED,
    )
private val EXPIRED_STATE: PairingState =
    PairingState(
        screen = PairingScreenState.EXPIRED_QR,
        failure = PairingFailureCategory.EXPIRED,
    )
private val CONNECTING_STATE: PairingState =
    PairingState(
        screen = PairingScreenState.CONNECTING,
        failure = null,
    )
