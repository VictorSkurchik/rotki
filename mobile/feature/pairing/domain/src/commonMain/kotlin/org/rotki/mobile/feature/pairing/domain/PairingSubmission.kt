package org.rotki.mobile.feature.pairing.domain

/** Coarse reasons for refusing a submitted Pairing payload. */
public enum class PairingSubmissionRejection {
    MALFORMED,
    UNSUPPORTED,
    EXPIRED,
}

/** A secret-free result of one Pairing submission attempt. */
public sealed interface PairingSubmissionOutcome {
    public data object Accepted : PairingSubmissionOutcome

    /** The current local session did not own this submission; native UI remains unchanged. */
    public data object Ignored : PairingSubmissionOutcome

    public data class Rejected(
        public val reason: PairingSubmissionRejection,
    ) : PairingSubmissionOutcome
}

/**
 * Accepts a raw Pairing payload for immediate processing.
 *
 * Implementations must treat [rawPayload] as ephemeral authority: do not retain it, include it in
 * state, or expose it through logs and object representations.
 */
public fun interface PairingSubmissionGateway {
    public fun submit(rawPayload: String): PairingSubmissionOutcome
}
