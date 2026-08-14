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
        decoder.complete(PairingScannerTestBridge.emptyResult())
        assertEquals(1, fixture.closeCount)
    }

    @Test
    fun `closes a frame exactly once when a decoder completes more than once`() {
        val decoder = FakeFrameDecoder()
        val fixture = Fixture(decoder)
        fixture.analyzer.analyze(fixture.image)

        decoder.complete(PairingScannerTestBridge.detectedResult("first-payload"))
        decoder.complete(PairingScannerTestBridge.detectedResult("second-payload"))

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
        decoder: PairingScannerTestBridge.TestDecoder,
    ) {
        var closeCount: Int = 0
        val delivered = mutableListOf<String>()
        val failures = mutableListOf<PairingCodeScannerFailure>()
        val image: ImageProxy = recordingImageProxy { closeCount += 1 }
        private val deliveryGate = PairingScannerTestBridge.createGate().apply { activate() }
        val analyzer: PairingScannerTestBridge.TestAnalyzer =
            PairingScannerTestBridge.createAnalyzer(
                decoder,
                deliveryGate,
                delivered::add,
                failures::add,
            )
    }

    @Test
    fun `late asynchronous result after scanner stop is discarded and frame closes`() {
        val decoder = FakeFrameDecoder()
        val gate = PairingScannerTestBridge.createGate().apply { activate() }
        var closeCount = 0
        val delivered = mutableListOf<String>()
        val analyzer =
            PairingScannerTestBridge.createAnalyzer(
                decoder,
                gate,
                delivered::add,
                {},
            )

        analyzer.analyze(recordingImageProxy { closeCount += 1 })
        gate.deactivate()
        decoder.complete(PairingScannerTestBridge.detectedResult("late-payload"))

        assertEquals(emptyList<String>(), delivered)
        assertEquals(1, closeCount)
    }

    @Test
    fun `late decoder failure after scanner stop is discarded and frame closes`() {
        val decoder = FakeFrameDecoder()
        val gate = PairingScannerTestBridge.createGate().apply { activate() }
        var closeCount = 0
        val failures = mutableListOf<PairingCodeScannerFailure>()
        val analyzer =
            PairingScannerTestBridge.createAnalyzer(
                decoder,
                gate,
                {},
                failures::add,
            )

        analyzer.analyze(recordingImageProxy { closeCount += 1 })
        gate.deactivate()
        decoder.complete(PairingScannerTestBridge.failedResult())

        assertEquals(emptyList<PairingCodeScannerFailure>(), failures)
        assertEquals(1, closeCount)
    }

    private class FakeFrameDecoder : PairingScannerTestBridge.TestDecoder {
        private var completion: PairingScannerTestBridge.TestCompletion? = null

        override fun decode(
            frame: ImageProxy,
            complete: PairingScannerTestBridge.TestCompletion,
        ) {
            completion = complete
        }

        fun complete(result: PairingScannerTestBridge.TestDecodeResult) {
            checkNotNull(completion).complete(result)
        }
    }

    private class ThrowingFrameDecoder : PairingScannerTestBridge.TestDecoder {
        override fun decode(
            frame: ImageProxy,
            complete: PairingScannerTestBridge.TestCompletion,
        ): Unit = error("synthetic decoder failure")
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
