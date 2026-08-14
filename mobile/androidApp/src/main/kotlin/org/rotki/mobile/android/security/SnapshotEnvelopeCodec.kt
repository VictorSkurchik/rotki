package org.rotki.mobile.android.security

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class SnapshotEnvelope(
    initializationVector: ByteArray,
    ciphertextAndTag: ByteArray,
) {
    private val storedInitializationVector: ByteArray = initializationVector.copyOf()
    private val storedCiphertextAndTag: ByteArray = ciphertextAndTag.copyOf()

    fun initializationVectorCopy(): ByteArray = storedInitializationVector.copyOf()

    fun ciphertextAndTagCopy(): ByteArray = storedCiphertextAndTag.copyOf()

    override fun toString(): String = "SnapshotEnvelope(redacted)"
}

/** Strict, bounded on-disk envelope for the one authenticated offline snapshot. */
internal object SnapshotEnvelopeCodec {
    /**
     * Parser/resource-safety guard, not the product Snapshot byte budget.
     *
     * D3.2 owns the lower, measured publication limit. Keeping this distinct prevents the
     * Android storage envelope from prematurely freezing that product decision.
     */
    const val DEFENSIVE_MAX_PLAINTEXT_BYTES: Int = 64 * 1024 * 1024
    const val GCM_IV_BYTES: Int = 12
    const val GCM_TAG_BYTES: Int = 16

    private const val VERSION: Int = 1
    private const val HEADER_BYTES: Int = 4 + 1 + 1 + Int.SIZE_BYTES
    private const val MAX_CIPHERTEXT_BYTES: Int = DEFENSIVE_MAX_PLAINTEXT_BYTES + GCM_TAG_BYTES
    const val DEFENSIVE_MAX_ENCODED_BYTES: Int =
        HEADER_BYTES + GCM_IV_BYTES + MAX_CIPHERTEXT_BYTES

    private val magic: ByteArray =
        byteArrayOf(
            'R'.code.toByte(),
            'K'.code.toByte(),
            'S'.code.toByte(),
            'E'.code.toByte(),
        )
    private val authenticatedDomain: ByteArray =
        "rotki-companion/snapshot-envelope/v1".toByteArray(Charsets.US_ASCII)

    fun encode(envelope: SnapshotEnvelope): ByteArray {
        val initializationVector = envelope.initializationVectorCopy()
        val ciphertextAndTag = envelope.ciphertextAndTagCopy()
        require(initializationVector.size == GCM_IV_BYTES) {
            "AES-GCM initialization vector must contain 12 bytes"
        }
        require(
            ciphertextAndTag.size >= GCM_TAG_BYTES &&
                isWithinDefensivePlaintextBound(ciphertextAndTag.size - GCM_TAG_BYTES),
        ) {
            "Snapshot ciphertext is outside the supported bounds"
        }

        return ByteBuffer
            .allocate(HEADER_BYTES + initializationVector.size + ciphertextAndTag.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(magic)
            .put(VERSION.toByte())
            .put(initializationVector.size.toByte())
            .putInt(ciphertextAndTag.size)
            .put(initializationVector)
            .put(ciphertextAndTag)
            .array()
    }

    fun decode(encoded: ByteArray): SnapshotEnvelope {
        require(encoded.size in minimumEncodedBytes()..DEFENSIVE_MAX_ENCODED_BYTES) {
            "Snapshot envelope is truncated or exceeds the supported bound"
        }
        val input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val actualMagic = ByteArray(magic.size).also(input::get)
        require(actualMagic.contentEquals(magic)) { "Snapshot envelope magic is invalid" }
        require(input.get().toInt() and 0xff == VERSION) {
            "Snapshot envelope version is unsupported"
        }

        val initializationVectorSize = input.get().toInt() and 0xff
        require(initializationVectorSize == GCM_IV_BYTES) {
            "AES-GCM initialization vector must contain 12 bytes"
        }
        val ciphertextSize = input.int
        require(ciphertextSize in GCM_TAG_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Snapshot ciphertext is outside the supported bounds"
        }
        require(input.remaining() == initializationVectorSize + ciphertextSize) {
            "Snapshot envelope length is inconsistent"
        }

        val initializationVector = ByteArray(initializationVectorSize).also(input::get)
        val ciphertextAndTag = ByteArray(ciphertextSize).also(input::get)
        check(!input.hasRemaining()) { "Snapshot envelope has trailing bytes" }
        return SnapshotEnvelope(initializationVector, ciphertextAndTag)
    }

    fun authenticatedDomainCopy(): ByteArray = authenticatedDomain.copyOf()

    internal fun isWithinDefensivePlaintextBound(byteCount: Int): Boolean =
        byteCount in 0..DEFENSIVE_MAX_PLAINTEXT_BYTES

    private fun minimumEncodedBytes(): Int = HEADER_BYTES + GCM_IV_BYTES + GCM_TAG_BYTES
}
