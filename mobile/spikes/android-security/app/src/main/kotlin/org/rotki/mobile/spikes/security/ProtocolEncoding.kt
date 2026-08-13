package org.rotki.mobile.spikes.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.Locale

/** Strict encodings at the frozen Companion Protocol device-proof boundary. */
object ProtocolEncoding {
    private const val DER_SEQUENCE = 0x30
    private const val DER_INTEGER = 0x02
    private const val P256_COMPONENT_BYTES = 32
    private val proofDomain = "rotki-companion-device-proof/v1".toByteArray(StandardCharsets.US_ASCII)

    internal val p256Order: BigInteger = BigInteger(
        "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551",
        16,
    )

    fun encodeDeviceProofTranscript(
        canonicalEngineOrigin: String,
        deviceSessionId: ByteArray,
        challengeId: ByteArray,
        nonce: ByteArray,
        expiresAt: ULong,
    ): ByteArray {
        requireCanonicalEngineOrigin(canonicalEngineOrigin)
        require(deviceSessionId.size == 32) { "Device Session ID must contain 32 bytes" }
        require(challengeId.size == 16) { "Challenge ID must contain 16 bytes" }
        require(nonce.size == 32) { "Nonce must contain 32 bytes" }

        val origin = canonicalEngineOrigin.toByteArray(StandardCharsets.UTF_8)
        require(origin.size <= 0xffff) { "Engine origin exceeds the uint16 length prefix" }

        return ByteArrayOutputStream(
            proofDomain.size + 1 + 2 + origin.size + deviceSessionId.size +
                challengeId.size + nonce.size + ULong.SIZE_BYTES,
        ).use { output ->
            output.write(proofDomain)
            output.write(0)
            output.write(origin.size ushr 8)
            output.write(origin.size)
            output.write(origin)
            output.write(deviceSessionId)
            output.write(challengeId)
            output.write(nonce)
            for (shift in 56 downTo 0 step 8) {
                output.write((expiresAt shr shift).toInt())
            }
            output.toByteArray()
        }
    }

    fun p256PublicKeyToSec1(publicKey: PublicKey): ByteArray {
        val ecPublicKey = publicKey as? ECPublicKey
            ?: throw IllegalArgumentException("Expected an EC public key")
        require(ecPublicKey.params.curve.field.fieldSize == 256) { "Expected a 256-bit EC field" }
        require(ecPublicKey.params.order == p256Order) { "Expected the NIST P-256 curve" }

        return byteArrayOf(0x04) +
            fixedWidthUnsigned(ecPublicKey.w.affineX, P256_COMPONENT_BYTES) +
            fixedWidthUnsigned(ecPublicKey.w.affineY, P256_COMPONENT_BYTES)
    }

    fun derEcdsaToP1363(derSignature: ByteArray): ByteArray {
        val outer = DerReader(derSignature)
        val sequence = outer.readElement(DER_SEQUENCE)
        require(outer.isExhausted) { "Trailing data after ECDSA signature sequence" }

        val components = DerReader(sequence)
        val r = components.readPositiveP256Integer()
        val s = components.readPositiveP256Integer()
        require(components.isExhausted) { "ECDSA signature must contain exactly r and s" }
        return r + s
    }

    fun p1363ToDerEcdsa(p1363Signature: ByteArray): ByteArray {
        require(p1363Signature.size == P256_COMPONENT_BYTES * 2) {
            "P-256 P1363 signature must contain exactly 64 bytes"
        }
        val r = p1363Signature.copyOfRange(0, P256_COMPONENT_BYTES)
        val s = p1363Signature.copyOfRange(P256_COMPONENT_BYTES, p1363Signature.size)
        validateP256Component(r)
        validateP256Component(s)

        val encodedR = encodeDerInteger(r)
        val encodedS = encodeDerInteger(s)
        val body = byteArrayOf(DER_INTEGER.toByte(), encodedR.size.toByte()) + encodedR +
            byteArrayOf(DER_INTEGER.toByte(), encodedS.size.toByte()) + encodedS
        return byteArrayOf(DER_SEQUENCE.toByte(), body.size.toByte()) + body
    }

