package org.rotki.mobile.spikes.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

class EncryptedBlob(iv: ByteArray, ciphertext: ByteArray) {
    val iv: ByteArray = iv.copyOf()
    val ciphertext: ByteArray = ciphertext.copyOf()
}

/** Small, versioned envelope used only to prove that no plaintext is persisted. */
object EncryptedBlobCodec {
    private val magic = byteArrayOf('R'.code.toByte(), 'P'.code.toByte(), '0'.code.toByte(), '3'.code.toByte())
    private const val VERSION = 1
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val HEADER_BYTES = 4 + 1 + 1 + Int.SIZE_BYTES
    const val MAX_CIPHERTEXT_BYTES = 1024 * 1024

    fun encode(blob: EncryptedBlob): ByteArray {
        validate(blob.iv, blob.ciphertext)
        return ByteBuffer.allocate(HEADER_BYTES + blob.iv.size + blob.ciphertext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(VERSION.toByte())
            .put(blob.iv.size.toByte())
            .putInt(blob.ciphertext.size)
            .put(blob.iv)
            .put(blob.ciphertext)
            .array()
    }

    fun decode(encoded: ByteArray): EncryptedBlob {
        require(encoded.size >= HEADER_BYTES + GCM_IV_BYTES + GCM_TAG_BYTES) {
            "Encrypted envelope is truncated"
        }
        require(encoded.size <= HEADER_BYTES + GCM_IV_BYTES + MAX_CIPHERTEXT_BYTES) {
            "Encrypted envelope exceeds the spike size limit"
        }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(magic.size).also(input::get)
        require(actualMagic.contentEquals(magic)) { "Encrypted envelope has the wrong magic" }
        require(input.get().toInt() and 0xff == VERSION) { "Unsupported encrypted envelope version" }
        val ivSize = input.get().toInt() and 0xff
        require(ivSize == GCM_IV_BYTES) { "AES-GCM IV must contain 12 bytes" }
        val ciphertextSize = input.int
        require(ciphertextSize in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Invalid encrypted envelope ciphertext length"
        }
        require(input.remaining() == ivSize + ciphertextSize) { "Encrypted envelope length mismatch" }

        val iv = ByteArray(ivSize).also(input::get)
        val ciphertext = ByteArray(ciphertextSize).also(input::get)
        require(!input.hasRemaining()) { "Trailing encrypted envelope bytes" }
        return EncryptedBlob(iv, ciphertext)
    }

    private fun validate(iv: ByteArray, ciphertext: ByteArray) {
        require(iv.size == GCM_IV_BYTES) { "AES-GCM IV must contain 12 bytes" }
        require(ciphertext.size in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Ciphertext must contain a GCM tag and stay within the spike size limit"
        }
    }
}
