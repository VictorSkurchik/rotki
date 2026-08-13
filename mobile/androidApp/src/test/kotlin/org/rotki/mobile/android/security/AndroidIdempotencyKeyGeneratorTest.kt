package org.rotki.mobile.android.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidIdempotencyKeyGeneratorTest {
    @Test
    fun `fills exactly sixteen random bytes and validates the protocol key`(): Unit {
        val source = ByteArray(16) { index -> index.toByte() }
        var capturedBuffer: ByteArray? = null
        val generator = AndroidIdempotencyKeyGenerator(
            randomFill = IdempotencyRandomFill { target ->
                assertEquals(16, target.size)
                source.copyInto(target)
                capturedBuffer = target
            },
        )

        val generated = generator.generate()

        assertEquals(acceptedKey(source), generated)
        assertNotNull(capturedBuffer)
        assertArrayEquals(ByteArray(16), capturedBuffer)
        assertEquals("AndroidIdempotencyKeyGenerator(redacted)", generator.toString())
        assertEquals("IdempotencyKey(redacted)", generated.toString())
        assertFalse(generator.toString().contains(generated.encoded))
    }

    @Test
    fun `uses a fresh cleared buffer for every key`(): Unit {
        val invocation = AtomicInteger()
        val capturedBuffers = mutableListOf<ByteArray>()
        val generator = AndroidIdempotencyKeyGenerator(
            randomFill = IdempotencyRandomFill { target ->
                capturedBuffers += target
                target.fill((invocation.incrementAndGet()).toByte())
            },
        )

        val first = generator.generate()
        val second = generator.generate()

        assertNotEquals(first, second)
        assertEquals(2, capturedBuffers.size)
        assertFalse(capturedBuffers[0] === capturedBuffers[1])
        capturedBuffers.forEach { buffer ->
            assertArrayEquals(ByteArray(16), buffer)
        }
    }

    @Test
    fun `sanitizes random source failures and clears partially filled bytes`(): Unit {
        var capturedBuffer: ByteArray? = null
        val generator = AndroidIdempotencyKeyGenerator(
            randomFill = IdempotencyRandomFill { target ->
                target.fill(0x5a)
                capturedBuffer = target
                throw IllegalArgumentException(SEEDED_SECRET)
            },
        )

        var capturedFailure: IllegalStateException? = null
        try {
            generator.generate()
        } catch (failure: IllegalStateException) {
            capturedFailure = failure
        }
        val failure = capturedFailure ?: error("Expected generation to fail")

        assertEquals("Unable to generate idempotency key", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(SEEDED_SECRET))
        assertNotNull(capturedBuffer)
        assertArrayEquals(ByteArray(16), capturedBuffer)
    }

    @Test
    fun `serializes concurrent access to the random fill seam`(): Unit {
        val firstFillEntered = CountDownLatch(1)
        val releaseFirstFill = CountDownLatch(1)
        val secondCallStarted = CountDownLatch(1)
        val invocation = AtomicInteger()
        val activeFills = AtomicInteger()
        val overlappingFill = AtomicBoolean(false)
        val secondThread = AtomicReference<Thread>()
        val generator = AndroidIdempotencyKeyGenerator(
            randomFill = IdempotencyRandomFill { target ->
                if (activeFills.incrementAndGet() != 1) {
                    overlappingFill.set(true)
                }
                try {
                    val call = invocation.incrementAndGet()
                    if (call == 1) {
                        firstFillEntered.countDown()
                        assertTrue(releaseFirstFill.await(5, TimeUnit.SECONDS))
                    }
                    target.fill(call.toByte())
                } finally {
                    activeFills.decrementAndGet()
                }
            },
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<IdempotencyKey> { generator.generate() }
            assertTrue(firstFillEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit<IdempotencyKey> {
                secondThread.set(Thread.currentThread())
                secondCallStarted.countDown()
                generator.generate()
            }
            assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS))
            assertTrue(awaitBlocked(secondThread.get() ?: error("Missing second thread")))

            releaseFirstFill.countDown()

            assertNotEquals(
                first.get(5, TimeUnit.SECONDS),
                second.get(5, TimeUnit.SECONDS),
            )
            assertFalse(overlappingFill.get())
            assertEquals(2, invocation.get())
        } finally {
            releaseFirstFill.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

private fun acceptedKey(bytes: ByteArray): IdempotencyKey =
    when (val parsed = IdempotencyKey.fromBytes(bytes)) {
        is ProtocolValueParseOutcome.Accepted -> parsed.value
        is ProtocolValueParseOutcome.Rejected -> error("Expected a valid fixture key")
    }

private fun awaitBlocked(thread: Thread): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        if (thread.state == Thread.State.BLOCKED) return true
        Thread.onSpinWait()
    }
    return false
}

private const val SEEDED_SECRET: String = "seeded-random-source-secret"
