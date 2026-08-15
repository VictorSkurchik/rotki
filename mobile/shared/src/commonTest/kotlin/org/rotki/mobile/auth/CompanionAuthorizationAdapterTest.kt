@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.rotki.mobile.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.core.ports.ApplicationVisibility
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.SnapshotCoverage
import org.rotki.mobile.feature.authorization.application.AuthorizationAuthorityUseOutcome
import org.rotki.mobile.feature.authorization.application.AuthorizationCoordinatorEvent
import org.rotki.mobile.feature.authorization.application.AuthorizationCoordinatorOutcome
import org.rotki.mobile.feature.authorization.application.AuthorizationExchangeKind
import org.rotki.mobile.feature.authorization.application.AuthorizationInvalidation
import org.rotki.mobile.feature.authorization.application.AuthorizationProcessControl
import org.rotki.mobile.feature.authorization.application.AuthorizationRequest
import org.rotki.mobile.feature.authorization.application.AuthorizationRequestAuthority
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteFailure
import org.rotki.mobile.feature.pairing.domain.PairingAdmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompanionAuthorizationAdapterTest {
    @Test
    fun `proof failures map exhaustively without widening the root vocabulary`() =
        runTest {
            val cases =
                listOf(
                    FailureCase(
                        AuthorizationRemoteFailure.LOCKED_ENGINE,
                        CompanionRootState.EngineLocked,
                        CompanionAuthorizationResult.ENGINE_LOCKED,
                    ),
                    FailureCase(
                        AuthorizationRemoteFailure.PROFILE_MISMATCH,
                        CompanionRootState.ProfileMismatch,
                        CompanionAuthorizationResult.PROFILE_MISMATCH,
                    ),
                    FailureCase(
                        AuthorizationRemoteFailure.INCOMPATIBLE_PROTOCOL,
                        CompanionRootState.Incompatible,
                        CompanionAuthorizationResult.INCOMPATIBLE,
                    ),
                    FailureCase(
                        AuthorizationRemoteFailure.CHALLENGE_UNAVAILABLE,
                        CompanionRootState.Connecting,
                        CompanionAuthorizationResult.CHALLENGE_UNAVAILABLE,
                    ),
                    FailureCase(
                        AuthorizationRemoteFailure.RATE_LIMITED,
                        CompanionRootState.Connecting,
                        CompanionAuthorizationResult.RATE_LIMITED,
                        retryAfterSeconds = 5,
                    ),
                    FailureCase(
                        AuthorizationRemoteFailure.INVALID_REQUEST,
                        CompanionRootState.Connecting,
                        CompanionAuthorizationResult.ENGINE_FAILURE,
                    ),
                    FailureCase(
                        AuthorizationRemoteFailure.UNEXPECTED_ENGINE_ERROR,
                        CompanionRootState.Connecting,
                        CompanionAuthorizationResult.ENGINE_FAILURE,
                    ),
                    FailureCase(
                        AuthorizationRemoteFailure.NOT_AUTHORIZED,
                        CompanionRootState.Revoked,
                        CompanionAuthorizationResult.REVOKED,
                        cleanupCalls = 1,
                    ),
                )

            cases.forEach { case ->
                val harness = harness(CompanionRootState.Connecting)
                harness.authority.outcomes +=
                    AuthorizationCoordinatorOutcome.RemoteFailure(
                        failure = case.failure,
                        retryAfterSeconds = case.retryAfterSeconds,
                    )

                harness.adapter.authorizeOrJoin()

                assertEquals(case.root, harness.facade.status.value.rootState, case.failure.name)
                assertEquals(case.result, harness.adapter.status.value.result, case.failure.name)
                assertEquals(case.retryAfterSeconds, harness.adapter.status.value.retryAfterSeconds)
                assertEquals(case.cleanupCalls, harness.cleaner.calls, case.failure.name)
                if (case.root != CompanionRootState.Revoked) {
                    assertEquals(SnapshotCoverage.Complete, harness.facade.status.value.snapshotCoverage)
                }
            }
        }

    @Test
    fun `only remote not authorized and local pairing absence invoke destructive cleanup`() =
        runTest {
            val nondestructive =
                listOf(
                    AuthorizationCoordinatorOutcome.ContractFailure,
                    AuthorizationCoordinatorOutcome.UnexpectedFailure,
                    AuthorizationCoordinatorOutcome.LocalStorageUnavailable,
                    AuthorizationCoordinatorOutcome.LocalSecurityUnavailable,
                    AuthorizationCoordinatorOutcome.DeviceAuthenticationCancelled,
                    AuthorizationCoordinatorOutcome.NetworkUnavailable,
                )
            nondestructive.forEach { outcome ->
                val harness = harness(CompanionRootState.Connecting)
                harness.authority.outcomes += outcome

                harness.adapter.authorizeOrJoin()

                assertEquals(0, harness.cleaner.calls, outcome.toString())
                assertTrue(harness.facade.status.value.rootState != CompanionRootState.Revoked)
            }

            val missing = harness(CompanionRootState.Connecting)
            missing.authority.outcomes += AuthorizationCoordinatorOutcome.PairingRequired
            missing.adapter.authorizeOrJoin()

            assertEquals(CompanionRootState.Unpaired, missing.facade.status.value.rootState)
            assertEquals(1, missing.cleaner.calls)
            assertEquals(CompanionAuthorizationResult.PAIRING_REQUIRED, missing.adapter.status.value.result)
        }

    @Test
    fun `failed cleanup remains fail closed in revoked state`() =
        runTest {
            val harness = harness(CompanionRootState.Connecting, cleanupSucceeds = false)
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.RemoteFailure(
                    AuthorizationRemoteFailure.NOT_AUTHORIZED,
                    retryAfterSeconds = null,
                )

            harness.adapter.authorizeOrJoin()

            assertEquals(CompanionRootState.Revoked, harness.facade.status.value.rootState)
            assertEquals(SnapshotCoverage.Absent, harness.facade.status.value.snapshotCoverage)
            assertEquals(1, harness.cleaner.calls)
            assertEquals(
                CompanionAuthorizationResult.CLEANUP_INCOMPLETE,
                harness.adapter.status.value.result,
            )
        }

    @Test
    fun `access unavailable and websocket 1008 clear before one fresh proof`() =
        runTest {
            listOf<(CompanionAuthorizationAdapter) -> suspend () -> CompanionAuthorizationStatus>(
                { adapter -> suspend { adapter.onAccessSessionUnavailable() } },
                { adapter -> suspend { adapter.onWebSocketPolicyClosed() } },
            ).forEach { operation ->
                val harness = harness(CompanionRootState.Online)
                harness.authority.outcomes +=
                    AuthorizationCoordinatorOutcome.Authorized(1_900, sessionRevision = 1)

                val result = operation(harness.adapter).invoke()

                assertEquals(listOf("clear", "authorize"), harness.authority.calls)
                assertEquals(CompanionRootState.Connecting, harness.facade.status.value.rootState)
                assertEquals(SnapshotCoverage.Complete, harness.facade.status.value.snapshotCoverage)
                assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, result.result)
                assertEquals(0, harness.cleaner.calls)
                assertEquals(
                    listOf(CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST),
                    harness.sessionWork.reasons,
                )
            }
        }

    @Test
    fun `duplicate recovery signals join one adapter flight`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            val release = CompletableDeferred<Unit>()
            harness.authority.release = release
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.Authorized(1_900, sessionRevision = 1)

            val first = async { harness.adapter.onAccessSessionUnavailable() }
            runCurrent()
            val joined = async { harness.adapter.onWebSocketPolicyClosed() }
            runCurrent()

            assertEquals(1, harness.authority.authorizeCalls)
            assertEquals(1, harness.authority.clearCalls)
            release.complete(Unit)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, first.await().result)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, joined.await().result)
            assertEquals(1, harness.authority.authorizeCalls)
        }

    @Test
    fun `explicit foreground retry delegates discovery then selects and proves`() =
        runTest {
            listOf(
                CompanionRootState.EngineLocked,
                CompanionRootState.ProfileMismatch,
                CompanionRootState.Incompatible,
                CompanionRootState.Unreachable,
            ).forEach { source ->
                val harness = harness(source)
                harness.discovery.outcomes += CompanionAuthorizationDiscoveryOutcome.Compatible(2)
                harness.authority.outcomes +=
                    AuthorizationCoordinatorOutcome.Authorized(2_000, sessionRevision = 1)

                harness.adapter.onExplicitForegroundRetry()

                assertEquals(1, harness.discovery.calls, source.code)
                assertEquals(listOf(2), harness.authority.selectedVersions, source.code)
                assertEquals(1, harness.authority.clearCalls, source.code)
                assertEquals(1, harness.authority.authorizeCalls, source.code)
                assertEquals(CompanionRootState.Connecting, harness.facade.status.value.rootState)
                assertEquals(SnapshotCoverage.Complete, harness.facade.status.value.snapshotCoverage)
            }
        }

    @Test
    fun `explicit retry rejects invalid roots without discovery or network`() =
        runTest {
            val harness = harness(CompanionRootState.Online)

            val outcome = harness.adapter.onExplicitForegroundRetry()

            assertEquals(CompanionAuthorizationResult.TRANSITION_REJECTED, outcome.result)
            assertEquals(0, harness.discovery.calls)
            assertEquals(0, harness.authority.authorizeCalls)
            assertEquals(CompanionRootState.Online, harness.facade.status.value.rootState)
        }

    @Test
    fun `rediscovery incompatibility reclassifies without proof`() =
        runTest {
            val harness = harness(CompanionRootState.Incompatible)
            harness.discovery.outcomes += CompanionAuthorizationDiscoveryOutcome.Incompatible

            harness.adapter.onExplicitForegroundRetry()

            assertEquals(1, harness.discovery.calls)
            assertEquals(0, harness.authority.authorizeCalls)
            assertEquals(CompanionRootState.Incompatible, harness.facade.status.value.rootState)
            assertEquals(0, harness.cleaner.calls)
        }

    @Test
    fun `background fences a non cooperative late terminal response`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            val release = CompletableDeferred<Unit>()
            harness.authority.release = release
            harness.authority.ignoreCancellation = true
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.RemoteFailure(
                    AuthorizationRemoteFailure.NOT_AUTHORIZED,
                    retryAfterSeconds = null,
                )

            val recovery = async { harness.adapter.onAccessSessionUnavailable() }
            runCurrent()
            val background = async { harness.adapter.onBackgroundOrSystemLock() }
            runCurrent()
            release.complete(Unit)
            background.await()
            recovery.await()

            assertEquals(CompanionRootState.DeviceLocked, harness.facade.status.value.rootState)
            assertEquals(SnapshotCoverage.Complete, harness.facade.status.value.snapshotCoverage)
            assertEquals(0, harness.cleaner.calls)
            assertEquals(
                CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND,
                harness.adapter.status.value.result,
            )
            assertFalse(harness.adapter.status.value.result == CompanionAuthorizationResult.REVOKED)
        }

    @Test
    fun `inactive recovery performs no transition discovery or proof`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            harness.visibility.value = ApplicationVisibilityState.INACTIVE

            val outcome = harness.adapter.onAccessSessionUnavailable()

            assertEquals(CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND, outcome.result)
            assertEquals(CompanionRootState.Online, harness.facade.status.value.rootState)
            assertEquals(0, harness.authority.clearCalls)
            assertEquals(0, harness.authority.authorizeCalls)
            assertEquals(0, harness.discovery.calls)
        }

    @Test
    fun `automatic renewal surfaces connecting but not false snapshot reconciliation`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(
                harness = harness,
                exchangeId = 6,
                kind = AuthorizationExchangeKind.ACQUISITION,
                revision = 6,
            )

            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeStarted(
                    exchangeId = 7,
                    kind = AuthorizationExchangeKind.RENEWAL,
                ),
            )
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeCompleted(
                    exchangeId = 7,
                    kind = AuthorizationExchangeKind.RENEWAL,
                    outcome = AuthorizationCoordinatorOutcome.Authorized(2_000, sessionRevision = 7),
                ),
            )

            assertEquals(CompanionRootState.Connecting, harness.facade.status.value.rootState)
            assertEquals(SnapshotCoverage.Complete, harness.facade.status.value.snapshotCoverage)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, harness.adapter.status.value.result)
            assertEquals(
                listOf(CompanionAuthenticatedSessionWorkCloseReason.REPLACED),
                harness.sessionWork.reasons,
            )
            assertEquals(listOf<Long?>(6L), harness.sessionWork.revisions)
        }

    @Test
    fun `current expiry event clears and starts one fresh proof`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeStarted(
                    exchangeId = 7,
                    kind = AuthorizationExchangeKind.ACQUISITION,
                ),
            )
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeCompleted(
                    exchangeId = 7,
                    kind = AuthorizationExchangeKind.ACQUISITION,
                    outcome = AuthorizationCoordinatorOutcome.Authorized(1_900, sessionRevision = 11),
                ),
            )
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.Authorized(2_000, sessionRevision = 12)

            harness.bridge.emit(AuthorizationCoordinatorEvent.AccessSessionExpired(sessionRevision = 11))
            assertEquals(0, harness.authority.authorizeCalls)
            runCurrent()

            assertEquals(listOf("authorize"), harness.authority.calls)
            assertEquals(CompanionRootState.Connecting, harness.facade.status.value.rootState)
            assertEquals(
                listOf(CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST),
                harness.sessionWork.reasons,
            )
            assertEquals(listOf<Long?>(11L), harness.sessionWork.revisions)
        }

    @Test
    fun `request authority delegates without exposing material in adapter state`() =
        runTest {
            val harness = harness(CompanionRootState.Connecting)

            val outcome =
                harness.adapter.executeRequest(FakeAuthorizationRequest)

            assertEquals(AuthorizationAuthorityUseOutcome.Unavailable, outcome)
            assertEquals(1, harness.authority.authorityUseCalls)
            assertEquals("CompanionAuthorizationAdapter(redacted)", harness.adapter.toString())
            assertEquals(
                "CompanionAuthorizationStatus(redacted)",
                harness.adapter.status.value
                    .toString(),
            )
            assertEquals("CompanionAuthorizationEventBridge(redacted)", harness.bridge.toString())
        }

    @Test
    fun `canonical C3 threat fixtures remain exact`() {
        val threats =
            ProtocolFixtureData.cases
                .getValue("threat_cases")
                .jsonArray
                .associate { element ->
                    val case = element.jsonObject
                    case.string("id") to case
                }

        assertThreat(
            threats,
            "expired_access_bearer",
            surface = "access_route",
            outcome = "http_error",
            mutation = "none",
            disclosure = "none",
            code = "access_session_unavailable",
        )
        assertThreat(
            threats,
            "engine_restart_invalidates_access_bearer",
            surface = "access_route",
            outcome = "http_error",
            mutation = "none",
            disclosure = "none",
            code = "access_session_unavailable",
        )
        assertThreat(
            threats,
            "revoked_device_rejects_access_bearer",
            surface = "access_route",
            outcome = "http_error",
            mutation = "none",
            disclosure = "none",
            code = "not_authorized",
        )
        assertThreat(
            threats,
            "background_purges_access_bearer",
            surface = "client_lifecycle",
            outcome = "local_purge",
            mutation = "delete_bearer_and_plaintext",
            disclosure = "encrypted_snapshot_only",
        )
        assertThreat(
            threats,
            "websocket_1008_requires_http_proof",
            surface = "websocket_close",
            outcome = "local_transition_to_connecting",
            mutation = "delete_bearer",
            disclosure = "no_state_reason",
        )
    }
}

