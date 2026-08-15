@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.InternalForInheritanceCoroutinesApi::class,
    kotlinx.coroutines.InternalCoroutinesApi::class,
)

package org.rotki.mobile.feature.authorization.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteOutcome
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthorizationAuthorityRaceTest {
    @Test
    fun `executor local cancellation is rethrown while request authority remains active`() =
        runTest {
            val request = TestAuthorizationRequest<Unit>("local-cancellation")
            val executor =
                FakeAuthorizationRequestExecutor { _, _ ->
                    throw CancellationException("executor-local-cancellation")
                }
            val coordinator = authorityCoordinator(requestExecutor = executor)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            val cancellation =
                try {
                    coordinator.execute(request)
                    error("Expected executor-local cancellation")
                } catch (caught: CancellationException) {
                    caught
                }

            assertEquals("executor-local-cancellation", cancellation.message)
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `caller cancellation after admission prevents executor dispatch`() =
        runTest {
            val request = TestAuthorizationRequest<Unit>("admission-start-cancellation")
            var invocationCount = 0
            val executor =
                FakeAuthorizationRequestExecutor { _, _ ->
                    invocationCount += 1
                    Unit
                }
            val coordinator = authorityCoordinator(requestExecutor = executor)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            val callerJob = CancelOnCancellingHandlerRegistrationJob()
            val completion =
                CompletableDeferred<Result<AuthorizationAuthorityUseOutcome<Unit>>>()
            val callerContext = coroutineContext.minusKey(Job) + callerJob

            suspend { coordinator.execute(request) }
                .startCoroutine(
                    object : Continuation<AuthorizationAuthorityUseOutcome<Unit>> {
                        override val context: CoroutineContext = callerContext

                        override fun resumeWith(result: Result<AuthorizationAuthorityUseOutcome<Unit>>) {
                            completion.complete(result)
                        }
                    },
                )
            runCurrent()

            val result = completion.await()
            assertEquals(0, invocationCount)
            assertTrue(
                result.exceptionOrNull() is CancellationException ||
                    result.getOrNull() == AuthorizationAuthorityUseOutcome.AuthorityLost,
            )
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `running caller cancellation cancels only its request and retains session`() =
        runTest {
            val request = TestAuthorizationRequest<Unit>("running-caller-cancellation")
            val operationStarted = CompletableDeferred<Unit>()
            var operationCancelled = false
            val executor =
                FakeAuthorizationRequestExecutor { _, credential ->
                    assertTrue(credential.applyTo(RecordingAuthorizationTarget()))
                    operationStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        operationCancelled = true
                    }
                }
            val coordinator = authorityCoordinator(requestExecutor = executor)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            val caller =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(request)
                }
            operationStarted.await()

            caller.cancelAndJoin()

            assertTrue(caller.isCancelled)
            assertTrue(operationCancelled)
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `request credential applies once and retained capability stays invalid after return`() =
        runTest {
            val request = TestAuthorizationRequest<Unit>("one-shot-credential")
            lateinit var retainedCredential: AuthorizationRequestCredential
            val executor =
                FakeAuthorizationRequestExecutor { _, credential ->
                    retainedCredential = credential
                    val firstTarget = RecordingAuthorizationTarget()
                    assertTrue(credential.applyTo(firstTarget))
                    assertEquals(TestValues.ACCESS_CREDENTIAL, firstTarget.credential)
                    assertFalse(credential.applyTo(RecordingAuthorizationTarget()))
                    Unit
                }
            val coordinator = authorityCoordinator(requestExecutor = executor)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            assertEquals(
                AuthorizationAuthorityUseOutcome.Executed(Unit),
                coordinator.execute(request),
            )
            assertFalse(retainedCredential.applyTo(RecordingAuthorizationTarget()))
        }

    @Test
    fun `throwing authorization target still consumes the one-shot credential`() =
        runTest {
            val request = TestAuthorizationRequest<Unit>("throwing-target")
            val executor =
                FakeAuthorizationRequestExecutor { _, credential ->
                    val failure =
                        try {
                            credential.applyTo { error("target-rejected") }
                            error("Expected target failure")
                        } catch (caught: IllegalStateException) {
                            caught
                        }
                    assertEquals("target-rejected", failure.message)
                    assertFalse(credential.applyTo(RecordingAuthorizationTarget()))
                    Unit
                }
            val coordinator = authorityCoordinator(requestExecutor = executor)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            assertEquals(
                AuthorizationAuthorityUseOutcome.Executed(Unit),
                coordinator.execute(request),
            )
        }

    @Test
    fun `renewal fences pre-apply old request but lets already-applied old request finish`() =
        runTest {
            val oldExpiry = TestValues.NOW + 301
            val clock = MutableClock()
            val preApplyRequest = TestAuthorizationRequest<String>("pre-apply-old-request")
            val appliedRequest = TestAuthorizationRequest<String>("applied-old-request")
            val preApplyStarted = CompletableDeferred<Unit>()
            val appliedStarted = CompletableDeferred<Unit>()
            val attemptPreApply = CompletableDeferred<Unit>()
            val preApplyResult = CompletableDeferred<Boolean>()
            val releaseApplied = CompletableDeferred<Unit>()
            val executor =
                FakeAuthorizationRequestExecutor { request, credential ->
                    when (request) {
                        preApplyRequest -> {
                            withContext(NonCancellable) {
                                preApplyStarted.complete(Unit)
                                attemptPreApply.await()
                                preApplyResult.complete(
                                    credential.applyTo(RecordingAuthorizationTarget()),
                                )
                            }
                            "pre-apply-finished"
                        }

                        appliedRequest -> {
                            val target = RecordingAuthorizationTarget()
                            assertTrue(credential.applyTo(target))
                            assertEquals(TestValues.ACCESS_CREDENTIAL, target.credential)
                            appliedStarted.complete(Unit)
                            releaseApplied.await()
                            "applied-finished"
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
                    requestExecutor = executor,
                )
            val initial = assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            val preApplyWork =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(preApplyRequest)
                }
            val appliedWork =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(appliedRequest)
                }
            preApplyStarted.await()
            appliedStarted.await()

            clock.now = TestValues.NOW + 1
            val renewed = assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertTrue(renewed.sessionRevision > initial.sessionRevision)

            attemptPreApply.complete(Unit)
            runCurrent()
            assertFalse(preApplyResult.await())
            assertEquals(AuthorizationAuthorityUseOutcome.AuthorityLost, preApplyWork.await())

            releaseApplied.complete(Unit)
            assertEquals(
                AuthorizationAuthorityUseOutcome.Executed("applied-finished"),
                appliedWork.await(),
            )
            assertTrue(coordinator.hasActiveAccessSession())
        }

    @Test
    fun `protocol selection cannot mix versions between challenge and proof`() =
        runTest {
            val firstChallengeStarted = CompletableDeferred<Unit>()
            val releaseFirstChallenge = CompletableDeferred<Unit>()
            var challengeAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    challengeOperation = {
                        challengeAttempt += 1
                        if (challengeAttempt == 1) {
                            withContext(NonCancellable) {
                                firstChallengeStarted.complete(Unit)
                                releaseFirstChallenge.await()
                            }
                        }
                        AuthorizationRemoteOutcome.Success(TestValues.challenge())
                    },
                    expectedProtocolVersion = null,
                )
            val coordinator = authorityCoordinator(gateway = gateway)
            val oldFlight =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.authorize()
                }
            firstChallengeStarted.await()

            val selection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.selectProtocolVersion(2)
                }
            assertFalse(selection.isCompleted)
            assertEquals(AuthorizationCoordinatorOutcome.OutsideActiveForeground, oldFlight.await())

            releaseFirstChallenge.complete(Unit)
            selection.await()
            assertTrue(gateway.proofProtocolVersions.isEmpty())

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertEquals(listOf(1, 2), gateway.challengeProtocolVersions)
            assertEquals(listOf(2), gateway.proofProtocolVersions)
        }

    @Test
    fun `expiry event is emitted before a noncancellable request finalizer completes`() =
        runTest {
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val events = RecordingAuthorizationEventSink()
            val request = TestAuthorizationRequest<Unit>("expiry-finalizer")
            val operationStarted = CompletableDeferred<Unit>()
            val finalizerStarted = CompletableDeferred<Unit>()
            val finalizerCompleted = CompletableDeferred<Unit>()
            val releaseFinalizer = CompletableDeferred<Unit>()
            val executor =
                FakeAuthorizationRequestExecutor { _, credential ->
                    assertTrue(credential.applyTo(RecordingAuthorizationTarget()))
                    try {
                        operationStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            finalizerStarted.complete(Unit)
                            releaseFinalizer.await()
                            finalizerCompleted.complete(Unit)
                        }
                    }
                }
            val coordinator =
                authorityCoordinator(
                    clock = clock,
                    deadlineWaiter = deadlines,
                    eventSink = events,
                    requestExecutor = executor,
                )
            val authorized = assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()
            val work =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(request)
                }
            operationStarted.await()

            clock.now = TestValues.ACCESS_EXPIRY
            deadlines.releaseOne(TestValues.ACCESS_EXPIRY)
            runCurrent()

            assertTrue(finalizerStarted.isCompleted)
            assertFalse(finalizerCompleted.isCompleted)
            assertFalse(work.isCompleted)
            assertEquals(
                listOf(authorized.sessionRevision),
                events.values
                    .filterIsInstance<AuthorizationCoordinatorEvent.AccessSessionExpired>()
                    .map { event -> event.sessionRevision },
            )

            releaseFinalizer.complete(Unit)
            assertEquals(AuthorizationAuthorityUseOutcome.AuthorityLost, work.await())
            assertTrue(finalizerCompleted.isCompleted)
        }

    @Test
    fun `terminal exchange event is emitted before a noncancellable request finalizer completes`() =
        runTest {
            val oldExpiry = TestValues.NOW + 301
            val clock = MutableClock()
            val events = RecordingAuthorizationEventSink()
            val request = TestAuthorizationRequest<Unit>("terminal-finalizer")
            val operationStarted = CompletableDeferred<Unit>()
            val finalizerStarted = CompletableDeferred<Unit>()
            val finalizerCompleted = CompletableDeferred<Unit>()
            val releaseFinalizer = CompletableDeferred<Unit>()
            val executor =
                FakeAuthorizationRequestExecutor { _, credential ->
                    assertTrue(credential.applyTo(RecordingAuthorizationTarget()))
                    try {
                        operationStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            finalizerStarted.complete(Unit)
                            releaseFinalizer.await()
                            finalizerCompleted.complete(Unit)
                        }
                    }
                }
            var proofAttempt = 0
            val gateway =
                FakeAuthorizationGateway(
                    proofOperation = {
                        proofAttempt += 1
                        if (proofAttempt == 1) {
                            AuthorizationRemoteOutcome.Success(TestValues.session(oldExpiry))
                        } else {
                            AuthorizationRemoteOutcome.Rejected(
                                AuthorizationRemoteFailure.NOT_AUTHORIZED,
                                retryAfterSeconds = null,
                            )
                        }
                    },
                )
            val coordinator =
                authorityCoordinator(
                    gateway = gateway,
                    clock = clock,
                    eventSink = events,
                    requestExecutor = executor,
                )
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            val work =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(request)
                }
            operationStarted.await()

            clock.now = TestValues.NOW + 1
            val renewal = async(start = CoroutineStart.UNDISPATCHED) { coordinator.authorize() }
            runCurrent()

            assertTrue(finalizerStarted.isCompleted)
            assertFalse(finalizerCompleted.isCompleted)
            assertFalse(renewal.isCompleted)
            val terminal =
                events.values
                    .filterIsInstance<AuthorizationCoordinatorEvent.ExchangeCompleted>()
                    .last()
            val terminalFailure =
                assertIs<AuthorizationCoordinatorOutcome.RemoteFailure>(terminal.outcome)
            assertEquals(AuthorizationRemoteFailure.NOT_AUTHORIZED, terminalFailure.failure)

            releaseFinalizer.complete(Unit)
            assertEquals(terminalFailure, renewal.await())
            assertEquals(AuthorizationAuthorityUseOutcome.AuthorityLost, work.await())
            assertTrue(finalizerCompleted.isCompleted)
        }

    @Test
    fun `old requests finish before old expiry but are cancelled at exact expiry after renewal`() =
        runTest {
            val oldExpiry = TestValues.NOW + 301
            val clock = MutableClock()
            val deadlines = ManualDeadlineWaiter()
            val finishRequest = TestAuthorizationRequest<String>("finish-before-old-expiry")
            val expireRequest = TestAuthorizationRequest<String>("expire-at-old-expiry")
            val newRequest = TestAuthorizationRequest<String>("new-session-request")
            val finishStarted = CompletableDeferred<Unit>()
            val expireStarted = CompletableDeferred<Unit>()
            val releaseFinish = CompletableDeferred<Unit>()
            val expiredWorkCancelled = CompletableDeferred<Unit>()
            val executor =
                FakeAuthorizationRequestExecutor { request, credential ->
                    val target = RecordingAuthorizationTarget()
                    assertTrue(credential.applyTo(target))
                    when (request) {
                        finishRequest -> {
                            assertEquals(TestValues.ACCESS_CREDENTIAL, target.credential)
                            finishStarted.complete(Unit)
                            releaseFinish.await()
                            "old-finished"
                        }

                        expireRequest -> {
                            assertEquals(TestValues.ACCESS_CREDENTIAL, target.credential)
                            expireStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                expiredWorkCancelled.complete(Unit)
                            }
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
            runCurrent()
            val finishingWork =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(finishRequest)
                }
            val expiringWork =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(expireRequest)
                }
            finishStarted.await()
            expireStarted.await()

            clock.now = TestValues.NOW + 1
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            runCurrent()

            releaseFinish.complete(Unit)
            assertEquals(
                AuthorizationAuthorityUseOutcome.Executed("old-finished"),
                finishingWork.await(),
            )
            assertFalse(expiringWork.isCompleted)

            clock.now = oldExpiry
            deadlines.releaseOne(oldExpiry)
            runCurrent()
            assertTrue(expiredWorkCancelled.isCompleted)
            assertEquals(AuthorizationAuthorityUseOutcome.AuthorityLost, expiringWork.await())
            assertTrue(coordinator.hasActiveAccessSession())
            assertEquals(
                AuthorizationAuthorityUseOutcome.Executed("new-finished"),
                coordinator.execute(newRequest),
            )
        }
}

private class CancelOnCancellingHandlerRegistrationJob(
    private val delegate: Job = SupervisorJob(),
) : Job by delegate {
    override val key: CoroutineContext.Key<*>
        get() = Job

    override fun <R> fold(
        initial: R,
        operation: (R, CoroutineContext.Element) -> R,
    ): R = operation(initial, this)

    @Suppress("UNCHECKED_CAST")
    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
        if (key === Job) this as E else null

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
        if (key === Job) EmptyCoroutineContext else this

    override fun invokeOnCompletion(
        onCancelling: Boolean,
        invokeImmediately: Boolean,
        handler: (cause: Throwable?) -> Unit,
    ): DisposableHandle {
        if (onCancelling) {
            delegate.cancel(CancellationException("caller-cancelled-after-admission"))
        }
        return delegate.invokeOnCompletion(onCancelling, invokeImmediately, handler)
    }
}
