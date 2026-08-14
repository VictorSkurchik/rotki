package org.rotki.mobile.feature.pairing.domain

/** Secret-free result of admitting decoded Pairing material into the current local session. */
public enum class PairingAdmission {
    ACCEPTED,
    IGNORED,
}

/**
 * Admits decoded Pairing material and exposes only the coarse session facts needed by presentation.
 *
 * [Material] is secret-bearing, ephemeral authority. Implementations may retain it only in the
 * active in-memory Pairing session; they must never persist it or expose it through logs, exceptions,
 * diagnostics, or object representations.
 */
public interface PairingSessionPort<Material : Any> {
    public fun isConnecting(): Boolean

    public fun isUnpaired(): Boolean

    public fun admit(material: Material): PairingAdmission
}

/**
 * Owns one-shot Pairing attempt and cleanup capabilities used by connection orchestration.
 *
 * [Attempt] and [Cleanup] are opaque, session-scoped capabilities. Consumers must not persist,
 * log, introspect, or reuse them outside the port instance that issued them. Implementations must
 * reject stale or foreign capabilities without exposing their contents.
 */
public interface PairingAttemptPort<Attempt : Any, Cleanup : Any> {
    /** Serializes connection and cleanup operations for this Pairing session. */
    public suspend fun <T> withConnectionOwnership(operation: suspend () -> T): T

    public fun hasPendingCleanup(): Boolean

    /** Takes the current attempt at most once; the returned capability remains opaque. */
    public fun takePending(): Attempt?

    public fun isCurrent(attempt: Attempt): Boolean

    public suspend fun awaitLoss(attempt: Attempt): Unit

    public fun markCleanupRequired(attempt: Attempt): Cleanup?

    public fun markDurable(attempt: Attempt): Boolean

    public fun commit(attempt: Attempt): Boolean

    public fun abort(attempt: Attempt): Boolean

    public fun completeCleanup(cleanup: Cleanup): Boolean

    public fun abandonCleanup(cleanup: Cleanup): Boolean

    /** Claims a non-destructive recovery barrier only when no fresh attempt is pending. */
    public fun claimRecoveredCleanup(): Cleanup?
}