class CompanionAuthorizationAdapterRaceTest {
    @Test
    fun `explicit fence is atomic against queued lifecycle contenders`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(
                harness = harness,
                exchangeId = 40,
                kind = AuthorizationExchangeKind.ACQUISITION,
                revision = 40,
            )
            harness.facade.transportBudgetExhausted()
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeStarted(
                    exchangeId = 41,
                    kind = AuthorizationExchangeKind.ACQUISITION,
                ),
            )
            harness.discovery.outcomes += CompanionAuthorizationDiscoveryOutcome.Compatible(2)
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.Authorized(2_100, sessionRevision = 41)
            val selectStarted = CompletableDeferred<Unit>()
            val selectRelease = CompletableDeferred<Unit>()
            val selectCompletionRelease = CompletableDeferred<Unit>()
            harness.authority.selectStarted = selectStarted
            harness.authority.selectRelease = selectRelease
            harness.authority.selectCompletionRelease = selectCompletionRelease

            val retry = async { harness.adapter.onExplicitForegroundRetry() }
            selectStarted.await()
            val staleEvent =
                async {
                    harness.bridge.emit(
                        AuthorizationCoordinatorEvent.ExchangeCompleted(
                            exchangeId = 41,
                            kind = AuthorizationExchangeKind.ACQUISITION,
                            outcome =
                                AuthorizationCoordinatorOutcome.RemoteFailure(
                                    AuthorizationRemoteFailure.NOT_AUTHORIZED,
                                    retryAfterSeconds = null,
                                ),
                        ),
                    )
                }
            val newAuthorization = async { harness.adapter.authorizeOrJoin() }
            val background = async { harness.adapter.onBackgroundOrSystemLock() }
            val close = async { harness.adapter.close() }
            runCurrent()

            assertFalse(staleEvent.isCompleted)
            assertFalse(newAuthorization.isCompleted)
            assertFalse(background.isCompleted)
            assertFalse(close.isCompleted)
            assertEquals(1, harness.authority.clearCalls)
            assertEquals(listOf(2), harness.authority.selectedVersions)
            assertEquals(0, harness.authority.authorizeCalls)

            selectRelease.complete(Unit)
            runCurrent()

            assertEquals(
                AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                newAuthorization.await(),
            )
            staleEvent.await()
            background.await()
            close.await()
            assertEquals(
                CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND,
                retry.await().result,
            )
            assertEquals(0, harness.authority.authorizeCalls)
            assertEquals(0, harness.cleaner.calls)
            assertEquals(1, harness.authority.closeCalls)
            assertEquals(CompanionAuthorizationResult.CLOSED, harness.adapter.status.value.result)
            selectCompletionRelease.complete(Unit)
        }

    @Test
    fun `background fences bearer before non cooperative discovery finishes`() =
        runTest {
            val harness = harness(CompanionRootState.EngineLocked)
            val discoveryRelease = CompletableDeferred<Unit>()
            harness.discovery.release = discoveryRelease
            harness.discovery.ignoreCancellation = true
            harness.discovery.outcomes += CompanionAuthorizationDiscoveryOutcome.Compatible(2)

            val retry = async { harness.adapter.onExplicitForegroundRetry() }
            runCurrent()
            val background = async { harness.adapter.onBackgroundOrSystemLock() }
            runCurrent()

            assertEquals(CompanionRootState.DeviceLocked, harness.facade.status.value.rootState)
            assertEquals(1, harness.authority.clearCalls)
            assertEquals(
                listOf(CompanionAuthenticatedSessionWorkCloseReason.BACKGROUND_OR_SYSTEM_LOCK),
                harness.sessionWork.reasons,
            )
            assertFalse(background.isCompleted)

            discoveryRelease.complete(Unit)
            background.await()
            retry.await()
            assertEquals(0, harness.authority.authorizeCalls)
        }

    @Test
    fun `explicit retry fences retained authority before a failed fresh proof`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(harness, exchangeId = 1, kind = AuthorizationExchangeKind.ACQUISITION, revision = 60)
            harness.facade.transportBudgetExhausted()
            harness.discovery.outcomes += CompanionAuthorizationDiscoveryOutcome.Compatible(1)
            harness.authority.outcomes += AuthorizationCoordinatorOutcome.NetworkUnavailable

            val result = harness.adapter.onExplicitForegroundRetry()

            assertEquals(CompanionAuthorizationResult.NETWORK_UNAVAILABLE, result.result)
            assertEquals(1, harness.authority.clearCalls)
            assertFalse(harness.authority.activeSession)
            assertEquals(
                listOf(CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST),
                harness.sessionWork.reasons,
            )
        }

    @Test
    fun `explicit retry fences retained authority before a successful fresh proof`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(harness, exchangeId = 1, kind = AuthorizationExchangeKind.ACQUISITION, revision = 70)
            harness.facade.transportBudgetExhausted()
            harness.discovery.outcomes += CompanionAuthorizationDiscoveryOutcome.Compatible(1)
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.Authorized(2_500, sessionRevision = 71)

            val result = harness.adapter.onExplicitForegroundRetry()

            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, result.result)
            assertEquals(1, harness.authority.clearCalls)
            assertTrue(harness.authority.activeSession)
            assertEquals(CompanionRootState.Connecting, harness.facade.status.value.rootState)
            assertEquals(
                listOf(CompanionAuthenticatedSessionWorkCloseReason.AUTHORITY_LOST),
                harness.sessionWork.reasons,
            )
        }

    @Test
    fun `stale expiry cannot clear or recover over a replacement revision`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(harness, exchangeId = 1, kind = AuthorizationExchangeKind.ACQUISITION, revision = 10)
            emitAuthorized(harness, exchangeId = 2, kind = AuthorizationExchangeKind.RENEWAL, revision = 11)

            harness.bridge.emit(AuthorizationCoordinatorEvent.AccessSessionExpired(sessionRevision = 10))
            runCurrent()

            assertEquals(0, harness.authority.authorizeCalls)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, harness.adapter.status.value.result)
            assertEquals(
                listOf(CompanionAuthenticatedSessionWorkCloseReason.REPLACED),
                harness.sessionWork.reasons,
            )
        }

    @Test
    fun `websocket invalidation supersedes non cooperative explicit discovery`() =
        runTest {
            val harness = harness(CompanionRootState.EngineLocked)
            val discoveryRelease = CompletableDeferred<Unit>()
            val discoveryCompleted = CompletableDeferred<Unit>()
            harness.discovery.release = discoveryRelease
            harness.discovery.completed = discoveryCompleted
            harness.discovery.ignoreCancellation = true
            harness.discovery.outcomes += CompanionAuthorizationDiscoveryOutcome.Compatible(2)
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.Authorized(2_000, sessionRevision = 20)

            val weak = async { harness.adapter.onExplicitForegroundRetry() }
            runCurrent()
            val strong = async { harness.adapter.onWebSocketPolicyClosed() }
            runCurrent()

            assertEquals(1, harness.authority.clearCalls)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, strong.await().result)
            discoveryRelease.complete(Unit)
            discoveryCompleted.await()
            runCurrent()
            weak.await()

            assertEquals(1, harness.authority.authorizeCalls)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, harness.adapter.status.value.result)
            assertEquals(CompanionRootState.Connecting, harness.facade.status.value.rootState)
        }

    @Test
    fun `terminal cleanup barrier rejects a replacement until deletion commits`() =
        runTest {
            val harness = harness(CompanionRootState.Connecting)
            val cleanerStarted = CompletableDeferred<Unit>()
            val cleanerRelease = CompletableDeferred<Unit>()
            harness.cleaner.started = cleanerStarted
            harness.cleaner.release = cleanerRelease
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.RemoteFailure(
                    AuthorizationRemoteFailure.NOT_AUTHORIZED,
                    retryAfterSeconds = null,
                )

            val terminal = async { harness.adapter.authorizeOrJoin() }
            cleanerStarted.await()
            val pairing = CompanionPairingSessionAdapter(harness.facade)
            assertEquals(PairingAdmission.IGNORED, pairing.admit(testPairingQr()))

            cleanerRelease.complete(Unit)
            terminal.await()
            assertEquals(PairingAdmission.ACCEPTED, pairing.admit(testPairingQr(expiresAt = TEST_NOW + 200)))
        }

    @Test
    fun `failed terminal cleanup keeps the facade barrier fail closed`() =
        runTest {
            val harness = harness(CompanionRootState.Connecting, cleanupSucceeds = false)
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.RemoteFailure(
                    AuthorizationRemoteFailure.NOT_AUTHORIZED,
                    retryAfterSeconds = null,
                )

            harness.adapter.authorizeOrJoin()

            val pairing = CompanionPairingSessionAdapter(harness.facade)
            assertTrue(harness.facade.hasPendingPairingCleanup())
            assertEquals(PairingAdmission.IGNORED, pairing.admit(testPairingQr()))
            assertEquals(CompanionAuthorizationResult.CLEANUP_INCOMPLETE, harness.adapter.status.value.result)
        }

    @Test
    fun `failed terminal session close keeps the facade barrier fail closed`() =
        runTest {
            val harness = harness(CompanionRootState.Connecting)
            harness.sessionWork.throws = true
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.RemoteFailure(
                    AuthorizationRemoteFailure.NOT_AUTHORIZED,
                    retryAfterSeconds = null,
                )

            harness.adapter.authorizeOrJoin()

            val pairing = CompanionPairingSessionAdapter(harness.facade)
            assertEquals(1, harness.cleaner.calls)
            assertTrue(harness.facade.hasPendingPairingCleanup())
            assertEquals(PairingAdmission.IGNORED, pairing.admit(testPairingQr()))
            assertEquals(CompanionAuthorizationResult.CLEANUP_INCOMPLETE, harness.adapter.status.value.result)
        }

    @Test
    fun `terminal cleanup starts before blocked authenticated session close finishes`() =
        runTest {
            val harness = harness(CompanionRootState.Connecting)
            val sessionCloseStarted = CompletableDeferred<Unit>()
            val sessionCloseRelease = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            harness.sessionWork.started = sessionCloseStarted
            harness.sessionWork.release = sessionCloseRelease
            harness.cleaner.started = cleanupStarted
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.RemoteFailure(
                    AuthorizationRemoteFailure.NOT_AUTHORIZED,
                    retryAfterSeconds = null,
                )

            val terminal = async { harness.adapter.authorizeOrJoin() }
            sessionCloseStarted.await()
            cleanupStarted.await()

            assertEquals(CompanionRootState.Revoked, harness.facade.status.value.rootState)
            assertEquals(1, harness.cleaner.calls)
            assertFalse(terminal.isCompleted)
            val pairing = CompanionPairingSessionAdapter(harness.facade)
            assertEquals(PairingAdmission.IGNORED, pairing.admit(testPairingQr()))

            sessionCloseRelease.complete(Unit)
            terminal.await()
            assertEquals(PairingAdmission.ACCEPTED, pairing.admit(testPairingQr(expiresAt = TEST_NOW + 200)))
        }

    @Test
    fun `explicit local unpair consumes an existing pairing cleanup marker`() =
        runTest {
            val facade = CompanionFacade()
            val pairing = CompanionPairingSessionAdapter(facade)
            assertEquals(PairingAdmission.ACCEPTED, pairing.admit(testPairingQr()))
            val lease = requireNotNull(pairing.takePending())
            requireNotNull(pairing.markCleanupRequired(lease))
            val harness = harness(facade)

            harness.adapter.onLocalUnpair()

            assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
            assertFalse(pairing.hasPendingCleanup())
            assertEquals(1, harness.cleaner.calls)
            assertEquals(CompanionAuthorizationResult.PAIRING_REQUIRED, harness.adapter.status.value.result)
        }

    @Test
    fun `background teardown blocks fresh authorization until the old drain completes`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(
                harness = harness,
                exchangeId = 89,
                kind = AuthorizationExchangeKind.ACQUISITION,
                revision = 90,
            )
            val clearRelease = CompletableDeferred<Unit>()
            harness.authority.clearCompletionRelease = clearRelease

            val background = async { harness.adapter.onBackgroundOrSystemLock() }
            runCurrent()
            assertEquals(CompanionRootState.DeviceLocked, harness.facade.status.value.rootState)
            assertEquals(listOf<Long?>(90L), harness.sessionWork.revisions)
            harness.facade.deviceAuthenticationSucceeded()
            assertEquals(CompanionRootState.Connecting, harness.facade.status.value.rootState)

            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.Authorized(2_100, sessionRevision = 91)
            assertEquals(
                AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                harness.adapter.authorizeOrJoin(),
            )
            assertEquals(0, harness.authority.authorizeCalls)

            clearRelease.complete(Unit)
            background.await()
            assertEquals(
                CompanionAuthorizationResult.OUTSIDE_ACTIVE_FOREGROUND,
                harness.adapter.status.value.result,
            )

            assertIs<AuthorizationCoordinatorOutcome.Authorized>(harness.adapter.authorizeOrJoin())
            assertEquals(1, harness.authority.authorizeCalls)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, harness.adapter.status.value.result)
        }

    @Test
    fun `local unpair holds its cleanup barrier through the old authority drain`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            val clearRelease = CompletableDeferred<Unit>()
            harness.authority.clearCompletionRelease = clearRelease

            val unpair = async { harness.adapter.onLocalUnpair() }
            runCurrent()

            val pairing = CompanionPairingSessionAdapter(harness.facade)
            assertTrue(harness.facade.hasPendingPairingCleanup())
            assertEquals(PairingAdmission.IGNORED, pairing.admit(testPairingQr()))

            clearRelease.complete(Unit)
            assertEquals(CompanionAuthorizationResult.PAIRING_REQUIRED, unpair.await().result)
            assertEquals(PairingAdmission.ACCEPTED, pairing.admit(testPairingQr(expiresAt = TEST_NOW + 200)))
        }

    @Test
    fun `external cleanup callbacks fail fast when they reenter the adapter`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            var sessionFailure: IllegalStateException? = null
            var cleanupFailure: IllegalStateException? = null
            harness.sessionWork.onAwait = {
                try {
                    harness.adapter.authorizeOrJoin()
                } catch (failure: IllegalStateException) {
                    sessionFailure = failure
                }
            }
            harness.cleaner.onDestroy = {
                try {
                    harness.adapter.onAccessSessionUnavailable()
                } catch (failure: IllegalStateException) {
                    cleanupFailure = failure
                }
            }

            harness.adapter.onLocalUnpair()

            val expectedMessage = "Authorization external callback must not re-enter its adapter"
            assertEquals(expectedMessage, requireNotNull(sessionFailure).message)
            assertEquals(expectedMessage, requireNotNull(cleanupFailure).message)
            assertFalse(expectedMessage.contains("credential", ignoreCase = true))
        }

    @Test
    fun `authority fence discards a late renewal completion after fresh proof`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(harness, exchangeId = 80, kind = AuthorizationExchangeKind.ACQUISITION, revision = 80)
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeStarted(
                    exchangeId = 99,
                    kind = AuthorizationExchangeKind.RENEWAL,
                ),
            )
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.Authorized(2_800, sessionRevision = 81)

            val recovered = harness.adapter.onAccessSessionUnavailable()
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeCompleted(
                    exchangeId = 99,
                    kind = AuthorizationExchangeKind.RENEWAL,
                    outcome = AuthorizationCoordinatorOutcome.OutsideActiveForeground,
                ),
            )

            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, recovered.result)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, harness.adapter.status.value.result)
            assertEquals(1, harness.authority.authorizeCalls)
        }

    @Test
    fun `close racing blocked local cleanup keeps closed status`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            val cleanerStarted = CompletableDeferred<Unit>()
            val cleanerRelease = CompletableDeferred<Unit>()
            harness.cleaner.started = cleanerStarted
            harness.cleaner.release = cleanerRelease

            val unpair = async { harness.adapter.onLocalUnpair() }
            cleanerStarted.await()
            harness.adapter.close()
            cleanerRelease.complete(Unit)
            unpair.await()

            assertEquals(CompanionAuthorizationResult.CLOSED, harness.adapter.status.value.result)
        }

    @Test
    fun `process completion and racing explicit close commit teardown once`() =
        runTest {
            val processJob = SupervisorJob()
            val processScope =
                CoroutineScope(backgroundScope.coroutineContext.minusKey(kotlinx.coroutines.Job) + processJob)
            val harness = harness(CompanionRootState.Online, processScope = processScope)

            val explicit = async { harness.adapter.close() }
            processJob.cancel()
            val joined = async { harness.adapter.close() }
            runCurrent()
            explicit.await()
            joined.await()

            assertEquals(1, harness.authority.closeCalls)
            assertEquals(
                listOf(CompanionAuthenticatedSessionWorkCloseReason.CLOSED),
                harness.sessionWork.reasons,
            )
            assertEquals(CompanionAuthorizationResult.CLOSED, harness.adapter.status.value.result)
        }

    @Test
    fun `process cancellation joiner cannot deadlock an external close owner`() =
        runTest {
            val processJob = SupervisorJob()
            val processScope =
                CoroutineScope(backgroundScope.coroutineContext.minusKey(kotlinx.coroutines.Job) + processJob)
            val harness = harness(CompanionRootState.Online, processScope = processScope)
            val closeStarted = CompletableDeferred<Unit>()
            val closeRelease = CompletableDeferred<Unit>()
            harness.authority.closeStarted = closeStarted
            harness.authority.closeRelease = closeRelease

            val explicit = async { harness.adapter.close() }
            closeStarted.await()
            processJob.cancel()
            runCurrent()
            closeRelease.complete(Unit)
            explicit.await()

            assertEquals(1, harness.authority.closeCalls)
            assertEquals(CompanionAuthorizationResult.CLOSED, harness.adapter.status.value.result)
        }

    @Test
    fun `normal process completion does not wait on the independent adapter scope`() =
        runTest {
            val processJob = SupervisorJob()
            val processScope =
                CoroutineScope(backgroundScope.coroutineContext.minusKey(kotlinx.coroutines.Job) + processJob)
            val harness = harness(CompanionRootState.Online, processScope = processScope)

            assertTrue(processJob.complete())
            runCurrent()

            assertTrue(processJob.isCompleted)
            assertEquals(1, harness.authority.closeCalls)
            assertEquals(CompanionAuthorizationResult.CLOSED, harness.adapter.status.value.result)
        }

    @Test
    fun `throwing session close cannot skip committed bearer purge`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            harness.sessionWork.throws = true
            harness.authority.outcomes +=
                AuthorizationCoordinatorOutcome.Authorized(2_000, sessionRevision = 30)

            val result = harness.adapter.onWebSocketPolicyClosed()

            assertEquals(1, harness.authority.clearCalls)
            assertTrue(harness.authority.activeSession)
            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, result.result)
        }

    @Test
    fun `expired renewal response retains the old session work`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(harness, exchangeId = 1, kind = AuthorizationExchangeKind.ACQUISITION, revision = 40)
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeStarted(2, AuthorizationExchangeKind.RENEWAL),
            )
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeCompleted(
                    exchangeId = 2,
                    kind = AuthorizationExchangeKind.RENEWAL,
                    outcome = AuthorizationCoordinatorOutcome.SessionExpired,
                ),
            )

            assertEquals(CompanionAuthorizationResult.AUTHORITY_READY, harness.adapter.status.value.result)
            assertTrue(harness.sessionWork.reasons.isEmpty())
            assertTrue(harness.authority.activeSession)
        }

    @Test
    fun `background wins while replacement session close is suspended`() =
        runTest {
            val harness = harness(CompanionRootState.Online)
            emitAuthorized(harness, exchangeId = 1, kind = AuthorizationExchangeKind.ACQUISITION, revision = 50)
            val closeStarted = CompletableDeferred<Unit>()
            val closeRelease = CompletableDeferred<Unit>()
            harness.sessionWork.started = closeStarted
            harness.sessionWork.release = closeRelease
            harness.bridge.emit(
                AuthorizationCoordinatorEvent.ExchangeStarted(2, AuthorizationExchangeKind.RENEWAL),
            )
            val replacement =
                async {
                    harness.bridge.emit(
                        AuthorizationCoordinatorEvent.ExchangeCompleted(
                            exchangeId = 2,
                            kind = AuthorizationExchangeKind.RENEWAL,
                            outcome = AuthorizationCoordinatorOutcome.Authorized(2_100, sessionRevision = 51),
                        ),
                    )
                }
            closeStarted.await()
            val background = async { harness.adapter.onBackgroundOrSystemLock() }
            runCurrent()

            assertEquals(CompanionRootState.DeviceLocked, harness.facade.status.value.rootState)
            assertEquals(1, harness.authority.clearCalls)
            closeRelease.complete(Unit)
            replacement.await()
            background.await()

            assertFalse(harness.adapter.status.value.result == CompanionAuthorizationResult.AUTHORITY_READY)
        }
}

