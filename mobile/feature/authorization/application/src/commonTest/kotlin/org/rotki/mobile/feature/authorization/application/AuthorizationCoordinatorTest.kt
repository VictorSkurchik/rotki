package org.rotki.mobile.feature.authorization.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.rotki.mobile.core.ports.ApplicationVisibility
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.ports.PairingRecordDeleteOutcome
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.ports.PairingRecordStore
import org.rotki.mobile.core.ports.PairingRecordWriteOutcome
import org.rotki.mobile.core.protocol.AccessSessionCredential
import org.rotki.mobile.core.protocol.ChallengeId
import org.rotki.mobile.core.protocol.ChallengeNonce
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.P1363Signature
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.X963PublicKey
import org.rotki.mobile.feature.authorization.domain.AccessSession
import org.rotki.mobile.feature.authorization.domain.AuthorizationChallenge
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteGateway
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteOutcome
import org.rotki.mobile.feature.authorization.domain.DeviceProofTranscriptEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthorizationCoordinatorTest {
    @Test
    fun `successful exchange requires existing key wipes transcript and retains bearer only internally`() =
        runTest {
            val transcript = byteArrayOf(7, 8, 9)
            val signer = FakeDeviceProofSigner()
            var encodedTranscript: ByteArray? = null
            signer.signOperation = { supplied ->
                assertTrue(supplied === transcript)
                DeviceProofSigningOutcome.Signed(TestValues.signature())
            }
            val gateway = FakeAuthorizationGateway()
            val coordinator =
                coordinator(
                    gateway = gateway,
                    signer = signer,
                    encoder =
                        DeviceProofTranscriptEncoder { _, _, _ ->
                            transcript.also { encodedTranscript = it }
                        },
                )

            val outcome = assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(TestValues.ACCESS_EXPIRY, outcome.expiresAtEpochSeconds)
            assertEquals("Authorized(redacted)", outcome.toString())
            assertTrue(encodedTranscript === transcript)
            assertTrue(transcript.all { byte -> byte == 0.toByte() })
            assertEquals(1, signer.currentKeyReads)
            assertEquals(0, signer.createKeyCalls)
            assertEquals(1, signer.signCalls)
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())
            assertFalse(TestValues.ACCESS_CREDENTIAL in coordinator.toString())

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)

            coordinator.close()
            assertTrue(gateway.closed)
            assertFalse(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `concurrent callers join one challenge proof exchange`() =
        runTest {
            val challengeStarted = CompletableDeferred<Unit>()
            val releaseChallenge = CompletableDeferred<Unit>()
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeStarted.complete(Unit)
                        releaseChallenge.await()
                        AuthorizationRemoteOutcome.Success(TestValues.challenge())
                    },
                )
            val coordinator = coordinator(gateway = gateway)

            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            challengeStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            releaseChallenge.complete(Unit)

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(first.await())
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(second.await())
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
        }

    @Test
    fun `clear generation fences a late proof response`() =
        runTest {
            val proofStarted = CompletableDeferred<Unit>()
            var proofAttempts = 0
            var firstProofCancelled = false
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempts += 1
                        if (proofAttempts == 1) {
                            proofStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                firstProofCancelled = true
                            }
                        } else {
                            AuthorizationRemoteOutcome.Success(TestValues.session())
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            val authorization = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            proofStarted.await()

            coordinator.clearAccessSession()

            assertEquals(
                AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                authorization.await(),
            )
            assertTrue(firstProofCancelled)
            assertFalse(coordinator.hasActiveAccessSession())

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
        }

    @Test
    fun `clear cancels challenge and a fresh caller never joins the stale flight`() =
        runTest {
            val challengeStarted = CompletableDeferred<Unit>()
            var challengeAttempts = 0
            var firstChallengeCancelled = false
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeAttempts += 1
                        if (challengeAttempts == 1) {
                            challengeStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                firstChallengeCancelled = true
                            }
                        } else {
                            AuthorizationRemoteOutcome.Success(TestValues.challenge())
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            val stale = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            challengeStarted.await()

            coordinator.clearAccessSession()

            assertEquals(AuthorizationCoordinatorOutcome.OutsideActiveForeground, stale.await())
            assertTrue(firstChallengeCancelled)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(2, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
        }

    @Test
    fun `owner cancellation releases joined callers and allows a fresh flight`() =
        runTest {
            val challengeStarted = CompletableDeferred<Unit>()
            var challengeAttempts = 0
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeAttempts += 1
                        if (challengeAttempts == 1) {
                            challengeStarted.complete(Unit)
                            awaitCancellation()
                        } else {
                            AuthorizationRemoteOutcome.Success(TestValues.challenge())
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            val owner = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            challengeStarted.await()
            val joined = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }

            owner.cancelAndJoin()

            assertFailsWith<CancellationException> { joined.await() }
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(2, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
        }

    @Test
    fun `lost proof response starts the next attempt from a new challenge`() =
        runTest {
            var proofAttempts = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempts += 1
                        if (proofAttempts == 1) {
                            AuthorizationRemoteOutcome.CompleteResponseTransportFailure
                        } else {
                            AuthorizationRemoteOutcome.Success(TestValues.session())
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway)

            assertEquals(AuthorizationCoordinatorOutcome.NetworkUnavailable, coordinator.authorize())
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
        }

    @Test
    fun `challenge failures stop before signing and map without replay`() =
        runTest {
            val cases =
                listOf(
                    AuthorizationRemoteOutcome.Rejected(
                        AuthorizationRemoteFailure.LOCKED_ENGINE,
                        retryAfterSeconds = null,
                    ) to
                        AuthorizationCoordinatorOutcome.RemoteFailure(
                            AuthorizationRemoteFailure.LOCKED_ENGINE,
                            retryAfterSeconds = null,
                        ),
                    AuthorizationRemoteOutcome.ContractFailure to
                        AuthorizationCoordinatorOutcome.ContractFailure,
                    AuthorizationRemoteOutcome.PreResponseTransportFailure to
                        AuthorizationCoordinatorOutcome.NetworkUnavailable,
                    AuthorizationRemoteOutcome.CompleteResponseTransportFailure to
                        AuthorizationCoordinatorOutcome.NetworkUnavailable,
                )
            cases.forEach { (remote, expected) ->
                val signer = FakeDeviceProofSigner()
                val gateway = FakeAuthorizationGateway(challengeOperation = { remote })
                val coordinator = coordinator(gateway = gateway, signer = signer)

                assertEquals(expected, coordinator.authorize())
                assertEquals(1, gateway.challengeCalls)
                assertEquals(0, gateway.proofCalls)
                assertEquals(0, signer.signCalls)
            }
        }

    @Test
    fun `expired access response is never installed`() =
        runTest {
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        AuthorizationRemoteOutcome.Success(TestValues.session(TestValues.NOW))
                    },
                )
            val coordinator = coordinator(gateway = gateway)

            assertEquals(AuthorizationCoordinatorOutcome.SessionExpired, coordinator.authorize())
            assertFalse(coordinator.hasActiveAccessSession())
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
        }

    @Test
    fun `missing or unreadable Pairing records fail before key and network use`() =
        runTest {
            listOf(
                PairingRecordReadOutcome.Missing to AuthorizationCoordinatorOutcome.PairingRequired,
                PairingRecordReadOutcome.Corrupt to AuthorizationCoordinatorOutcome.LocalStorageUnavailable,
                PairingRecordReadOutcome.Unavailable to AuthorizationCoordinatorOutcome.LocalStorageUnavailable,
            ).forEach { (stored, expected) ->
                val signer = FakeDeviceProofSigner()
                val gateway = FakeAuthorizationGateway()
                val coordinator =
                    coordinator(
                        gateway = gateway,
                        signer = signer,
                        store = FakePairingRecordStore { stored },
                    )

                assertEquals(expected, coordinator.authorize())
                assertEquals(0, signer.currentKeyReads)
                assertEquals(0, gateway.challengeCalls)
                assertEquals(0, gateway.proofCalls)
            }
        }

    @Test
    fun `missing key never creates replacement or contacts Engine`() =
        runTest {
            val signer =
                FakeDeviceProofSigner(
                    currentKeyOutcome = DeviceProofPublicKeyOutcome.PairingRequired,
                )
            val gateway = FakeAuthorizationGateway()
            val coordinator = coordinator(gateway = gateway, signer = signer)

            assertEquals(AuthorizationCoordinatorOutcome.PairingRequired, coordinator.authorize())
            assertEquals(1, signer.currentKeyReads)
            assertEquals(0, signer.createKeyCalls)
            assertEquals(0, signer.signCalls)
            assertEquals(0, gateway.challengeCalls)
            assertEquals(0, gateway.proofCalls)
        }

    @Test
    fun `background blocks exchange and destroys installed authority`() =
        runTest {
            val visibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND)
            val gateway = FakeAuthorizationGateway()
            val coordinator = coordinator(gateway = gateway, visibility = visibility)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            visibility.mutableState.value = ApplicationVisibilityState.BACKGROUND_OR_LOCKED
            assertEquals(
                AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                coordinator.authorize(),
            )
            assertFalse(coordinator.hasActiveAccessSession())
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
        }

    @Test
    fun `cancellation is rethrown and transcript is wiped`() =
        runTest {
            val transcript = byteArrayOf(4, 5, 6)
            val signingStarted = CompletableDeferred<Unit>()
            val signer = FakeDeviceProofSigner()
            signer.signOperation = {
                signingStarted.complete(Unit)
                awaitCancellation()
            }
            val coordinator =
                coordinator(
                    signer = signer,
                    encoder = DeviceProofTranscriptEncoder { _, _, _ -> transcript },
                )
            val authorization = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            signingStarted.await()

            authorization.cancelAndJoin()

            assertTrue(authorization.isCancelled)
            assertTrue(transcript.all { byte -> byte == 0.toByte() })
        }

    @Test
    fun `unexpected adapter exception becomes redacted failure`() =
        runTest {
            val store =
                FakePairingRecordStore {
                    error("seeded-${TestValues.ACCESS_CREDENTIAL}")
                }
            val coordinator = coordinator(store = store)

            val outcome = coordinator.authorize()

            assertEquals(AuthorizationCoordinatorOutcome.UnexpectedFailure, outcome)
            assertFalse(TestValues.ACCESS_CREDENTIAL in outcome.toString())
        }

    private fun coordinator(
        gateway: FakeAuthorizationGateway = FakeAuthorizationGateway(),
        signer: FakeDeviceProofSigner = FakeDeviceProofSigner(),
        encoder: DeviceProofTranscriptEncoder = DeviceProofTranscriptEncoder { _, _, _ -> byteArrayOf(1) },
        visibility: FakeVisibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND),
        store: PairingRecordStore = FakePairingRecordStore(),
    ): AuthorizationCoordinator =
        AuthorizationCoordinator(
            remoteGateway = gateway,
            transcriptEncoder = encoder,
            pairingRecordStore = store,
            deviceProofSigner = signer,
            applicationVisibility = visibility,
            clock = Clock { TestValues.NOW },
            selectedProtocolVersion = 1,
        )
}

