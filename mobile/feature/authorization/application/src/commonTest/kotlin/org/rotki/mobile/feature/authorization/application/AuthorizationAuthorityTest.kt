@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.rotki.mobile.feature.authorization.application

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.protocol.AccessSessionAuthorizationTarget
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteOutcome
import org.rotki.mobile.feature.authorization.domain.DeviceProofTranscriptEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthorizationAuthorityTest {
    @Test
    fun `authorized outcome requires a positive revision`() {
        assertFailsWith<IllegalArgumentException> {
            AuthorizationCoordinatorOutcome.Authorized(
                expiresAtEpochSeconds = 2_000,
                sessionRevision = 0,
            )
        }
    }

    @Test
    fun `owner flight emits one acquisition event pair for every joined caller`() =
        runTest {
            val challengeStarted = CompletableDeferred<Unit>()
            val releaseChallenge = CompletableDeferred<Unit>()
            val events = RecordingAuthorizationEventSink()
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeStarted.complete(Unit)
                        releaseChallenge.await()
                        AuthorizationRemoteOutcome.Success(TestValues.challenge())
                    },
                )
            val coordinator = authorityCoordinator(gateway = gateway, eventSink = events)

            val owner = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            challengeStarted.await()
            val joiner = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }

            val started =
                assertIs<AuthorizationCoordinatorEvent.ExchangeStarted>(events.values.single())
            assertEquals(1L, started.exchangeId)
            assertEquals(AuthorizationExchangeKind.ACQUISITION, started.kind)

            releaseChallenge.complete(Unit)
            val ownerOutcome = assertIs<AuthorizationCoordinatorOutcome.Authorized>(owner.await())
            assertEquals(ownerOutcome, joiner.await())

            val completed =
                assertIs<AuthorizationCoordinatorEvent.ExchangeCompleted>(events.values.last())
            assertEquals(2, events.values.size)
            assertEquals(started.exchangeId, completed.exchangeId)
            assertEquals(AuthorizationExchangeKind.ACQUISITION, completed.kind)
            assertEquals(ownerOutcome, completed.outcome)

            assertEquals(ownerOutcome, coordinator.authorize())
            assertEquals(2, events.values.size)
            assertFalse(TestValues.ACCESS_CREDENTIAL in events.values.joinToString())
        }

    @Test
    fun `exchange events distinguish acquisition from renewal`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val events = RecordingAuthorizationEventSink()
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        AuthorizationRemoteOutcome.Success(
                            TestValues.session(
                                if (proofAttempt == 1) TestValues.NOW + 301 else TestValues.ACCESS_EXPIRY,
                            ),
                        )
                    },
                )
            val coordinator =
                authorityCoordinator(
                    gateway = gateway,
                    clock = clock,
                    deadlineWaiter = deadlines,
                    eventSink = events,
                )

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()
            clock.now = TestValues.NOW + 1
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            val started = events.values.filterIsInstance<AuthorizationCoordinatorEvent.ExchangeStarted>()
            val completed = events.values.filterIsInstance<AuthorizationCoordinatorEvent.ExchangeCompleted>()
            assertEquals(
                listOf(AuthorizationExchangeKind.ACQUISITION, AuthorizationExchangeKind.RENEWAL),
                started.map(AuthorizationCoordinatorEvent.ExchangeStarted::kind),
            )
            assertEquals(started.map { event -> event.exchangeId }, completed.map { event -> event.exchangeId })
            assertEquals(started.map { event -> event.kind }, completed.map { event -> event.kind })
        }

    @Test
    fun `only the current session revision emits expiry`() =
        runTest {
            val oldExpiry = TestValues.NOW + 301
            val newExpiry = TestValues.NOW + 1_000
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter(nonCooperativeDeadlines = setOf(oldExpiry))
            val events = RecordingAuthorizationEventSink()
            val request = TestAuthorizationRequest<Boolean>("current-credential")
            val executor =
                FakeAuthorizationRequestExecutor { _, credential ->
                    val target = RecordingAuthorizationTarget()
                    assertTrue(credential.applyTo(target))
                    target.credential == TestValues.ALTERNATE_ACCESS_CREDENTIAL
                }
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        AuthorizationRemoteOutcome.Success(
                            if (proofAttempt == 1) {
                                TestValues.session(oldExpiry)
                            } else {
                                TestValues.session(TestValues.alternateCredential(), newExpiry)
                            },
                        )
                    },
                )
            val coordinator =
                authorityCoordinator(
                    gateway = gateway,
                    clock = clock,
                    deadlineWaiter = deadlines,
                    eventSink = events,
                    requestExecutor = executor,
                )

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()
            assertEquals(1, deadlines.pending(oldExpiry))

            clock.now = TestValues.NOW + 1
            val renewed = assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            clock.now = oldExpiry
            deadlines.releaseOne(oldExpiry)
            runCurrent()
            assertTrue(
                events.values.none { event -> event is AuthorizationCoordinatorEvent.AccessSessionExpired },
            )
            assertEquals(1, deadlines.pending(newExpiry))
            val currentUse =
                assertIs<AuthorizationAuthorityUseOutcome.Executed<Boolean>>(
                    coordinator.execute(request),
                )
            assertTrue(currentUse.value)

            clock.now = newExpiry
            deadlines.releaseOne(newExpiry)
            runCurrent()
            val expiryEvents =
                events.values.filterIsInstance<AuthorizationCoordinatorEvent.AccessSessionExpired>()
            assertEquals(listOf(renewed.sessionRevision), expiryEvents.map { event -> event.sessionRevision })
            assertEquals(
                AuthorizationAuthorityUseOutcome.Unavailable,
                coordinator.execute(request),
            )
        }

    @Test
    fun `late terminal proof after background is governed outside and emits no terminal event`() =
        runTest {
            val proofStarted = CompletableDeferred<Unit>()
            val releaseProof = CompletableDeferred<Unit>()
            val visibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND)
            val events = RecordingAuthorizationEventSink()
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        withContext(NonCancellable) {
                            proofStarted.complete(Unit)
                            releaseProof.await()
                        }
                        AuthorizationRemoteOutcome.Rejected(
                            AuthorizationRemoteFailure.NOT_AUTHORIZED,
                            retryAfterSeconds = null,
                        )
                    },
                )
            val coordinator =
                authorityCoordinator(
                    gateway = gateway,
                    visibility = visibility,
                    eventSink = events,
                )
            val authorization = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            proofStarted.await()

            visibility.mutableState.value = ApplicationVisibilityState.BACKGROUND_OR_LOCKED
            runCurrent()
            assertEquals(AuthorizationCoordinatorOutcome.OutsideActiveForeground, authorization.await())

            releaseProof.complete(Unit)
            runCurrent()
            val completions =
                events.values.filterIsInstance<AuthorizationCoordinatorEvent.ExchangeCompleted>()
            assertTrue(
                completions.none { completion ->
                    val outcome = completion.outcome
                    outcome is AuthorizationCoordinatorOutcome.RemoteFailure &&
                        outcome.failure == AuthorizationRemoteFailure.NOT_AUTHORIZED
                },
            )
            assertTrue(
                completions.all { completion ->
                    completion.outcome == AuthorizationCoordinatorOutcome.OutsideActiveForeground
                },
            )
            assertFalse(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `scoped request authority executes only with an active unexpired credential`() =
        runTest {
            val request = TestAuthorizationRequest<String>("scoped-request")
            var encodedScopeEntered = false
            val executor =
                FakeAuthorizationRequestExecutor { suppliedRequest, credential ->
                    assertTrue(suppliedRequest === request)
                    assertEquals("AuthorizationRequestCredential(redacted)", credential.toString())
                    val target = RecordingAuthorizationTarget()
                    assertTrue(credential.applyTo(target))
                    encodedScopeEntered = true
                    assertEquals(TestValues.ACCESS_CREDENTIAL, target.credential)
                    "request-complete"
                }
            val coordinator = authorityCoordinator(requestExecutor = executor)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            val outcome =
                assertIs<AuthorizationAuthorityUseOutcome.Executed<String>>(
                    coordinator.execute(request),
                )

            assertTrue(encodedScopeEntered)
            assertEquals("request-complete", outcome.value)
            assertEquals("Executed(redacted)", outcome.toString())
            assertFalse(TestValues.ACCESS_CREDENTIAL in outcome.toString())
        }

    @Test
    fun `renewal lets old work finish while new work receives the replacement credential`() =
        runTest {
            val oldExpiry = TestValues.NOW + 301
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val oldWorkStarted = CompletableDeferred<Unit>()
            val releaseOldWork = CompletableDeferred<Unit>()
            val oldRequest = TestAuthorizationRequest<String>("old-request")
            val newRequest = TestAuthorizationRequest<String>("new-request")
            val executor =
                FakeAuthorizationRequestExecutor { request, credential ->
                    val target = RecordingAuthorizationTarget()
                    assertTrue(credential.applyTo(target))
                    when (request) {
                        oldRequest -> {
                            assertEquals(TestValues.ACCESS_CREDENTIAL, target.credential)
                            oldWorkStarted.complete(Unit)
                            releaseOldWork.await()
                            "old-finished"
                        }

                        newRequest -> {
                            assertEquals(TestValues.ALTERNATE_ACCESS_CREDENTIAL, target.credential)
                            "new-finished"
                        }

                        else -> {
                            error("Unexpected test request")
                        }
                    }
                }
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        AuthorizationRemoteOutcome.Success(
                            if (proofAttempt == 1) {
                                TestValues.session(oldExpiry)
                            } else {
                                TestValues.session(
                                    TestValues.alternateCredential(),
                                    TestValues.ACCESS_EXPIRY,
                                )
                            },
                        )
                    },
                )
            val coordinator =
                authorityCoordinator(
                    gateway = gateway,
                    clock = clock,
                    deadlineWaiter = deadlines,
                    requestExecutor = executor,
                )
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            val oldWork =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(oldRequest)
                }
            oldWorkStarted.await()

            clock.now = TestValues.NOW + 1
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertFalse(oldWork.isCompleted)
            val newWork =
                assertIs<AuthorizationAuthorityUseOutcome.Executed<String>>(
                    coordinator.execute(newRequest),
                )
            assertEquals("new-finished", newWork.value)

            releaseOldWork.complete(Unit)
            assertEquals(
                AuthorizationAuthorityUseOutcome.Executed("old-finished"),
                oldWork.await(),
            )
        }

    @Test
    fun `inactive background clear and expiry cancel admitted authenticated work`() =
        runTest {
            AuthorityInvalidation.entries.forEach { invalidation ->
                val visibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND)
                val clock = MutableClock()
                val deadlines = ManualDeadlineWaiter()
                val request = TestAuthorizationRequest<Unit>("cancel-${invalidation.name}")
                val operationStarted = CompletableDeferred<Unit>()
                var operationCancelled = false
                val executor =
                    FakeAuthorizationRequestExecutor { _, _ ->
                        operationStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            operationCancelled = true
                        }
                    }
                val coordinator =
                    authorityCoordinator(
                        visibility = visibility,
                        clock = clock,
                        deadlineWaiter = deadlines,
                        requestExecutor = executor,
                    )
                assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
                runCurrent()
                val work =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        coordinator.execute(request)
                    }
                operationStarted.await()

                when (invalidation) {
                    AuthorityInvalidation.INACTIVE -> {
                        visibility.mutableState.value = ApplicationVisibilityState.INACTIVE
                        runCurrent()
                    }

                    AuthorityInvalidation.BACKGROUND -> {
                        visibility.mutableState.value = ApplicationVisibilityState.BACKGROUND_OR_LOCKED
                        runCurrent()
                    }

                    AuthorityInvalidation.CLEAR -> {
                        coordinator.clearAccessSession()
                    }

                    AuthorityInvalidation.EXPIRY -> {
                        clock.now = TestValues.ACCESS_EXPIRY
                        deadlines.releaseOne(TestValues.ACCESS_EXPIRY)
                        runCurrent()
                    }
                }

                assertEquals(
                    AuthorizationAuthorityUseOutcome.AuthorityLost,
                    work.await(),
                    invalidation.name,
                )
                assertTrue(operationCancelled, invalidation.name)
                if (invalidation == AuthorityInvalidation.INACTIVE) {
                    assertFalse(coordinator.hasActiveAccessSession())
                    visibility.mutableState.value = ApplicationVisibilityState.ACTIVE_FOREGROUND
                    runCurrent()
                    assertTrue(coordinator.hasActiveAccessSession())
                } else {
                    assertFalse(coordinator.hasActiveAccessSession())
                }
                coordinator.close()
            }
        }

    @Test
    fun `request authority reports unavailable outside foreground and closed without invoking work`() =
        runTest {
            val visibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND)
            var invocationCount = 0
            val request = TestAuthorizationRequest<Unit>("unavailable-request")
            val executor =
                FakeAuthorizationRequestExecutor { _, _ ->
                    invocationCount += 1
                    Unit
                }
            val coordinator = authorityCoordinator(visibility = visibility, requestExecutor = executor)

            val unavailable = coordinator.execute(request)
            visibility.mutableState.value = ApplicationVisibilityState.INACTIVE
            runCurrent()
            val outside = coordinator.execute(request)
            coordinator.close()
            val closed = coordinator.execute(request)

            assertEquals(AuthorizationAuthorityUseOutcome.Unavailable, unavailable)
            assertEquals(AuthorizationAuthorityUseOutcome.OutsideActiveForeground, outside)
            assertEquals(AuthorizationAuthorityUseOutcome.Closed, closed)
            assertEquals(0, invocationCount)
            listOf(unavailable, outside, closed).forEach { outcome ->
                assertFalse(TestValues.ACCESS_CREDENTIAL in outcome.toString())
            }
        }
}

