package org.rotki.mobile.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.rotki.mobile.android.ui.shell.HomeConnectionBannerState
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionStatus
import org.rotki.mobile.core.state.SnapshotCoverage

class HomeConnectionBannerStateMapperTest {
    @Test
    fun `only explicit snapshot-bearing root states enter the authenticated graph`() {
        val expectedAuthorizedStates =
            mapOf(
                status(CompanionRootState.Online, SnapshotCoverage.Complete) to
                    HomeConnectionBannerState.CONNECTED,
                status(CompanionRootState.Online, SnapshotCoverage.Degraded) to
                    HomeConnectionBannerState.CONNECTED,
                status(CompanionRootState.Refreshing, SnapshotCoverage.Complete) to
                    HomeConnectionBannerState.REFRESHING_COMPLETE,
                status(CompanionRootState.Refreshing, SnapshotCoverage.Degraded) to
                    HomeConnectionBannerState.REFRESHING_PARTIAL,
                status(CompanionRootState.Degraded, SnapshotCoverage.Degraded) to
                    HomeConnectionBannerState.DEGRADED,
                status(CompanionRootState.Degraded, SnapshotCoverage.Complete) to
                    HomeConnectionBannerState.DEGRADED,
                status(CompanionRootState.Unreachable, SnapshotCoverage.Complete) to
                    HomeConnectionBannerState.UNREACHABLE,
                status(CompanionRootState.Unreachable, SnapshotCoverage.Degraded) to
                    HomeConnectionBannerState.UNREACHABLE,
            )

        allRootStates().forEach { rootState ->
            allSnapshotCoverages().forEach { snapshotCoverage ->
                val status = status(rootState, snapshotCoverage)
                assertEquals(
                    "Unexpected authenticated mapping for ${rootState.code}/${snapshotCoverage.code}",
                    expectedAuthorizedStates[status],
                    status.toHomeConnectionBannerState(),
                )
            }
        }
    }

    private fun allRootStates(): List<CompanionRootState> =
        listOf(
            CompanionRootState.Unpaired,
            CompanionRootState.DeviceLocked,
            CompanionRootState.Connecting,
            CompanionRootState.Online,
            CompanionRootState.Refreshing,
            CompanionRootState.Degraded,
            CompanionRootState.Unreachable,
            CompanionRootState.EngineLocked,
            CompanionRootState.ProfileMismatch,
            CompanionRootState.Incompatible,
            CompanionRootState.Revoked,
        )

    private fun allSnapshotCoverages(): List<SnapshotCoverage> =
        listOf(
            SnapshotCoverage.Absent,
            SnapshotCoverage.Complete,
            SnapshotCoverage.Degraded,
        )

    private fun status(
        rootState: CompanionRootState,
        snapshotCoverage: SnapshotCoverage,
    ): CompanionStatus =
        CompanionStatus(
            rootState = rootState,
            snapshotCoverage = snapshotCoverage,
        )
}
