package org.rotki.mobile.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.auth.protocol.PairingQr
import org.rotki.mobile.auth.protocol.PairingQrParseOutcome
import org.rotki.mobile.auth.protocol.PairingQrParser
import org.rotki.mobile.auth.protocol.PairingQrRejection
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionTransitionOutcome
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionGateway
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionOutcome
import org.rotki.mobile.feature.pairing.domain.PairingSubmissionRejection
import org.rotki.mobile.feature.pairing.presentation.PairingAction
import org.rotki.mobile.feature.pairing.presentation.PairingFailureCategory
import org.rotki.mobile.feature.pairing.presentation.PairingReducer
import org.rotki.mobile.feature.pairing.presentation.PairingScreenState
import org.rotki.mobile.feature.pairing.presentation.PairingState
import kotlin.time.Clock as KotlinClock

/** A platform-neutral screen state suitable for direct use from Kotlin or Swift UI code. */
public enum class PairingUiState(
    public val code: String,
) {
    INTRO("intro"),
    SCANNING("scanning"),
    CAMERA_DENIED("camera_denied"),
    SCANNER_UNAVAILABLE("scanner_unavailable"),
    INVALID_QR("invalid_qr"),
    EXPIRED_QR("expired_qr"),
    CONNECTING("connecting"),
}

/** Coarse, non-sensitive categories that native UI can turn into localized copy. */
public enum class PairingRejectionCategory(
    public val code: String,
) {
    MALFORMED("malformed"),
    UNSUPPORTED("unsupported"),
    EXPIRED("expired"),
}

/**
 * An immutable projection for native UI. It intentionally contains no decoded QR material.
 */
public class PairingPresentation internal constructor(
    public val state: PairingUiState,
    public val rejectionCategory: PairingRejectionCategory?,
) {
    override fun toString(): String = "PairingPresentation(state=${state.code}, rejection=${rejectionCategory?.code})"
}

/**
 * Stable native-facing adapter over the platform-neutral Pairing reducer and existing QR/facade
 * integration.
 *
 * Synchronous commands must be delivered serially by the platform UI owner on its main executor.
 * Concurrent or reentrant command delivery is unsupported.
 *
 * A successful scan hands the decoded QR directly to the facade's private, in-memory connection
 * context. It never becomes part of the public presentation state.
 */
public class PairingFlow internal constructor(
    private val facade: CompanionFacade,
    private val parser: PairingQrParser,
) {
    internal constructor(facade: CompanionFacade, clock: Clock) : this(
        facade = facade,
        parser = PairingQrParser(clock),
    )

    internal constructor(facade: CompanionFacade) : this(
        facade = facade,
        clock = Clock { KotlinClock.System.now().epochSeconds },
    )

    private val submissionGateway: PairingSubmissionGateway =
        PairingSubmissionGateway(::submitPairing)
    private val mutablePresentation: MutableStateFlow<PairingPresentation> =
        MutableStateFlow(
            PairingReducer
                .initial(
                    sessionIsConnecting = facade.status.value.rootState == CompanionRootState.Connecting,
                ).toPublicPresentation(),
        )

    public val presentation: StateFlow<PairingPresentation> = mutablePresentation.asStateFlow()

    public fun startScanning(): Unit = dispatch(PairingAction.StartScanning)

    public fun cameraPermissionDenied(): Unit = dispatch(PairingAction.CameraPermissionDenied)

    public fun scannerUnavailable(): Unit = dispatch(PairingAction.ScannerUnavailable)

    public fun submitQr(rawPayload: String) {
        if (!PairingReducer.acceptsSubmission(mutablePresentation.value.toFeatureState())) return

        dispatch(
            PairingAction.SubmissionCompleted(
                submissionGateway.submit(rawPayload),
            ),
        )
    }

    public fun retryScanning(): Unit = dispatch(PairingAction.RetryScanning)

    public fun reset(): Unit =
        dispatch(
            PairingAction.Reset(
                sessionIsUnpaired = facade.status.value.rootState == CompanionRootState.Unpaired,
            ),
        )

    override fun toString(): String = "PairingFlow(redacted)"

    private fun dispatch(action: PairingAction) {
        mutablePresentation.update { currentPresentation ->
            PairingReducer
                .reduce(currentPresentation.toFeatureState(), action)
                .toPublicPresentation()
        }
    }

    private fun submitPairing(rawPayload: String): PairingSubmissionOutcome =
        when (val outcome = parser.parse(rawPayload.encodeToByteArray())) {
            is PairingQrParseOutcome.Accepted -> {
                acceptQr(outcome.pairingQr)
            }

            is PairingQrParseOutcome.Rejected -> {
                PairingSubmissionOutcome.Rejected(outcome.reason.toDomainRejection())
            }
        }

    private fun acceptQr(pairingQr: PairingQr): PairingSubmissionOutcome =
        if (facade.acceptPairing(pairingQr) is CompanionTransitionOutcome.Applied) {
            PairingSubmissionOutcome.Accepted
        } else {
            PairingSubmissionOutcome.Ignored
        }
}

