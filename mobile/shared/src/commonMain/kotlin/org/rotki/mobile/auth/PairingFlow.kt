package org.rotki.mobile.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.auth.protocol.PairingQr
import org.rotki.mobile.auth.protocol.PairingQrParseOutcome
import org.rotki.mobile.auth.protocol.PairingQrParser
import org.rotki.mobile.auth.protocol.PairingQrRejection
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionTransitionOutcome
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
 * Owns the small, synchronous pairing-screen state machine shared by Android and iOS.
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

    private val mutablePresentation: MutableStateFlow<PairingPresentation> =
        MutableStateFlow(
            if (facade.status.value.rootState == CompanionRootState.Connecting) {
                CONNECTING_PRESENTATION
            } else {
                INTRO_PRESENTATION
            },
        )

    public val presentation: StateFlow<PairingPresentation> = mutablePresentation.asStateFlow()

    public fun startScanning(): Unit = moveUnlessConnecting(SCANNING_PRESENTATION)

    public fun cameraPermissionDenied() {
        if (mutablePresentation.value.state == PairingUiState.SCANNING) {
            mutablePresentation.value = CAMERA_DENIED_PRESENTATION
        }
    }

    public fun scannerUnavailable() {
        if (mutablePresentation.value.state == PairingUiState.SCANNING) {
            mutablePresentation.value = SCANNER_UNAVAILABLE_PRESENTATION
        }
    }

    public fun submitQr(rawPayload: String) {
        if (mutablePresentation.value.state != PairingUiState.SCANNING) return

        when (val outcome = parser.parse(rawPayload.encodeToByteArray())) {
            is PairingQrParseOutcome.Accepted -> acceptQr(outcome.pairingQr)
            is PairingQrParseOutcome.Rejected -> rejectQr(outcome.reason)
        }
    }

    public fun retryScanning() {
        when (mutablePresentation.value.state) {
            PairingUiState.CAMERA_DENIED,
            PairingUiState.EXPIRED_QR,
            PairingUiState.INVALID_QR,
            PairingUiState.SCANNER_UNAVAILABLE,
            -> mutablePresentation.value = SCANNING_PRESENTATION

            PairingUiState.CONNECTING,
            PairingUiState.INTRO,
            PairingUiState.SCANNING,
            -> Unit
        }
    }

    public fun reset() {
        if (
            mutablePresentation.value.state != PairingUiState.CONNECTING ||
            facade.status.value.rootState == CompanionRootState.Unpaired
        ) {
            mutablePresentation.value = INTRO_PRESENTATION
        }
    }

    override fun toString(): String = "PairingFlow(redacted)"

    private fun acceptQr(pairingQr: PairingQr) {
        val outcome = facade.acceptPairing(pairingQr)
        if (outcome is CompanionTransitionOutcome.Applied) {
            mutablePresentation.value = CONNECTING_PRESENTATION
        }
    }

    private fun rejectQr(reason: PairingQrRejection) {
        mutablePresentation.value =
            when (reason) {
                PairingQrRejection.EXPIRED -> EXPIRED_PRESENTATION

                PairingQrRejection.UNSUPPORTED_FORMAT -> UNSUPPORTED_PRESENTATION

                PairingQrRejection.DUPLICATE_MEMBER,
                PairingQrRejection.INVALID_CREDENTIAL,
                PairingQrRejection.INVALID_ORIGIN,
                PairingQrRejection.INVALID_PAIRING_ID,
                PairingQrRejection.INVALID_SHAPE,
                PairingQrRejection.INVALID_UTF8,
                PairingQrRejection.TOO_LARGE,
                -> MALFORMED_PRESENTATION
            }
    }

    private fun moveUnlessConnecting(presentation: PairingPresentation) {
        if (mutablePresentation.value.state != PairingUiState.CONNECTING) {
            mutablePresentation.value = presentation
        }
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