private class FakeAuthorizationGateway(
    private val challengeOperation: suspend () -> AuthorizationRemoteOutcome<AuthorizationChallenge> = {
        AuthorizationRemoteOutcome.Success(TestValues.challenge())
    },
    private val proofOperation: suspend () -> AuthorizationRemoteOutcome<AccessSession> = {
        AuthorizationRemoteOutcome.Success(TestValues.session())
    },
) : AuthorizationRemoteGateway {
    var challengeCalls: Int = 0
    var proofCalls: Int = 0
    var closed: Boolean = false

    override suspend fun requestChallenge(
        engineOrigin: EngineOrigin,
        deviceSessionId: DeviceSessionId,
        selectedProtocolVersion: Int,
    ): AuthorizationRemoteOutcome<AuthorizationChallenge> {
        challengeCalls += 1
        assertEquals(TestValues.ORIGIN, engineOrigin.canonical)
        assertEquals(TestValues.DEVICE_SESSION_ID, deviceSessionId.encoded)
        assertEquals(1, selectedProtocolVersion)
        return challengeOperation()
    }

    override suspend fun submitProof(
        engineOrigin: EngineOrigin,
        deviceSessionId: DeviceSessionId,
        challengeId: ChallengeId,
        signature: P1363Signature,
        selectedProtocolVersion: Int,
    ): AuthorizationRemoteOutcome<AccessSession> {
        proofCalls += 1
        assertEquals(TestValues.ORIGIN, engineOrigin.canonical)
        assertEquals(TestValues.DEVICE_SESSION_ID, deviceSessionId.encoded)
        assertEquals(TestValues.CHALLENGE_ID, challengeId.encoded)
        assertEquals(TestValues.SIGNATURE, signature.encoded)
        assertEquals(1, selectedProtocolVersion)
        return proofOperation()
    }

    override fun close() {
        closed = true
    }
}

