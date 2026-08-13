package org.rotki.mobile.android.security

import java.math.BigInteger
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec

/** Strict Android/JCA encodings at the Companion Protocol P-256 boundary. */
internal object AndroidP256Encoding {
    private const val DER_SEQUENCE = 0x30
    private const val DER_INTEGER = 0x02
    private const val P256_COMPONENT_BYTES = 32
    private const val P256_PUBLIC_KEY_BYTES = 65
    private const val MAX_P256_DER_SIGNATURE_BYTES = 72

    private val fieldPrime = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF",
        16,
    )
    private val curveA = BigInteger(
        "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC",
        16,
    )
    private val curveB = BigInteger(
        "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B",
        16,
    )
    private val generatorX = BigInteger(
        "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296",
        16,
    )
    private val generatorY = BigInteger(
        "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5",
        16,
    )
    internal val p256Order: BigInteger = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        16,
    )

    internal fun publicKeyToX963(publicKey: PublicKey): ByteArray {
        val ecPublicKey = publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("Expected an EC public key")
        requireP256(ecPublicKey.params)

        val point = ecPublicKey.w
        val x = point.affineX ?: throw IllegalArgumentException("P-256 point is at infinity")
        val y = point.affineY ?: throw IllegalArgumentException("P-256 point is at infinity")
        require(x.signum() >= 0 && x < fieldPrime) { "P-256 X coordinate is out of range" }
        require(y.signum() >= 0 && y < fieldPrime) { "P-256 Y coordinate is out of range" }
        require(y.modPow(BigInteger.valueOf(2), fieldPrime) == curveEquation(x)) {
            "Public key is not on P-256"
        }

        return ByteArray(P256_PUBLIC_KEY_BYTES).also { encoded ->
            encoded[0] = 0x04
            fixedWidthUnsigned(x).copyInto(encoded, destinationOffset = 1)
            fixedWidthUnsigned(y).copyInto(encoded, destinationOffset = 1 + P256_COMPONENT_BYTES)
        }
    }

    internal fun derEcdsaToP1363(derSignature: ByteArray): ByteArray {
        require(derSignature.size in 8..MAX_P256_DER_SIGNATURE_BYTES) {
            "ECDSA DER signature has an invalid length"
        }
        val outer = DerReader(derSignature)
        val sequence = outer.readElement(DER_SEQUENCE)
        require(outer.isExhausted) { "Trailing data after ECDSA signature sequence" }

        val components = DerReader(sequence)
        val r = components.readPositiveP256Integer()
        val s = components.readPositiveP256Integer()
        require(components.isExhausted) { "ECDSA signature must contain exactly r and s" }
        return r + s
    }

    internal fun p1363ToDerEcdsa(p1363Signature: ByteArray): ByteArray {
        require(p1363Signature.size == P256_COMPONENT_BYTES * 2) {
            "P-256 P1363 signature must contain exactly 64 bytes"
        }
        val r = p1363Signature.copyOfRange(0, P256_COMPONENT_BYTES)
        val s = p1363Signature.copyOfRange(P256_COMPONENT_BYTES, p1363Signature.size)
        validateP256Scalar(r)
        validateP256Scalar(s)

        val encodedR = encodeDerInteger(r)
        val encodedS = encodeDerInteger(s)
        val body = byteArrayOf(DER_INTEGER.toByte(), encodedR.size.toByte()) + encodedR +
            byteArrayOf(DER_INTEGER.toByte(), encodedS.size.toByte()) + encodedS
        return byteArrayOf(DER_SEQUENCE.toByte(), body.size.toByte()) + body
    }

    private fun requireP256(parameters: ECParameterSpec) {
        val field = parameters.curve.field as? ECFieldFp
            ?: throw IllegalArgumentException("Expected a prime-field EC key")
        require(field.p == fieldPrime) { "Expected the NIST P-256 field" }
        require(parameters.curve.a == curveA && parameters.curve.b == curveB) {
            "Expected the NIST P-256 curve"
        }
        require(
            parameters.generator.affineX == generatorX &&
                parameters.generator.affineY == generatorY,
        ) { "Expected the NIST P-256 generator" }
        require(parameters.order == p256Order && parameters.cofactor == 1) {
            "Expected the NIST P-256 group"
        }
    }

    private fun curveEquation(x: BigInteger): BigInteger =
        x.modPow(BigInteger.valueOf(3), fieldPrime)
            .add(curveA.multiply(x))
            .add(curveB)
            .mod(fieldPrime)

    private fun fixedWidthUnsigned(value: BigInteger): ByteArray {
        val encoded = value.toByteArray()
        val magnitude = if (encoded.size > 1 && encoded.first() == 0.toByte()) {
            encoded.copyOfRange(1, encoded.size)
        } else {
            encoded
        }
        require(magnitude.size <= P256_COMPONENT_BYTES) { "P-256 integer exceeds 32 bytes" }
        return ByteArray(P256_COMPONENT_BYTES - magnitude.size) + magnitude
    }

    private fun validateP256Scalar(component: ByteArray) {
        val value = BigInteger(1, component)
        require(value.signum() > 0 && value < p256Order) {
            "ECDSA component is outside [1, n)"
        }
    }

    private fun encodeDerInteger(fixed: ByteArray): ByteArray {
        val firstNonZero = fixed.indexOfFirst { byte -> byte != 0.toByte() }
        val magnitude = fixed.copyOfRange(firstNonZero, fixed.size)
        return if (magnitude.first().toInt() and 0x80 != 0) {
            byteArrayOf(0) + magnitude
        } else {
            magnitude
        }
    }

    private class DerReader(private val input: ByteArray) {
        private var offset: Int = 0

        val isExhausted: Boolean
            get() = offset == input.size

        fun readElement(expectedTag: Int): ByteArray {
            require(offset < input.size) { "Missing DER tag" }
            val tag = input[offset++].toInt() and 0xff
            require(tag == expectedTag) { "Unexpected DER tag" }
            val length = readLength()
            require(length <= input.size - offset) { "Truncated DER element" }
            return input.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun readPositiveP256Integer(): ByteArray {
            val encoded = readElement(DER_INTEGER)
            require(encoded.size in 1..P256_COMPONENT_BYTES + 1) {
                "ECDSA component exceeds P-256 width"
            }
            require(encoded.first().toInt() and 0x80 == 0) { "Negative DER INTEGER" }
            if (encoded.size > 1 && encoded.first() == 0.toByte()) {
                require(encoded[1].toInt() and 0x80 != 0) {
                    "Redundant DER INTEGER sign byte"
                }
            }

            val magnitude = if (encoded.size > 1 && encoded.first() == 0.toByte()) {
                encoded.copyOfRange(1, encoded.size)
            } else {
                encoded
            }
            require(magnitude.size <= P256_COMPONENT_BYTES) {
                "ECDSA component exceeds P-256 width"
            }
            val fixed = ByteArray(P256_COMPONENT_BYTES - magnitude.size) + magnitude
            validateP256Scalar(fixed)
            return fixed
        }

        private fun readLength(): Int {
            require(offset < input.size) { "Missing DER length" }
            val length = input[offset++].toInt() and 0xff
            require(length < 0x80) { "Non-minimal or unsupported DER length" }
            return length
        }
    }
}
