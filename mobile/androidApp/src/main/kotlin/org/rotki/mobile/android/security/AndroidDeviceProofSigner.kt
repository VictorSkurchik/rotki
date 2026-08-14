package org.rotki.mobile.android.security

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.X963PublicKey
import java.util.concurrent.CancellationException

/** Android implementation of the shared Device Key boundary. */
internal class AndroidDeviceProofSigner(
    private val keyStore: DeviceSigningKeyStore = AndroidSigningKeyStore(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DeviceProofSigner {
    override suspend fun createKeyForPairing(): DeviceProofPublicKeyOutcome =
        try {
            val material = onCryptoDispatcher { keyStore.createOrCurrent() }
            material.publicKey.toProtocolOutcome()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceProofPublicKeyOutcome.UnexpectedFailure
        }

    override suspend fun currentPublicKeyX963(): DeviceProofPublicKeyOutcome =
        try {
            val material = onCryptoDispatcher { keyStore.currentOrNull() }
            if (material == null) {
                DeviceProofPublicKeyOutcome.PairingRequired
            } else {
                material.publicKey.toProtocolOutcome()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceProofPublicKeyOutcome.UnexpectedFailure
        }

    override suspend fun sign(transcript: ByteArray): DeviceProofSigningOutcome {
        val frozenTranscript = transcript.copyOf()
        return try {
            val derSignature =
                onCryptoDispatcher {
                    keyStore.signIfPresent(frozenTranscript)
                } ?: return DeviceProofSigningOutcome.PairingRequired
            when (
                val parsed =
                    P1363Signature.fromBytes(
                        AndroidP256Encoding.derEcdsaToP1363(derSignature),
                    )
            ) {
                is ProtocolValueParseOutcome.Accepted -> {
                    DeviceProofSigningOutcome.Signed(parsed.value)
                }

                is ProtocolValueParseOutcome.Rejected -> {
                    DeviceProofSigningOutcome.UnexpectedFailure
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceProofSigningOutcome.UnexpectedFailure
        } finally {
            frozenTranscript.fill(0)
        }
    }

    override suspend fun deleteKey(): DeviceProofKeyDeleteOutcome =
        try {
            onCryptoDispatcher { keyStore.delete() }
            DeviceProofKeyDeleteOutcome.Deleted
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DeviceProofKeyDeleteOutcome.Unavailable
        }

    private suspend fun <T> onCryptoDispatcher(operation: () -> T): T = withContext(dispatcher) { operation() }

    private fun java.security.PublicKey.toProtocolOutcome(): DeviceProofPublicKeyOutcome =
        when (val parsed = X963PublicKey.fromBytes(AndroidP256Encoding.publicKeyToX963(this))) {
            is ProtocolValueParseOutcome.Accepted -> {
                DeviceProofPublicKeyOutcome.PublicKey(parsed.value)
            }

            is ProtocolValueParseOutcome.Rejected -> {
                DeviceProofPublicKeyOutcome.UnexpectedFailure
            }
        }
}