private class FakeDeviceProofSigner(
    private val currentKeyOutcome: DeviceProofPublicKeyOutcome =
        DeviceProofPublicKeyOutcome.PublicKey(TestValues.publicKey()),
) : DeviceProofSigner {
    var currentKeyReads: Int = 0
    var createKeyCalls: Int = 0
    var signCalls: Int = 0
    var signOperation: suspend (ByteArray) -> DeviceProofSigningOutcome = {
        DeviceProofSigningOutcome.Signed(TestValues.signature())
    }

    override suspend fun createKeyForPairing(): DeviceProofPublicKeyOutcome {
        createKeyCalls += 1
        return currentKeyOutcome
    }

    override suspend fun currentPublicKeyX963(): DeviceProofPublicKeyOutcome {
        currentKeyReads += 1
        return currentKeyOutcome
    }

    override suspend fun sign(transcript: ByteArray): DeviceProofSigningOutcome {
        signCalls += 1
        return signOperation(transcript)
    }

    override suspend fun deleteKey(): DeviceProofKeyDeleteOutcome = DeviceProofKeyDeleteOutcome.Deleted
}

private class FakePairingRecordStore(
    private val readOperation: suspend () -> PairingRecordReadOutcome = {
        PairingRecordReadOutcome.Present(
            PairingRecord(TestValues.origin(), TestValues.deviceSessionId()),
        )
    },
) : PairingRecordStore {
    override suspend fun read(): PairingRecordReadOutcome = readOperation()

    override suspend fun write(record: PairingRecord): PairingRecordWriteOutcome = PairingRecordWriteOutcome.Unavailable

    override suspend fun delete(): PairingRecordDeleteOutcome = PairingRecordDeleteOutcome.Unavailable
}

