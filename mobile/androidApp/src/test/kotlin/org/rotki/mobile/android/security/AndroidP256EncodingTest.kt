package org.rotki.mobile.android.security

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.X963PublicKey

class AndroidP256EncodingTest {
    @Test
    fun `DER and P1363 round trip strict scalar boundaries`() {
        val components = listOf(
            BigInteger.ONE,
            BigInteger.valueOf(127),
            BigInteger.valueOf(128),
            BigInteger.ONE.shiftLeft(255),
            AndroidP256Encoding.p256Order.subtract(BigInteger.ONE),
        )

        components.forEach { r ->
            components.forEach { s ->
                val p1363 = fixed(r) + fixed(s)
                assertArrayEquals(
                    p1363,
                    AndroidP256Encoding.derEcdsaToP1363(
                        AndroidP256Encoding.p1363ToDerEcdsa(p1363),
                    ),
                )
            }
        }
    }

    @Test
    fun `DER parser rejects malformed and malleable encodings`() {
        val valid = AndroidP256Encoding.p1363ToDerEcdsa(
            fixed(BigInteger.ONE) + fixed(BigInteger.valueOf(2)),
        )
        val malformed = listOf(
            byteArrayOf(),
            valid + 0,
            byteArrayOf(0x31, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x81.toByte(), 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x07, 0x02, 0x02, 0x00, 0x01, 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x00, 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x05, 0x02, 0x01, 0x01, 0x02, 0x01),
            byteArrayOf(0x30, 0x81.toByte(), 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02),
            byteArrayOf(0x30, 0x09, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02, 0x05, 0x00),
        )

        malformed.forEach { signature ->
            assertThrows(IllegalArgumentException::class.java) {
                AndroidP256Encoding.derEcdsaToP1363(signature)
            }
        }
    }

    @Test
    fun `P1363 rejects wrong width zero and values outside P256 order`() {
        val invalid = listOf(
            ByteArray(63),
            ByteArray(32) + fixed(BigInteger.ONE),
            fixed(AndroidP256Encoding.p256Order) + fixed(BigInteger.ONE),
        )

        invalid.forEach { signature ->
            assertThrows(IllegalArgumentException::class.java) {
                AndroidP256Encoding.p1363ToDerEcdsa(signature)
            }
        }
    }

    @Test
    fun `JCA signature survives strict DER P1363 conversion`() {
        val keyPair = p256KeyPair()
        val transcript = "rotki-companion-device-proof-test".toByteArray()
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(transcript)
            sign()
        }

        val p1363 = AndroidP256Encoding.derEcdsaToP1363(der)

        assertEquals(64, p1363.size)
        assertTrue(
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(keyPair.public)
                update(transcript)
                verify(AndroidP256Encoding.p1363ToDerEcdsa(p1363))
            },
        )
    }

    @Test
    fun `P256 public key becomes strict uncompressed X963`() {
        val encoded = AndroidP256Encoding.publicKeyToX963(p256KeyPair().public)

        assertEquals(65, encoded.size)
        assertEquals(0x04, encoded.first().toInt())
        assertTrue(X963PublicKey.fromBytes(encoded) is ProtocolValueParseOutcome.Accepted)
    }

    @Test
    fun `non P256 public key is rejected`() {
        val p384 = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp384r1"))
            generateKeyPair()
        }

        assertThrows(IllegalArgumentException::class.java) {
            AndroidP256Encoding.publicKeyToX963(p384.public)
        }
    }

    private fun p256KeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun fixed(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        val magnitude = if (encoded.size > 1 && encoded.first() == 0.toByte()) {
            encoded.copyOfRange(1, encoded.size)
        } else {
            encoded
        }
        return ByteArray(32 - magnitude.size) + magnitude
    }
}