private fun PairingQrRejection.toDomainRejection(): PairingSubmissionRejection =
    when (this) {
        PairingQrRejection.EXPIRED -> PairingSubmissionRejection.EXPIRED

        PairingQrRejection.UNSUPPORTED_FORMAT -> PairingSubmissionRejection.UNSUPPORTED

        PairingQrRejection.DUPLICATE_MEMBER,
        PairingQrRejection.INVALID_CREDENTIAL,
        PairingQrRejection.INVALID_ORIGIN,
        PairingQrRejection.INVALID_PAIRING_ID,
        PairingQrRejection.INVALID_SHAPE,
        PairingQrRejection.INVALID_UTF8,
        PairingQrRejection.TOO_LARGE,
        -> PairingSubmissionRejection.MALFORMED
    }

private fun PairingPresentation.toFeatureState(): PairingState =
    when (state) {
        PairingUiState.INTRO -> {
            PairingState(PairingScreenState.INTRO, failure = null)
        }

        PairingUiState.SCANNING -> {
            PairingState(PairingScreenState.SCANNING, failure = null)
        }

        PairingUiState.CAMERA_DENIED -> {
            PairingState(PairingScreenState.CAMERA_DENIED, failure = null)
        }

        PairingUiState.SCANNER_UNAVAILABLE -> {
            PairingState(PairingScreenState.SCANNER_UNAVAILABLE, failure = null)
        }

        PairingUiState.INVALID_QR -> {
            val failure =
                when (rejectionCategory) {
                    PairingRejectionCategory.MALFORMED -> PairingFailureCategory.MALFORMED

                    PairingRejectionCategory.UNSUPPORTED -> PairingFailureCategory.UNSUPPORTED

                    PairingRejectionCategory.EXPIRED,
                    null,
                    -> error("Invalid Pairing rejection category")
                }
            PairingState(PairingScreenState.INVALID_QR, failure)
        }

        PairingUiState.EXPIRED_QR -> {
            PairingState(PairingScreenState.EXPIRED_QR, PairingFailureCategory.EXPIRED)
        }

        PairingUiState.CONNECTING -> {
            PairingState(PairingScreenState.CONNECTING, failure = null)
        }
    }

private fun PairingState.toPublicPresentation(): PairingPresentation =
    when (screen) {
        PairingScreenState.INTRO -> {
            INTRO_PRESENTATION
        }

        PairingScreenState.SCANNING -> {
            SCANNING_PRESENTATION
        }

        PairingScreenState.CAMERA_DENIED -> {
            CAMERA_DENIED_PRESENTATION
        }

        PairingScreenState.SCANNER_UNAVAILABLE -> {
            SCANNER_UNAVAILABLE_PRESENTATION
        }

        PairingScreenState.INVALID_QR -> {
            when (failure) {
                PairingFailureCategory.MALFORMED -> MALFORMED_PRESENTATION

                PairingFailureCategory.UNSUPPORTED -> UNSUPPORTED_PRESENTATION

                PairingFailureCategory.EXPIRED,
                null,
                -> error("Invalid Pairing failure category")
            }
        }

        PairingScreenState.EXPIRED_QR -> {
            EXPIRED_PRESENTATION
        }

        PairingScreenState.CONNECTING -> {
            CONNECTING_PRESENTATION
        }
    }

private val INTRO_PRESENTATION: PairingPresentation =
    PairingPresentation(
        state = PairingUiState.INTRO,
        rejectionCategory = null,
    )
private val SCANNING_PRESENTATION: PairingPresentation =
    PairingPresentation(
        state = PairingUiState.SCANNING,
        rejectionCategory = null,
    )
private val CAMERA_DENIED_PRESENTATION: PairingPresentation =
    PairingPresentation(
        state = PairingUiState.CAMERA_DENIED,
        rejectionCategory = null,
    )
private val SCANNER_UNAVAILABLE_PRESENTATION: PairingPresentation =
    PairingPresentation(
        state = PairingUiState.SCANNER_UNAVAILABLE,
        rejectionCategory = null,
    )
private val MALFORMED_PRESENTATION: PairingPresentation =
    PairingPresentation(
        state = PairingUiState.INVALID_QR,
        rejectionCategory = PairingRejectionCategory.MALFORMED,
    )
private val UNSUPPORTED_PRESENTATION: PairingPresentation =
    PairingPresentation(
        state = PairingUiState.INVALID_QR,
        rejectionCategory = PairingRejectionCategory.UNSUPPORTED,
    )
private val EXPIRED_PRESENTATION: PairingPresentation =
    PairingPresentation(
        state = PairingUiState.EXPIRED_QR,
        rejectionCategory = PairingRejectionCategory.EXPIRED,
    )
private val CONNECTING_PRESENTATION: PairingPresentation =
    PairingPresentation(
        state = PairingUiState.CONNECTING,
        rejectionCategory = null,
    )