private suspend fun emitAuthorized(
    harness: AdapterHarness,
    exchangeId: Long,
    kind: AuthorizationExchangeKind,
    revision: Long,
) {
    harness.bridge.emit(AuthorizationCoordinatorEvent.ExchangeStarted(exchangeId, kind))
    harness.bridge.emit(
        AuthorizationCoordinatorEvent.ExchangeCompleted(
            exchangeId = exchangeId,
            kind = kind,
            outcome = AuthorizationCoordinatorOutcome.Authorized(2_000 + revision, revision),
        ),
    )
}

private fun TestScope.harness(
    rootState: CompanionRootState,
    cleanupSucceeds: Boolean = true,
    processScope: CoroutineScope = backgroundScope,
): AdapterHarness {
    val facade = facade(rootState)
    val visibility = FakeApplicationVisibility()
    val bridge = CompanionAuthorizationEventBridge()
    val authority = FakeAuthorizationProcessAuthority(bridge)
    val discovery = FakeCompanionAuthorizationDiscovery()
    val cleaner = FakeLocalAuthorityCleaner(cleanupSucceeds)
    val sessionWork = FakeAuthenticatedSessionWorkController()
    val adapter =
        CompanionAuthorizationAdapter(
            facade = facade,
            coordinator = authority,
            requestAuthority = authority,
            applicationVisibility = visibility,
            discovery = discovery,
            localAuthorityCleaner = cleaner,
            sessionWorkController = sessionWork,
            processScope = processScope,
            eventBridge = bridge,
        )
    return AdapterHarness(facade, visibility, bridge, authority, discovery, cleaner, sessionWork, adapter)
}

