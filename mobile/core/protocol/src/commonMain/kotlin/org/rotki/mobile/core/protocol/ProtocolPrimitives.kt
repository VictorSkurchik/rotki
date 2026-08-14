@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.core.protocol

import com.ionspin.kotlin.bignum.integer.BigInteger
import org.rotki.mobile.core.protocol.generated.ProtocolEncodedLengths
import kotlin.io.encoding.Base64
import kotlin.io.encoding.Base64.PaddingOption
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.native.HiddenFromObjC

public sealed interface ProtocolValueParseOutcome<out T> {
    public data class Accepted<T>(
        public val value: T,
    ) : ProtocolValueParseOutcome<T>

    public data class Rejected(
        public val reason: ProtocolValueRejection,
    ) : ProtocolValueParseOutcome<Nothing>
}

public enum class ProtocolValueRejection {
    EMPTY,
    INVALID_ALPHABET,
    INVALID_LENGTH,
    NON_CANONICAL,
    INVALID_PUBLIC_KEY,
    INVALID_SIGNATURE,
}

public class DeviceSessionId private constructor(
    public val encoded: String,
) {
    public override fun equals(other: Any?): Boolean = other is DeviceSessionId && encoded == other.encoded

    public override fun hashCode(): Int = encoded.hashCode()

    public override fun toString(): String = "DeviceSessionId(redacted)"

    @HiddenFromObjC
    public fun bytesCopy(): ByteArray = decodeCanonicalBase64Url(encoded)

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<DeviceSessionId> =
            parseFixedBase64Url(candidate, ProtocolEncodedLengths.DeviceSessionId, 32) { encoded ->
                DeviceSessionId(encoded)
            }
    }
}

public class IdempotencyKey private constructor(
    public val encoded: String,
) {
    public override fun equals(other: Any?): Boolean = other is IdempotencyKey && encoded == other.encoded

    public override fun hashCode(): Int = encoded.hashCode()

    public override fun toString(): String = "IdempotencyKey(redacted)"

    internal fun bytesCopy(): ByteArray = decodeCanonicalBase64Url(encoded)

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<IdempotencyKey> =
            parseFixedBase64Url(candidate, ProtocolEncodedLengths.IdempotencyKey, 16) { encoded ->
                IdempotencyKey(encoded)
            }

        public fun fromBytes(bytes: ByteArray): ProtocolValueParseOutcome<IdempotencyKey> =
            if (bytes.size != 16) {
                ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.INVALID_LENGTH)
            } else {
                ProtocolValueParseOutcome.Accepted(IdempotencyKey(encodeBase64Url(bytes)))
            }
    }
}

public class P1363Signature private constructor(
    public val encoded: String,
) {
    public override fun equals(other: Any?): Boolean = other is P1363Signature && encoded == other.encoded

    public override fun hashCode(): Int = encoded.hashCode()

    public override fun toString(): String = "P1363Signature(redacted)"

    internal fun bytesCopy(): ByteArray = decodeCanonicalBase64Url(encoded)

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<P1363Signature> =
            when (
                val parsed =
                    parseFixedBase64Url(
                        candidate,
                        ProtocolEncodedLengths.P1363Signature,
                        64,
                        ::P1363Signature,
                    )
            ) {
                is ProtocolValueParseOutcome.Accepted -> {
                    val bytes = parsed.value.bytesCopy()
                    if (isValidP256Scalar(bytes.copyOfRange(0, 32)) &&
                        isValidP256Scalar(bytes.copyOfRange(32, 64))
                    ) {
                        parsed
                    } else {
                        ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.INVALID_SIGNATURE)
                    }
                }

                is ProtocolValueParseOutcome.Rejected -> {
                    parsed
                }
            }

        public fun fromBytes(bytes: ByteArray): ProtocolValueParseOutcome<P1363Signature> =
            if (bytes.size != 64) {
                ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.INVALID_LENGTH)
            } else {
                parse(encodeBase64Url(bytes))
            }
    }
}

public class X963PublicKey private constructor(
    public val encoded: String,
) {
    public override fun equals(other: Any?): Boolean = other is X963PublicKey && encoded == other.encoded

    public override fun hashCode(): Int = encoded.hashCode()

    public override fun toString(): String = "X963PublicKey(redacted)"

    internal fun bytesCopy(): ByteArray = decodeCanonicalBase64Url(encoded)

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<X963PublicKey> =
            when (
                val parsed =
                    parseFixedBase64Url(
                        candidate,
                        ProtocolEncodedLengths.P256PublicKey,
                        65,
                        ::X963PublicKey,
                    )
            ) {
                is ProtocolValueParseOutcome.Accepted -> {
                    if (isValidUncompressedP256PublicKey(parsed.value.bytesCopy())) {
                        parsed
                    } else {
                        ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.INVALID_PUBLIC_KEY)
                    }
                }

                is ProtocolValueParseOutcome.Rejected -> {
                    parsed
                }
            }

        public fun fromBytes(bytes: ByteArray): ProtocolValueParseOutcome<X963PublicKey> =
            if (bytes.size != 65) {
                ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.INVALID_LENGTH)
            } else {
                parse(encodeBase64Url(bytes))
            }
    }
}

@HiddenFromObjC
public class PairingId private constructor(
    public val encoded: String,
) {
    override fun toString(): String = "PairingId(redacted)"

    internal fun bytesCopy(): ByteArray = decodeCanonicalBase64Url(encoded)

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<PairingId> =
            parseFixedBase64Url(candidate, ProtocolEncodedLengths.PairingId, 16, ::PairingId)
    }
}

@HiddenFromObjC
public class PairingCredential private constructor(
    public val encoded: String,
) {
    override fun toString(): String = "PairingCredential(redacted)"

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<PairingCredential> =
            parseFixedBase64Url(
                candidate,
                ProtocolEncodedLengths.PairingCredential,
                32,
                ::PairingCredential,
            )
    }
}

