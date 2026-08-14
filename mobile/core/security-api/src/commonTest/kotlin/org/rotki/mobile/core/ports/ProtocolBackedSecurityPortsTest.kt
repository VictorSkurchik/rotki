package org.rotki.mobile.core.ports

import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.X963PublicKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.Base64.PaddingOption
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class ProtocolBackedSecurityPortsTest {
    @Test
    fun pairingRecordRetainsTypedProtocolFieldsWithoutLeakingThemToDiagnostics() {
        val origin = acceptedOrigin(SEEDED_ORIGIN)
        val sessionId = acceptedDeviceSessionId()
        val record = PairingRecord(origin, sessionId)
        val present = PairingRecordReadOutcome.Present(record)

        assertEquals(origin, record.engineOrigin)
        assertEquals(sessionId, record.deviceSessionId)
        assertEquals("PairingRecord(redacted)", record.toString())
        assertEquals("Present(record=PairingRecord(redacted))", present.toString())
        listOf<Any>(
            record,
            PairingRecordReadOutcome.Missing,
            present,
            PairingRecordReadOutcome.Corrupt,
            PairingRecordReadOutcome.Unavailable,
            PairingRecordWriteOutcome.Stored,
            PairingRecordWriteOutcome.Unavailable,
            PairingRecordDeleteOutcome.Deleted,
            PairingRecordDeleteOutcome.Unavailable,
        ).forEach { outcome ->
            assertDoesNotContainProtocolMaterial(
                outcome.toString(),
                origin.canonical,
                sessionId.encoded,
            )
        }
    }

    @Test
    fun deviceProofOutcomesKeepProtocolMaterialBehindRedactedValues() {
        val publicKey = acceptedPublicKey()
        val signature = acceptedSignature()
        val publicKeyOutcome = DeviceProofPublicKeyOutcome.PublicKey(publicKey)
        val signingOutcome = DeviceProofSigningOutcome.Signed(signature)

        assertEquals(publicKey, publicKeyOutcome.value)
        assertEquals(signature, signingOutcome.signature)
        assertEquals("PublicKey(value=X963PublicKey(redacted))", publicKeyOutcome.toString())
        assertEquals("Signed(signature=P1363Signature(redacted))", signingOutcome.toString())
        listOf<Any>(
            publicKeyOutcome,
            DeviceProofPublicKeyOutcome.PairingRequired,
            DeviceProofPublicKeyOutcome.UnexpectedFailure,
            signingOutcome,
            DeviceProofSigningOutcome.DeviceAuthenticationCancelled,
            DeviceProofSigningOutcome.DeviceAuthenticationUnavailable,
            DeviceProofSigningOutcome.PairingRequired,
            DeviceProofSigningOutcome.UnexpectedFailure,
            DeviceProofKeyDeleteOutcome.Deleted,
            DeviceProofKeyDeleteOutcome.Unavailable,
        ).forEach { outcome ->
            assertDoesNotContainProtocolMaterial(
                outcome.toString(),
                publicKey.encoded,
                signature.encoded,
            )
        }
    }

    @Test
    fun portContractsExposeTypedOutcomesWithoutAPlatformRuntime() {
        val idempotencyKey = acceptedIdempotencyKey()
        val generator = IdempotencyKeyGenerator { idempotencyKey }
        assertEquals(idempotencyKey, generator.generate())
        assertEquals("IdempotencyKey(redacted)", generator.generate().toString())
        assertDoesNotContainProtocolMaterial(generator.generate().toString(), idempotencyKey.encoded)

        val signer = FixedDeviceProofSigner(acceptedPublicKey(), acceptedSignature())
        assertIs<DeviceProofPublicKeyOutcome.PublicKey>(
            runImmediately { signer.createKeyForPairing() },
        )
        assertIs<DeviceProofPublicKeyOutcome.PublicKey>(
            runImmediately { signer.currentPublicKeyX963() },
        )
        assertIs<DeviceProofSigningOutcome.Signed>(
            runImmediately { signer.sign("seeded_transcript".encodeToByteArray()) },
        )
        assertSame(DeviceProofKeyDeleteOutcome.Deleted, runImmediately { signer.deleteKey() })

        val record = PairingRecord(acceptedOrigin(SEEDED_ORIGIN), acceptedDeviceSessionId())
        val store = FixedPairingRecordStore(record)
        assertEquals(
            record,
            assertIs<PairingRecordReadOutcome.Present>(runImmediately { store.read() }).record,
        )
        assertSame(PairingRecordWriteOutcome.Stored, runImmediately { store.write(record) })
        assertSame(PairingRecordDeleteOutcome.Deleted, runImmediately { store.delete() })
    }
}