    fun encodeBase64UrlNoPadding(value: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    fun decodeCanonicalBase64Url(value: String): ByteArray {
        require(value.isNotEmpty()) { "Base64URL value must not be empty" }
        require(value.none { it == '=' || it == '+' || it == '/' }) {
            "Base64URL must be unpadded and use the URL-safe alphabet"
        }
        require(value.all { it.isAsciiLetterOrDigit() || it == '-' || it == '_' }) {
            "Base64URL contains an invalid character"
        }
        val decoded = try {
            Base64.getUrlDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed Base64URL", error)
        }
        require(encodeBase64UrlNoPadding(decoded) == value) { "Non-canonical Base64URL" }
        return decoded
    }

    private fun requireCanonicalEngineOrigin(value: String) {
        require(value.toByteArray(StandardCharsets.US_ASCII).toString(StandardCharsets.US_ASCII) == value) {
            "Engine origin must be ASCII"
        }
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed Engine origin", error)
        }
        require(uri.scheme == "https") { "Engine origin must use lowercase https" }
        require(uri.host != null) { "Engine origin must contain a host" }
        require(uri.host == uri.host.lowercase(Locale.ROOT)) { "Engine host must be lowercase" }
        require(uri.rawUserInfo == null) { "Engine origin must not contain userinfo" }
        require(uri.rawPath.isNullOrEmpty()) { "Engine origin must not contain a path" }
        require(uri.rawQuery == null) { "Engine origin must not contain a query" }
        require(uri.rawFragment == null) { "Engine origin must not contain a fragment" }
        require(uri.port != 443) { "Canonical HTTPS origin omits the default port" }
        require(uri.toASCIIString() == value) { "Engine origin is not canonical ASCII" }
    }

    private fun encodeDerInteger(fixed: ByteArray): ByteArray {
        var firstNonZero = fixed.indexOfFirst { it.toInt() != 0 }
        if (firstNonZero == -1) {
            firstNonZero = fixed.lastIndex
        }
        val magnitude = fixed.copyOfRange(firstNonZero, fixed.size)
        return if (magnitude.first().toInt() and 0x80 != 0) byteArrayOf(0) + magnitude else magnitude
    }

    private fun fixedWidthUnsigned(value: BigInteger, width: Int): ByteArray {
        require(value.signum() >= 0) { "Expected an unsigned integer" }
        val encoded = value.toByteArray()
        val magnitude = if (encoded.size > 1 && encoded.first().toInt() == 0) {
            encoded.copyOfRange(1, encoded.size)
        } else {
            encoded
        }
        require(magnitude.size <= width) { "Integer does not fit in $width bytes" }
        return ByteArray(width - magnitude.size) + magnitude
    }

    private fun validateP256Component(component: ByteArray) {
        val value = BigInteger(1, component)
        require(value.signum() > 0 && value < p256Order) { "ECDSA component is outside [1, n)" }
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private class DerReader(private val input: ByteArray) {
        private var offset = 0

        val isExhausted: Boolean
            get() = offset == input.size

        fun readElement(expectedTag: Int): ByteArray {
            require(offset < input.size && input[offset++].toInt() and 0xff == expectedTag) {
                "Unexpected DER tag"
            }
            val length = readLength()
            require(length <= input.size - offset) { "Truncated DER element" }
            return input.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun readPositiveP256Integer(): ByteArray {
            val encoded = readElement(DER_INTEGER)
            require(encoded.isNotEmpty()) { "Empty DER INTEGER" }
            require(encoded.first().toInt() and 0x80 == 0) { "Negative DER INTEGER" }
            if (encoded.size > 1 && encoded.first().toInt() == 0) {
                require(encoded[1].toInt() and 0x80 != 0) { "Redundant DER INTEGER sign byte" }
            }

            val magnitude = if (encoded.size > 1 && encoded.first().toInt() == 0) {
                encoded.copyOfRange(1, encoded.size)
            } else {
                encoded
            }
            require(magnitude.size <= P256_COMPONENT_BYTES) { "ECDSA component exceeds P-256 width" }
            val fixed = ByteArray(P256_COMPONENT_BYTES - magnitude.size) + magnitude
            validateP256Component(fixed)
            return fixed
        }

        private fun readLength(): Int {
            require(offset < input.size) { "Missing DER length" }
            val first = input[offset++].toInt() and 0xff
            if (first < 0x80) {
                return first
            }

            val octets = first and 0x7f
            require(octets in 1..4) { "Invalid DER length width" }
            require(octets <= input.size - offset) { "Truncated DER length" }
            require(input[offset].toInt() != 0) { "Non-minimal DER length" }
            var length = 0
            repeat(octets) {
                length = (length shl 8) or (input[offset++].toInt() and 0xff)
            }
            require(length >= 0x80) { "Long-form DER length was not required" }
            return length
        }
    }
}
