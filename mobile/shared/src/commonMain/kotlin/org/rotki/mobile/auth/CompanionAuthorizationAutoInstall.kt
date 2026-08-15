package org.rotki.mobile.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.PairingCleanupHandle
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalClearOutcome
import org.rotki.mobile.core.ports.PairingCleanupJournalWriteOutcome
import org.rotki.mobile.core.ports.PairingRecordDeleteOutcome
import org.rotki.mobile.core.state.CompanionRootState
import org.rotki.mobile.feature.authorization.data.createPlatformAuthorizationRemoteGateway

internal interface CompanionAuthorizationHandoff {
    fun onPairingRegistered()

    fun onDeviceAuthenticationSucceeded()

    fun onExplicitForegroundRetryAfterTransition()

    fun onAccessSessionUnavailableAfterTransition()

    fun onWebSocketPolicyClosedAfterTransition()

    fun onBackgroundOrSystemLock()

    fun onLocalUnpairAfterTransition(cleanup: PairingCleanupHandle)

    fun close()
}

internal interface CompanionAuthorizationHandoffLease {
    fun onPairingRegistered()

    fun close()
}

internal fun interface CompanionAuthorizationInstaller {
    fun install(
        facade: CompanionFacade,
        configuration: PairingConnectionConfiguration,
        initiallyArmed: Boolean,
    ): CompanionAuthorizationHandoff
}

internal expect fun createPlatformCompanionAuthorizationInstaller(): CompanionAuthorizationInstaller?

internal class CompanionAuthorizationAutoInstallation(
    private val installer: CompanionAuthorizationInstaller?,
) {
    private val installation: MutableStateFlow<Installation?> = MutableStateFlow(null)

    fun acquire(
        facade: CompanionFacade,
        configuration: PairingConnectionConfiguration,
        initiallyArmed: Boolean,
    ): CompanionAuthorizationHandoffLease? {
        val resolvedInstaller = installer ?: return null
        while (true) {
            val current = installation.value
            if (current != null) {
                check(current.matches(configuration)) {
                    "Companion Authorization is already bound to a different native security graph"
                }
                check(current.leaseCount < Int.MAX_VALUE) {
                    "Companion Authorization connection lease capacity exceeded"
                }
                val retained = current.copy(leaseCount = current.leaseCount + 1)
                if (installation.compareAndSet(expect = current, update = retained)) {
                    return lease(current.handoff)
                }
                continue
            }

            val handoff =
                resolvedInstaller.install(
                    facade = facade,
                    configuration = configuration,
                    initiallyArmed = initiallyArmed,
                )
            val installed =
                Installation(
                    configuration = configuration,
                    handoff = handoff,
                    leaseCount = 1,
                )
            if (installation.compareAndSet(expect = null, update = installed)) {
                return lease(handoff)
            }
            handoff.close()
        }
    }

    fun <T> withCurrentHandoff(operation: (CompanionAuthorizationHandoff?) -> T): T {
        val retained = retainCurrent()
        return try {
            operation(retained)
        } finally {
            retained?.let(::release)
        }
    }

    private fun lease(handoff: CompanionAuthorizationHandoff): CompanionAuthorizationHandoffLease =
        DefaultCompanionAuthorizationHandoffLease(handoff) {
            release(handoff)
        }

    private fun retainCurrent(): CompanionAuthorizationHandoff? {
        while (true) {
            val current = installation.value ?: return null
            check(current.leaseCount < Int.MAX_VALUE) {
                "Companion Authorization operation lease capacity exceeded"
            }
            val retained = current.copy(leaseCount = current.leaseCount + 1)
            if (installation.compareAndSet(expect = current, update = retained)) {
                return current.handoff
            }
        }
    }

    private fun release(handoff: CompanionAuthorizationHandoff) {
        while (true) {
            val current = installation.value ?: return
            if (current.handoff !== handoff) return
            val retained =
                current.takeIf { it.leaseCount > 1 }?.copy(leaseCount = current.leaseCount - 1)
            if (installation.compareAndSet(expect = current, update = retained)) {
                if (retained == null) handoff.close()
                return
            }
        }
    }

    private data class Installation(
        val configuration: PairingConnectionConfiguration,
        val handoff: CompanionAuthorizationHandoff,
        val leaseCount: Int,
    ) {
        fun matches(other: PairingConnectionConfiguration): Boolean =
            configuration.deviceProofSigner === other.deviceProofSigner &&
                configuration.pairingRecordStore === other.pairingRecordStore &&
                configuration.pairingCleanupJournal === other.pairingCleanupJournal &&
                configuration.applicationVisibility === other.applicationVisibility &&
                configuration.clock === other.clock
    }
}

