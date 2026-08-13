package org.rotki.mobile.android.security

import android.content.ContextWrapper
import android.util.AtomicFile
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.IdentityHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.rotki.mobile.core.ports.SecureSnapshotReadOutcome
import org.rotki.mobile.core.ports.SecureSnapshotWriteOutcome

class AndroidSecureSnapshotStoreTest {
    @Test
    fun `corrupt stored envelope is reported without key or biometric access`() = runBlocking {
        val keyStore = FakeSnapshotKeyStore()
        val biometricBroker = ImmediateBiometricBroker()
        val materialCleaner = FakeLocalMaterialCleaner()
        val fixture = snapshotFile("not-an-envelope".toByteArray())

        val outcome = store(
            keyStore = keyStore,
            biometricBroker = biometricBroker,
            materialCleaner = materialCleaner,
            file = fixture.file,
        ).readAfterDeviceAuthentication()

        assertSame(SecureSnapshotReadOutcome.Corrupt, outcome)
        assertEquals(0, keyStore.presenceCalls)
        assertEquals(0, keyStore.decryptCalls)
        assertEquals(0, biometricBroker.authorizeCalls)
        assertEquals(0, materialCleaner.destroyAllCalls)
    }

    @Test
    fun `missing expected AES key destroys material and requires pairing`() = runBlocking {
        val keyStore = FakeSnapshotKeyStore(
            presenceOutcome = SnapshotKeyPresence.PermanentlyInvalidated,
        )
        val biometricBroker = ImmediateBiometricBroker()
        val materialCleaner = FakeLocalMaterialCleaner(succeeds = true)

        val outcome = store(
            keyStore = keyStore,
            biometricBroker = biometricBroker,
            materialCleaner = materialCleaner,
            file = snapshotFile(initialBytes = null).file,
        ).readAfterDeviceAuthentication()

        assertSame(SecureSnapshotReadOutcome.PairingRequired, outcome)
        assertEquals(1, keyStore.presenceCalls)
        assertEquals(1, materialCleaner.destroyAllCalls)
        assertEquals(1, biometricBroker.cancelCalls)
    }

    @Test
    fun `permanently invalidated AES key with failed cleanup is unavailable`() = runBlocking {
        val keyStore = FakeSnapshotKeyStore(
            decryptOutcome = { SnapshotCipherPreparationOutcome.PermanentlyInvalidated },
        )
        val biometricBroker = ImmediateBiometricBroker()
        val materialCleaner = FakeLocalMaterialCleaner(succeeds = false)
        val encoded = SnapshotEnvelopeCodec.encode(
            SnapshotEnvelope(ByteArray(SnapshotEnvelopeCodec.GCM_IV_BYTES), ByteArray(16)),
        )

        val outcome = store(
            keyStore = keyStore,
            biometricBroker = biometricBroker,
            materialCleaner = materialCleaner,
            file = snapshotFile(encoded).file,
        ).readAfterDeviceAuthentication()

        assertSame(SecureSnapshotReadOutcome.Unavailable, outcome)
        assertEquals(1, keyStore.decryptCalls)
        assertEquals(1, materialCleaner.destroyAllCalls)
        assertEquals(1, biometricBroker.cancelCalls)
        assertEquals(0, biometricBroker.authorizeCalls)
    }

    @Test
    fun `background during late replace wipes plaintext and preserves last good`() = runBlocking {
        val lastGood = encryptedEnvelope("last-good".toByteArray())
        val fixture = snapshotFile(lastGood)
        val keyStore = FakeSnapshotKeyStore(
            encryptOutcome = {
                SnapshotCipherPreparationOutcome.Prepared(encryptionCipher())
            },
        )
        val biometricBroker = LateAuthorizationBroker()
        val replacementDocument = "new-private-document".toByteArray()
        val originalDocument = replacementDocument.copyOf()
        val store = store(
            keyStore = keyStore,
            biometricBroker = biometricBroker,
            materialCleaner = FakeLocalMaterialCleaner(),
            file = fixture.file,
        )

        val replacement = async(start = CoroutineStart.UNDISPATCHED) {
            store.replace(replacementDocument)
        }
        biometricBroker.awaitAuthorizationRequest()
        val activeWriteBuffer = activeWriteBuffers(store).single()

        store.discardPlaintext()

        assertEquals(1, biometricBroker.cancelCalls)
        assertTrue(activeWriteBuffer.all { byte -> byte == 0.toByte() })
        assertArrayEquals(originalDocument, replacementDocument)

        biometricBroker.authorizeLate()

        assertSame(SecureSnapshotWriteOutcome.DeviceAuthenticationRequired, replacement.await())
        assertArrayEquals(lastGood, fixture.platform.storedBytes())
        assertEquals(0, fixture.platform.startWriteCalls)
    }

