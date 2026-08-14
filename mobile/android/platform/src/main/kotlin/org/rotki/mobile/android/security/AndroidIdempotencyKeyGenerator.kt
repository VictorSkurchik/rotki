package org.rotki.mobile.android.security

import org.rotki.mobile.core.ports.IdempotencyKeyGenerator
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import java.security.SecureRandom

/**
 * Creates an idempotency-key generator backed by one process-local secure-random source.
 *
 * The adapter retains no Context or Activity. The application should retain the returned port for
 * its process lifetime so concurrent callers share the same guarded random source.
 */
public fun createAndroidIdempotencyKeyGenerator(): IdempotencyKeyGenerator =
    DefaultAndroidIdempotencyKeyGenerator(SecureRandom())

private class DefaultAndroidIdempotencyKeyGenerator(
    private val secureRandom: SecureRandom,
) : IdempotencyKeyGenerator {
    private val randomFillLock: Any = Any()

    override fun generate(): IdempotencyKey {
        val bytes = ByteArray(IDEMPOTENCY_KEY_BYTE_COUNT)
        return try {
            try {
                synchronized(randomFillLock) {
                    secureRandom.nextBytes(bytes)
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

    private companion object {
        const val IDEMPOTENCY_KEY_BYTE_COUNT: Int = 16
        const val GENERATION_FAILURE_MESSAGE: String = "Unable to generate idempotency key"
    }
}
