@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.rotki.mobile.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.PairingCleanupHandle
import org.rotki.mobile.core.ports.ApplicationVisibilityController
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.DeviceProofSigningOutcome
import org.rotki.mobile.core.ports.IdempotencyKeyGenerator
import org.rotki.mobile.core.ports.PairingCleanupJournal
import org.rotki.mobile.core.ports.PairingCleanupJournalClearOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalReadOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome
import org.rotki.mobile.core.ports.PairingRecord
import org.rotki.mobile.core.ports.PairingRecordDeleteOutcome
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.ports.PairingRecordStore
import org.rotki.mobile.core.ports.PairingRecordWriteOutcome
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.ProtocolValueParseOutcome
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.core.state.CompanionStatus
import org.rotki.mobile.core.state.CompanionTransitionOutcome
import org.rotki.mobile.core.state.SnapshotCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompanionAuthorizationAutoInstallTest {
    @Test
    fun `fresh Pairing stays unarmed until registration is durable`() =
        runTest {
            val graph = TestNativeGraph()
            val commands = RecordingCommands()
            val installer = defaultInstaller(commands)
            val facade = facade(CompanionRootState.Unpaired, authorizationInstaller = null)
            val handoff =
                installer.install(
                    facade = facade,
                    configuration = graph.configuration(),
                    initiallyArmed = false,
                )
            try {
                graph.visibility.onActiveForeground()
                val flow = facade.pairingFlow(graph.clock)
                flow.startScanning()
                flow.submitQr(validQr())
                runCurrent()

                assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
                handoff.onPairingRegistered()
                assertTrue(commands.events.isEmpty())

                val lease = assertNotNull(facade.takePendingPairingForConnection())
                handoff.onPairingRegistered()
                assertTrue(commands.events.isEmpty())
                assertNotNull(facade.markPairingCleanupRequired(lease))
                assertTrue(facade.markPendingPairingDurable(lease))
                assertTrue(facade.commitPendingPairing(lease))

                handoff.onPairingRegistered()
                assertEquals(listOf(RecordedCommand.PAIRING_REGISTERED), commands.events)
            } finally {
                handoff.close()
            }
        }

    @Test
    fun `restored handoff follows lifecycle and forwards public recovery commands after transitions`() =
        runTest {
            val graph = TestNativeGraph()
            val commands = RecordingCommands()
            val installer = defaultInstaller(commands)
            val facade = facade(CompanionRootState.DeviceLocked, installer)
            val connection = facade.pairingConnection(graph.configuration())
            try {
                graph.visibility.onActiveForeground()
                runCurrent()
                assertTrue(commands.events.isEmpty())

                assertIs<CompanionTransitionOutcome.Applied>(facade.deviceAuthenticationSucceeded())
                assertEquals(listOf(RecordedCommand.ACTIVE_FOREGROUND), commands.events)

                graph.visibility.onInactive()
                runCurrent()
                assertEquals(listOf(RecordedCommand.ACTIVE_FOREGROUND), commands.events)

                assertIs<CompanionTransitionOutcome.Applied>(facade.accessSessionUnavailable())
                assertEquals(RecordedCommand.ACCESS_SESSION_UNAVAILABLE, commands.events.last())
                assertEquals(CompanionRootState.Connecting, commands.lastRootState)

                graph.visibility.onActiveForeground()
                runCurrent()
                assertEquals(
                    2,
                    commands.events.count { event -> event == RecordedCommand.ACTIVE_FOREGROUND },
                )

                graph.visibility.onBackgroundOrLocked()
                runCurrent()
                assertIs<CompanionTransitionOutcome.Applied>(facade.lock())
                assertEquals(RecordedCommand.BACKGROUND_OR_SYSTEM_LOCK, commands.events.last())
                assertEquals(CompanionRootState.DeviceLocked, commands.lastRootState)
                graph.visibility.onActiveForeground()
                runCurrent()
                assertEquals(
                    2,
                    commands.events.count { event -> event == RecordedCommand.ACTIVE_FOREGROUND },
                )

                assertIs<CompanionTransitionOutcome.Applied>(facade.deviceAuthenticationSucceeded())
                assertEquals(
                    3,
                    commands.events.count { event -> event == RecordedCommand.ACTIVE_FOREGROUND },
                )

                assertIs<CompanionTransitionOutcome.Applied>(facade.accessSessionUnavailable())
                assertEquals(RecordedCommand.ACCESS_SESSION_UNAVAILABLE, commands.events.last())
                assertEquals(CompanionRootState.Connecting, commands.lastRootState)

                assertIs<CompanionTransitionOutcome.Applied>(facade.webSocketPolicyClosed())
                assertEquals(RecordedCommand.WEBSOCKET_POLICY_CLOSED, commands.events.last())
                assertEquals(CompanionRootState.Connecting, commands.lastRootState)

                assertIs<CompanionTransitionOutcome.Applied>(facade.transportBudgetExhausted())
                assertIs<CompanionTransitionOutcome.Applied>(facade.transportRestored())
                assertEquals(RecordedCommand.EXPLICIT_FOREGROUND_RETRY, commands.events.last())
                assertEquals(CompanionRootState.Connecting, commands.lastRootState)

                assertIs<CompanionTransitionOutcome.Applied>(facade.engineLocked())
                assertIs<CompanionTransitionOutcome.Applied>(facade.retryResolvedEngineState())
                assertEquals(RecordedCommand.EXPLICIT_FOREGROUND_RETRY, commands.events.last())
                assertEquals(CompanionRootState.Connecting, commands.lastRootState)

                assertIs<CompanionTransitionOutcome.Applied>(facade.unpair())
                assertEquals(RecordedCommand.LOCAL_UNPAIR, commands.events.last())
                assertEquals(CompanionRootState.Unpaired, commands.lastRootState)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `terminal unpair retains the handoff until journaled cleanup starts`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.DeviceLocked, installer)
            val connection = facade.pairingConnection(graph.configuration())
            var closeCallsDuringHandoff: Int? = null
            installer.handoff.onLocalUnpair = { _ ->
                connection.close()
                closeCallsDuringHandoff = installer.handoff.closeCalls
            }

            assertIs<CompanionTransitionOutcome.Applied>(facade.unpair())

            assertEquals(0, closeCallsDuringHandoff)
            assertEquals(1, installer.handoff.closeCalls)
            assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
            connection.close()
        }

    @Test
    fun `unpair wins the durable Pairing commit fence without duplicating cleanup`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.Unpaired, installer)
            val connection = facade.pairingConnection(graph.configuration())
            try {
                assertIs<CompanionTransitionOutcome.Applied>(
                    facade.acceptPairing(acceptedPairingQr(validQr(), NOW)),
                )
                val lease = assertNotNull(facade.takePendingPairingForConnection())
                val cleanup = assertNotNull(facade.markPairingCleanupRequired(lease))
                assertTrue(facade.markPendingPairingDurable(lease))
                var unpairOutcome: CompanionTransitionOutcome? = null

                val committed =
                    facade.commitPendingPairing(lease) {
                        unpairOutcome = facade.unpair()
                    }

                assertFalse(committed)
                assertIs<CompanionTransitionOutcome.Applied>(unpairOutcome)
                assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
                assertTrue(facade.hasPendingPairingCleanup())
                assertNull(installer.handoff.lastLocalUnpairCleanup)
                val retainedCleanup = assertNotNull(facade.claimRecoveredPairingCleanup())
                assertSame(cleanup.token, retainedCleanup.token)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `unpair takes over a recovered marker published after Pairing commit`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.Unpaired, installer)
            val connection = facade.pairingConnection(graph.configuration())
            try {
                assertIs<CompanionTransitionOutcome.Applied>(
                    facade.acceptPairing(acceptedPairingQr(validQr(), NOW)),
                )
                val lease = assertNotNull(facade.takePendingPairingForConnection())
                assertNotNull(facade.markPairingCleanupRequired(lease))
                assertTrue(facade.markPendingPairingDurable(lease))
                var staleRecoveredCleanup: PairingCleanupHandle? = null

                val outcome =
                    facade.unpair(
                        afterOwnershipCapturedBeforeTransition = {
                            assertTrue(facade.commitPendingPairing(lease))
                            staleRecoveredCleanup =
                                assertNotNull(facade.claimRecoveredPairingCleanup())
                        },
                    )

                assertIs<CompanionTransitionOutcome.Applied>(outcome)
                assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
                val authorizationCleanup =
                    assertNotNull(installer.handoff.lastLocalUnpairCleanup)
                assertSame(
                    assertNotNull(staleRecoveredCleanup).token,
                    authorizationCleanup.token,
                )
                assertFalse(
                    facade.abandonPendingPairingCleanup(assertNotNull(staleRecoveredCleanup)),
                )
                assertTrue(facade.hasPendingPairingCleanup())
            } finally {
                connection.close()
            }
        }

    @Test
    fun `authority epoch rejects replacement before post-commit unpair cleanup`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.Unpaired, installer)
            val connection = facade.pairingConnection(graph.configuration())
            try {
                assertIs<CompanionTransitionOutcome.Applied>(
                    facade.acceptPairing(acceptedPairingQr(validQr(), NOW)),
                )
                val lease = assertNotNull(facade.takePendingPairingForConnection())
                assertNotNull(facade.markPairingCleanupRequired(lease))
                assertTrue(facade.markPendingPairingDurable(lease))
                var replacementAdmission: CompanionTransitionOutcome? = null

                val outcome =
                    facade.unpair(
                        afterOwnershipCapturedBeforeTransition = {
                            assertTrue(facade.commitPendingPairing(lease))
                        },
                        afterTransitionBeforeCleanupClaim = {
                            replacementAdmission =
                                facade.acceptPairing(acceptedPairingQr(validQr(), NOW))
                        },
                    )

                assertIs<CompanionTransitionOutcome.Applied>(outcome)
                assertIs<CompanionTransitionOutcome.Rejected>(replacementAdmission)
                assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
                val cleanup = assertNotNull(installer.handoff.lastLocalUnpairCleanup)
                assertTrue(facade.hasPendingPairingCleanup())
                assertTrue(facade.completeAuthorizationCleanup(cleanup))
                assertIs<CompanionTransitionOutcome.Applied>(
                    facade.acceptPairing(acceptedPairingQr(validQr(), NOW)),
                )
            } finally {
                connection.close()
            }
        }

    @Test
    fun `explicit unpair fences replacement Pairing before the cleanup handoff`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.DeviceLocked, installer)
            val connection = facade.pairingConnection(graph.configuration())
            val replacement = facade.pairingFlow(graph.clock)
            replacement.startScanning()
            var claimedCleanup: PairingCleanupHandle? = null
            installer.handoff.onLocalUnpair = { cleanup ->
                claimedCleanup = cleanup
                replacement.submitQr(validQr())
            }
            try {
                assertIs<CompanionTransitionOutcome.Applied>(facade.unpair())

                assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
                assertEquals(PairingUiState.SCANNING, replacement.presentation.value.state)
                assertTrue(facade.hasPendingPairingCleanup())
                assertTrue(facade.completeAuthorizationCleanup(assertNotNull(claimedCleanup)))
                assertFalse(facade.hasPendingPairingCleanup())
                assertIs<CompanionTransitionOutcome.Applied>(
                    facade.acceptPairing(acceptedPairingQr(validQr(), NOW)),
                )
                assertIs<CompanionTransitionOutcome.Applied>(facade.unpair())
                assertEquals(1, installer.handoff.localUnpairCalls)
                assertFalse(facade.hasPendingPairingCleanup())
            } finally {
                connection.close()
            }
        }

    @Test
    fun `Pairing admission cannot publish over durable authority before unpair`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.DeviceLocked, installer)
            val connection = facade.pairingConnection(graph.configuration())
            var afterPendingStoredCalled = false
            try {
                val admission =
                    facade.acceptPairing(
                        pairingQr = acceptedPairingQr(validQr(), NOW),
                        afterPendingStored = {
                            afterPendingStoredCalled = true
                            facade.unpair()
                        },
                    )

                assertIs<CompanionTransitionOutcome.Rejected>(admission)
                assertFalse(afterPendingStoredCalled)
                assertIs<CompanionTransitionOutcome.Applied>(facade.unpair())
                assertEquals(CompanionRootState.Unpaired, facade.status.value.rootState)
                val cleanup = assertNotNull(installer.handoff.lastLocalUnpairCleanup)
                assertTrue(facade.hasPendingPairingCleanup())
                assertTrue(facade.completeAuthorizationCleanup(cleanup))
            } finally {
                connection.close()
            }
        }

    @Test
    fun `post CAS status fence clears only its pending attempt before callbacks`() =
        runTest {
            val facade = facade(CompanionRootState.Unpaired, authorizationInstaller = null)
            var afterPendingStoredCalled = false

            val admission =
                facade.acceptPairing(
                    pairingQr = acceptedPairingQr(validQr(), NOW),
                    afterPendingStored = {
                        afterPendingStoredCalled = true
                    },
                    beforeFinalOwnershipCheck = {},
                    afterPendingPublishedBeforeStatusCheck = {
                        assertIs<CompanionTransitionOutcome.Applied>(facade.beginPairing())
                    },
                )

            assertIs<CompanionTransitionOutcome.Rejected>(admission)
            assertFalse(afterPendingStoredCalled)
            assertNull(facade.takePendingPairingForConnection())
            assertEquals(CompanionRootState.Connecting, facade.status.value.rootState)
            assertIs<CompanionTransitionOutcome.Applied>(facade.unpair())
        }

    @Test
    fun `Pairing admission cannot publish over restored authority before background`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.DeviceLocked, installer)
            val connection = facade.pairingConnection(graph.configuration())
            var afterPendingStoredCalled = false
            try {
                val admission =
                    facade.acceptPairing(
                        pairingQr = acceptedPairingQr(validQr(), NOW),
                        afterPendingStored = {
                            afterPendingStoredCalled = true
                            facade.lock()
                        },
                    )

                assertIs<CompanionTransitionOutcome.Rejected>(admission)
                assertFalse(afterPendingStoredCalled)
                assertIs<CompanionTransitionOutcome.Applied>(facade.lock())
                assertEquals(CompanionRootState.DeviceLocked, facade.status.value.rootState)
                assertTrue(facade.isAuthorizationRelationshipDurable())
                assertFalse(facade.hasPendingPairingCleanup())
                assertEquals(1, installer.handoff.backgroundCalls)
            } finally {
                connection.close()
            }
        }

    @Test
    fun `rejected Pairing over restored authority leaves recovered cleanup admission available`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.DeviceLocked, installer)
            val connection = facade.pairingConnection(graph.configuration())
            var afterPendingStoredCalled = false
            try {
                val admission =
                    facade.acceptPairing(
                        pairingQr = acceptedPairingQr(validQr(), NOW),
                        afterPendingStored = {
                            afterPendingStoredCalled = true
                        },
                    )

                assertIs<CompanionTransitionOutcome.Rejected>(admission)
                assertFalse(afterPendingStoredCalled)
                val recoveredCleanup =
                    assertNotNull(
                        facade.claimRecoveredPairingCleanup(forAuthorization = true),
                    )
                assertTrue(facade.hasPendingPairingCleanup())
                assertTrue(facade.completeAuthorizationCleanup(recoveredCleanup))
            } finally {
                connection.close()
            }
        }

    @Test
    fun `one facade retains one matching native graph and closes it after the last lease`() =
        runTest {
            val graph = TestNativeGraph()
            val installer = RecordingInstaller()
            val facade = facade(CompanionRootState.Unpaired, installer)
            val firstConfiguration =
                graph.configuration(
                    deviceLabel = SECRET_DEVICE_LABEL,
                    idempotencyKeyGenerator = TestIdempotencyKeyGenerator(),
                )
            val secondConfiguration =
                graph.configuration(
                    deviceLabel = "another label",
                    idempotencyKeyGenerator = TestIdempotencyKeyGenerator(),
                )
            val first = facade.pairingConnection(firstConfiguration)
            val second = facade.pairingConnection(secondConfiguration)
            try {
                assertEquals(1, installer.installCalls)
                assertFalse(installer.initiallyArmed)
                assertSame(graph.signer, installer.configuration.deviceProofSigner)
                assertSame(graph.store, installer.configuration.pairingRecordStore)
                assertSame(graph.journal, installer.configuration.pairingCleanupJournal)
                assertSame(graph.visibility, installer.configuration.applicationVisibility)
                assertSame(graph.clock, installer.configuration.clock)

                val mismatch =
                    assertFailsWith<IllegalStateException> {
                        facade.pairingConnection(
                            graph.configuration(
                                deviceLabel = SECRET_DEVICE_LABEL,
                                clock = Clock { NOW + 1 },
                            ),
                        )
                    }
                assertFalse(SECRET_DEVICE_LABEL in mismatch.message.orEmpty())
                assertFalse(ORIGIN in mismatch.message.orEmpty())

                first.close()
                first.close()
                assertEquals(0, installer.handoff.closeCalls)
                second.close()
                assertEquals(1, installer.handoff.closeCalls)
            } finally {
                first.close()
                second.close()
            }
        }

    @Test
    fun `iOS authority cleanup journals both deletions and retains marker on partial failure`() =
        runTest {
            val completeEvents = mutableListOf<String>()
            val completeGraph = TestNativeGraph(completeEvents)

            assertTrue(
                JournaledPairingAuthorityDestroyer(completeGraph.configuration()).destroyAll(),
            )
            assertEquals(
                listOf("mark_cleanup", "delete_record", "delete_key", "clear_cleanup"),
                completeEvents,
            )
            assertFalse(completeGraph.journal.cleanupRequired)

            val partialEvents = mutableListOf<String>()
            val partialGraph = TestNativeGraph(partialEvents)
            partialGraph.store.deleteOutcome = PairingRecordDeleteOutcome.Unavailable

            assertFalse(
                JournaledPairingAuthorityDestroyer(partialGraph.configuration()).destroyAll(),
            )
            assertEquals(
                listOf("mark_cleanup", "delete_record", "delete_key"),
                partialEvents,
            )
            assertTrue(partialGraph.journal.cleanupRequired)
        }

    private fun TestScope.defaultInstaller(commands: RecordingCommands): DefaultCompanionAuthorizationInstaller =
        DefaultCompanionAuthorizationInstaller(
            commandsFactory = RecordingCommandsFactory(commands),
            processScopeFactory =
                CompanionAuthorizationProcessScopeFactory {
                    CoroutineScope(
                        backgroundScope.coroutineContext.minusKey(Job) + SupervisorJob(),
                    )
                },
        )

    private fun facade(
        rootState: CompanionRootState,
        authorizationInstaller: CompanionAuthorizationInstaller?,
    ): CompanionFacade =
        CompanionFacade(
            initialStatus = CompanionStatus(rootState, SnapshotCoverage.Absent),
            authorizationInstaller = authorizationInstaller,
        )
}

