package org.rotki.mobile.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.rotki.mobile.PairingCleanupHandle
import org.rotki.mobile.PendingPairingLease
import org.rotki.mobile.auth.protocol.DeviceLabel
import org.rotki.mobile.auth.protocol.parsePairingDeviceLabel
import org.rotki.mobile.core.network.RequestReplayPolicy
import org.rotki.mobile.core.network.RetryDecision
import org.rotki.mobile.core.network.RetryFailure
import org.rotki.mobile.core.network.RetryPolicy
import org.rotki.mobile.core.network.TypedErrorRetryDisposition
import org.rotki.mobile.core.ports.ApplicationVisibility
import org.rotki.mobile.core.ports.ApplicationVisibilityState
import org.rotki.mobile.core.ports.Clock
import org.rotki.mobile.core.ports.DeviceProofKeyDeleteOutcome
import org.rotki.mobile.core.ports.DeviceProofPublicKeyOutcome
import org.rotki.mobile.core.ports.DeviceProofSigner
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
import org.rotki.mobile.core.protocol.CompanionFailure
import org.rotki.mobile.core.protocol.IdempotencyKey
import org.rotki.mobile.core.protocol.X963PublicKey
import org.rotki.mobile.core.protocol.generated.CompanionPlatform
import org.rotki.mobile.core.protocol.generated.HttpErrorCode
import org.rotki.mobile.core.protocol.generated.ProtocolErrorAction
import org.rotki.mobile.core.protocol.generated.ProtocolLifetimesSeconds
import org.rotki.mobile.feature.pairing.domain.PairingAttemptPort

public enum class PairingDevicePlatform(
    public val code: String,
) {
    ANDROID("android"),
    IOS("ios"),
}

/** Native inputs for Pairing; its representation deliberately redacts the device label. */
public class PairingConnectionConfiguration(
    public val deviceLabel: String,
    public val platform: PairingDevicePlatform,
    public val deviceProofSigner: DeviceProofSigner,
    public val pairingRecordStore: PairingRecordStore,
    public val pairingCleanupJournal: PairingCleanupJournal,
    public val idempotencyKeyGenerator: IdempotencyKeyGenerator,
    public val applicationVisibility: ApplicationVisibility,
    public val clock: Clock,
) {
    override fun toString(): String = "PairingConnectionConfiguration(redacted)"
}

/** Coarse, secret-free terminal outcomes suitable for a thin Android or Swift adapter. */
public enum class PairingConnectionOutcome(
    public val code: String,
) {
    REGISTERED("registered"),
    NO_PENDING_PAIRING("no_pending_pairing"),
    OUTSIDE_ACTIVE_FOREGROUND("outside_active_foreground"),
    PAIRING_EXPIRED("pairing_expired"),
    PAIRING_UNAVAILABLE("pairing_unavailable"),
    INCOMPATIBLE("incompatible"),
    RATE_LIMITED("rate_limited"),
    NETWORK_UNAVAILABLE("network_unavailable"),
    LOCAL_SECURITY_UNAVAILABLE("local_security_unavailable"),
    LOCAL_STORAGE_UNAVAILABLE("local_storage_unavailable"),
    LOCAL_CLEANUP_INCOMPLETE("local_cleanup_incomplete"),
    UNEXPECTED_ENGINE_RESPONSE("unexpected_engine_response"),
    UNEXPECTED_FAILURE("unexpected_failure"),
}

/**
 * Registers the one pending QR attempt without exposing its origin or credential to native code.
 * Calls are single-flight. Structured cancellation is always rethrown.
 */