private class FakeVisibility(
    initialState: ApplicationVisibilityState,
) : ApplicationVisibility {
    val mutableState: MutableStateFlow<ApplicationVisibilityState> = MutableStateFlow(initialState)
    override val state: StateFlow<ApplicationVisibilityState> = mutableState
}

private object TestValues {
    const val ORIGIN: String = "https://rotki.example"
    const val DEVICE_SESSION_ID: String = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    const val CHALLENGE_ID: String = "ICEiIyQlJicoKSorLC0uLw"
    const val NONCE: String = "MDEyMzQ1Njc4OTo7PD0-P0BBQkNERUZHSElKS0xNTk8"
    const val SIGNATURE: String =
        "zKnDT8nsSEMoIxqZIzUybLt-QJJr6mtaaXm6SJMRmK7kJLpg-BP20iAJBHwLqXstAHFxaHwb_vs_jb4hSU6N8A"
    const val PUBLIC_KEY: String =
        "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"
    const val ACCESS_CREDENTIAL: String = "UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8"
    const val NOW: Long = 1_786_550_400
    const val ACCESS_EXPIRY: Long = 1_786_551_300

    fun origin(): EngineOrigin = assertIs<EngineOriginParseOutcome.Accepted>(EngineOrigin.parse(ORIGIN)).origin

    fun deviceSessionId(): DeviceSessionId = accepted(DeviceSessionId.parse(DEVICE_SESSION_ID))

    fun challengeId(): ChallengeId = accepted(ChallengeId.parse(CHALLENGE_ID))

    fun nonce(): ChallengeNonce = accepted(ChallengeNonce.parse(NONCE))

    fun signature(): P1363Signature = accepted(P1363Signature.parse(SIGNATURE))

    fun publicKey(): X963PublicKey = accepted(X963PublicKey.parse(PUBLIC_KEY))

    fun credential(): AccessSessionCredential = accepted(AccessSessionCredential.parse(ACCESS_CREDENTIAL))

    fun challenge(): AuthorizationChallenge = AuthorizationChallenge(challengeId(), nonce(), NOW + 60)

    fun session(expiresAtEpochSeconds: Long = ACCESS_EXPIRY): AccessSession =
        AccessSession(credential(), expiresAtEpochSeconds)

    private fun <T> accepted(outcome: ProtocolValueParseOutcome<T>): T =
        assertIs<ProtocolValueParseOutcome.Accepted<T>>(outcome).value
}
