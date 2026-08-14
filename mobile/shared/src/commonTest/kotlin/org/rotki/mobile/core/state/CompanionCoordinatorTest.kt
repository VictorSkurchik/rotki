package org.rotki.mobile.core.state

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.core.protocol.testing.ProtocolFixtureData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CompanionCoordinatorTest {
    @Test
    fun exposesTheCompleteRootStateVocabulary() {
        assertEquals(
            ProtocolFixtureData.vocabulary
                .getValue("root_states")
                .jsonArray
                .mapTo(mutableSetOf()) { value -> value.jsonPrimitive.content },
            CompanionRootState.entries.mapTo(mutableSetOf(), CompanionRootState::code),
        )
    }

    @Test
    fun preservesSnapshotCoverageAcrossTransientStates() {
        val facade: CompanionFacade = CompanionFacade()

        facade.beginPairing()
        facade.completeConnectionWithCompleteSnapshot()
        facade.activeRefreshReconciled()
        facade.transportBudgetExhausted()

        assertEquals(
            CompanionStatus(
                rootState = CompanionRootState.Unreachable,
                snapshotCoverage = SnapshotCoverage.Complete,
            ),
            facade.status.value,
        )

        facade.transportRestored()
        facade.engineLocked()

        assertEquals(
            CompanionStatus(
                rootState = CompanionRootState.EngineLocked,
                snapshotCoverage = SnapshotCoverage.Complete,
            ),
            facade.status.value,
        )
    }

    @Test
    fun notAuthorizedDeletesAllLocalAuthority() {
        pairedRestStates().forEach { facade ->
            val source = facade.status.value.rootState
            val outcome: CompanionTransitionOutcome = facade.notAuthorized()

            val applied: CompanionTransitionOutcome.Applied = assertIs(outcome, source.code)
            assertEquals(CompanionRootState.Revoked, applied.status.rootState, source.code)
            assertEquals(SnapshotCoverage.Absent, applied.status.snapshotCoverage, source.code)
            assertEquals(DeviceSessionEffect.DELETE, applied.effects.deviceSession, source.code)
            assertEquals(SnapshotEffect.DELETE, applied.effects.snapshot, source.code)
            assertEquals(BearerEffect.DELETE, applied.effects.bearer, source.code)
        }
    }

    @Test
    fun rejectedTransitionDoesNotChangeState() {
        val facade: CompanionFacade = CompanionFacade()

        val rejected: CompanionTransitionOutcome.Rejected =
            assertIs(
                facade.activeRefreshReconciled(),
            )

        assertEquals("active_refresh_reconciled", rejected.eventCode)
        assertEquals(CompanionRootState.Unpaired, rejected.status.rootState)
        assertEquals(rejected.status, facade.status.value)
    }

    @Test
    fun rejectsContradictoryCoverageAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            CompanionFacade(
                CompanionStatus(
                    rootState = CompanionRootState.Online,
                    snapshotCoverage = SnapshotCoverage.Degraded,
                ),
            )
        }
    }

    @Test
    fun restoresPairedInstallationBehindThePrivacyGate() {
        val restored = CompanionFacade.restorePaired(SnapshotCoverage.Degraded)

        assertEquals(
            CompanionStatus(CompanionRootState.DeviceLocked, SnapshotCoverage.Degraded),
            restored.status.value,
        )
        assertIs<CompanionTransitionOutcome.Applied>(restored.deviceAuthenticationSucceeded())
    }

    @Test
    fun proactiveRenewalRetainsBearerUntilItsOriginalExpiry() {
        val facade = CompanionFacade()
        facade.beginPairing()
        facade.completeConnectionWithCompleteSnapshot()

        val applied =
            assertIs<CompanionTransitionOutcome.Applied>(
                facade.proactiveRenewalStarted(),
            )

        assertEquals(CompanionRootState.Connecting, applied.status.rootState)
        assertEquals(BearerEffect.KEEP_UNTIL_EXPIRY, applied.effects.bearer)
        assertEquals(SnapshotCoverage.Complete, applied.status.snapshotCoverage)
    }

    @Test
    fun proofAndActiveRefreshReconciliationReplacesTheRetainedBearer() {
        val facade = onlineFacade()
        assertIs<CompanionTransitionOutcome.Applied>(facade.proactiveRenewalStarted())
        assertIs<CompanionTransitionOutcome.Rejected>(facade.activeRefreshReconciled())

        val applied =
            assertIs<CompanionTransitionOutcome.Applied>(
                facade.completeConnectionWithActiveRefresh(),
            )

        assertEquals(CompanionRootState.Refreshing, applied.status.rootState)
        assertEquals(SnapshotCoverage.Complete, applied.status.snapshotCoverage)
        assertEquals(DeviceSessionEffect.KEEP, applied.effects.deviceSession)
        assertEquals(SnapshotEffect.KEEP, applied.effects.snapshot)
        assertEquals(BearerEffect.REPLACE, applied.effects.bearer)
    }

    @Test
    fun proactiveRenewalClassificationHandlesTheRetainedBearer() {
        listOf(
            ClassificationCase(
                classify = CompanionFacade::engineLocked,
                expectedState = CompanionRootState.EngineLocked,
                expectedBearerEffect = BearerEffect.DELETE,
            ),
            ClassificationCase(
                classify = CompanionFacade::profileMismatch,
                expectedState = CompanionRootState.ProfileMismatch,
                expectedBearerEffect = BearerEffect.DELETE,
            ),
            ClassificationCase(
                classify = CompanionFacade::incompatibleProtocol,
                expectedState = CompanionRootState.Incompatible,
                expectedBearerEffect = BearerEffect.DELETE,
            ),
            ClassificationCase(
                classify = CompanionFacade::challengeUnavailable,
                expectedState = CompanionRootState.Connecting,
                expectedBearerEffect = BearerEffect.KEEP_UNTIL_EXPIRY,
            ),
        ).forEach { case ->
            val facade = onlineFacade()
            assertIs<CompanionTransitionOutcome.Applied>(facade.proactiveRenewalStarted())

            val applied = assertIs<CompanionTransitionOutcome.Applied>(case.classify(facade))

            assertEquals(case.expectedState, applied.status.rootState)
            assertEquals(SnapshotCoverage.Complete, applied.status.snapshotCoverage)
            assertEquals(DeviceSessionEffect.KEEP, applied.effects.deviceSession)
            assertEquals(SnapshotEffect.KEEP, applied.effects.snapshot)
            assertEquals(case.expectedBearerEffect, applied.effects.bearer)
        }
    }

    @Test
    fun connectingCanDiscardAnUnavailableOrPolicyClosedBearer() {
        listOf(
            CompanionFacade::accessSessionUnavailable,
            CompanionFacade::webSocketPolicyClosed,
        ).forEach { invalidateBearer ->
            val facade = onlineFacade()
            assertIs<CompanionTransitionOutcome.Applied>(facade.proactiveRenewalStarted())

            val applied =
                assertIs<CompanionTransitionOutcome.Applied>(
                    invalidateBearer(facade),
                )

            assertEquals(CompanionRootState.Connecting, applied.status.rootState)
            assertEquals(SnapshotCoverage.Complete, applied.status.snapshotCoverage)
            assertEquals(DeviceSessionEffect.KEEP, applied.effects.deviceSession)
            assertEquals(SnapshotEffect.KEEP, applied.effects.snapshot)
            assertEquals(BearerEffect.DELETE, applied.effects.bearer)
        }
    }

    private fun pairedRestStates(): List<CompanionFacade> {
        val connecting = CompanionFacade().also { facade -> facade.beginPairing() }
        val online = onlineFacade()
        val degraded =
            CompanionFacade().also { facade ->
                facade.beginPairing()
                facade.completeConnectionWithDegradedSnapshot()
            }
        val refreshing = onlineFacade().also { facade -> facade.activeRefreshReconciled() }
        val unreachable = onlineFacade().also { facade -> facade.transportBudgetExhausted() }
        return listOf(connecting, online, degraded, refreshing, unreachable)
    }

    private fun onlineFacade(): CompanionFacade =
        CompanionFacade().also { facade ->
            facade.beginPairing()
            facade.completeConnectionWithCompleteSnapshot()
        }

    private data class ClassificationCase(
        val classify: (CompanionFacade) -> CompanionTransitionOutcome,
        val expectedState: CompanionRootState,
        val expectedBearerEffect: BearerEffect,
    )
}