public class PairingConnection internal constructor(
    private val attempts: PairingAttemptPort<PendingPairingLease, PairingCleanupHandle>,
    private val configuration: PairingConnectionConfiguration,
    private val protocolClient: PairingRegistrationRemoteGateway,
    private val retryPolicy: RetryPolicy,
    private val retryDelay: PairingRetryDelay,
    private val authorizationHandoffLease: CompanionAuthorizationHandoffLease? = null,
) {
    private val closed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    public suspend fun connectPendingPairing(): PairingConnectionOutcome {
        val outcome = attempts.withConnectionOwnership(::connectSerialized)
        if (outcome == PairingConnectionOutcome.REGISTERED) {
            try {
                authorizationHandoffLease?.onPairingRegistered()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Authorization is a separate handoff and cannot change a durable Pairing result.
            }
        }
        return outcome
    }

    /** Retries an incomplete fail-closed cleanup without starting any network request. */
    public suspend fun retryIncompleteCleanup(): PairingConnectionOutcome =
        attempts.withConnectionOwnership {
            val cleanup =
                attempts.claimRecoveredCleanup()
                    ?: return@withConnectionOwnership if (attempts.hasPendingCleanup()) {
                        PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                    } else {
                        PairingConnectionOutcome.NO_PENDING_PAIRING
                    }
            val journal = readCleanupJournal()
            when (journal) {
                PairingCleanupJournalReadOutcome.Clear -> {
                    if (attempts.abandonCleanup(cleanup)) {
                        PairingConnectionOutcome.NO_PENDING_PAIRING
                    } else {
                        PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                    }
                }

                PairingCleanupJournalReadOutcome.CleanupRequired -> {
                    if (cleanupAttemptMaterial()) {
                        if (attempts.completeCleanup(cleanup)) {
                            PairingConnectionOutcome.NO_PENDING_PAIRING
                        } else {
                            PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                        }
                    } else {
                        PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                    }
                }

                PairingCleanupJournalReadOutcome.Unavailable -> {
                    PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                }
            }
        }

    public fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        try {
            protocolClient.close()
        } finally {
            authorizationHandoffLease?.close()
        }
    }

    override fun toString(): String = "PairingConnection(redacted)"

    private suspend fun connectSerialized(): PairingConnectionOutcome {
        if (attempts.hasPendingCleanup()) {
            return PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
        }
        val lease =
            attempts.takePending()
                ?: return PairingConnectionOutcome.NO_PENDING_PAIRING
        val cleanupJournal =
            try {
                readCleanupJournal()
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) {
                    attempts.abort(lease)
                }
                throw cancellation
            }
        if (cleanupJournal != PairingCleanupJournalReadOutcome.Clear) {
            attempts.markCleanupRequired(lease)
            return PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
        }
        var cleanupHandle: PairingCleanupHandle? = null
        var cleanupJournalStored = false
        var committed = false
        var result = PairingConnectionOutcome.UNEXPECTED_FAILURE
        try {
            result =
                try {
                    performRegistration(
                        lease = lease,
                        markCleanupRequired = {
                            attempts.markCleanupRequired(lease)?.also { cleanup ->
                                cleanupHandle = cleanup
                            } != null
                        },
                        onCleanupJournalStored = { cleanupJournalStored = true },
                    ).also { outcome ->
                        if (outcome == PairingConnectionOutcome.REGISTERED) {
                            committed = true
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    PairingConnectionOutcome.UNEXPECTED_FAILURE
                }
        } finally {
            if (!committed) {
                val cleanupComplete =
                    withContext(NonCancellable) {
                        val cleanup = cleanupHandle
                        if (cleanup != null && cleanupJournalStored) {
                            if (cleanupAttemptMaterial()) {
                                attempts.completeCleanup(cleanup)
                            } else {
                                false
                            }
                        } else if (cleanup == null) {
                            attempts.abort(lease)
                            true
                        } else {
                            false
                        }
                    }
                if (!cleanupComplete && currentCoroutineContext().isActive) {
                    result = PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                }
            }
        }
        return result
    }

    private suspend fun readCleanupJournal(): PairingCleanupJournalReadOutcome =
        try {
            configuration.pairingCleanupJournal.read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            PairingCleanupJournalReadOutcome.Unavailable
        }

    private suspend fun deleteDeviceKey(): Boolean =
        try {
            configuration.deviceProofSigner.deleteKey() == DeviceProofKeyDeleteOutcome.Deleted
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }

    private suspend fun cleanupAttemptMaterial(): Boolean {
        val recordDeleted =
            try {
                configuration.pairingRecordStore.delete() == PairingRecordDeleteOutcome.Deleted
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
        val keyDeleted =
            try {
                configuration.deviceProofSigner.deleteKey() == DeviceProofKeyDeleteOutcome.Deleted
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                false
            }
        val materialDeleted = recordDeleted && keyDeleted
        val journalCleared =
            if (materialDeleted) {
                try {
                    configuration.pairingCleanupJournal.clear() ==
                        PairingCleanupJournalClearOutcome.Cleared
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }
        return materialDeleted && journalCleared
    }

    private suspend fun performRegistration(
        lease: PendingPairingLease,
        markCleanupRequired: () -> Boolean,
        onCleanupJournalStored: () -> Unit,
    ): PairingConnectionOutcome {
        if (!gateAttempt(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        when (configuration.pairingRecordStore.read()) {
            PairingRecordReadOutcome.Missing -> Unit

            PairingRecordReadOutcome.Corrupt,
            is PairingRecordReadOutcome.Present,
            PairingRecordReadOutcome.Unavailable,
            -> return PairingConnectionOutcome.LOCAL_STORAGE_UNAVAILABLE
        }
        if (!gateAttempt(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        val label =
            parsePairingDeviceLabel(configuration.deviceLabel)
                ?: return PairingConnectionOutcome.LOCAL_SECURITY_UNAVAILABLE
        val pairingQr = lease.pairingQr
        if (pairingQr.expiresAtEpochSeconds <= configuration.clock.nowEpochSeconds()) {
            return PairingConnectionOutcome.PAIRING_EXPIRED
        }

        val selectedProtocolVersion =
            when (
                val discovery = discoverWithRetry(lease)
            ) {
                is DiscoverySequenceOutcome.Compatible -> {
                    discovery.selectedProtocolVersion
                }

                is DiscoverySequenceOutcome.Terminal -> {
                    return discovery.outcome.toPublicPairingOutcome()
                }

                DiscoverySequenceOutcome.Expired -> {
                    return PairingConnectionOutcome.PAIRING_EXPIRED
                }

                DiscoverySequenceOutcome.AttemptUnavailable -> {
                    return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
                }
            }
        if (!gateAttempt(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        if (pairingQr.expiresAtEpochSeconds <= configuration.clock.nowEpochSeconds()) {
            return PairingConnectionOutcome.PAIRING_EXPIRED
        }

        if (!markCleanupRequired()) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        if (configuration.pairingCleanupJournal.markCleanupRequired() !=
            PairingCleanupJournalWriteOutcome.Stored
        ) {
            return PairingConnectionOutcome.LOCAL_STORAGE_UNAVAILABLE
        }
        onCleanupJournalStored()
        if (!gateAttempt(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        val existingKey = configuration.deviceProofSigner.currentPublicKeyX963()
        if (!gateAttempt(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        when (existingKey) {
            is DeviceProofPublicKeyOutcome.PublicKey -> {
                if (!deleteDeviceKey()) {
                    return PairingConnectionOutcome.LOCAL_CLEANUP_INCOMPLETE
                }
                if (!gateAttempt(lease)) {
                    return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
                }
            }

            DeviceProofPublicKeyOutcome.PairingRequired -> {
                // There is no pre-existing key to reconcile before creating one.
            }

            DeviceProofPublicKeyOutcome.UnexpectedFailure -> {
                return PairingConnectionOutcome.LOCAL_SECURITY_UNAVAILABLE
            }
        }
        val publicKey =
            when (val key = configuration.deviceProofSigner.createKeyForPairing()) {
                is DeviceProofPublicKeyOutcome.PublicKey -> key.value

                DeviceProofPublicKeyOutcome.PairingRequired,
                DeviceProofPublicKeyOutcome.UnexpectedFailure,
                -> return PairingConnectionOutcome.LOCAL_SECURITY_UNAVAILABLE
            }
        if (!gateAttempt(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }

        val idempotencyKey = configuration.idempotencyKeyGenerator.generate()
        val deviceSession =
            when (
                val registration =
                    registerWithRetry(
                        lease = lease,
                        selectedProtocolVersion = selectedProtocolVersion,
                        idempotencyKey = idempotencyKey,
                        deviceLabel = label,
                        platform = configuration.platform.toProtocolPlatform(),
                        publicKey = publicKey,
                    )
            ) {
                is RegistrationSequenceOutcome.Registered -> {
                    registration.deviceSession.takeIf { session ->
                        val earliestPairing =
                            pairingQr.expiresAtEpochSeconds -
                                minOf(
                                    pairingQr.expiresAtEpochSeconds,
                                    ProtocolLifetimesSeconds.Pairing,
                                )
                        session.pairedAtEpochSeconds in
                            earliestPairing..pairingQr.expiresAtEpochSeconds
                    } ?: return PairingConnectionOutcome.UNEXPECTED_ENGINE_RESPONSE
                }

                is RegistrationSequenceOutcome.Terminal -> {
                    return registration.outcome.toPublicPairingOutcome()
                }

                RegistrationSequenceOutcome.Expired -> {
                    return PairingConnectionOutcome.PAIRING_EXPIRED
                }

                RegistrationSequenceOutcome.AttemptUnavailable -> {
                    return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
                }
            }
        if (!gateAttempt(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        val record = PairingRecord(pairingQr.engineOrigin, deviceSession.id)
        if (configuration.pairingRecordStore.write(record) != PairingRecordWriteOutcome.Stored) {
            return PairingConnectionOutcome.LOCAL_STORAGE_UNAVAILABLE
        }
        if (!gateAttempt(lease) || !attempts.markDurable(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        if (configuration.pairingCleanupJournal.clear() !=
            PairingCleanupJournalClearOutcome.Cleared
        ) {
            return PairingConnectionOutcome.LOCAL_STORAGE_UNAVAILABLE
        }
        if (!attempts.commit(lease)) {
            return PairingConnectionOutcome.OUTSIDE_ACTIVE_FOREGROUND
        }
        return PairingConnectionOutcome.REGISTERED
    }

    private suspend fun discoverWithRetry(lease: PendingPairingLease): DiscoverySequenceOutcome {
        var completedAttempts = 0
        while (true) {
            if (!attempts.isCurrent(lease)) {
                return DiscoverySequenceOutcome.AttemptUnavailable
            }
            if (lease.pairingQr.expiresAtEpochSeconds <= configuration.clock.nowEpochSeconds()) {
                return DiscoverySequenceOutcome.Expired
            }
            completedAttempts += 1
            val outcome =
                executeWhenActive(lease) {
                    executeBeforePairingExpiry(lease) {
                        protocolClient.discover(lease.pairingQr.engineOrigin)
                    }
                }
            if (outcome is LifecycleOperationOutcome.Backgrounded) {
                return DiscoverySequenceOutcome.AttemptUnavailable
            }
            if (outcome is LifecycleOperationOutcome.Suspended) {
                if (awaitActiveOrUnavailable(lease) == LifecycleGate.Unavailable) {
                    return DiscoverySequenceOutcome.AttemptUnavailable
                }
                completedAttempts = 0
                continue
            }
            outcome as LifecycleOperationOutcome.Completed
            val discovery = outcome.value ?: return DiscoverySequenceOutcome.Expired
            if (discovery is PairingDiscoveryOutcome.Compatible) {
                return DiscoverySequenceOutcome.Compatible(discovery.selectedProtocolVersion)
            }
            val retryFailure =
                discovery.toRetryFailure()
                    ?: return DiscoverySequenceOutcome.Terminal(discovery)
            if (!gateAttempt(lease)) {
                return DiscoverySequenceOutcome.AttemptUnavailable
            }
            val decision =
                retryPolicy.decide(
                    replayPolicy = RequestReplayPolicy.SAFE_READ,
                    completedAttempts = completedAttempts,
                    failure = retryFailure,
                    isActiveForeground = true,
                    isCredentialAvailable = attempts.isCurrent(lease),
                )
            when (decision) {
                is RetryDecision.RetryAfter -> {
                    when (
                        waitForRetryWhileActive(lease, decision.delayMillis)
                    ) {
                        RetryWaitOutcome.Ready -> {
                            // Keep the current retry budget in the same foreground epoch.
                        }

                        RetryWaitOutcome.ForegroundResumed -> {
                            completedAttempts = 0
                        }

                        RetryWaitOutcome.Backgrounded -> {
                            return DiscoverySequenceOutcome.AttemptUnavailable
                        }
                    }
                }

                is RetryDecision.Stop -> {
                    return DiscoverySequenceOutcome.Terminal(discovery)
                }
            }
        }
    }

    private suspend fun registerWithRetry(
        lease: PendingPairingLease,
        selectedProtocolVersion: Int,
        idempotencyKey: IdempotencyKey,
        deviceLabel: DeviceLabel,
        platform: CompanionPlatform,
        publicKey: X963PublicKey,
    ): RegistrationSequenceOutcome {
        var completedAttempts = 0
        while (true) {
            if (!attempts.isCurrent(lease)) {
                return RegistrationSequenceOutcome.AttemptUnavailable
            }
            if (lease.pairingQr.expiresAtEpochSeconds <= configuration.clock.nowEpochSeconds()) {
                return RegistrationSequenceOutcome.Expired
            }
            completedAttempts += 1
            val outcome =
                executeWhenActive(lease) {
                    executeBeforePairingExpiry(lease) {
                        protocolClient.register(
                            pairingQr = lease.pairingQr,
                            selectedProtocolVersion = selectedProtocolVersion,
                            idempotencyKey = idempotencyKey,
                            deviceLabel = deviceLabel,
                            platform = platform,
                            publicKey = publicKey,
                        )
                    }
                }
            if (outcome is LifecycleOperationOutcome.Backgrounded) {
                return RegistrationSequenceOutcome.AttemptUnavailable
            }
            if (outcome is LifecycleOperationOutcome.Suspended) {
                if (awaitActiveOrUnavailable(lease) == LifecycleGate.Unavailable) {
                    return RegistrationSequenceOutcome.AttemptUnavailable
                }
                completedAttempts = 0
                continue
            }
            outcome as LifecycleOperationOutcome.Completed
            val registration = outcome.value ?: return RegistrationSequenceOutcome.Expired
            if (registration is PairingRegistrationRemoteOutcome.Registered) {
                return RegistrationSequenceOutcome.Registered(registration.deviceSession)
            }
            val retryFailure =
                registration.toRetryFailure()
                    ?: return RegistrationSequenceOutcome.Terminal(registration)
            if (!gateAttempt(lease)) {
                return RegistrationSequenceOutcome.AttemptUnavailable
            }
            val decision =
                retryPolicy.decide(
                    replayPolicy = RequestReplayPolicy.IDEMPOTENT_WRITE,
                    completedAttempts = completedAttempts,
                    failure = retryFailure,
                    isActiveForeground = true,
                    isCredentialAvailable = attempts.isCurrent(lease),
                )
            when (decision) {
                is RetryDecision.RetryAfter -> {
                    when (
                        waitForRetryWhileActive(lease, decision.delayMillis)
                    ) {
                        RetryWaitOutcome.Ready -> {
                            // Keep the current retry budget in the same foreground epoch.
                        }

                        RetryWaitOutcome.ForegroundResumed -> {
                            completedAttempts = 0
                        }

                        RetryWaitOutcome.Backgrounded -> {
                            return RegistrationSequenceOutcome.AttemptUnavailable
                        }
                    }
                }

                is RetryDecision.Stop -> {
                    return RegistrationSequenceOutcome.Terminal(registration)
                }
            }
        }
    }

    private suspend fun <T> executeWhenActive(
        lease: PendingPairingLease,
        operation: suspend () -> T,
    ): LifecycleOperationOutcome<T> {
        if (awaitActiveOrUnavailable(lease) == LifecycleGate.Unavailable) {
            return LifecycleOperationOutcome.Backgrounded
        }
        return coroutineScope {
            val visibilityChange =
                async(start = CoroutineStart.UNDISPATCHED) {
                    configuration.applicationVisibility.state.first { candidate ->
                        candidate != ApplicationVisibilityState.ACTIVE_FOREGROUND
                    }
                }
            val ownershipLoss =
                async(start = CoroutineStart.UNDISPATCHED) {
                    attempts.awaitLoss(lease)
                }
            val inFlight = async(start = CoroutineStart.UNDISPATCHED) { operation() }
            select {
                inFlight.onAwait { value ->
                    visibilityChange.cancelAndJoin()
                    ownershipLoss.cancelAndJoin()
                    if (isActiveForeground() && attempts.isCurrent(lease)) {
                        LifecycleOperationOutcome.Completed(value)
                    } else {
                        if (!attempts.isCurrent(lease)) {
                            LifecycleOperationOutcome.Backgrounded
                        } else {
                            when (configuration.applicationVisibility.state.value) {
                                ApplicationVisibilityState.INACTIVE -> {
                                    LifecycleOperationOutcome.Suspended
                                }

                                ApplicationVisibilityState.BACKGROUND_OR_LOCKED -> {
                                    LifecycleOperationOutcome.Backgrounded
                                }

                                ApplicationVisibilityState.ACTIVE_FOREGROUND -> {
                                    LifecycleOperationOutcome.Completed(value)
                                }
                            }
                        }
                    }
                }
                visibilityChange.onAwait { next ->
                    inFlight.cancelAndJoin()
                    ownershipLoss.cancelAndJoin()
                    when (next) {
                        ApplicationVisibilityState.INACTIVE -> {
                            LifecycleOperationOutcome.Suspended
                        }

                        ApplicationVisibilityState.BACKGROUND_OR_LOCKED -> {
                            LifecycleOperationOutcome.Backgrounded
                        }

                        ApplicationVisibilityState.ACTIVE_FOREGROUND -> {
                            error("Visibility watcher accepted active foreground")
                        }
                    }
                }
                ownershipLoss.onAwait {
                    inFlight.cancelAndJoin()
                    visibilityChange.cancelAndJoin()
                    LifecycleOperationOutcome.Backgrounded
                }
            }
        }
    }

    private suspend fun <T> executeBeforePairingExpiry(
        lease: PendingPairingLease,
        operation: suspend () -> T,
    ): T? {
        val remainingSeconds =
            lease.pairingQr.expiresAtEpochSeconds -
                configuration.clock.nowEpochSeconds()
        if (remainingSeconds <= 0L) return null
        val remainingMillis =
            if (remainingSeconds > Long.MAX_VALUE / MILLIS_PER_SECOND) {
                Long.MAX_VALUE
            } else {
                remainingSeconds * MILLIS_PER_SECOND
            }
        return withContext(Dispatchers.Default) {
            withTimeoutOrNull(remainingMillis) { operation() }
        }
    }

    private suspend fun awaitActiveOrUnavailable(lease: PendingPairingLease): LifecycleGate {
        currentCoroutineContext().ensureActive()
        if (!attempts.isCurrent(lease)) return LifecycleGate.Unavailable
        return when (configuration.applicationVisibility.state.value) {
            ApplicationVisibilityState.ACTIVE_FOREGROUND -> {
                LifecycleGate.Active
            }

            ApplicationVisibilityState.BACKGROUND_OR_LOCKED -> {
                LifecycleGate.Unavailable
            }

            ApplicationVisibilityState.INACTIVE -> {
                coroutineScope {
                    val visibility =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            configuration.applicationVisibility.state.first { candidate ->
                                candidate != ApplicationVisibilityState.INACTIVE
                            }
                        }
                    val ownershipLoss =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            attempts.awaitLoss(lease)
                        }
                    select {
                        visibility.onAwait { next ->
                            ownershipLoss.cancelAndJoin()
                            if (next == ApplicationVisibilityState.ACTIVE_FOREGROUND &&
                                attempts.isCurrent(lease)
                            ) {
                                LifecycleGate.Active
                            } else {
                                LifecycleGate.Unavailable
                            }
                        }
                        ownershipLoss.onAwait {
                            visibility.cancelAndJoin()
                            LifecycleGate.Unavailable
                        }
                    }
                }
            }
        }
    }

    private suspend fun waitForRetryWhileActive(
        lease: PendingPairingLease,
        delayMillis: Long,
    ): RetryWaitOutcome {
        while (true) {
            if (awaitActiveOrUnavailable(lease) == LifecycleGate.Unavailable) {
                return RetryWaitOutcome.Backgrounded
            }
            val outcome = executeWhenActive(lease) { retryDelay.wait(delayMillis) }
            when (outcome) {
                is LifecycleOperationOutcome.Completed -> {
                    return RetryWaitOutcome.Ready
                }

                LifecycleOperationOutcome.Suspended -> {
                    return if (awaitActiveOrUnavailable(lease) == LifecycleGate.Active) {
                        RetryWaitOutcome.ForegroundResumed
                    } else {
                        RetryWaitOutcome.Backgrounded
                    }
                }

                LifecycleOperationOutcome.Backgrounded -> {
                    return RetryWaitOutcome.Backgrounded
                }
            }
        }
    }

    private suspend fun gateAttempt(lease: PendingPairingLease): Boolean =
        awaitActiveOrUnavailable(lease) == LifecycleGate.Active

    private fun isActiveForeground(): Boolean =
        configuration.applicationVisibility.state.value ==
            ApplicationVisibilityState.ACTIVE_FOREGROUND

    public companion object {
        internal fun create(
            attempts: PairingAttemptPort<PendingPairingLease, PairingCleanupHandle>,
            configuration: PairingConnectionConfiguration,
            authorizationHandoffLease: CompanionAuthorizationHandoffLease? = null,
        ): PairingConnection =
            PairingConnection(
                attempts = attempts,
                configuration = configuration,
                protocolClient = createPlatformPairingRegistrationRemoteGateway(),
                retryPolicy = RetryPolicy(),
                retryDelay = DefaultPairingRetryDelay,
                authorizationHandoffLease = authorizationHandoffLease,
            )
    }
}

internal fun interface PairingRetryDelay {
    suspend fun wait(delayMillis: Long): Unit
}

private object DefaultPairingRetryDelay : PairingRetryDelay {
    override suspend fun wait(delayMillis: Long): Unit = delay(delayMillis)
}

private sealed interface LifecycleOperationOutcome<out T> {
    data class Completed<T>(
        val value: T,
    ) : LifecycleOperationOutcome<T>

    data object Suspended : LifecycleOperationOutcome<Nothing>

    data object Backgrounded : LifecycleOperationOutcome<Nothing>
}

private enum class LifecycleGate {
    Active,
    Unavailable,
}

private enum class RetryWaitOutcome {
    Ready,
    ForegroundResumed,
    Backgrounded,
}

private sealed interface DiscoverySequenceOutcome {
    data class Compatible(
        val selectedProtocolVersion: Int,
    ) : DiscoverySequenceOutcome

    data class Terminal(
        val outcome: PairingDiscoveryOutcome,
    ) : DiscoverySequenceOutcome

    data object Expired : DiscoverySequenceOutcome

    data object AttemptUnavailable : DiscoverySequenceOutcome
}

private sealed interface RegistrationSequenceOutcome {
    data class Registered(
        val deviceSession: PairingRegisteredSession,
    ) : RegistrationSequenceOutcome

    data class Terminal(
        val outcome: PairingRegistrationRemoteOutcome,
    ) : RegistrationSequenceOutcome

    data object Expired : RegistrationSequenceOutcome

    data object AttemptUnavailable : RegistrationSequenceOutcome
}

private fun PairingDiscoveryOutcome.toRetryFailure(): RetryFailure? =
    when (this) {
        is PairingDiscoveryOutcome.Compatible,
        PairingDiscoveryOutcome.Incompatible,
        -> {
            null
        }

        is PairingDiscoveryOutcome.Rejected -> {
            when (val rejected = failure) {
                is CompanionFailure.Known -> {
                    RetryFailure.HttpResponse(
                        statusCode = rejected.statusCode,
                        typedError = rejected.toRetryDisposition(),
                        retryAfterSeconds = retryAfterSeconds,
                    )
                }

                CompanionFailure.UnexpectedEngineError -> {
                    RetryFailure.ContractViolation
                }
            }
        }

        is PairingDiscoveryOutcome.ContractFailure -> {
            if (statusCode in OPTIONAL_ENVELOPE_GATEWAY_STATUSES) {
                RetryFailure.HttpResponse(statusCode)
            } else {
                RetryFailure.ContractViolation
            }
        }

        PairingDiscoveryOutcome.PreResponseTransportFailure -> {
            RetryFailure.PreResponseTransport
        }

        PairingDiscoveryOutcome.CompleteResponseTransportFailure -> {
            RetryFailure.CompleteResponseTransport
        }
    }

private fun PairingRegistrationRemoteOutcome.toRetryFailure(): RetryFailure? =
    when (this) {
        is PairingRegistrationRemoteOutcome.Registered -> {
            null
        }

        is PairingRegistrationRemoteOutcome.Rejected -> {
            RetryFailure.HttpResponse(
                statusCode = statusCode,
                typedError = failure.toRetryDisposition(),
                retryAfterSeconds = retryAfterSeconds,
            )
        }

        is PairingRegistrationRemoteOutcome.ContractFailure -> {
            if (statusCode in OPTIONAL_ENVELOPE_GATEWAY_STATUSES) {
                RetryFailure.HttpResponse(statusCode)
            } else {
                RetryFailure.ContractViolation
            }
        }

        PairingRegistrationRemoteOutcome.PreResponseTransportFailure -> {
            RetryFailure.PreResponseTransport
        }

        PairingRegistrationRemoteOutcome.CompleteResponseTransportFailure -> {
            RetryFailure.CompleteResponseTransport
        }
    }

private fun CompanionFailure.toRetryDisposition(): TypedErrorRetryDisposition =
    when (this) {
        is CompanionFailure.Known -> {
            when {
                retryable -> TypedErrorRetryDisposition.RETRYABLE
                action != ProtocolErrorAction.None -> TypedErrorRetryDisposition.USER_ACTION_REQUIRED
                else -> TypedErrorRetryDisposition.NOT_RETRYABLE
            }
        }

        CompanionFailure.UnexpectedEngineError -> {
            TypedErrorRetryDisposition.UNEXPECTED_ENGINE_ERROR
        }
    }

private fun CompanionFailure.toPublicPairingOutcome(): PairingConnectionOutcome =
    when (this) {
        is CompanionFailure.Known -> {
            when (code) {
                HttpErrorCode.IncompatibleProtocol -> PairingConnectionOutcome.INCOMPATIBLE
                HttpErrorCode.PairingUnavailable -> PairingConnectionOutcome.PAIRING_UNAVAILABLE
                HttpErrorCode.RateLimited -> PairingConnectionOutcome.RATE_LIMITED
                HttpErrorCode.SnapshotUnavailable -> PairingConnectionOutcome.NETWORK_UNAVAILABLE
                else -> PairingConnectionOutcome.UNEXPECTED_ENGINE_RESPONSE
            }
        }

        CompanionFailure.UnexpectedEngineError -> {
            PairingConnectionOutcome.UNEXPECTED_ENGINE_RESPONSE
        }
    }

private fun PairingDiscoveryOutcome.toPublicPairingOutcome(): PairingConnectionOutcome =
    when (this) {
        is PairingDiscoveryOutcome.Compatible -> {
            PairingConnectionOutcome.UNEXPECTED_FAILURE
        }

        PairingDiscoveryOutcome.Incompatible -> {
            PairingConnectionOutcome.INCOMPATIBLE
        }

        is PairingDiscoveryOutcome.Rejected -> {
            failure.toPublicPairingOutcome()
        }

        is PairingDiscoveryOutcome.ContractFailure -> {
            if (statusCode in OPTIONAL_ENVELOPE_GATEWAY_STATUSES) {
                PairingConnectionOutcome.NETWORK_UNAVAILABLE
            } else {
                PairingConnectionOutcome.UNEXPECTED_ENGINE_RESPONSE
            }
        }

        PairingDiscoveryOutcome.PreResponseTransportFailure,
        PairingDiscoveryOutcome.CompleteResponseTransportFailure,
        -> {
            PairingConnectionOutcome.NETWORK_UNAVAILABLE
        }
    }

private fun PairingRegistrationRemoteOutcome.toPublicPairingOutcome(): PairingConnectionOutcome =
    when (this) {
        is PairingRegistrationRemoteOutcome.Registered -> {
            PairingConnectionOutcome.UNEXPECTED_FAILURE
        }

        is PairingRegistrationRemoteOutcome.Rejected -> {
            failure.toPublicPairingOutcome()
        }

        is PairingRegistrationRemoteOutcome.ContractFailure -> {
            if (statusCode in OPTIONAL_ENVELOPE_GATEWAY_STATUSES) {
                PairingConnectionOutcome.NETWORK_UNAVAILABLE
            } else {
                PairingConnectionOutcome.UNEXPECTED_ENGINE_RESPONSE
            }
        }

        PairingRegistrationRemoteOutcome.PreResponseTransportFailure,
        PairingRegistrationRemoteOutcome.CompleteResponseTransportFailure,
        -> {
            PairingConnectionOutcome.NETWORK_UNAVAILABLE
        }
    }

private fun PairingDevicePlatform.toProtocolPlatform(): CompanionPlatform =
    when (this) {
        PairingDevicePlatform.ANDROID -> CompanionPlatform.Android
        PairingDevicePlatform.IOS -> CompanionPlatform.Ios
    }

private val OPTIONAL_ENVELOPE_GATEWAY_STATUSES: Set<Int> = setOf(408, 502, 504)
private const val MILLIS_PER_SECOND: Long = 1_000L