private fun TestScope.harness(facade: CompanionFacade): AdapterHarness {
    val visibility = FakeApplicationVisibility()
    val bridge = CompanionAuthorizationEventBridge()
    val authority = FakeAuthorizationProcessAuthority(bridge)
    val discovery = FakeCompanionAuthorizationDiscovery()
    val cleaner = FakeLocalAuthorityCleaner(succeeds = true)
    val sessionWork = FakeAuthenticatedSessionWorkController()
    val adapter =
        CompanionAuthorizationAdapter(
            facade = facade,
            coordinator = authority,
            requestAuthority = authority,
            applicationVisibility = visibility,
            discovery = discovery,
            localAuthorityCleaner = cleaner,
            sessionWorkController = sessionWork,
            processScope = backgroundScope,
            eventBridge = bridge,
        )
    return AdapterHarness(facade, visibility, bridge, authority, discovery, cleaner, sessionWork, adapter)
}

private data class FailureCase(
    val failure: AuthorizationRemoteFailure,
    val root: CompanionRootState,
    val result: CompanionAuthorizationResult,
    val retryAfterSeconds: Long? = null,
    val cleanupCalls: Int = 0,
)

private data class AdapterHarness(
    val facade: CompanionFacade,
    val visibility: FakeApplicationVisibility,
    val bridge: CompanionAuthorizationEventBridge,
    val authority: FakeAuthorizationProcessAuthority,
    val discovery: FakeCompanionAuthorizationDiscovery,
    val cleaner: FakeLocalAuthorityCleaner,
    val sessionWork: FakeAuthenticatedSessionWorkController,
    val adapter: CompanionAuthorizationAdapter,
)

