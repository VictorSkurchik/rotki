package org.rotki.mobile.android.pairing.scanner;

import androidx.camera.core.ImageProxy;
import java.util.function.Consumer;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Test-only access to package-private scanner mechanics without widening the production ABI. */
public final class PairingScannerTestBridge {
    private PairingScannerTestBridge() {}

    public static TestGate createGate() {
        return new TestGate(new PairingCodeDeliveryGate());
    }

    public static TestAnalyzer createAnalyzer(
            TestDecoder decoder,
            TestGate gate,
            Consumer<String> onPairingCode,
            Consumer<PairingCodeScannerFailure> onFailure) {
        PairingFrameDecoder productionDecoder =
                new PairingFrameDecoder() {
                    @Override
                    public void decode(
                            ImageProxy frame,
                            Function1<? super PairingFrameDecodeResult, Unit> complete) {
                        decoder.decode(
                                frame,
                                result -> complete.invoke(toProductionResult(result)));
                    }

                    @Override
                    public void close() {}
                };
        PairingCodeAnalyzer analyzer =
                new PairingCodeAnalyzer(
                        productionDecoder,
                        gate.delegate,
                        payload -> {
                            onPairingCode.accept(payload);
                            return Unit.INSTANCE;
                        },
                        failure -> {
                            onFailure.accept(failure);
                            return Unit.INSTANCE;
                        });
        return new TestAnalyzer(analyzer);
    }

    public static TestDecodeResult detectedResult(String payload) {
        return new TestDecodeResult(ResultKind.DETECTED, payload);
    }

    public static TestDecodeResult emptyResult() {
        return new TestDecodeResult(ResultKind.EMPTY, null);
    }

    public static TestDecodeResult failedResult() {
        return new TestDecodeResult(ResultKind.FAILED, null);
    }

    public static String detectedResultDiagnostic(String payload) {
        return new DetectedPairingFrameDecodeResult(payload).toString();
    }

    private static PairingFrameDecodeResult toProductionResult(TestDecodeResult result) {
        switch (result.kind) {
            case DETECTED:
                return new DetectedPairingFrameDecodeResult(result.payload);
            case EMPTY:
                return EmptyPairingFrameDecodeResult.INSTANCE;
            case FAILED:
                return FailedPairingFrameDecodeResult.INSTANCE;
        }
        throw new AssertionError("Unhandled test decode result");
    }

    public static final class TestGate {
        private final PairingCodeDeliveryGate delegate;

        private TestGate(PairingCodeDeliveryGate delegate) {
            this.delegate = delegate;
        }

        public void activate() {
            delegate.activate();
        }

        public void deactivate() {
            delegate.deactivate();
        }

        public Long currentSession() {
            return delegate.currentSession();
        }

        public void offer(long session, String payload, Consumer<String> deliver) {
            delegate.offer(
                    session,
                    payload,
                    value -> {
                        deliver.accept(value);
                        return Unit.INSTANCE;
                    });
        }

        public void restart() {
            delegate.restart();
        }
    }

    public static final class TestAnalyzer {
        private final PairingCodeAnalyzer delegate;

        private TestAnalyzer(PairingCodeAnalyzer delegate) {
            this.delegate = delegate;
        }

        public void analyze(ImageProxy image) {
            delegate.analyze(image);
        }
    }

    @FunctionalInterface
    public interface TestDecoder {
        void decode(ImageProxy frame, TestCompletion complete);
    }

    @FunctionalInterface
    public interface TestCompletion {
        void complete(TestDecodeResult result);
    }

    public static final class TestDecodeResult {
        private final ResultKind kind;
        private final String payload;

        private TestDecodeResult(ResultKind kind, String payload) {
            this.kind = kind;
            this.payload = payload;
        }
    }

    private enum ResultKind {
        DETECTED,
        EMPTY,
        FAILED
    }
}
