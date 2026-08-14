package org.rotki.mobile.android.security

import org.rotki.mobile.core.ports.IdempotencyKeyGenerator
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import java.security.SecureRandom

internal fun interface IdempotencyRandomFill {
    fun fill(target: ByteArray): Unit
}

/** Generates protocol idempotency keys from Android's process-local secure random source. */
internal class AndroidIdempotencyKeyGenerator(
    private val randomFill: IdempotencyRandomFill = defaultIdempotencyRandomFill(),
) : IdempotencyKeyGenerator {
    private val randomFillLock: Any = Any()

    override fun generate(): IdempotencyKey {
        val bytes = ByteArray(IDEMPOTENCY_KEY_BYTE_COUNT)
        return try {
            try {
                synchronized(randomFillLock) {
                    randomFill.fill(bytes)
                }
            } catch (_: Exception) {
                error(GENERATION_FAILURE_MESSAGE)
            }

            when (val parsed = IdempotencyKey.fromBytes(bytes)) {
                is ProtocolValueParseOutcome.Accepted -> {
                    parsed.value
                }

                is ProtocolValueParseOutcome.Rejected -> {
                    error(GENERATION_FAILURE_MESSAGE)
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    override fun toString(): String = "AndroidIdempotencyKeyGenerator(redacted)"
}

private fun defaultIdempotencyRandomFill(): IdempotencyRandomFill {
    val secureRandom = SecureRandom()
    return IdempotencyRandomFill(secureRandom::nextBytes)
}

private const val IDEMPOTENCY_KEY_BYTE_COUNT: Int = 16
private const val GENERATION_FAILURE_MESSAGE: String = "Unable to generate idempotency key"
