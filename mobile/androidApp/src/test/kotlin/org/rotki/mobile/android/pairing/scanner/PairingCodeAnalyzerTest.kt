package org.rotki.mobile.android.pairing.scanner

import androidx.camera.core.ImageProxy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class PairingCodeAnalyzerTest {
    @Test
    fun `keeps frame open until asynchronous decoding completes`() {
        val decoder = FakeFrameDecoder()
        val fixture = Fixture(decoder)

        fixture.analyzer.analyze(fixture.image)

        assertEquals(0, fixture.closeCount)
        decoder.complete(PairingFrameDecodeResult.Empty)
        assertEquals(1, fixture.closeCount)
    }

    @Test
    fun `closes a frame exactly once when a decoder completes more than once`() {
        val decoder = FakeFrameDecoder()
        val fixture = Fixture(decoder)
        fixture.analyzer.analyze(fixture.image)

        decoder.complete(PairingFrameDecodeResult.Detected("first-payload"))
        decoder.complete(PairingFrameDecodeResult.Detected("second-payload"))

        assertEquals(1, fixture.closeCount)
        assertEquals(listOf("first-payload"), fixture.delivered)
        assertEquals(emptyList<PairingCodeScannerFailure>(), fixture.failures)
    }

    @Test
    fun `closes a frame and reports a redacted failure when decoder throws`() {
        val fixture = Fixture(ThrowingFrameDecoder())

        fixture.analyzer.analyze(fixture.image)

        assertEquals(1, fixture.closeCount)
        assertEquals(emptyList<String>(), fixture.delivered)
        assertEquals(
            listOf(PairingCodeScannerFailure.DECODER_FAILED),
            fixture.failures,
        )
    }

    private class Fixture(
        decoder: PairingFrameDecoder,
    ) {
        var closeCount: Int = 0
        val delivered = mutableListOf<String>()
        val failures = mutableListOf<PairingCodeScannerFailure>()
        val image: ImageProxy = recordingImageProxy { closeCount += 1 }
        private val deliveryGate = PairingCodeDeliveryGate().apply { activate() }
        val analyzer =
            PairingCodeAnalyzer(
                decoder = decoder,
                deliveryGate = deliveryGate,
                onPairingCode = delivered::add,
                onFailure = failures::add,
            )
    }

    @Test
    fun `late asynchronous result after scanner stop is discarded and frame closes`() {
        val decoder = FakeFrameDecoder()
        val gate = PairingCodeDeliveryGate().apply { activate() }
        var closeCount = 0
        val delivered = mutableListOf<String>()
        val analyzer =
            PairingCodeAnalyzer(
                decoder = decoder,
                deliveryGate = gate,
                onPairingCode = delivered::add,
                onFailure = {},
            )

        analyzer.analyze(recordingImageProxy { closeCount += 1 })
        gate.deactivate()
        decoder.complete(PairingFrameDecodeResult.Detected("late-payload"))

        assertEquals(emptyList<String>(), delivered)
        assertEquals(1, closeCount)
    }

    @Test
    fun `late decoder failure after scanner stop is discarded and frame closes`() {
        val decoder = FakeFrameDecoder()
        val gate = PairingCodeDeliveryGate().apply { activate() }
        var closeCount = 0
        val failures = mutableListOf<PairingCodeScannerFailure>()
        val analyzer =
            PairingCodeAnalyzer(
                decoder = decoder,
                deliveryGate = gate,
                onPairingCode = {},
                onFailure = failures::add,
            )

        analyzer.analyze(recordingImageProxy { closeCount += 1 })
        gate.deactivate()
        decoder.complete(PairingFrameDecodeResult.Failed)

        assertEquals(emptyList<PairingCodeScannerFailure>(), failures)
        assertEquals(1, closeCount)
    }

    private class FakeFrameDecoder : PairingFrameDecoder {
        private var completion: ((PairingFrameDecodeResult) -> Unit)? = null

        override fun decode(
            frame: ImageProxy,
            complete: (PairingFrameDecodeResult) -> Unit,
        ) {
            completion = complete
        }

        fun complete(result: PairingFrameDecodeResult) {
            checkNotNull(completion)(result)
        }

        override fun close(): Unit = Unit
    }

    private class ThrowingFrameDecoder : PairingFrameDecoder {
        override fun decode(
            frame: ImageProxy,
            complete: (PairingFrameDecodeResult) -> Unit,
        ): Unit = error("synthetic decoder failure")

        override fun close(): Unit = Unit
    }

    private companion object {
        fun recordingImageProxy(onClose: () -> Unit): ImageProxy {
            val proxy =
                Proxy.newProxyInstance(
                    ImageProxy::class.java.classLoader,
                    arrayOf(ImageProxy::class.java),
                ) { instance, method, arguments ->
                    when (method.name) {
                        "close" -> onClose()
                        "toString" -> "RecordingImageProxy"
                        "hashCode" -> System.identityHashCode(instance)
                        "equals" -> instance === arguments?.firstOrNull()
                        else -> error("Unexpected ImageProxy call: ${method.name}")
                    }
                }
            return checkNotNull(ImageProxy::class.java.cast(proxy))
        }
    }
}
