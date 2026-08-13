package org.rotki.mobile.core.ports

import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.X963PublicKey

public sealed interface DeviceProofPublicKeyOutcome {
    public data class PublicKey(public val value: X963PublicKey) : DeviceProofPublicKeyOutcome

    public data object PairingRequired : DeviceProofPublicKeyOutcome

    public data object UnexpectedFailure : DeviceProofPublicKeyOutcome
}

public sealed interface DeviceProofSigningOutcome {
    public data class Signed(public val signature: P1363Signature) : DeviceProofSigningOutcome

    public data object DeviceAuthenticationCancelled : DeviceProofSigningOutcome

    public data object DeviceAuthenticationUnavailable : DeviceProofSigningOutcome

    public data object PairingRequired : DeviceProofSigningOutcome

    public data object UnexpectedFailure : DeviceProofSigningOutcome
}

public sealed interface DeviceProofKeyDeleteOutcome {
    /** The key is absent after this idempotent operation. */
    public data object Deleted : DeviceProofKeyDeleteOutcome

    /** The adapter could not prove that the key is absent. */
    public data object Unavailable : DeviceProofKeyDeleteOutcome
}

public interface DeviceProofSigner {
    /** Creates a key only inside an authorized Pairing flow, or returns the existing Pairing key. */
    public suspend fun createKeyForPairing(): DeviceProofPublicKeyOutcome

    /** Reads the current key without silently replacing missing or invalidated Pairing identity. */
    public suspend fun currentPublicKeyX963(): DeviceProofPublicKeyOutcome

    public suspend fun sign(transcript: ByteArray): DeviceProofSigningOutcome

    /** Deletes native Device Key material; missing is treated as a successful idempotent delete. */
    public suspend fun deleteKey(): DeviceProofKeyDeleteOutcome
}