    @Test
    fun `background revokes an unlocked read handle`() = runBlocking {
        val document = "private-portfolio".toByteArray()
        val fixture = snapshotFile(encryptedEnvelope(document))
        val biometricBroker = ImmediateBiometricBroker()
        val store = store(
            keyStore = FakeSnapshotKeyStore(
                decryptOutcome = { initializationVector ->
                    SnapshotCipherPreparationOutcome.Prepared(
                        decryptionCipher(initializationVector),
                    )
                },
            ),
            biometricBroker = biometricBroker,
            materialCleaner = FakeLocalMaterialCleaner(),
            file = fixture.file,
        )

        val outcome = store.readAfterDeviceAuthentication()
        assertTrue(outcome is SecureSnapshotReadOutcome.Unlocked)
        val unlocked = outcome as SecureSnapshotReadOutcome.Unlocked
        val firstCopy = unlocked.documentCopy()
        assertArrayEquals(document, firstCopy)
        firstCopy?.fill(0)
        assertArrayEquals(document, unlocked.documentCopy())
        assertFalse(unlocked.isDiscarded)

        store.discardPlaintext()

        assertTrue(unlocked.isDiscarded)
        assertNull(unlocked.documentCopy())
        assertEquals(1, biometricBroker.cancelCalls)
    }

    @Test
    fun `background barrier prevents authorized read from decrypting afterward`() = runBlocking {
        val document = "private-after-background".toByteArray()
        val tamperedEnvelope = encryptedEnvelope(document).also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        val dispatcher = GateThirdDispatch()
        val biometricBroker = ImmediateBiometricBroker()
        val store = store(
            keyStore = FakeSnapshotKeyStore(
                decryptOutcome = { initializationVector ->
                    SnapshotCipherPreparationOutcome.Prepared(
                        decryptionCipher(initializationVector),
                    )
                },
            ),
            biometricBroker = biometricBroker,
            materialCleaner = FakeLocalMaterialCleaner(),
            file = snapshotFile(tamperedEnvelope).file,
            dispatcher = dispatcher,
        )

        val read = async(start = CoroutineStart.UNDISPATCHED) {
            store.readAfterDeviceAuthentication()
        }
        dispatcher.awaitDecryptDispatch()

        store.discardPlaintext()
        dispatcher.releaseDecryptDispatch()

        assertSame(SecureSnapshotReadOutcome.DeviceAuthenticationCancelled, read.await())
        assertEquals(1, biometricBroker.cancelCalls)
    }

    private fun store(
        keyStore: SnapshotKeyStore,
        biometricBroker: BiometricCryptoBroker,
        materialCleaner: LocalMaterialCleaner,
        file: AtomicSnapshotFile,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): AndroidSecureSnapshotStore = AndroidSecureSnapshotStore(
        context = allocateWithoutConstructor(ContextWrapper::class.java),
        keyStore = keyStore,
        biometricBroker = biometricBroker,
        materialCleaner = materialCleaner,
        file = file,
        dispatcher = dispatcher,
    )

    @Suppress("UNCHECKED_CAST")
    private fun activeWriteBuffers(store: AndroidSecureSnapshotStore): Set<ByteArray> =
        AndroidSecureSnapshotStore::class.java.getDeclaredField("activeWritePlaintext").run {
            isAccessible = true
            get(store) as Set<ByteArray>
        }

    private class FakeSnapshotKeyStore(
        private val presenceOutcome: SnapshotKeyPresence = SnapshotKeyPresence.Missing,
        private val encryptOutcome: () -> SnapshotCipherPreparationOutcome = {
            SnapshotCipherPreparationOutcome.PairingRequired
        },
        private val decryptOutcome: (ByteArray) -> SnapshotCipherPreparationOutcome = {
            SnapshotCipherPreparationOutcome.PairingRequired
        },
    ) : SnapshotKeyStore {
        var presenceCalls: Int = 0
        var decryptCalls: Int = 0

        override fun presence(): SnapshotKeyPresence {
            presenceCalls += 1
            return presenceOutcome
        }

        override fun prepareEncryptCipher(): SnapshotCipherPreparationOutcome = encryptOutcome()

        override fun prepareDecryptCipher(
            initializationVector: ByteArray,
        ): SnapshotCipherPreparationOutcome {
            decryptCalls += 1
            return decryptOutcome(initializationVector.copyOf())
        }

        override fun delete(): Boolean = true
    }

    private class ImmediateBiometricBroker : BiometricCryptoBroker {
        var authorizeCalls: Int = 0
        var cancelCalls: Int = 0

        override suspend fun authorize(cipher: Cipher): AndroidBiometricCryptoOutcome {
            authorizeCalls += 1
            return AndroidBiometricCryptoOutcome.Authorized(cipher)
        }

        override fun cancelPending() {
            cancelCalls += 1
        }
    }

    private class LateAuthorizationBroker : BiometricCryptoBroker {
        private val requestedCipher: CompletableDeferred<Cipher> = CompletableDeferred()
        private val release: CompletableDeferred<Unit> = CompletableDeferred()
        var cancelCalls: Int = 0

        override suspend fun authorize(cipher: Cipher): AndroidBiometricCryptoOutcome {
            requestedCipher.complete(cipher)
            release.await()
            return AndroidBiometricCryptoOutcome.Authorized(cipher)
        }

        override fun cancelPending() {
            cancelCalls += 1
        }

        suspend fun awaitAuthorizationRequest(): Unit {
            requestedCipher.await()
        }