internal interface CompanionAuthorizationCommands {
    fun onActiveForeground()

    fun onPairingRegistered()

    fun onExplicitForegroundRetryAfterTransition()

    fun onAccessSessionUnavailableAfterTransition()

    fun onWebSocketPolicyClosedAfterTransition()

    fun onBackgroundOrSystemLock()

    fun onLocalUnpairAfterTransition(cleanup: PairingCleanupHandle)
}

internal fun interface CompanionAuthorizationCommandsFactory {
    fun create(
        facade: CompanionFacade,
        configuration: PairingConnectionConfiguration,
        processScope: CoroutineScope,
        localAuthorityDestroyer: CompanionAuthorizationLocalAuthorityDestroyer,
    ): CompanionAuthorizationCommands
}

internal fun interface CompanionAuthorizationProcessScopeFactory {
    fun create(): CoroutineScope
}

internal class DefaultCompanionAuthorizationInstaller(
    private val commandsFactory: CompanionAuthorizationCommandsFactory =
        PlatformCompanionAuthorizationCommandsFactory,
    private val processScopeFactory: CompanionAuthorizationProcessScopeFactory =
        DefaultCompanionAuthorizationProcessScopeFactory,
) : CompanionAuthorizationInstaller {
    @Suppress("TooGenericExceptionCaught")
    override fun install(
        facade: CompanionFacade,
        configuration: PairingConnectionConfiguration,
        initiallyArmed: Boolean,
    ): CompanionAuthorizationHandoff {
        val processScope = processScopeFactory.create()
        requireNotNull(processScope.coroutineContext[Job]) {
            "Companion Authorization auto-install scope must contain a Job"
        }
        val commands =
            try {
                commandsFactory.create(
                    facade = facade,
                    configuration = configuration,
                    processScope = processScope,
                    localAuthorityDestroyer =
                        JournaledPairingAuthorityDestroyer(configuration),
                )
            } catch (failure: Exception) {
                processScope.cancel()
                throw failure
            }
        return DefaultCompanionAuthorizationHandoff(
            facade = facade,
            configuration = configuration,
            commands = commands,
            processScope = processScope,
            initiallyArmed = initiallyArmed,
        )
    }
}

internal class DefaultCompanionAuthorizationHandoffLease(
    private val handoff: CompanionAuthorizationHandoff,
    private val release: () -> Unit,
) : CompanionAuthorizationHandoffLease {
    private val closed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    override fun onPairingRegistered() {
        if (!closed.value) handoff.onPairingRegistered()
    }

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) release()
    }
}

