package org.rotki.mobile.android.pairing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.rotki.mobile.auth.PairingConnectionOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalReadOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.protocol.DeviceSessionId
import org.rotki.mobile.core.protocol.EngineOrigin
import org.rotki.mobile.core.protocol.EngineOriginParseOutcome
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.protocol.X963PublicKey
import org.rotki.mobile.core.state.CompanionRootState

class AndroidPairingStartupReconcilerTest {
    @Test
    fun `paired root is restored only when record and key are both present`() =
        runTest {
            val fixture =
                Fixture(
                    record = PairingRecordReadOutcome.Present(record()),
                    key = DeviceProofPublicKeyOutcome.PublicKey(publicKey()),
                )

            assertEquals(AndroidPairingStartupState.PAIRED, fixture.reconcile())
            assertEquals(0, fixture.markCalls)
        }

    @Test
    fun `fully absent material starts unpaired without a cleanup marker`() =
        runTest {
            val fixture = Fixture()

            assertEquals(AndroidPairingStartupState.UNPAIRED, fixture.reconcile())
            assertEquals(0, fixture.markCalls)
        }

    @Test
    fun `record without key is journaled before shared cleanup`() =
        runTest {
            val fixture = Fixture(record = PairingRecordReadOutcome.Present(record()))

            assertEquals(AndroidPairingStartupState.CLEANUP_REQUIRED, fixture.reconcile())
            assertEquals(1, fixture.markCalls)
        }

    @Test
    fun `key without record is journaled before shared cleanup`() =
        runTest {
            val fixture = Fixture(key = DeviceProofPublicKeyOutcome.PublicKey(publicKey()))

            assertEquals(AndroidPairingStartupState.CLEANUP_REQUIRED, fixture.reconcile())
            assertEquals(1, fixture.markCalls)
        }

    @Test
    fun `durable journal bypasses material reads and requires cleanup`() =
        runTest {
            val fixture = Fixture(journal = PairingCleanupJournalReadOutcome.CleanupRequired)

            assertEquals(AndroidPairingStartupState.CLEANUP_REQUIRED, fixture.reconcile())
            assertEquals(0, fixture.recordReads)
            assertEquals(0, fixture.keyReads)
        }

    @Test
    fun `unavailable journal or marker write remains fail closed`() =
        runTest {
            assertEquals(
                AndroidPairingStartupState.FAIL_CLOSED,
                Fixture(journal = PairingCleanupJournalReadOutcome.Unavailable).reconcile(),
            )
            assertEquals(
                AndroidPairingStartupState.FAIL_CLOSED,
                Fixture(
                    record = PairingRecordReadOutcome.Corrupt,
                    mark = PairingCleanupJournalWriteOutcome.Unavailable,
                ).reconcile(),
            )
        }

    @Test
    fun `unavailable material reads remain fail closed without destructive intent`() =
        runTest {
            listOf(
                Fixture(record = PairingRecordReadOutcome.Unavailable),
                Fixture(key = DeviceProofPublicKeyOutcome.UnexpectedFailure),
                Fixture(
                    record = PairingRecordReadOutcome.Present(record()),
                    key = DeviceProofPublicKeyOutcome.UnexpectedFailure,
                ),
            ).forEach { fixture ->
                assertEquals(AndroidPairingStartupState.FAIL_CLOSED, fixture.reconcile())
                assertEquals(0, fixture.markCalls)
            }
        }

    @Test
    fun `unknown startup authority uses DeviceLocked until retry proves a state`() =
        runTest {
            val facade = AndroidPairingStartupState.FAIL_CLOSED.createStartupFacade()
            assertEquals(CompanionRootState.DeviceLocked, facade.status.value.rootState)

            val pairedOutcome =
                AndroidPairingStartupState.PAIRED.applyRetryTo(facade) {
                    error("No cleanup expected")
                }
            assertEquals(PairingConnectionOutcome.NO_PENDING_PAIRING, pairedOutcome)
            assertEquals(CompanionRootState.DeviceLocked, facade.status.value.rootState)

            val unpairedOutcome =
                AndroidPairingStartupState.UNPAIRED.applyRetryTo(facade) {
                    error("No cleanup expected")
                }
            assertEquals(PairingConnectionOutcome.NO_PENDING_PAIRING, unpairedOutcome)
            assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
        }

    @Test
    fun `cleanup-required retry delegates once and failure stays fail closed`() =
        runTest {
            val facade = AndroidPairingStartupState.CLEANUP_REQUIRED.createStartupFacade()
            var cleanupCalls = 0

            val outcome =
                AndroidPairingStartupState.CLEANUP_REQUIRED.applyRetryTo(facade) {
                    cleanupCalls += 1
                    PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                }

            assertEquals(1, cleanupCalls)
            assertEquals(PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE, outcome)
            assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
        }

    private class Fixture(
        private val journal: PairingCleanupJournalReadOutcome =
            PairingCleanupJournalReadOutcome.Clear,
        private val record: PairingRecordReadOutcome = PairingRecordReadOutcome.Missing,
        private val key: DeviceProofPublicKeyOutcome =
            DeviceProofPublicKeyOutcome.PairingRequired,
        private val mark: PairingCleanupJournalWriteOutcome =
            PairingCleanupJournalWriteOutcome.Stored,
    ) {
        var recordReads: Int = 0
        var keyReads: Int = 0
        var markCalls: Int = 0

        suspend fun reconcile(): AndroidPairingStartupState =
            AndroidPairingStartupReconciler(
                readJournal = { journal },
                readRecord = {
                    recordReads += 1
                    record
                },
                readCurrentKey = {
                    keyReads += 1
                    key
                },
                markCleanupRequired = {
                    markCalls += 1
                    mark
                },
            ).reconcile()
    }
}

private fun record(): PairingRecord {
    val origin =
        (
            EngineOrigin.parse("https://rotki.example") as
                EngineOriginParseOutcome.Accepted
        ).origin
    val sessionId =
        (
            DeviceSessionId.parse(
                "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            ) as ProtocolValueParseOutcome.Accepted<DeviceSessionId>
        ).value
    return PairingRecord(origin, sessionId)
}

private fun publicKey(): X963PublicKey =
    (
        X963PublicKey.parse(
            "BGsX0fLhLEJH-Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU",
        ) as ProtocolValueParseOutcome.Accepted<X963PublicKey>
    ).value
