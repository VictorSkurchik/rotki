@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.rotki.mobile.feature.authorization.application

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
            val releaseLateProof = CompletableDeferred<Unit>()
            var proofAttempts = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempts += 1
                        if (proofAttempts == 1) {
                            withContext(NonCancellable) {
                                proofStarted.complete(Unit)
                                releaseLateProof.await()
                                AuthorizationRemoteOutcome.Success(TestValues.session())
                            }
                        } else {
                            AuthorizationRemoteOutcome.Success(TestValues.session())
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            val authorization = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            proofStarted.await()

            val clearing =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.clearAccessSession()
                }

            assertEquals(
                AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                authorization.await(),
            )
            releaseLateProof.complete(Unit)
            clearing.await()
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
    fun `owner cancellation does not cancel the process flight or joined callers`() =
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
            val owner = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            challengeStarted.await()
            val joined = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }

            owner.cancelAndJoin()
            releaseChallenge.complete(Unit)

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(joined.await())
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(1, gateway.challengeCalls)
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
    fun `proactive renewal starts at 300 seconds but not 301`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        AuthorizationRemoteOutcome.Success(
                            TestValues.session(
                                if (proofAttempt == 1) TestValues.NOW + 301 else TestValues.NOW + 900,
                            ),
                        )
                    },
                )
            val coordinator =
                coordinator(gateway = gateway, clock = clock, deadlineWaiter = deadlines)

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, deadlines.pending(TestValues.NOW + 1))

            clock.now = TestValues.NOW + 1
            deadlines.releaseOne(TestValues.NOW + 1)
            runCurrent()

            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
        }

    @Test
    fun `timer and simultaneous callers share one renewal flight`() =
        runTest {
            val renewalStarted = CompletableDeferred<Unit>()
            val releaseRenewal = CompletableDeferred<Unit>()
            var challengeAttempt = 0
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeAttempt += 1
                        if (challengeAttempt == 2) {
                            renewalStarted.complete(Unit)
                            releaseRenewal.await()
                        }
                        AuthorizationRemoteOutcome.Success(TestValues.challenge())
                    },
                    proofOperation = {
                        proofAttempt += 1
                        AuthorizationRemoteOutcome.Success(
                            TestValues.session(
                                if (proofAttempt == 1) TestValues.NOW + 300 else TestValues.NOW + 900,
                            ),
                        )
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            runCurrent()
            renewalStarted.await()
            val callers =
                List(8) {
                    async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
                }
            releaseRenewal.complete(Unit)

            callers.forEach { caller ->
                assertIs<AuthorizationCoordinatorOutcome.Authorized>(caller.await())
            }
            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
        }

    @Test
    fun `recoverable renewal transport loss retains the old bearer`() =
        runTest {
            val proofStarted = CompletableDeferred<Unit>()
            val releaseProof = CompletableDeferred<Unit>()
            val deadlines = ManualDeadlineWaiter()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        if (proofAttempt == 1) {
                            AuthorizationRemoteOutcome.Success(
                                TestValues.session(TestValues.NOW + 300),
                            )
                        } else {
                            proofStarted.complete(Unit)
                            releaseProof.await()
                            AuthorizationRemoteOutcome.CompleteResponseTransportFailure
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            runCurrent()
            proofStarted.await()
            assertTrue(coordinator.hasActiveAccessSession())
            releaseProof.complete(Unit)
            runCurrent()

            assertTrue(coordinator.hasActiveAccessSession())
            assertEquals(1, deadlines.pending(TestValues.NOW + PROACTIVE_RENEWAL_RETRY_DELAY_SECONDS))
        }

    @Test
    fun `proactive transport retry is one fresh challenge proof exchange`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        when (proofAttempt) {
                            1 -> {
                                AuthorizationRemoteOutcome.Success(
                                    TestValues.session(TestValues.NOW + 300),
                                )
                            }

                            2 -> {
                                AuthorizationRemoteOutcome.CompleteResponseTransportFailure
                            }

                            else -> {
                                AuthorizationRemoteOutcome.Success(TestValues.session(TestValues.NOW + 900))
                            }
                        }
                    },
                )
            val coordinator =
                coordinator(gateway = gateway, clock = clock, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            runCurrent()
            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
            val retryDeadline = TestValues.NOW + PROACTIVE_RENEWAL_RETRY_DELAY_SECONDS
            assertEquals(1, deadlines.pending(retryDeadline))

            clock.now = retryDeadline
            deadlines.releaseOne(retryDeadline)
            runCurrent()

            assertEquals(3, gateway.challengeCalls)
            assertEquals(3, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `proactive transport retry budget stops after one fresh exchange`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        if (proofAttempt == 1) {
                            AuthorizationRemoteOutcome.Success(
                                TestValues.session(TestValues.NOW + 300),
                            )
                        } else {
                            AuthorizationRemoteOutcome.CompleteResponseTransportFailure
                        }
                    },
                )
            val coordinator =
                coordinator(gateway = gateway, clock = clock, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            val firstRetryDeadline = TestValues.NOW + PROACTIVE_RENEWAL_RETRY_DELAY_SECONDS
            assertEquals(1, deadlines.pending(firstRetryDeadline))
            clock.now = firstRetryDeadline
            deadlines.releaseOne(firstRetryDeadline)
            runCurrent()

            val forbiddenSecondRetryDeadline =
                firstRetryDeadline + PROACTIVE_RENEWAL_RETRY_DELAY_SECONDS
            assertEquals(3, gateway.challengeCalls)
            assertEquals(3, gateway.proofCalls)
            assertEquals(0, deadlines.pending(forbiddenSecondRetryDeadline))
            assertEquals(1, deadlines.activeCount())
            assertTrue(coordinator.hasActiveAccessSession())
            runCurrent()
            assertEquals(3, gateway.challengeCalls)
            assertEquals(3, gateway.proofCalls)
        }

    @Test
    fun `inactive cancels a pending proactive retry and active resumes with a fresh exchange`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val visibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND)
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        when (proofAttempt) {
                            1 -> {
                                AuthorizationRemoteOutcome.Success(
                                    TestValues.session(TestValues.NOW + 300),
                                )
                            }

                            2 -> {
                                AuthorizationRemoteOutcome.CompleteResponseTransportFailure
                            }

                            else -> {
                                AuthorizationRemoteOutcome.Success(
                                    TestValues.session(TestValues.NOW + 900),
                                )
                            }
                        }
                    },
                )
            val coordinator =
                coordinator(
                    gateway = gateway,
                    visibility = visibility,
                    clock = clock,
                    deadlineWaiter = deadlines,
                )
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            val retryDeadline = TestValues.NOW + PROACTIVE_RENEWAL_RETRY_DELAY_SECONDS
            assertEquals(1, deadlines.pending(retryDeadline))
            assertEquals(2, deadlines.activeCount())
            visibility.mutableState.value = ApplicationVisibilityState.INACTIVE
            runCurrent()

            assertEquals(0, deadlines.pending(retryDeadline))
            assertEquals(1, deadlines.activeCount())
            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
            assertFalse(coordinator.hasActiveAccessSession())

            clock.now = retryDeadline
            runCurrent()
            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)

            visibility.mutableState.value = ApplicationVisibilityState.ACTIVE_FOREGROUND
            runCurrent()

            assertEquals(3, gateway.challengeCalls)
            assertEquals(3, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `rate limited proactive renewal schedules one canonical Retry-After exchange`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        when (proofAttempt) {
                            1 -> {
                                AuthorizationRemoteOutcome.Success(
                                    TestValues.session(TestValues.NOW + 300),
                                )
                            }

                            2 -> {
                                AuthorizationRemoteOutcome.Rejected(
                                    AuthorizationRemoteFailure.RATE_LIMITED,
                                    retryAfterSeconds = 5,
                                )
                            }

                            else -> {
                                AuthorizationRemoteOutcome.Success(
                                    TestValues.session(TestValues.NOW + 900),
                                )
                            }
                        }
                    },
                )
            val coordinator =
                coordinator(gateway = gateway, clock = clock, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()
            assertEquals(1, deadlines.pending(TestValues.NOW + 5))

            clock.now = TestValues.NOW + 5
            deadlines.releaseOne(TestValues.NOW + 5)
            runCurrent()

            assertEquals(3, gateway.challengeCalls)
            assertEquals(3, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `rate limit above five seconds does not schedule hidden renewal work`() =
        runTest {
            val deadlines = ManualDeadlineWaiter()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        if (proofAttempt == 1) {
                            AuthorizationRemoteOutcome.Success(
                                TestValues.session(TestValues.NOW + 300),
                            )
                        } else {
                            AuthorizationRemoteOutcome.Rejected(
                                AuthorizationRemoteFailure.RATE_LIMITED,
                                retryAfterSeconds = 6,
                            )
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
            assertEquals(0, deadlines.pending(TestValues.NOW + 6))
            assertEquals(1, deadlines.activeCount())
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `old bearer expires while renewal continues and delayed success may install new authority`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val renewalProofStarted = CompletableDeferred<Unit>()
            val releaseRenewalProof = CompletableDeferred<Unit>()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        if (proofAttempt == 1) {
                            AuthorizationRemoteOutcome.Success(
                                TestValues.session(TestValues.NOW + 300),
                            )
                        } else {
                            renewalProofStarted.complete(Unit)
                            releaseRenewalProof.await()
                            AuthorizationRemoteOutcome.Success(
                                TestValues.session(TestValues.NOW + 900),
                            )
                        }
                    },
                )
            val coordinator =
                coordinator(gateway = gateway, clock = clock, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()
            renewalProofStarted.await()

            clock.now = TestValues.NOW + 300
            deadlines.releaseOne(TestValues.NOW + 300)
            runCurrent()
            assertFalse(coordinator.hasActiveAccessSession())

            releaseRenewalProof.complete(Unit)
            runCurrent()
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `successful renewal atomically replaces old Engine expiry even when the new expiry is earlier`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val thirdProofStarted = CompletableDeferred<Unit>()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        when (proofAttempt) {
                            1 -> {
                                AuthorizationRemoteOutcome.Success(TestValues.session(TestValues.NOW + 900))
                            }

                            2 -> {
                                AuthorizationRemoteOutcome.Success(TestValues.session(TestValues.NOW + 850))
                            }

                            else -> {
                                thirdProofStarted.complete(Unit)
                                awaitCancellation()
                            }
                        }
                    },
                )
            val coordinator =
                coordinator(gateway = gateway, clock = clock, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            clock.now = TestValues.NOW + 600
            deadlines.releaseOne(TestValues.NOW + 600)
            runCurrent()
            thirdProofStarted.await()
            assertEquals(1, deadlines.pending(TestValues.NOW + 850))

            clock.now = TestValues.NOW + 850
            deadlines.releaseOne(TestValues.NOW + 850)
            runCurrent()

            assertFalse(coordinator.hasActiveAccessSession())
            coordinator.clearAccessSession()
        }

    @Test
    fun `expired renewal response never replaces a still usable old bearer`() =
        runTest {
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        AuthorizationRemoteOutcome.Success(
                            TestValues.session(
                                if (proofAttempt == 1) TestValues.NOW + 300 else TestValues.NOW,
                            ),
                        )
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            assertEquals(AuthorizationCoordinatorOutcome.SessionExpired, coordinator.authorize())

            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `terminal proof outcomes drop only process bearer authority`() =
        runTest {
            val terminalFailures =
                listOf(
                    AuthorizationRemoteFailure.NOT_AUTHORIZED,
                    AuthorizationRemoteFailure.PROFILE_MISMATCH,
                    AuthorizationRemoteFailure.LOCKED_ENGINE,
                    AuthorizationRemoteFailure.INCOMPATIBLE_PROTOCOL,
                )
            terminalFailures.forEach { failure ->
                var proofAttempt = 0
                val gateway =
                    FakeAuthorizationGateway(
                        proofOperation = {
                            proofAttempt += 1
                            if (proofAttempt == 1) {
                                AuthorizationRemoteOutcome.Success(
                                    TestValues.session(TestValues.NOW + 300),
                                )
                            } else {
                                AuthorizationRemoteOutcome.Rejected(failure, retryAfterSeconds = null)
                            }
                        },
                    )
                val store = FakePairingRecordStore()
                val coordinator = coordinator(gateway = gateway, store = store)
                assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

                val outcome = assertIs<AuthorizationCoordinatorOutcome.RemoteFailure>(coordinator.authorize())

                assertEquals(failure, outcome.failure)
                assertFalse(coordinator.hasActiveAccessSession())
                assertEquals(0, store.deleteCalls)
            }
        }

    @Test
    fun `loss of the durable Pairing relationship drops an old bearer`() =
        runTest {
            var storeRead = 0
            val store =
                FakePairingRecordStore {
                    storeRead += 1
                    if (storeRead == 1) {
                        PairingRecordReadOutcome.Present(
                            PairingRecord(TestValues.origin(), TestValues.deviceSessionId()),
                        )
                    } else {
                        PairingRecordReadOutcome.Missing
                    }
                }
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        AuthorizationRemoteOutcome.Success(
                            TestValues.session(TestValues.NOW + 300),
                        )
                    },
                )
            val coordinator = coordinator(gateway = gateway, store = store)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            assertEquals(AuthorizationCoordinatorOutcome.PairingRequired, coordinator.authorize())

            assertFalse(coordinator.hasActiveAccessSession())
            assertEquals(2, storeRead)
        }

    @Test
    fun `repeated challenge unavailable starts fresh challenges and retains old bearer`() =
        runTest {
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        if (proofAttempt == 1) {
                            AuthorizationRemoteOutcome.Success(
                                TestValues.session(TestValues.NOW + 300),
                            )
                        } else {
                            AuthorizationRemoteOutcome.Rejected(
                                AuthorizationRemoteFailure.CHALLENGE_UNAVAILABLE,
                                retryAfterSeconds = null,
                            )
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            val outcome = assertIs<AuthorizationCoordinatorOutcome.RemoteFailure>(coordinator.authorize())

            assertEquals(AuthorizationRemoteFailure.CHALLENGE_UNAVAILABLE, outcome.failure)
            assertEquals(3, gateway.challengeCalls)
            assertEquals(3, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `inactive cancels renewal but retains bearer and active resumes with a fresh exchange`() =
        runTest {
            val visibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND)
            val renewalStarted = CompletableDeferred<Unit>()
            var cancelledRenewal = false
            var challengeAttempt = 0
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeAttempt += 1
                        if (challengeAttempt == 2) {
                            renewalStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                cancelledRenewal = true
                            }
                        }
                        AuthorizationRemoteOutcome.Success(TestValues.challenge())
                    },
                    proofOperation = {
                        proofAttempt += 1
                        AuthorizationRemoteOutcome.Success(
                            TestValues.session(
                                if (proofAttempt == 1) TestValues.NOW + 300 else TestValues.NOW + 900,
                            ),
                        )
                    },
                )
            val coordinator = coordinator(gateway = gateway, visibility = visibility)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()
            renewalStarted.await()

            visibility.mutableState.value = ApplicationVisibilityState.INACTIVE
            runCurrent()

            assertTrue(cancelledRenewal)
            assertFalse(coordinator.hasActiveAccessSession())
            visibility.mutableState.value = ApplicationVisibilityState.ACTIVE_FOREGROUND
            runCurrent()

            assertEquals(3, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `background observer purges bearer without an inspection call`() =
        runTest {
            val visibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND)
            val gateway = FakeAuthorizationGateway()
            val coordinator = coordinator(gateway = gateway, visibility = visibility)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            visibility.mutableState.value = ApplicationVisibilityState.BACKGROUND_OR_LOCKED
            runCurrent()
            visibility.mutableState.value = ApplicationVisibilityState.ACTIVE_FOREGROUND
            runCurrent()

            assertFalse(coordinator.hasActiveAccessSession())
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
        }

    @Test
    fun `background observer cancels signing and wipes its transcript`() =
        runTest {
            val visibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND)
            val transcript = byteArrayOf(11, 12, 13)
            val signingStarted = CompletableDeferred<Unit>()
            val signer = FakeDeviceProofSigner()
            signer.signOperation = {
                signingStarted.complete(Unit)
                awaitCancellation()
            }
            val coordinator =
                coordinator(
                    signer = signer,
                    visibility = visibility,
                    encoder = DeviceProofTranscriptEncoder { _, _, _ -> transcript },
                )
            val authorization = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            signingStarted.await()

            visibility.mutableState.value = ApplicationVisibilityState.BACKGROUND_OR_LOCKED
            runCurrent()

            assertEquals(
                AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                authorization.await(),
            )
            assertTrue(transcript.all { byte -> byte == 0.toByte() })
            assertFalse(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `clock rollback cannot resurrect an expired session`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val coordinator = coordinator(clock = clock, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            clock.now = TestValues.ACCESS_EXPIRY
            deadlines.releaseOne(TestValues.ACCESS_EXPIRY)
            runCurrent()
            assertFalse(coordinator.hasActiveAccessSession())

            clock.now = TestValues.NOW
            assertFalse(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `early deadline wakes recheck the clock before renewal and expiry`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val gateway = FakeAuthorizationGateway()
            val coordinator =
                coordinator(gateway = gateway, clock = clock, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            val renewalDeadline = TestValues.ACCESS_EXPIRY - 300
            val expiryDeadline = TestValues.ACCESS_EXPIRY
            assertEquals(1, deadlines.pending(renewalDeadline))
            assertEquals(1, deadlines.pending(expiryDeadline))

            clock.now = TestValues.NOW + 100
            deadlines.releaseOne(renewalDeadline)
            deadlines.releaseOne(expiryDeadline)
            runCurrent()

            assertEquals(1, deadlines.pending(renewalDeadline))
            assertEquals(1, deadlines.pending(expiryDeadline))
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())

            clock.now = TestValues.NOW
            deadlines.releaseOne(renewalDeadline)
            deadlines.releaseOne(expiryDeadline)
            runCurrent()

            assertEquals(1, deadlines.pending(renewalDeadline))
            assertEquals(1, deadlines.pending(expiryDeadline))
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())
            coordinator.close()
        }

    @Test
    fun `default deadlines start renewal after a forward clock jump into the renewal window`() =
        runTest {
            val clock = MutableClock()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        AuthorizationRemoteOutcome.Success(
                            TestValues.session(
                                if (proofAttempt == 1) {
                                    TestValues.ACCESS_EXPIRY
                                } else {
                                    clock.now + 900
                                },
                            ),
                        )
                    },
                )
            val coordinator = coordinator(gateway = gateway, clock = clock)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            clock.now = TestValues.ACCESS_EXPIRY - 250
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2, gateway.challengeCalls)
            assertEquals(2, gateway.proofCalls)
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `close cancels timers drops authority and closes gateway once`() =
        runTest {
            val deadlines = ManualDeadlineWaiter()
            val gateway = FakeAuthorizationGateway()
            val coordinator = coordinator(gateway = gateway, deadlineWaiter = deadlines)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()
            assertEquals(2, deadlines.activeCount())
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)

            coordinator.close()
            coordinator.close()

            assertEquals(0, deadlines.activeCount())
            runCurrent()
            assertEquals(1, gateway.challengeCalls)
            assertEquals(1, gateway.proofCalls)
            assertTrue(gateway.closed)
            assertEquals(1, gateway.closeCalls)
            assertFalse(coordinator.hasActiveAccessSession())
            assertEquals(AuthorizationCoordinatorOutcome.Closed, coordinator.authorize())
        }

    @Test
    fun `cancelled close still commits flight cancellation and transport shutdown`() =
        runTest {
            val challengeStarted = CompletableDeferred<Unit>()
            val cancellationFinalizerStarted = CompletableDeferred<Unit>()
            val releaseCancellationFinalizer = CompletableDeferred<Unit>()
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cancellationFinalizerStarted.complete(Unit)
                                releaseCancellationFinalizer.await()
                            }
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            val authorization = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            challengeStarted.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { coordinator.close() }
            cancellationFinalizerStarted.await()

            closing.cancel()
            releaseCancellationFinalizer.complete(Unit)
            closing.join()
            runCurrent()

            assertEquals(AuthorizationCoordinatorOutcome.Closed, authorization.await())
            assertEquals(1, gateway.closeCalls)
            assertFalse(coordinator.hasActiveAccessSession())
            assertEquals(AuthorizationCoordinatorOutcome.Closed, coordinator.authorize())
        }

    @Test
    fun `process scope cancellation start purges authority before child cleanup completes`() =
        runTest {
            val processJob = SupervisorJob()
            val processScope = CoroutineScope(backgroundScope.coroutineContext + processJob)
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val lingeringChild =
                processScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleanupStarted.complete(Unit)
                            releaseCleanup.await()
                        }
                    }
                }
            val gateway = FakeAuthorizationGateway()
            val coordinator =
                coordinator(
                    gateway = gateway,
                    processScope = processScope,
                )
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            processJob.cancel()
            cleanupStarted.await()
            runCurrent()

            assertFalse(processJob.isCompleted)
            assertFalse(coordinator.hasActiveAccessSession())
            assertEquals(AuthorizationCoordinatorOutcome.Closed, coordinator.authorize())
            assertEquals(1, gateway.closeCalls)

            releaseCleanup.complete(Unit)
            lingeringChild.join()
            runCurrent()

            assertTrue(processJob.isCompleted)
            assertEquals(1, gateway.closeCalls)
        }

    @Test
    fun `normal process scope completion purges authority and closes transport once`() =
        runTest {
            val processJob = SupervisorJob()
            val gateway = FakeAuthorizationGateway()
            val coordinator =
                coordinator(
                    gateway = gateway,
                    processScope = CoroutineScope(backgroundScope.coroutineContext + processJob),
                )
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            assertTrue(processJob.complete())
            runCurrent()

            assertTrue(processJob.isCompleted)
            assertFalse(coordinator.hasActiveAccessSession())
            assertEquals(AuthorizationCoordinatorOutcome.Closed, coordinator.authorize())
            assertEquals(1, gateway.closeCalls)
        }

    @Test
    fun `process scope cancellation releases an in-flight waiter and cannot leave a sticky flight`() =
        runTest {
            val processJob = SupervisorJob()
            val challengeStarted = CompletableDeferred<Unit>()
            var challengeCancelled = false
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            challengeCancelled = true
                        }
                    },
                )
            val coordinator =
                coordinator(
                    gateway = gateway,
                    processScope = CoroutineScope(backgroundScope.coroutineContext + processJob),
                )
            val authorization = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            challengeStarted.await()

            processJob.cancel()
            runCurrent()

            assertTrue(challengeCancelled)
            assertEquals(AuthorizationCoordinatorOutcome.Closed, authorization.await())
            assertEquals(AuthorizationCoordinatorOutcome.Closed, coordinator.authorize())
            assertEquals(1, gateway.challengeCalls)
            assertEquals(0, gateway.proofCalls)
            assertEquals(1, gateway.closeCalls)
        }

    @Test
    fun `close cancels a process flight and releases every waiter with closed outcome`() =
        runTest {
            val challengeStarted = CompletableDeferred<Unit>()
            var challengeCancelled = false
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            challengeCancelled = true
                        }
                    },
                )
            val coordinator = coordinator(gateway = gateway)
            val first = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            challengeStarted.await()
            val joined = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }

            coordinator.close()

            assertTrue(challengeCancelled)
            assertEquals(AuthorizationCoordinatorOutcome.Closed, first.await())
            assertEquals(AuthorizationCoordinatorOutcome.Closed, joined.await())
            assertEquals(1, gateway.closeCalls)
        }

    @Test
    fun `waiter cancellation leaves process flight alive until explicit clear wipes transcript`() =
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
            assertFalse(transcript.all { byte -> byte == 0.toByte() })
            coordinator.clearAccessSession()
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

    private fun TestScope.coordinator(
        gateway: FakeAuthorizationGateway = FakeAuthorizationGateway(),
        signer: FakeDeviceProofSigner = FakeDeviceProofSigner(),
        encoder: DeviceProofTranscriptEncoder = DeviceProofTranscriptEncoder { _, _, _ -> byteArrayOf(1) },
        visibility: FakeVisibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND),
        store: PairingRecordStore = FakePairingRecordStore(),
        clock: Clock = Clock { TestValues.NOW },
        deadlineWaiter: AuthorizationDeadlineWaiter? = null,
        processScope: CoroutineScope = backgroundScope,
    ): AuthorizationCoordinator {
        val arguments =
            AuthorizationCoordinatorArguments(
                gateway = gateway,
                signer = signer,
                encoder = encoder,
                visibility = visibility,
                store = store,
                clock = clock,
            )
        return if (deadlineWaiter == null) {
            arguments.create(processScope)
        } else {
            arguments.create(processScope, deadlineWaiter)
        }
    }
}