private object DefaultCompanionAuthorizationProcessScopeFactory :
    CompanionAuthorizationProcessScopeFactory {
    override fun create(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

private object PlatformCompanionAuthorizationCommandsFactory :
    CompanionAuthorizationCommandsFactory {
    override fun create(
        facade: CompanionFacade,
        configuration: PairingConnectionConfiguration,
        processScope: CoroutineScope,
        localAuthorityDestroyer: CompanionAuthorizationLocalAuthorityDestroyer,
    ): CompanionAuthorizationCommands =
        DefaultCompanionAuthorizationCommands(
            createCompanionAuthorizationController(
                facade = facade,
                remoteGateway = createPlatformAuthorizationRemoteGateway(),
                discoveryGateway = createPlatformPairingRegistrationRemoteGateway(),
                pairingRecordStore = configuration.pairingRecordStore,
                deviceProofSigner = configuration.deviceProofSigner,
                applicationVisibility = configuration.applicationVisibility,
                clock = configuration.clock,
                processScope = processScope,
                localAuthorityDestroyer = localAuthorityDestroyer,
            ),
        )
}

private class DefaultCompanionAuthorizationCommands(
    private val controller: CompanionAuthorizationController,
) : CompanionAuthorizationCommands {
    override fun onActiveForeground(): Unit = controller.onActiveForeground()

    override fun onPairingRegistered(): Unit = controller.onPairingRegistered()

    override fun onExplicitForegroundRetryAfterTransition(): Unit =
        controller.onExplicitForegroundRetryAfterTransition()

    override fun onAccessSessionUnavailableAfterTransition(): Unit =
        controller.onAccessSessionUnavailableAfterTransition()

    override fun onWebSocketPolicyClosedAfterTransition(): Unit = controller.onWebSocketPolicyClosedAfterTransition()

    override fun onBackgroundOrSystemLock(): Unit = controller.onBackgroundOrSystemLockAfterTransition()

    override fun onLocalUnpairAfterTransition(cleanup: PairingCleanupHandle): Unit =
        controller.onLocalUnpairAfterTransition(cleanup)
}

private class DefaultCompanionAuthorizationHandoff(
    private val facade: CompanionFacade,
    private val configuration: PairingConnectionConfiguration,
    private val commands: CompanionAuthorizationCommands,
    private val processScope: CoroutineScope,
    initiallyArmed: Boolean,
) : CompanionAuthorizationHandoff {
    private val armed: MutableStateFlow<Boolean> = MutableStateFlow(initiallyArmed)
    private val closed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    init {
        processScope.launch(start = CoroutineStart.UNDISPATCHED) {
            configuration.applicationVisibility.state.drop(1).collect { visibility ->
                when (visibility) {
                    ApplicationVisibilityState.ACTIVE_FOREGROUND -> {
                        if (canAuthorize()) commands.onActiveForeground()
                    }

                    ApplicationVisibilityState.INACTIVE -> {
                        // A transient system prompt keeps the current bearer and in-flight work.
                    }

                    ApplicationVisibilityState.BACKGROUND_OR_LOCKED -> {
                        // The ordered native facade.lock() handoff owns the synchronous purge fence.
                    }
                }
            }
        }
        processScope.launch(start = CoroutineStart.UNDISPATCHED) {
            facade.status.drop(1).collect { status ->
                if (status.rootState == CompanionRootState.Unpaired ||
                    status.rootState == CompanionRootState.Revoked
                ) {
                    armed.value = false
                }
            }
        }
    }

    override fun onPairingRegistered() {
        if (closed.value || !facade.isAuthorizationRelationshipDurable()) return
        armed.value = true
        if (canAuthorize()) commands.onPairingRegistered()
    }

    override fun onDeviceAuthenticationSucceeded() {
        if (canAuthorize()) commands.onActiveForeground()
    }

    override fun onExplicitForegroundRetryAfterTransition() {
        if (canAuthorize()) commands.onExplicitForegroundRetryAfterTransition()
    }

    override fun onAccessSessionUnavailableAfterTransition() {
        if (canHandleAuthorityLoss()) commands.onAccessSessionUnavailableAfterTransition()
    }

    override fun onWebSocketPolicyClosedAfterTransition() {
        if (canHandleAuthorityLoss()) commands.onWebSocketPolicyClosedAfterTransition()
    }

    override fun onBackgroundOrSystemLock() {
        if (!closed.value && armed.value) commands.onBackgroundOrSystemLock()
    }

    override fun onLocalUnpairAfterTransition(cleanup: PairingCleanupHandle) {
        if (closed.value) return
        armed.value = false
        commands.onLocalUnpairAfterTransition(cleanup)
    }

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            armed.value = false
            processScope.cancel()
        }
    }

    private fun canAuthorize(): Boolean =
        !closed.value &&
            armed.value &&
            configuration.applicationVisibility.state.value ==
            ApplicationVisibilityState.ACTIVE_FOREGROUND &&
            facade.status.value.rootState == CompanionRootState.Connecting &&
            facade.isAuthorizationRelationshipDurable()

    private fun canHandleAuthorityLoss(): Boolean =
        !closed.value &&
            armed.value &&
            facade.status.value.rootState == CompanionRootState.Connecting &&
            facade.isAuthorizationRelationshipDurable()
}

internal class JournaledPairingAuthorityDestroyer(
    configuration: PairingConnectionConfiguration,
) : CompanionAuthorizationLocalAuthorityDestroyer {
    private val pairingRecordStore = configuration.pairingRecordStore
    private val deviceProofSigner = configuration.deviceProofSigner
    private val pairingCleanupJournal = configuration.pairingCleanupJournal
    private val cleanupMutex: Mutex = Mutex()

    override suspend fun destroyAll(): Boolean =
        cleanupMutex.withLock {
            withContext(NonCancellable) {
                val journalStored =
                    bestEffort { pairingCleanupJournal.markCleanupRequired() } ==
                        PairingCleanupJournalWriteOutcome.Stored
                val recordDeleted =
                    journalStored &&
                        bestEffort { pairingRecordStore.delete() } ==
                        PairingRecordDeleteOutcome.Deleted
                val keyDeleted =
                    journalStored &&
                        bestEffort { deviceProofSigner.deleteKey() } ==
                        DeviceProofKeyDeleteOutcome.Deleted
                val journalCleared =
                    if (recordDeleted && keyDeleted) {
                        bestEffort { pairingCleanupJournal.clear() } ==
                            PairingCleanupJournalClearOutcome.Cleared
                    } else {
                        false
                    }
                recordDeleted && keyDeleted && journalCleared
            }
        }

    private suspend inline fun <T> bestEffort(crossinline operation: suspend () -> T): T? =
        try {
            operation()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
}
