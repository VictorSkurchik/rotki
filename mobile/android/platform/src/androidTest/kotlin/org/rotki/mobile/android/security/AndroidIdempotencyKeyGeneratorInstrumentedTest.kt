package org.rotki.mobile.android.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome

@RunWith(AndroidJUnit4::class)
class AndroidIdempotencyKeyGeneratorInstrumentedTest {
    @Test
    fun androidSecureRandomProducesFreshCanonicalRedactedKeys() {
        val generator = createAndroidIdempotencyKeyGenerator()
        val generated = List(GENERATED_KEY_COUNT) { generator.generate() }

        assertEquals(GENERATED_KEY_COUNT, generated.toSet().size)
        generated.forEach { key ->
            assertEquals(ENCODED_KEY_LENGTH, key.encoded.length)
            assertEquals(key, requireParsed(key.encoded))
            assertEquals("IdempotencyKey(redacted)", key.toString())
        }
        assertEquals("AndroidIdempotencyKeyGenerator(redacted)", generator.toString())
        assertTrue(generated.none { key -> generator.toString().contains(key.encoded) })
    }

    @Test
    fun oneGeneratorRemainsCorrectUnderConcurrentCalls() =
        runBlocking {
            val generator = createAndroidIdempotencyKeyGenerator()
            val generated =
                List(CONCURRENT_KEY_COUNT) {
                    async(Dispatchers.Default) { generator.generate() }
                }.awaitAll()

            assertEquals(CONCURRENT_KEY_COUNT, generated.toSet().size)
            generated.forEach { key ->
                assertEquals(key, requireParsed(key.encoded))
                assertFalse(key.encoded.contains('='))
            }
        }

    private fun requireParsed(encoded: String): IdempotencyKey =
        when (val parsed = IdempotencyKey.parse(encoded)) {
            is ProtocolValueParseOutcome.Accepted -> {
                parsed.value
            }

            is ProtocolValueParseOutcome.Rejected -> {
                throw AssertionError("Generated key was rejected: ${parsed.reason}")
            }
        }

    private companion object {
        const val GENERATED_KEY_COUNT = 128
        const val CONCURRENT_KEY_COUNT = 64
        const val ENCODED_KEY_LENGTH = 22
    }
}