private class RecordingCommandsFactory(
    private val commands: RecordingCommands,
) : CompanionAuthorizationCommandsFactory {
    override fun create(
        facade: CompanionFacade,
        configuration: PairingConnectionConfiguration,
        processScope: CoroutineScope,
        localAuthorityDestroyer: CompanionAuthorizationLocalAuthorityDestroyer,
    ): CompanionAuthorizationCommands = commands.also { it.bind(facade) }
}

private class RecordingCommands : CompanionAuthorizationCommands {
    val events: MutableList<RecordedCommand> = mutableListOf()
    val lastRootState: CompanionRootState?
        get() = rootsAtEvent.lastOrNull()

    private val rootsAtEvent: MutableList<CompanionRootState> = mutableListOf()
    private lateinit var facade: CompanionFacade

    fun bind(facade: CompanionFacade) {
        this.facade = facade
    }

    override fun onActiveForeground() {
        record(RecordedCommand.ACTIVE_FOREGROUND)
    }

    override fun onPairingRegistered() {
        record(RecordedCommand.PAIRING_REGISTERED)
    }

    override fun onExplicitForegroundRetryAfterTransition() {
        record(RecordedCommand.EXPLICIT_FOREGROUND_RETRY)
    }

    override fun onAccessSessionUnavailableAfterTransition() {
        record(RecordedCommand.ACCESS_SESSION_UNAVAILABLE)
    }

