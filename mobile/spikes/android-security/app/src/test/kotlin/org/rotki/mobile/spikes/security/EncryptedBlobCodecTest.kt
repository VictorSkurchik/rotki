package org.rotki.mobile.spikes.security

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedBlobCodecTest {
    @Test
    fun `versioned envelope round trips exact bytes`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(48) { (it + 20).toByte() }

        val decoded = EncryptedBlobCodec.decode(
            EncryptedBlobCodec.encode(EncryptedBlob(iv, ciphertext)),
        )

        assertArrayEquals(iv, decoded.iv)
        assertArrayEquals(ciphertext, decoded.ciphertext)
    }

    @Test
    fun `envelope owns defensive copies`() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(16) { (it + 1).toByte() }
        val blob = EncryptedBlob(iv, ciphertext)
        iv.fill(99)
        ciphertext.fill(99)

        assertFalse(blob.iv.all { it == 99.toByte() })
        assertFalse(blob.ciphertext.all { it == 99.toByte() })
    }

    @Test
    fun `decoder rejects wrong magic version IV and lengths`() {
        val valid = EncryptedBlobCodec.encode(EncryptedBlob(ByteArray(12), ByteArray(16)))
        val cases = listOf(
            byteArrayOf(),
            valid.copyOf().also { it[0] = 0 },
            valid.copyOf().also { it[4] = 2 },
            valid.copyOf().also { it[5] = 11 },
            valid.copyOf().also {
                ByteBuffer.wrap(it, 6, 4).order(ByteOrder.BIG_ENDIAN).putInt(17)
            },
            valid + 0,
        )
        cases.forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                EncryptedBlobCodec.decode(malformed)
            }
        }
    }

    @Test
    fun `encoder rejects invalid GCM fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBlobCodec.encode(EncryptedBlob(ByteArray(11), ByteArray(16)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedBlobCodec.encode(EncryptedBlob(ByteArray(12), ByteArray(15)))
        }
    }
}