internal fun TestScope.authorityCoordinator(
    gateway: FakeAuthorizationGateway = FakeAuthorizationGateway(),
    visibility: FakeVisibility = FakeVisibility(ApplicationVisibilityState.ACTIVE_FOREGROUND),
    clock: MutableClock = MutableClock(),
    deadlineWaiter: AuthorizationDeadlineWaiter? = null,
    eventSink: AuthorizationCoordinatorEventSink = AuthorizationCoordinatorEventSink { },
    requestExecutor: AuthorizationRequestExecutor? = null,
    processScope: CoroutineScope? = null,
): AuthorizationCoordinator {
    val commonArguments =
        AuthorizationCoordinatorArguments(
            gateway = gateway,
            signer = FakeDeviceProofSigner(),
            encoder = DeviceProofTranscriptEncoder { _, _, _ -> byteArrayOf(1) },
            visibility = visibility,
            store = FakePairingRecordStore(),
            clock = clock,
            eventSink = eventSink,
            requestExecutor = requestExecutor,
        )
    return commonArguments.create(processScope ?: backgroundScope, deadlineWaiter)
}

internal class RecordingAuthorizationEventSink : AuthorizationCoordinatorEventSink {
    val values: MutableList<AuthorizationCoordinatorEvent> = mutableListOf()

    override suspend fun emit(event: AuthorizationCoordinatorEvent) {
        values += event
    }
}

private enum class AuthorityInvalidation {
    INACTIVE,
    BACKGROUND,
    CLEAR,
    EXPIRY,
}

internal class TestAuthorizationRequest<R : Any>(
    val identifier: String,
) : AuthorizationRequest<R> {
    override fun toString(): String = "TestAuthorizationRequest(redacted)"
}

internal class FakeAuthorizationRequestExecutor(
    private val operation: suspend (AuthorizationRequest<*>, AuthorizationRequestCredential) -> Any,
) : AuthorizationRequestExecutor {
    @Suppress("UNCHECKED_CAST")
    override suspend fun <R : Any> execute(
        request: AuthorizationRequest<R>,
        credential: AuthorizationRequestCredential,
    ): R = operation(request, credential) as R
}

internal class RecordingAuthorizationTarget : AccessSessionAuthorizationTarget {
    var credential: String? = null
        private set

    override fun setBearerCredential(encodedCredential: String) {
        credential = encodedCredential
    }
}