    override fun onWebSocketPolicyClosedAfterTransition() {
        record(RecordedCommand.WEBSOCKET_POLICY_CLOSED)
    }

    override fun onBackgroundOrSystemLock() {
        record(RecordedCommand.BACKGROUND_OR_SYSTEM_LOCK)
    }

    override fun onLocalUnpairAfterTransition(cleanup: PairingCleanupHandle) {
        record(RecordedCommand.LOCAL_UNPAIR)
    }

    private fun record(command: RecordedCommand) {
        events += command
        rootsAtEvent += facade.status.value.rootState
    }
}

private enum class RecordedCommand {
    ACTIVE_FOREGROUND,
    PAIRING_REGISTERED,
    EXPLICIT_FOREGROUND_RETRY,
    ACCESS_SESSION_UNAVAILABLE,
    WEBSOCKET_POLICY_CLOSED,
    BACKGROUND_OR_SYSTEM_LOCK,
    LOCAL_UNPAIR,
}

private class RecordingInstaller : CompanionAuthorizationInstaller {
    var installCalls: Int = 0
        private set
    lateinit var configuration: PairingConnectionConfiguration
        private set
    var initiallyArmed: Boolean = false
        private set
    lateinit var handoff: RecordingHandoff
        private set

    override fun install(
        facade: CompanionFacade,
        configuration: PairingConnectionConfiguration,
        initiallyArmed: Boolean,
    ): CompanionAuthorizationHandoff {
        installCalls += 1
        this.configuration = configuration
        this.initiallyArmed = initiallyArmed
        return RecordingHandoff().also { handoff = it }
    }
}

