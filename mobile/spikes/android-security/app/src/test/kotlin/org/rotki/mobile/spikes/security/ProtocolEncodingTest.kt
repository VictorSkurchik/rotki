package org.rotki.mobile.spikes.security

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolEncodingTest {
    @Test
    fun `checked proof vector builds byte-for-byte`() {
        val transcript = ProofVector.transcript()

        assertEquals(EXPECTED_TRANSCRIPT_HEX, transcript.toHex())
        assertEquals(
            ProofVector.TRANSCRIPT_SHA256,
            MessageDigest.getInstance("SHA-256").digest(transcript).toHex(),
        )
    }

    @Test
    fun `transcript rejects non-canonical origins and wrong field widths`() {
        listOf(
            "http://rotki.example",
            "HTTPS://rotki.example",
            "https://ROTKI.example",
            "https://rotki.example/",
            "https://rotki.example/path",
            "https://user@rotki.example",
            "https://rotki.example?query",
            "https://rotki.example#fragment",
            "https://rotki.example:443",
        ).forEach { origin ->
            assertThrows(IllegalArgumentException::class.java) {
                ProtocolEncoding.encodeDeviceProofTranscript(
                    origin,
                    ByteArray(32),
                    ByteArray(16),
                    ByteArray(32),
                    0uL,
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            ProtocolEncoding.encodeDeviceProofTranscript(
                ProofVector.ENGINE_ORIGIN,
                ByteArray(31),
                ByteArray(16),
                ByteArray(32),
                0uL,
            )
        }
    }

    @Test
    fun `DER and P1363 round trip canonical boundary values`() {
        val components = listOf(
            BigInteger.ONE,
            BigInteger.valueOf(127),
            BigInteger.valueOf(128),
            BigInteger.ONE.shiftLeft(255),
            ProtocolEncoding.p256Order.subtract(BigInteger.ONE),
        )
        components.forEach { r ->
            components.forEach { s ->
                val p1363 = fixed(r) + fixed(s)
                val der = ProtocolEncoding.p1363ToDerEcdsa(p1363)
                assertArrayEquals(p1363, ProtocolEncoding.derEcdsaToP1363(der))
            }
        }
    }

    @Test
    fun `DER parser rejects malleable and malformed signatures`() {
        val valid = ProtocolEncoding.p1363ToDerEcdsa(fixed(BigInteger.ONE) + fixed(BigInteger.TWO))
        val cases = listOf(
            byteArrayOf(),
            valid + 0,
            byteArrayOf(0x31, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x81.toByte(), 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x07, 0x02, 0x02, 0x00, 0x01, 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x05, 0x02, 0x01, 0x01, 0x02, 0x01),
            byteArrayOf(0x30, 0x81.toByte(), 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02),
        )
        cases.forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                ProtocolEncoding.derEcdsaToP1363(malformed)
            }
        }
    }

    @Test
    fun `P1363 rejects wrong width zero and values outside the P-256 order`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolEncoding.p1363ToDerEcdsa(ByteArray(63))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolEncoding.p1363ToDerEcdsa(ByteArray(32) + fixed(BigInteger.ONE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProtocolEncoding.p1363ToDerEcdsa(
                fixed(ProtocolEncoding.p256Order) + fixed(BigInteger.ONE),
            )
        }
    }

    @Test
    fun `JCA signature converts through P1363 and rejects altered message`() {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val message = ProofVector.transcript()
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(message)
            sign()
        }
        val p1363 = ProtocolEncoding.derEcdsaToP1363(der)

        assertEquals(64, p1363.size)
        assertTrue(AndroidKeyStoreProbe.verifyDeviceProof(keyPair.public, message, p1363))
        assertFalse(
            AndroidKeyStoreProbe.verifyDeviceProof(
                keyPair.public,
                message.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
                p1363,
            ),
        )
    }

    @Test
    fun `SEC1 public point is uncompressed and fixed width`() {
        val publicKey = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair().public
        }

        val encoded = ProtocolEncoding.p256PublicKeyToSec1(publicKey)

        assertEquals(65, encoded.size)
        assertEquals(0x04, encoded.first().toInt())
    }

    @Test
    fun `Base64URL is canonical unpadded and rejects alternate forms`() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xef.toByte())
        val canonical = ProtocolEncoding.encodeBase64UrlNoPadding(bytes)
        assertEquals("-__v", canonical)
        assertArrayEquals(bytes, ProtocolEncoding.decodeCanonicalBase64Url(canonical))

        listOf("-__v=", "+//v", "-__v\n", "A", "").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ProtocolEncoding.decodeCanonicalBase64Url(invalid)
            }
        }
    }

    private fun fixed(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().let {
            if (it.size > 1 && it.first().toInt() == 0) it.copyOfRange(1, it.size) else it
        }
        return ByteArray(32 - bytes.size) + bytes
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val EXPECTED_TRANSCRIPT_HEX =
            "726f746b692d636f6d70616e696f6e2d6465766963652d70726f6f662f76310000156874" +
                "7470733a2f2f726f746b692e6578616d706c65000102030405060708090a0b0c0d0e0f101112" +
                "131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738" +
                "393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f000000006a7c9880"
    }
}
