@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.rotki.mobile.feature.authorization.application

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AuthorizationInvalidationTest {
    @Test
    fun `begin clear fences bearer and credential before blocked finalizer completion`() =
        runTest {
            val request = TestAuthorizationRequest<Unit>("begin-clear-finalizer")
            val operationStarted = CompletableDeferred<Unit>()
            val finalizerStarted = CompletableDeferred<Unit>()
            val attemptApply = CompletableDeferred<Unit>()
            val applyResult = CompletableDeferred<Boolean>()
            val releaseFinalizer = CompletableDeferred<Unit>()
            val executor =
                FakeAuthorizationRequestExecutor { _, credential ->
                    try {
                        operationStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            finalizerStarted.complete(Unit)
                            attemptApply.await()
                            applyResult.complete(
                                credential.applyTo(RecordingAuthorizationTarget()),
                            )
                            releaseFinalizer.await()
                        }
                    }
                }
            val coordinator = authorityCoordinator(requestExecutor = executor)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            assertTrue(coordinator.hasActiveAccessSession())
            val work =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(request)
                }
            operationStarted.await()

            val invalidation = coordinator.beginClearAccessSession()
            assertFalse(coordinator.hasActiveAccessSession())
            finalizerStarted.await()
            val completion =
                async(start = CoroutineStart.UNDISPATCHED) {
                    invalidation.awaitCompletion()
                }
            assertFalse(completion.isCompleted)

            attemptApply.complete(Unit)
            runCurrent()
            assertFalse(applyResult.await())
            assertFalse(completion.isCompleted)

            releaseFinalizer.complete(Unit)
            assertEquals(AuthorizationAuthorityUseOutcome.AuthorityLost, work.await())
            completion.await()
        }

    @Test
    fun `external begin close wins queued process cancellation and closes gateway before cleanup`() =
        runTest {
            val processJob = SupervisorJob()
            val processScope = CoroutineScope(backgroundScope.coroutineContext + processJob)
            val request = TestAuthorizationRequest<Unit>("external-close-owner")
            val operationStarted = CompletableDeferred<Unit>()
            val finalizerStarted = CompletableDeferred<Unit>()
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
                        }
                    }
                }
            val gateway = FakeAuthorizationGateway()
            val coordinator =
                authorityCoordinator(
                    gateway = gateway,
                    requestExecutor = executor,
                    processScope = processScope,
                )
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())
            val work =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(request)
                }
            operationStarted.await()

            processJob.cancel()
            val invalidation = coordinator.beginClose()
            assertEquals(1, gateway.closeCalls)
            assertEquals(AuthorizationCoordinatorOutcome.Closed, coordinator.authorize())
            finalizerStarted.await()
            val completion =
                async(start = CoroutineStart.UNDISPATCHED) {
                    invalidation.awaitCompletion()
                }
            assertFalse(completion.isCompleted)

            val joinedInvalidation = coordinator.beginClose()
            assertTrue(joinedInvalidation === invalidation)
            val joinedCompletion =
                async(start = CoroutineStart.UNDISPATCHED) {
                    joinedInvalidation.awaitCompletion()
                }
            assertFalse(joinedCompletion.isCompleted)
            assertEquals(1, gateway.closeCalls)

            releaseFinalizer.complete(Unit)
            assertEquals(AuthorizationAuthorityUseOutcome.AuthorityLost, work.await())
            completion.await()
            joinedCompletion.await()
            coordinator.close()
            assertEquals(1, gateway.closeCalls)
        }

    @Test
    fun `process cancellation watcher owns close and external caller joins exactly once`() =
        runTest {
            val processJob = SupervisorJob()
            val processScope = CoroutineScope(backgroundScope.coroutineContext + processJob)
            val gateway = FakeAuthorizationGateway()
            val coordinator =
                authorityCoordinator(
                    gateway = gateway,
                    processScope = processScope,
                )
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            processJob.cancel()
            runCurrent()
            assertEquals(1, gateway.closeCalls)

            val externalInvalidation = coordinator.beginClose()
            externalInvalidation.awaitCompletion()
            coordinator.close()
            assertEquals(AuthorizationCoordinatorOutcome.Closed, coordinator.authorize())
            assertEquals(1, gateway.closeCalls)
        }

    @Test
    fun `executor and finalizer process reentry fail fast without leaking credential`() =
        runTest {
            val directRequest = TestAuthorizationRequest<Unit>("direct-reentry")
            val finalizerRequest = TestAuthorizationRequest<Unit>("finalizer-reentry")
            val directFailure = CompletableDeferred<IllegalStateException>()
            val finalizerStarted = CompletableDeferred<Unit>()
            val finalizerFailure = CompletableDeferred<IllegalStateException>()
            val finalizerApplyResult = CompletableDeferred<Boolean>()
            lateinit var retainedDirectCredential: AuthorizationRequestCredential
            lateinit var retainedFinalizerCredential: AuthorizationRequestCredential
            lateinit var coordinator: AuthorizationCoordinator
            val executor =
                FakeAuthorizationRequestExecutor { request, credential ->
                    when (request) {
                        directRequest -> {
                            retainedDirectCredential = credential
                            directFailure.complete(captureReentryFailure { coordinator.hasActiveAccessSession() })
                            assertTrue(credential.applyTo(RecordingAuthorizationTarget()))
                            Unit
                        }

                        finalizerRequest -> {
                            retainedFinalizerCredential = credential
                            try {
                                finalizerStarted.complete(Unit)
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    finalizerFailure.complete(
                                        captureReentryFailure { coordinator.beginClose() },
                                    )
                                    finalizerApplyResult.complete(
                                        credential.applyTo(RecordingAuthorizationTarget()),
                                    )
                                }
                            }
                        }

                        else -> {
                            error("Unexpected test request")
                        }
                    }
                }
            coordinator = authorityCoordinator(requestExecutor = executor)
            assertIs<AuthorizationCoordinatorOutcome.Authorized>(coordinator.authorize())

            assertEquals(
                AuthorizationAuthorityUseOutcome.Executed(Unit),
                coordinator.execute(directRequest),
            )
            assertReentryFailureIsRedacted(directFailure.await())
            assertFalse(retainedDirectCredential.applyTo(RecordingAuthorizationTarget()))

            val finalizerWork =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.execute(finalizerRequest)
                }
            finalizerStarted.await()
            val invalidation = coordinator.beginClearAccessSession()
            invalidation.awaitCompletion()

            assertReentryFailureIsRedacted(finalizerFailure.await())
            assertFalse(finalizerApplyResult.await())
            assertFalse(retainedFinalizerCredential.applyTo(RecordingAuthorizationTarget()))
            assertEquals(AuthorizationAuthorityUseOutcome.AuthorityLost, finalizerWork.await())
        }

    private suspend fun captureReentryFailure(operation: suspend () -> Any?): IllegalStateException =
        try {
            operation()
            error("Expected process-control re-entry failure")
        } catch (caught: IllegalStateException) {
            caught
        }

    private fun assertReentryFailureIsRedacted(failure: IllegalStateException) {
        assertEquals(
            "Authorization executor re-entry into process authority is forbidden",
            failure.message,
        )
        assertFalse(TestValues.ACCESS_CREDENTIAL in failure.toString())
        assertFalse(TestValues.ALTERNATE_ACCESS_CREDENTIAL in failure.toString())
    }
}