private class RecordingHandoff : CompanionAuthorizationHandoff {
    var closeCalls: Int = 0
        private set
    var backgroundCalls: Int = 0
        private set
    var localUnpairCalls: Int = 0
        private set
    var onLocalUnpair: (PairingCleanupHandle) -> Unit = {}
    var lastLocalUnpairCleanup: PairingCleanupHandle? = null
        private set

    override fun onPairingRegistered(): Unit = Unit

    override fun onDeviceAuthenticationSucceeded(): Unit = Unit

    override fun onExplicitForegroundRetryAfterTransition(): Unit = Unit

    override fun onAccessSessionUnavailableAfterTransition(): Unit = Unit

    override fun onWebSocketPolicyClosedAfterTransition(): Unit = Unit

    override fun onBackgroundOrSystemLock() {
        backgroundCalls += 1
    }

    override fun onLocalUnpairAfterTransition(cleanup: PairingCleanupHandle) {
        localUnpairCalls += 1
        lastLocalUnpairCleanup = cleanup
        onLocalUnpair.invoke(cleanup)
    }

    override fun close() {
        closeCalls += 1
    }
}

private class TestNativeGraph(
    events: MutableList<String>? = null,
) {
    val signer: TestDeviceProofSigner = TestDeviceProofSigner(events)
    val store: TestPairingRecordStore = TestPairingRecordStore(events)
    val journal: TestPairingCleanupJournal = TestPairingCleanupJournal(events)
    val visibility: ApplicationVisibilityController = ApplicationVisibilityController()
    val clock: Clock = Clock { NOW }

    fun configuration(
        deviceLabel: String = "iPhone",
        idempotencyKeyGenerator: IdempotencyKeyGenerator = TestIdempotencyKeyGenerator(),
        clock: Clock = this.clock,
    ): PairingConnectionConfiguration =
        PairingConnectionConfiguration(
            deviceLabel = deviceLabel,
            platform = PairingDevicePlatform.IOS,
            deviceProofSigner = signer,
            pairingRecordStore = store,
            pairingCleanupJournal = journal,
            idempotencyKeyGenerator = idempotencyKeyGenerator,
            applicationVisibility = visibility,
            clock = clock,
        )
}