private data class AuthorizationCoordinatorArguments(
    val gateway: AuthorizationRemoteGateway,
    val signer: DeviceProofSigner,
    val encoder: DeviceProofTranscriptEncoder,
    val visibility: ApplicationVisibility,
    val store: PairingRecordStore,
    val clock: Clock,
) {
    fun create(
        processScope: CoroutineScope,
        deadlineWaiter: AuthorizationDeadlineWaiter? = null,
    ): AuthorizationCoordinator =
        if (deadlineWaiter == null) {
            AuthorizationCoordinator(
                remoteGateway = gateway,
                transcriptEncoder = encoder,
                pairingRecordStore = store,
                deviceProofSigner = signer,
                applicationVisibility = visibility,
                clock = clock,
                selectedProtocolVersion = 1,
                processScope = processScope,
            )
        } else {
            AuthorizationCoordinator(
                remoteGateway = gateway,
                transcriptEncoder = encoder,
                pairingRecordStore = store,
                deviceProofSigner = signer,
                applicationVisibility = visibility,
                clock = clock,
                selectedProtocolVersion = 1,
                processScope = processScope,
                deadlineWaiter = deadlineWaiter,
            )
        }
}

private class MutableClock(
    var now: Long = TestValues.NOW,
) : Clock {
    override fun nowEpochSeconds(): Long = now
}

