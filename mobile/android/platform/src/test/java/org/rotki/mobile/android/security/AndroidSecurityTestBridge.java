package org.rotki.mobile.android.security;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import kotlinx.coroutines.CoroutineDispatcher;
import org.rotki.mobile.core.ports.DeviceProofSigner;
import org.rotki.mobile.core.ports.IdempotencyKeyGenerator;

/** Test-only access to package-private Kotlin implementations without widening the production ABI. */
public final class AndroidSecurityTestBridge {
    private AndroidSecurityTestBridge() {}

    public static DeviceProofSigner createDeviceProofSigner(
            TestDeviceSigningKeyStore keyStore,
            CoroutineDispatcher dispatcher) {
        return new DefaultAndroidDeviceProofSigner(
                new DeviceSigningKeyStore() {
                    @Override
                    public AndroidSigningKeyMaterial createOrCurrent() {
                        return material(keyStore.createOrCurrent());
                    }

                    @Override
                    public AndroidSigningKeyMaterial currentOrNull() {
                        KeyPair keyPair = keyStore.currentOrNull();
                        return keyPair == null ? null : material(keyPair);
                    }

                    @Override
                    public byte[] signIfPresent(byte[] transcript) {
                        return keyStore.signIfPresent(transcript);
                    }

                    @Override
                    public void delete() {
                        keyStore.delete();
                    }
                },
                dispatcher);
    }

    public static IdempotencyKeyGenerator createIdempotencyKeyGenerator(TestRandomFill randomFill) {
        return new DefaultAndroidIdempotencyKeyGenerator(
                new SecureRandom() {
                    @Override
                    public void nextBytes(byte[] target) {
                        randomFill.fill(target);
                    }
                });
    }

    public static byte[] publicKeyToX963(PublicKey publicKey) {
        return AndroidP256Encoding.INSTANCE.publicKeyToX963(publicKey);
    }

    public static byte[] derEcdsaToP1363(byte[] derSignature) {
        return AndroidP256Encoding.INSTANCE.derEcdsaToP1363(derSignature);
    }

    public static byte[] p1363ToDerEcdsa(byte[] p1363Signature) {
        return AndroidP256Encoding.INSTANCE.p1363ToDerEcdsa(p1363Signature);
    }

    public static String signingKeyMaterialDiagnostic(KeyPair keyPair) {
        return material(keyPair).toString();
    }

    private static AndroidSigningKeyMaterial material(KeyPair keyPair) {
        return new AndroidSigningKeyMaterial(keyPair.getPrivate(), keyPair.getPublic());
    }

    public interface TestDeviceSigningKeyStore {
        KeyPair createOrCurrent();

        KeyPair currentOrNull();

        byte[] signIfPresent(byte[] transcript);

        void delete();
    }

    @FunctionalInterface
    public interface TestRandomFill {
        void fill(byte[] target);
    }
}