private class TestDeviceProofSigner(
    private val events: MutableList<String>?,
) : DeviceProofSigner {
    var deleteOutcome: DeviceProofKeyDeleteOutcome = DeviceProofKeyDeleteOutcome.Deleted

    override suspend fun createKeyForPairing(): DeviceProofPublicKeyOutcome =
        DeviceProofPublicKeyOutcome.PairingRequired

    override suspend fun currentPublicKeyX963(): DeviceProofPublicKeyOutcome =
        DeviceProofPublicKeyOutcome.PairingRequired

    override suspend fun sign(transcript: ByteArray): DeviceProofSigningOutcome =
        DeviceProofSigningOutcome.UnexpectedFailure

    override suspend fun deleteKey(): DeviceProofKeyDeleteOutcome {
        events?.add("delete_key")
        return deleteOutcome
    }
}

private class TestPairingRecordStore(
    private val events: MutableList<String>?,
) : PairingRecordStore {
    var deleteOutcome: PairingRecordDeleteOutcome = PairingRecordDeleteOutcome.Deleted

    override suspend fun read(): PairingRecordReadOutcome = PairingRecordReadOutcome.Missing

    override suspend fun write(record: PairingRecord): PairingRecordWriteOutcome = PairingRecordWriteOutcome.Stored

    override suspend fun delete(): PairingRecordDeleteOutcome {
        events?.add("delete_record")
        return deleteOutcome
    }
}

