package org.rotki.mobile.core.decimal.internal

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.math.absoluteValue

internal class BigNumDecimalBackend private constructor(
    private val value: BigDecimal,
) : Comparable<BigNumDecimalBackend> {
    internal fun add(other: BigNumDecimalBackend): BigNumDecimalBackend =
        checked(value.add(other.value, DecimalMode.DEFAULT))

    internal fun subtract(other: BigNumDecimalBackend): BigNumDecimalBackend =
        checked(value.subtract(other.value, DecimalMode.DEFAULT))

    internal fun multiply(other: BigNumDecimalBackend): BigNumDecimalBackend =
        checked(value.multiply(other.value, DecimalMode.DEFAULT))

    internal fun divide(other: BigNumDecimalBackend): BigNumDecimalBackend {
        if (other.isZero()) {
            fail(ExactDecimalErrorCode.DIVISION_BY_ZERO)
        }
        return checked(divideHalfEven(value, other.value), enforcePrecision = false)
    }

    internal fun absoluteValue(): BigNumDecimalBackend = checked(value.abs())

    internal fun signum(): Int = value.signum()

    internal fun isZero(): Boolean = value.isZero()

    internal fun canonicalString(): String = canonicalize(value)

    internal fun canonicalScale(): Int = canonicalString().substringAfter('.', missingDelimiterValue = "").length

    internal fun toDisplayString(fractionDigits: Int): String {
        if (fractionDigits !in 0..MAX_DISPLAY_FRACTION_DIGITS) {
            fail(ExactDecimalErrorCode.INVALID_SCALE)
        }
        val rounded =
            value.roundToDigitPositionAfterDecimalPoint(
                fractionDigits.toLong(),
                RoundingMode.ROUND_HALF_TO_EVEN,
            )
        val canonical = canonicalize(rounded)
        if (fractionDigits == 0) {
            return canonical.substringBefore('.')
        }
        val integer = canonical.substringBefore('.')
        val fraction = canonical.substringAfter('.', missingDelimiterValue = "")
        return "$integer.${fraction.padEnd(fractionDigits, '0')}"
    }

    override fun compareTo(other: BigNumDecimalBackend): Int = value.compare(other.value)

    override fun equals(other: Any?): Boolean = other is BigNumDecimalBackend && compareTo(other) == 0

    override fun hashCode(): Int = value.hashCode()

    internal companion object {
        private const val MAX_SIGNIFICANT_DIGITS: Int = 78
        private const val MAX_ABSOLUTE_EXPONENT: Long = 1024
        private const val MAX_INPUT_LENGTH: Int = 2048
        private const val MAX_CANONICAL_LENGTH: Int = 2048
        private const val MAX_DISPLAY_FRACTION_DIGITS: Int = 30

        private val DECIMAL_PATTERN =
            Regex(
                pattern = "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?",
            )
        private val NON_FINITE_PATTERN =
            Regex(
                pattern = "[+-]?(?:nan|snan|inf|infinity)",
                option = RegexOption.IGNORE_CASE,
            )

        internal fun parse(raw: String): BigNumDecimalBackend {
            if (raw.length > MAX_INPUT_LENGTH) {
                fail(ExactDecimalErrorCode.RANGE_EXCEEDED)
            }
            if (NON_FINITE_PATTERN.matches(raw)) {
                fail(ExactDecimalErrorCode.NON_FINITE)
            }
            if (!DECIMAL_PATTERN.matches(raw)) {
                fail(ExactDecimalErrorCode.INVALID_SYNTAX)
            }
            enforceExponentRange(raw)

            val parsed =
                try {
                    BigDecimal.parseStringWithMode(raw)
                } catch (_: ArithmeticException) {
                    fail(ExactDecimalErrorCode.INVALID_SYNTAX)
                }
            return checked(parsed)
        }

        private fun enforceExponentRange(raw: String) {
            val marker = raw.indexOfFirst { it == 'e' || it == 'E' }
            if (marker < 0) {
                return
            }
            val exponent =
                raw.substring(marker + 1).toLongOrNull()
                    ?: fail(ExactDecimalErrorCode.RANGE_EXCEEDED)
            if (exponent == Long.MIN_VALUE || exponent.absoluteValue > MAX_ABSOLUTE_EXPONENT) {
                fail(ExactDecimalErrorCode.RANGE_EXCEEDED)
            }
        }

        private fun checked(
            number: BigDecimal,
            enforcePrecision: Boolean = true,
        ): BigNumDecimalBackend {
            val canonical = canonicalize(number)
            if (enforcePrecision && normalizedSignificantDigits(canonical) > MAX_SIGNIFICANT_DIGITS) {
                fail(ExactDecimalErrorCode.PRECISION_EXCEEDED)
            }
            if (canonical.length > MAX_CANONICAL_LENGTH) {
                fail(ExactDecimalErrorCode.RANGE_EXCEEDED)
            }
            return BigNumDecimalBackend(number)
        }

        /**
         * BigNum 0.3.10 compares the raw integer remainder with decimal digit 5 when
         * rounding division. That truncates 2 / 3 instead of rounding its final digit up.
         * Keep this candidate bug behind the backend and calculate the 78-digit quotient
         * from the exact integer ratio instead.
         */
        private fun divideHalfEven(
            dividend: BigDecimal,
            divisor: BigDecimal,
        ): BigDecimal {
            if (dividend.isZero()) {
                return BigDecimal.ZERO
            }
            val numerator = normalizedSignificand(dividend)
            val denominator = normalizedSignificand(divisor)
            val numeratorDigits = numerator.numberOfDecimalDigits()
            val denominatorDigits = denominator.numberOfDecimalDigits()
            val digitDelta = numeratorDigits - denominatorDigits
            val normalizedComparison =
                if (digitDelta >= 0) {
                    numerator.compare(denominator * BigInteger.TEN.pow(digitDelta))
                } else {
                    (numerator * BigInteger.TEN.pow(-digitDelta)).compare(denominator)
                }
            val ratioExponent = if (normalizedComparison < 0) digitDelta - 1 else digitDelta
            val scalePower = MAX_SIGNIFICANT_DIGITS.toLong() - 1 - ratioExponent
            check(scalePower >= 0) { "ExactDecimal division scale unexpectedly became negative" }

            val scaledNumerator = numerator * BigInteger.TEN.pow(scalePower)
            val quotientAndRemainder = scaledNumerator divrem denominator
            var quotient = quotientAndRemainder.quotient
            val remainderComparison =
                (quotientAndRemainder.remainder * BigInteger.TWO)
                    .compare(denominator)
            val quotientIsOdd = !(quotient % BigInteger.TWO).isZero()
            if (remainderComparison > 0 || (remainderComparison == 0 && quotientIsOdd)) {
                quotient++
            }

            val decimalPower =
                dividend.exponent - numeratorDigits + 1 -
                    (divisor.exponent - denominatorDigits + 1)
            var resultExponent = ratioExponent + decimalPower
            if (quotient.numberOfDecimalDigits() > MAX_SIGNIFICANT_DIGITS) {
                quotient /= BigInteger.TEN
                resultExponent++
            }
            if (dividend.signum() != divisor.signum()) {
                quotient = -quotient
            }
            return BigDecimal.fromBigIntegerWithExponent(quotient, resultExponent)
        }

        private fun normalizedSignificand(number: BigDecimal): BigInteger {
            var significand = number.significand.abs()
            while (!significand.isZero() && (significand % BigInteger.TEN).isZero()) {
                significand /= BigInteger.TEN
            }
            return significand
        }

        private fun canonicalize(number: BigDecimal): String {
            val expanded = number.toStringExpanded()
            if (expanded == "-0") {
                return "0"
            }
            if ('.' !in expanded) {
                return expanded
            }
            return expanded.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
        }

        private fun normalizedSignificantDigits(canonical: String): Int {
            val digits = canonical.filter { it in '0'..'9' }.trim('0')
            return if (digits.isEmpty()) 1 else digits.length
        }

        private fun fail(code: ExactDecimalErrorCode): Nothing = throw ExactDecimalException(code)
    }
}

internal enum class ExactDecimalErrorCode(
    internal val wireValue: String,
) {
    DIVISION_BY_ZERO("division_by_zero"),
    INVALID_SCALE("invalid_scale"),
    INVALID_SYNTAX("invalid_syntax"),
    NON_FINITE("non_finite"),
    PRECISION_EXCEEDED("precision_exceeded"),
    RANGE_EXCEEDED("range_exceeded"),
}

internal class ExactDecimalException(
    internal val code: ExactDecimalErrorCode,
) : IllegalArgumentException(code.wireValue)
