package org.rotki.mobile.core.decimal

import kotlinx.serialization.Serializable
import org.rotki.mobile.core.decimal.internal.BigNumDecimalBackend

@Serializable(with = ExactDecimalStringSerializer::class)
public class ExactDecimal private constructor(
    private val backend: BigNumDecimalBackend,
) : Comparable<ExactDecimal> {
    public val sign: ExactDecimalSign
        get() =
            when (backend.signum()) {
                -1 -> ExactDecimalSign.NEGATIVE
                0 -> ExactDecimalSign.ZERO
                else -> ExactDecimalSign.POSITIVE
            }

    public val isZero: Boolean
        get() = backend.isZero()

    public val magnitude: ExactDecimal
        get() = ExactDecimal(backend.absoluteValue())

    public val canonicalScale: Int
        get() = backend.canonicalScale()

    public operator fun plus(other: ExactDecimal): ExactDecimal = ExactDecimal(backend.add(other.backend))

    public operator fun minus(other: ExactDecimal): ExactDecimal = ExactDecimal(backend.subtract(other.backend))

    public operator fun times(other: ExactDecimal): ExactDecimal = ExactDecimal(backend.multiply(other.backend))

    public operator fun div(other: ExactDecimal): ExactDecimal = ExactDecimal(backend.divide(other.backend))

    public fun toDisplayString(fractionDigits: Int): String = backend.toDisplayString(fractionDigits)

    public override fun compareTo(other: ExactDecimal): Int = backend.compareTo(other.backend)

    public override fun equals(other: Any?): Boolean = other is ExactDecimal && compareTo(other) == 0

    public override fun hashCode(): Int = backend.hashCode()

    public override fun toString(): String = backend.canonicalString()

    public companion object {
        public val ZERO: ExactDecimal = ExactDecimal(BigNumDecimalBackend.parse("0"))

        public fun parse(value: String): ExactDecimal = ExactDecimal(BigNumDecimalBackend.parse(value))
    }
}

public enum class ExactDecimalSign {
    NEGATIVE,
    ZERO,
    POSITIVE,
}