        fun authorizeLate(): Unit {
            release.complete(Unit)
        }
    }

    private class FakeLocalMaterialCleaner(
        private val succeeds: Boolean = true,
    ) : LocalMaterialCleaner {
        var destroyAllCalls: Int = 0

        override suspend fun destroyAll(): Boolean {
            destroyAllCalls += 1
            return succeeds
        }
    }

    private class GateThirdDispatch : CoroutineDispatcher() {
        private val decryptDispatchStarted: CompletableDeferred<Unit> = CompletableDeferred()
        private val release: CompletableDeferred<Unit> = CompletableDeferred()
        private var dispatchCount: Int = 0

        override fun dispatch(context: CoroutineContext, block: Runnable): Unit {
            dispatchCount += 1
            if (dispatchCount != 3) {
                block.run()
                return
            }
            thread(name = "snapshot-read-decrypt-gate", isDaemon = true) {
                decryptDispatchStarted.complete(Unit)
                runBlocking { release.await() }
                block.run()
            }
        }

        suspend fun awaitDecryptDispatch(): Unit {
            decryptDispatchStarted.await()
        }

        fun releaseDecryptDispatch(): Unit {
            release.complete(Unit)
        }
    }

    private data class SnapshotFileFixture(
        val file: AtomicSnapshotFile,
        val platform: FakePlatformAtomicFile,
    )

    private class FakePlatformAtomicFile private constructor() : AtomicFile(File("unused")) {
        override fun openRead(): FileInputStream {
            val (bytes, target) = synchronized(states) {
                val state = state()
                state.readCalls += 1
                val snapshot = state.stored?.copyOf() ?: throw FileNotFoundException()
                snapshot to File(state.directory, "read-${state.readCalls}")
            }
            FileOutputStream(target).use { output -> output.write(bytes) }
            target.deleteOnExit()
            return FileInputStream(target)
        }

        override fun startWrite(): FileOutputStream {
            val target = synchronized(states) {
                val state = state()
                state.startWriteCalls += 1
                File(state.directory, "pending-${state.startWriteCalls}").also {
                    state.pending = it
                }
            }
            return FileOutputStream(target)
        }

        override fun finishWrite(stream: FileOutputStream): Unit {
            stream.close()
            val pending = synchronized(states) {
                checkNotNull(state().pending)
            }
            val stored = FileInputStream(pending).use { input -> input.readBytes() }
            synchronized(states) {
                state().stored = stored
                state().pending = null
            }
        }

        override fun failWrite(stream: FileOutputStream): Unit {
            stream.close()
            synchronized(states) {
                state().pending = null
            }
        }

        override fun delete(): Unit {
            synchronized(states) {
                val state = state()
                state.stored = null
                state.deleteCalls += 1
            }
        }

        override fun getBaseFile(): File = synchronized(states) {
            File(state().directory, "base")
        }

        fun storedBytes(): ByteArray? = synchronized(states) {
            state().stored?.copyOf()
        }

        val startWriteCalls: Int
            get() = synchronized(states) { state().startWriteCalls }

        private fun state(): State = checkNotNull(states[this])

        private data class State(
            val directory: File,
            var stored: ByteArray?,
            var pending: File? = null,
            var readCalls: Int = 0,
            var startWriteCalls: Int = 0,
            var deleteCalls: Int = 0,
        )

        companion object {
            private val states: IdentityHashMap<FakePlatformAtomicFile, State> = IdentityHashMap()

            fun create(initialBytes: ByteArray?): FakePlatformAtomicFile {
                val instance = allocateWithoutConstructor(FakePlatformAtomicFile::class.java)
                val directory = Files.createTempDirectory("rotki-snapshot-store-test").toFile()
                directory.deleteOnExit()
                synchronized(states) {
                    states[instance] = State(directory, initialBytes?.copyOf())
                }
                return instance
            }
        }
    }

    private companion object {
        private val testKey = SecretKeySpec(
            ByteArray(32) { index -> (index + 1).toByte() },
            "AES",
        )

        fun snapshotFile(initialBytes: ByteArray?): SnapshotFileFixture {
            val platform = FakePlatformAtomicFile.create(initialBytes)
            val constructor = AtomicSnapshotFile::class.java.getDeclaredConstructor(
                AtomicFile::class.java,
            )
            constructor.isAccessible = true
            return SnapshotFileFixture(constructor.newInstance(platform), platform)
        }

        fun encryptionCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, testKey)
        }

        fun decryptionCipher(initializationVector: ByteArray): Cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    testKey,
                    GCMParameterSpec(128, initializationVector),
                )
            }

        fun encryptedEnvelope(document: ByteArray): ByteArray {
            val cipher = encryptionCipher()
            cipher.updateAAD(SnapshotEnvelopeCodec.authenticatedDomainCopy())
            return SnapshotEnvelopeCodec.encode(
                SnapshotEnvelope(cipher.iv, cipher.doFinal(document)),
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : Any> allocateWithoutConstructor(type: Class<T>): T {
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeType.getDeclaredField("theUnsafe").run {
                isAccessible = true
                get(null)
            }
            return unsafeType.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, type) as T
        }
    }
}