private class FakeApplicationVisibility : ApplicationVisibility {
    private val mutableState = MutableStateFlow(ApplicationVisibilityState.ACTIVE_FOREGROUND)
    override val state: StateFlow<ApplicationVisibilityState> = mutableState

    var value: ApplicationVisibilityState
        get() = mutableState.value
        set(value) {
            mutableState.value = value
        }
}

private class FakeAuthorizationProcessAuthority(
    private val eventSink: CompanionAuthorizationEventBridge,
) : AuthorizationProcessControl,
    AuthorizationRequestAuthority {
    val outcomes = ArrayDeque<AuthorizationCoordinatorOutcome>()
    val calls = mutableListOf<String>()
    val selectedVersions = mutableListOf<Int>()
    var release: CompletableDeferred<Unit>? = null
    var closeStarted: CompletableDeferred<Unit>? = null
    var closeRelease: CompletableDeferred<Unit>? = null
    var clearCompletionRelease: CompletableDeferred<Unit>? = null
    var selectStarted: CompletableDeferred<Unit>? = null
    var selectRelease: CompletableDeferred<Unit>? = null
    var selectCompletionRelease: CompletableDeferred<Unit>? = null
    var ignoreCancellation: Boolean = false
    var authorizeCalls: Int = 0
    var clearCalls: Int = 0
    var closeCalls: Int = 0
    var authorityUseCalls: Int = 0
    var receivedRequest: Any? = null
    var activeSession: Boolean = true
    private var nextExchangeId: Long = 0

    override suspend fun authorize(): AuthorizationCoordinatorOutcome {
        calls += "authorize"
        authorizeCalls += 1
        nextExchangeId += 1
        val exchangeId = nextExchangeId
        eventSink.emit(
            AuthorizationCoordinatorEvent.ExchangeStarted(
                exchangeId = exchangeId,
                kind = AuthorizationExchangeKind.ACQUISITION,
            ),
        )
        val operation: suspend () -> AuthorizationCoordinatorOutcome = {
            release?.await()
            outcomes.removeFirst()
        }
        val outcome =
            if (ignoreCancellation) {
                withContext(NonCancellable) { operation() }
            } else {
                operation()
            }
        val emit: suspend () -> Unit = {
            eventSink.emit(
                AuthorizationCoordinatorEvent.ExchangeCompleted(
                    exchangeId = exchangeId,
                    kind = AuthorizationExchangeKind.ACQUISITION,
                    outcome = outcome,
                ),
            )
        }
        if (ignoreCancellation) {
            withContext(NonCancellable) { emit() }
        } else {
            emit()
        }
        if (outcome is AuthorizationCoordinatorOutcome.Authorized) activeSession = true
        return outcome
    }

    override suspend fun beginClearAccessSession(): AuthorizationInvalidation {
        calls += "clear"
        clearCalls += 1
        activeSession = false
        return AuthorizationInvalidation { clearCompletionRelease?.await() }
    }

    override suspend fun clearAccessSession() {
        beginClearAccessSession().awaitCompletion()
    }

    override suspend fun beginSelectProtocolVersion(selectedProtocolVersion: Int): AuthorizationInvalidation {
        selectedVersions += selectedProtocolVersion
        selectStarted?.complete(Unit)
        selectRelease?.await()
        return AuthorizationInvalidation { selectCompletionRelease?.await() }
    }

    override suspend fun selectProtocolVersion(selectedProtocolVersion: Int) {
        beginSelectProtocolVersion(selectedProtocolVersion).awaitCompletion()
    }

    override suspend fun hasActiveAccessSession(): Boolean = activeSession

    override suspend fun <R : Any> execute(request: AuthorizationRequest<R>): AuthorizationAuthorityUseOutcome<R> {
        authorityUseCalls += 1
        receivedRequest = request
        return AuthorizationAuthorityUseOutcome.Unavailable
    }

    override suspend fun beginClose(): AuthorizationInvalidation {
        closeCalls += 1
        activeSession = false
        closeStarted?.complete(Unit)
        closeRelease?.await()
        return AuthorizationInvalidation { }
    }

    override suspend fun close() {
        beginClose().awaitCompletion()
    }
}