private class ManualDeadlineWaiter : AuthorizationDeadlineWaiter {
    private val requests: MutableList<DeadlineRequest> = mutableListOf()

    override suspend fun waitUntil(deadlineEpochSeconds: Long) {
        val request = DeadlineRequest(deadlineEpochSeconds)
        requests += request
        try {
            request.release.await()
        } finally {
            request.active = false
        }
    }

    fun pending(deadlineEpochSeconds: Long): Int =
        requests.count { request -> request.active && request.deadlineEpochSeconds == deadlineEpochSeconds }

    fun activeCount(): Int = requests.count { request -> request.active }

    fun releaseOne(deadlineEpochSeconds: Long) {
        val request =
            requests.firstOrNull { candidate ->
                candidate.active && candidate.deadlineEpochSeconds == deadlineEpochSeconds
            } ?: error("No pending deadline $deadlineEpochSeconds")
        request.release.complete(Unit)
    }

    private class DeadlineRequest(
        val deadlineEpochSeconds: Long,
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
        var active: Boolean = true,
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
    var closeCalls: Int = 0
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
        closeCalls += 1
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
    var deleteCalls: Int = 0

    override suspend fun read(): PairingRecordReadOutcome = readOperation()

    override suspend fun write(record: PairingRecord): PairingRecordWriteOutcome = PairingRecordWriteOutcome.Unavailable

    override suspend fun delete(): PairingRecordDeleteOutcome {
        deleteCalls += 1
        return PairingRecordDeleteOutcome.Unavailable
    }
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