@HiddenFromObjC
public class ChallengeId private constructor(
    public val encoded: String,
) {
    override fun toString(): String = "ChallengeId(redacted)"

    public fun bytesCopy(): ByteArray = decodeCanonicalBase64Url(encoded)

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<ChallengeId> =
            parseFixedBase64Url(candidate, ProtocolEncodedLengths.ChallengeId, 16, ::ChallengeId)
    }
}

@HiddenFromObjC
public class ChallengeNonce private constructor(
    internal val encoded: String,
) {
    override fun toString(): String = "ChallengeNonce(redacted)"

    public fun bytesCopy(): ByteArray = decodeCanonicalBase64Url(encoded)

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<ChallengeNonce> =
            parseFixedBase64Url(candidate, ProtocolEncodedLengths.Nonce, 32, ::ChallengeNonce)
    }
}

@HiddenFromObjC
public class AccessSessionCredential private constructor(
    internal val encoded: String,
) {
    override fun toString(): String = "AccessSessionCredential(redacted)"

    public companion object {
        public fun parse(candidate: String): ProtocolValueParseOutcome<AccessSessionCredential> =
            parseFixedBase64Url(
                candidate,
                ProtocolEncodedLengths.AccessSessionCredential,
                32,
                ::AccessSessionCredential,
            )
    }
}

private inline fun <T> parseFixedBase64Url(
    candidate: String,
    encodedLength: Int,
    byteLength: Int,
    construct: (String) -> T,
): ProtocolValueParseOutcome<T> {
    if (candidate.isEmpty()) {
        return ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.EMPTY)
    }
    if (candidate.length != encodedLength) {
        return ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.INVALID_LENGTH)
    }
    if (candidate.any { character -> !character.isBase64UrlCharacter() }) {
        return ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.INVALID_ALPHABET)
    }
    val decoded =
        try {
            decodeCanonicalBase64Url(candidate)
        } catch (_: IllegalArgumentException) {
            return ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.NON_CANONICAL)
        }
    if (decoded.size != byteLength || encodeBase64Url(decoded) != candidate) {
        return ProtocolValueParseOutcome.Rejected(ProtocolValueRejection.NON_CANONICAL)
    }
    return ProtocolValueParseOutcome.Accepted(construct(candidate))
}

@HiddenFromObjC
public fun isCanonicalFixedBase64Url(
    candidate: String,
    encodedLength: Int,
    byteLength: Int,
): Boolean =
    parseFixedBase64Url(candidate, encodedLength, byteLength) { Unit } is
        ProtocolValueParseOutcome.Accepted

private fun Char.isBase64UrlCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' || this == '_'

@OptIn(ExperimentalEncodingApi::class)
private fun decodeCanonicalBase64Url(encoded: String): ByteArray =
    Base64.UrlSafe.withPadding(PaddingOption.ABSENT_OPTIONAL).decode(encoded)

@OptIn(ExperimentalEncodingApi::class)
private fun encodeBase64Url(bytes: ByteArray): String = Base64.UrlSafe.withPadding(PaddingOption.ABSENT).encode(bytes)

private fun isValidP256Scalar(bytes: ByteArray): Boolean {
    if (bytes.size != 32 || bytes.all { byte -> byte == 0.toByte() }) {
        return false
    }
    for (index in bytes.indices) {
        val candidate = bytes[index].toInt() and 0xff
        val order = P256_ORDER[index].toInt() and 0xff
        if (candidate < order) return true
        if (candidate > order) return false
    }
    return false
}

private fun isValidUncompressedP256PublicKey(bytes: ByteArray): Boolean {
    if (bytes.size != 65 || bytes.first() != UNCOMPRESSED_POINT_PREFIX) return false
    val x = bytes.copyOfRange(1, 33).toUnsignedBigInteger()
    val y = bytes.copyOfRange(33, 65).toUnsignedBigInteger()
    if (x >= P256_FIELD_PRIME || y >= P256_FIELD_PRIME) return false

    val left = (y * y).mod(P256_FIELD_PRIME)
    val right = positiveMod(x * x * x - x * 3 + P256_CURVE_B, P256_FIELD_PRIME)
    return left == right
}

private fun positiveMod(
    value: BigInteger,
    modulus: BigInteger,
): BigInteger {
    val remainder = value % modulus
    return if (remainder.signum() < 0) remainder + modulus else remainder
}

private fun ByteArray.toUnsignedBigInteger(): BigInteger =
    BigInteger.parseString(
        joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') },
        16,
    )

private const val UNCOMPRESSED_POINT_PREFIX: Byte = 0x04

private val P256_FIELD_PRIME: BigInteger =
    BigInteger.parseString(
        "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
        16,
    )
private val P256_CURVE_B: BigInteger =
    BigInteger.parseString(
        "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
        16,
    )

private val P256_ORDER: ByteArray =
    byteArrayOf(
        0xff.toByte(),
        0xff.toByte(),
        0xff.toByte(),
        0xff.toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
        0xff.toByte(),
        0xff.toByte(),
        0xff.toByte(),
        0xff.toByte(),
        0xff.toByte(),
        0xff.toByte(),
        0xff.toByte(),
        0xff.toByte(),
        0xbc.toByte(),
        0xe6.toByte(),
        0xfa.toByte(),
        0xad.toByte(),
        0xa7.toByte(),
        0x17,
        0x9e.toByte(),
        0x84.toByte(),
        0xf3.toByte(),
        0xb9.toByte(),
        0xca.toByte(),
        0xc2.toByte(),
        0xfc.toByte(),
        0x63,
        0x25,
        0x51,
    )