private data object FakeAuthorizationRequest : AuthorizationRequest<String>

private class FakeAuthenticatedSessionWorkController : CompanionAuthenticatedSessionWorkController {
    val reasons = mutableListOf<CompanionAuthenticatedSessionWorkCloseReason>()
    val revisions = mutableListOf<Long?>()
    var throws: Boolean = false
    var started: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null
    var onAwait: (suspend () -> Unit)? = null

    override fun beginClose(
        sessionRevision: Long?,
        reason: CompanionAuthenticatedSessionWorkCloseReason,
    ): CompanionAuthenticatedSessionWorkInvalidation {
        reasons += reason
        revisions += sessionRevision
        return CompanionAuthenticatedSessionWorkInvalidation {
            started?.complete(Unit)
            onAwait?.invoke()
            release?.await()
            if (throws) error("secret transport detail")
            true
        }
    }
}

private class FakeCompanionAuthorizationDiscovery : CompanionAuthorizationDiscovery {
    val outcomes = ArrayDeque<CompanionAuthorizationDiscoveryOutcome>()
    var calls: Int = 0
    var release: CompletableDeferred<Unit>? = null
    var completed: CompletableDeferred<Unit>? = null
    var ignoreCancellation: Boolean = false

    override suspend fun discover(): CompanionAuthorizationDiscoveryOutcome {
        calls += 1
        val operation: suspend () -> CompanionAuthorizationDiscoveryOutcome = {
            release?.await()
            outcomes.removeFirst().also { completed?.complete(Unit) }
        }
        return if (ignoreCancellation) withContext(NonCancellable) { operation() } else operation()
    }
}

