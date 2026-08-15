@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.core.ports.ApplicationVisibility
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.ports.DeviceProofSigner
import org.rotki.mobile.core.ports.PairingRecordReadOutcome
import org.rotki.mobile.core.ports.PairingRecordStore
import org.rotki.mobile.core.protocol.generated.SUPPORTED_PROTOCOL_VERSIONS
import org.rotki.mobile.feature.authorization.application.AuthorizationCoordinator
import org.rotki.mobile.feature.authorization.application.AuthorizationCoordinatorOutcome
import org.rotki.mobile.feature.authorization.data.createDeviceProofTranscriptEncoder
import org.rotki.mobile.feature.authorization.domain.AuthorizationRemoteGateway
import kotlin.native.HiddenFromObjC

/** Platform-owned destructive cleanup used by the Kotlin-only native composition bridge. */
@HiddenFromObjC
public fun interface CompanionAuthorizationLocalAuthorityDestroyer {
    public suspend fun destroyAll(): Boolean
}

/**
 * Kotlin-only process controller used by native composition roots.
 *
 * Calls are launched undispatched so lifecycle authority is fenced before control returns whenever
 * the uncontended coordinator path can proceed synchronously.
 */
@HiddenFromObjC
public class CompanionAuthorizationController internal constructor(
    private val adapter: CompanionAuthorizationAdapter,
    private val discoveryGateway: PairingRegistrationRemoteGateway,
    private val processScope: CoroutineScope,
) {
    @HiddenFromObjC
    public fun onActiveForeground(): Unit =
        launchCommand {
            adapter.onActiveForegroundAuthorization()
        }

    @HiddenFromObjC
    public fun onPairingRegistered(): Unit = onActiveForeground()

    @HiddenFromObjC
    public fun onExplicitForegroundRetry(): Unit =
        launchCommand {
            adapter.onExplicitForegroundRetry()
        }

    @HiddenFromObjC
    public fun onBackgroundOrSystemLock(): Unit =
        launchCommand {
            adapter.onBackgroundOrSystemLock()
        }

    @HiddenFromObjC
    public suspend fun close(): Unit =
        try {
            adapter.close()
        } finally {
            discoveryGateway.close()
        }

    public override fun toString(): String = "CompanionAuthorizationController(redacted)"

    private fun launchCommand(command: suspend () -> Unit) {
        processScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                command()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Native lifecycle entry points stay fail-closed and secret-free.
            }
        }
    }
}

/** Builds exactly one coordinator and facade adapter for a native application process. */
@HiddenFromObjC
public fun createCompanionAuthorizationController(
    facade: CompanionFacade,
    remoteGateway: AuthorizationRemoteGateway,
    discoveryGateway: PairingRegistrationRemoteGateway,
    pairingRecordStore: PairingRecordStore,
    deviceProofSigner: DeviceProofSigner,
    applicationVisibility: ApplicationVisibility,
    clock: Clock,
    processScope: CoroutineScope,
    localAuthorityDestroyer: CompanionAuthorizationLocalAuthorityDestroyer,
): CompanionAuthorizationController {
    val initialProtocolVersion =
        requireNotNull(SUPPORTED_PROTOCOL_VERSIONS.maxOrNull()) {
            "The Companion client must support at least one protocol version"
        }
    val eventBridge = CompanionAuthorizationEventBridge()
    val coordinator =
        AuthorizationCoordinator(
            remoteGateway = remoteGateway,
            transcriptEncoder = createDeviceProofTranscriptEncoder(),
            pairingRecordStore = pairingRecordStore,
            deviceProofSigner = deviceProofSigner,
            applicationVisibility = applicationVisibility,
            clock = clock,
            selectedProtocolVersion = initialProtocolVersion,
            processScope = processScope,
            eventSink = eventBridge,
        )
    val adapter =
        CompanionAuthorizationAdapter(
            facade = facade,
            coordinator = coordinator,
            requestAuthority = coordinator,
            applicationVisibility = applicationVisibility,
            discovery =
                CompanionAuthorizationDiscovery {
                    when (val record = pairingRecordStore.read()) {
                        is PairingRecordReadOutcome.Present -> {
                            discoveryGateway.discover(record.record.engineOrigin).toAuthorizationDiscoveryOutcome()
                        }

                        PairingRecordReadOutcome.Missing -> {
                            CompanionAuthorizationDiscoveryOutcome.LocalFailure(
                                AuthorizationCoordinatorOutcome.PairingRequired,
                            )
                        }

                        PairingRecordReadOutcome.Corrupt,
                        PairingRecordReadOutcome.Unavailable,
                        -> {
                            CompanionAuthorizationDiscoveryOutcome.LocalFailure(
                                AuthorizationCoordinatorOutcome.LocalStorageUnavailable,
                            )
                        }
                    }
                },
            localAuthorityCleaner =
                CompanionAuthorizationLocalAuthorityCleaner(localAuthorityDestroyer::destroyAll),
            sessionWorkController =
                CompanionAuthenticatedSessionWorkController { _, _ ->
                    CompanionAuthenticatedSessionWorkInvalidation { true }
                },
            processScope = processScope,
            eventBridge = eventBridge,
        )
    processScope.coroutineContext[Job]?.invokeOnCompletion {
        discoveryGateway.close()
    }
    return CompanionAuthorizationController(adapter, discoveryGateway, processScope)
}

private fun PairingDiscoveryOutcome.toAuthorizationDiscoveryOutcome(): CompanionAuthorizationDiscoveryOutcome =
    when (this) {
        is PairingDiscoveryOutcome.Compatible -> {
            CompanionAuthorizationDiscoveryOutcome.Compatible(selectedProtocolVersion)
        }

        PairingDiscoveryOutcome.Incompatible -> {
            CompanionAuthorizationDiscoveryOutcome.Incompatible
        }

        is PairingDiscoveryOutcome.Rejected,
        is PairingDiscoveryOutcome.ContractFailure,
        -> {
            CompanionAuthorizationDiscoveryOutcome.ContractFailure
        }

        PairingDiscoveryOutcome.PreResponseTransportFailure,
        PairingDiscoveryOutcome.CompleteResponseTransportFailure,
        -> {
            CompanionAuthorizationDiscoveryOutcome.NetworkUnavailable
        }
    }