private class TestPairingCleanupJournal(
    private val events: MutableList<String>?,
) : PairingCleanupJournal {
    var cleanupRequired: Boolean = false
        private set

    override suspend fun read(): PairingCleanupJournalReadOutcome =
        if (cleanupRequired) {
            PairingCleanupJournalReadOutcome.CleanupRequired
        } else {
            PairingCleanupJournalReadOutcome.Clear
        }

    override suspend fun markCleanupRequired(): PairingCleanupJournalWriteOutcome {
        events?.add("mark_cleanup")
        cleanupRequired = true
        return PairingCleanupJournalWriteOutcome.Stored
    }

    override suspend fun clear(): PairingCleanupJournalClearOutcome {
        events?.add("clear_cleanup")
        cleanupRequired = false
        return PairingCleanupJournalClearOutcome.Cleared
    }
}

private class TestIdempotencyKeyGenerator : IdempotencyKeyGenerator {
    override fun generate(): IdempotencyKey = TEST_IDEMPOTENCY_KEY
}

private fun validQr(): String =
    """
    {
      "kind": "rotki_companion_pairing",
      "format_version": 1,
      "engine_origin": "$ORIGIN",
      "pairing_id": "$PAIRING_ID",
      "pairing_credential": "$PAIRING_CREDENTIAL",
      "expires_at": ${NOW + 100}
    }
    """.trimIndent()

private val TEST_IDEMPOTENCY_KEY: IdempotencyKey =
    when (val parsed = IdempotencyKey.fromBytes(ByteArray(16))) {
        is ProtocolValueParseOutcome.Accepted -> parsed.value
        is ProtocolValueParseOutcome.Rejected -> error("Static idempotency key fixture is invalid")
    }

private const val NOW: Long = 1_786_550_300L
private const val ORIGIN: String = "https://rotki.example"
private const val PAIRING_ID: String = "AAECAwQFBgcICQoLDA0ODw"
private const val PAIRING_CREDENTIAL: String =
    "EBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8"
private const val SECRET_DEVICE_LABEL: String = "private iPhone label"