private class FakeLocalAuthorityCleaner(
    private val succeeds: Boolean,
) : CompanionAuthorizationLocalAuthorityCleaner {
    var calls: Int = 0
    var started: CompletableDeferred<Unit>? = null
    var release: CompletableDeferred<Unit>? = null
    var onDestroy: (suspend () -> Unit)? = null

    override suspend fun destroyAll(): Boolean {
        calls += 1
        started?.complete(Unit)
        onDestroy?.invoke()
        release?.await()
        return succeeds
    }
}

private fun facade(rootState: CompanionRootState): CompanionFacade {
    val facade = CompanionFacade.restorePaired(SnapshotCoverage.Complete)
    if (rootState == CompanionRootState.DeviceLocked) return facade
    facade.deviceAuthenticationSucceeded()
    when (rootState) {
        CompanionRootState.Connecting -> {
        }

        CompanionRootState.Online -> {
            facade.completeConnectionWithCompleteSnapshot()
        }

        CompanionRootState.Degraded -> {
            facade.completeConnectionWithDegradedSnapshot()
        }

        CompanionRootState.Refreshing -> {
            facade.completeConnectionWithCompleteSnapshot()
            facade.activeRefreshReconciled()
        }

        CompanionRootState.Unreachable -> {
            facade.completeConnectionWithCompleteSnapshot()
            facade.transportBudgetExhausted()
        }

        CompanionRootState.EngineLocked -> {
            facade.engineLocked()
        }

        CompanionRootState.ProfileMismatch -> {
            facade.profileMismatch()
        }

        CompanionRootState.Incompatible -> {
            facade.incompatibleProtocol()
        }

        CompanionRootState.Revoked -> {
            facade.notAuthorized()
        }

        CompanionRootState.Unpaired -> {
            facade.unpair()
        }

        CompanionRootState.DeviceLocked -> {
            error("handled before authentication")
        }
    }
    assertEquals(rootState, facade.status.value.rootState)
    return facade
}

private fun assertThreat(
    threats: Map<String, JsonObject>,
    id: String,
    surface: String,
    outcome: String,
    mutation: String,
    disclosure: String,
    code: String? = null,
) {
    val case = threats.getValue(id)
    val expected = case.getValue("expected").jsonObject
    assertEquals(surface, case.string("surface"), id)
    assertEquals(outcome, expected.string("outcome"), id)
    assertEquals(mutation, expected.string("mutation"), id)
    assertEquals(disclosure, expected.string("disclosure"), id)
    if (code != null) {
        assertEquals(code, expected.string("code"), id)
        assertEquals(
            401,
            expected
                .getValue("status")
                .jsonPrimitive.content
                .toInt(),
            id,
        )
    }
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun testPairingQr(expiresAt: Long = TEST_NOW + 100) =
    acceptedPairingQr(
        rawPayload =
            """{"kind":"rotki_companion_pairing","format_version":1,"engine_origin":"https://rotki.example","pairing_id":"AAECAwQFBgcICQoLDA0ODw","pairing_credential":"EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8","expires_at":$expiresAt}""",
        nowEpochSeconds = TEST_NOW,
    )

private const val TEST_NOW: Long = 1_786_550_300L