private class FixedDeviceProofSigner(
    private val publicKey: X963PublicKey,
    private val signature: P1363Signature,
) : DeviceProofSigner {
    override suspend fun createKeyForPairing(): DeviceProofPublicKeyOutcome =
        DeviceProofPublicKeyOutcome.PublicKey(publicKey)

    override suspend fun currentPublicKeyX963(): DeviceProofPublicKeyOutcome =
        DeviceProofPublicKeyOutcome.PublicKey(publicKey)

    override suspend fun sign(transcript: ByteArray): DeviceProofSigningOutcome {
        check(transcript.isNotEmpty())
        return DeviceProofSigningOutcome.Signed(signature)
    }

    override suspend fun deleteKey(): DeviceProofKeyDeleteOutcome = DeviceProofKeyDeleteOutcome.Deleted
}

private class FixedPairingRecordStore(
    private val record: PairingRecord,
) : PairingRecordStore {
    override suspend fun read(): PairingRecordReadOutcome = PairingRecordReadOutcome.Present(record)

    override suspend fun write(record: PairingRecord): PairingRecordWriteOutcome {
        check(record == this.record)
        return PairingRecordWriteOutcome.Stored
    }

    override suspend fun delete(): PairingRecordDeleteOutcome = PairingRecordDeleteOutcome.Deleted
}

private fun acceptedOrigin(candidate: String): EngineOrigin =
    assertIs<EngineOriginParseOutcome.Accepted>(EngineOrigin.parse(candidate)).origin

@OptIn(ExperimentalEncodingApi::class)
private fun acceptedDeviceSessionId(): DeviceSessionId =
    assertIs<ProtocolValueParseOutcome.Accepted<DeviceSessionId>>(
        DeviceSessionId.parse(
            Base64.UrlSafe
                .withPadding(PaddingOption.ABSENT)
                .encode(ByteArray(32) { index -> (index + 1).toByte() }),
        ),
    ).value

private fun acceptedIdempotencyKey(): IdempotencyKey =
    assertIs<ProtocolValueParseOutcome.Accepted<IdempotencyKey>>(
        IdempotencyKey.fromBytes(ByteArray(16) { index -> (index + 1).toByte() }),
    ).value

private fun acceptedSignature(): P1363Signature =
    assertIs<ProtocolValueParseOutcome.Accepted<P1363Signature>>(
        P1363Signature.fromBytes(
            ByteArray(64).also { bytes ->
                bytes[31] = 1
                bytes[63] = 1
            },
        ),
    ).value

private fun acceptedPublicKey(): X963PublicKey =
    assertIs<ProtocolValueParseOutcome.Accepted<X963PublicKey>>(
        X963PublicKey.fromBytes(
            byteArrayOf(0x04) + (P256_GENERATOR_X + P256_GENERATOR_Y).hexBytes(),
        ),
    ).value

private fun String.hexBytes(): ByteArray =
    chunked(2)
        .map { octet -> octet.toInt(radix = 16).toByte() }
        .toByteArray()

private fun assertDoesNotContainProtocolMaterial(
    diagnostic: String,
    vararg material: String,
) {
    material.forEach { value -> assertFalse(diagnostic.contains(value), diagnostic) }
}

private fun <T> runImmediately(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                completed = result
            }
        },
    )
    return completed?.getOrThrow() ?: error("Test port unexpectedly suspended")
}

private const val SEEDED_ORIGIN: String = "https://seeded-engine.example"
private const val P256_GENERATOR_X: String =
    "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"
private const val P256_GENERATOR_Y: String =
    "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
