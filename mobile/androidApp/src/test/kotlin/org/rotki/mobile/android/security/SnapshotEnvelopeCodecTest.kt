package org.rotki.mobile.android.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SnapshotEnvelopeCodecTest {
    @Test
    fun `round trip is defensive and redacted`() {
        val initializationVector = ByteArray(12) { index -> index.toByte() }
        val ciphertext = ByteArray(48) { index -> (index + 20).toByte() }
        val envelope = SnapshotEnvelope(initializationVector, ciphertext)
        initializationVector.fill(99)
        ciphertext.fill(99)

        val decoded = SnapshotEnvelopeCodec.decode(SnapshotEnvelopeCodec.encode(envelope))
        assertArrayEquals(ByteArray(12) { index -> index.toByte() }, decoded.initializationVectorCopy())
        assertArrayEquals(ByteArray(48) { index -> (index + 20).toByte() }, decoded.ciphertextAndTagCopy())
        assertEquals("SnapshotEnvelope(redacted)", decoded.toString())
    }

    @Test
    fun `decoder rejects version length and trailing mutations`() {
        val valid =
            SnapshotEnvelopeCodec.encode(
                SnapshotEnvelope(ByteArray(12), ByteArray(16)),
            )
        val mutations =
            listOf(
                valid.copyOf().also { bytes -> bytes[4] = 2 },
                valid.copyOf().also { bytes -> bytes[5] = 11 },
                valid.copyOf().also { bytes ->
                    ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(6, 17)
                },
                valid + 0,
                valid.copyOf(valid.lastIndex),
                valid.copyOf().also { bytes -> bytes[0] = 'X'.code.toByte() },
            )
        mutations.forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                SnapshotEnvelopeCodec.decode(encoded)
            }
        }
    }

    @Test
    fun `codec enforces document and IV bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotEnvelopeCodec.encode(SnapshotEnvelope(ByteArray(11), ByteArray(16)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotEnvelopeCodec.encode(SnapshotEnvelope(ByteArray(12), ByteArray(15)))
        }
        assertTrue(SnapshotEnvelopeCodec.isWithinDefensivePlaintextBound(0))
        assertTrue(
            SnapshotEnvelopeCodec.isWithinDefensivePlaintextBound(
                SnapshotEnvelopeCodec.DEFENSIVE_MAX_PLAINTEXT_BYTES,
            ),
        )
        assertFalse(
            SnapshotEnvelopeCodec.isWithinDefensivePlaintextBound(
                SnapshotEnvelopeCodec.DEFENSIVE_MAX_PLAINTEXT_BYTES + 1,
            ),
        )
    }

    @Test
    fun `AAD is stable and returned defensively`() {
        val expected = "rotki-companion/snapshot-envelope/v1".toByteArray(Charsets.US_ASCII)
        val first = SnapshotEnvelopeCodec.authenticatedDomainCopy()
        assertArrayEquals(expected, first)
        first.fill(0)
        assertArrayEquals(expected, SnapshotEnvelopeCodec.authenticatedDomainCopy())
    }
}
