@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package org.rotki.mobile.feature.authorization.application

import org.rotki.mobile.core.protocol.AccessSessionAuthorizationTarget
import kotlin.native.HiddenFromObjC

/** Opaque, Ktor-free request command understood only by its bound data executor. */
@HiddenFromObjC
public interface AuthorizationRequest<out R : Any>

/**
 * Short-lived write-only credential capability available only to the bound request executor.
 *
 * The executor must apply it exactly at dispatch, must not retain or return this capability, and
 * must never place bearer material in results, diagnostics, logs, or exception text.
 */
@HiddenFromObjC
public interface AuthorizationRequestCredential {
    /** Returns false after the enclosing request execution has ended or lost authority. */
    public suspend fun applyTo(target: AccessSessionAuthorizationTarget): Boolean
}

/**
 * Trusted data-owned executor fixed when the process coordinator is constructed.
 *
 * Execution and finalizers must not re-enter [AuthorizationProcessControl] or recursively invoke
 * request authority. Lifecycle/error feedback is returned normally or queued only after this call
 * has fully unwound. Credential application and dispatch must not have a suspension boundary.
 */
@HiddenFromObjC
public interface AuthorizationRequestExecutor {
    public suspend fun <R : Any> execute(
        request: AuthorizationRequest<R>,
        credential: AuthorizationRequestCredential,
    ): R
}

/** Secret-free result of attempting one scoped authenticated operation. */
@HiddenFromObjC
public sealed interface AuthorizationAuthorityUseOutcome<out T : Any> {
    @HiddenFromObjC
    public data class Executed<T : Any>(
        public val value: T,
    ) : AuthorizationAuthorityUseOutcome<T> {
        public override fun toString(): String = "Executed(redacted)"
    }

    @HiddenFromObjC
    public data object Unavailable : AuthorizationAuthorityUseOutcome<Nothing>

    @HiddenFromObjC
    public data object OutsideActiveForeground : AuthorizationAuthorityUseOutcome<Nothing>

    @HiddenFromObjC
    public data object AuthorityLost : AuthorizationAuthorityUseOutcome<Nothing>

    @HiddenFromObjC
    public data object Closed : AuthorizationAuthorityUseOutcome<Nothing>
}

/** Why one coordinator-owned authorization exchange was created. */
@HiddenFromObjC
public enum class AuthorizationExchangeKind {
    ACQUISITION,
    RENEWAL,
}

/** Secret-free owner events. Joined callers never create duplicate events. */
@HiddenFromObjC
public sealed interface AuthorizationCoordinatorEvent {
    public val exchangeId: Long?

    @HiddenFromObjC
    public data class ExchangeStarted(
        override val exchangeId: Long,
        public val kind: AuthorizationExchangeKind,
    ) : AuthorizationCoordinatorEvent {
        public override fun toString(): String = "ExchangeStarted(redacted)"
    }

    @HiddenFromObjC
    public data class ExchangeCompleted(
        override val exchangeId: Long,
        public val kind: AuthorizationExchangeKind,
        public val outcome: AuthorizationCoordinatorOutcome,
    ) : AuthorizationCoordinatorEvent {
        public override fun toString(): String = "ExchangeCompleted(redacted)"
    }

    @HiddenFromObjC
    public data class AccessSessionExpired(
        public val sessionRevision: Long,
    ) : AuthorizationCoordinatorEvent {
        override val exchangeId: Long? = null

        public override fun toString(): String = "AccessSessionExpired(redacted)"
    }
}

/**
 * Consumer-owned event boundary used by the shared facade adapter.
 *
 * Completion is acknowledged before joined callers return. Implementations must not re-enter the
 * emitting authority; they may map state and invoke independent committed cleanup only.
 */
@HiddenFromObjC
public fun interface AuthorizationCoordinatorEventSink {
    public suspend fun emit(event: AuthorizationCoordinatorEvent): Unit
}

/** Least-authority request capability passed to later data features. */
@HiddenFromObjC
public interface AuthorizationRequestAuthority {
    public suspend fun <R : Any> execute(request: AuthorizationRequest<R>): AuthorizationAuthorityUseOutcome<R>
}

/** Completion handle returned after process authority has already been atomically fenced. */
@HiddenFromObjC
public fun interface AuthorizationInvalidation {
    /** Waits only for cancelled exchange/request cleanup; authority is unusable before this call. */
    public suspend fun awaitCompletion(): Unit
}

/** Ktor-free process control consumed only by the facade/composition boundary. */
@HiddenFromObjC
public interface AuthorizationProcessControl {
    public suspend fun authorize(): AuthorizationCoordinatorOutcome

    public suspend fun beginClearAccessSession(): AuthorizationInvalidation

    public suspend fun clearAccessSession(): Unit

    public suspend fun beginSelectProtocolVersion(selectedProtocolVersion: Int): AuthorizationInvalidation

    public suspend fun selectProtocolVersion(selectedProtocolVersion: Int): Unit

    public suspend fun hasActiveAccessSession(): Boolean

    public suspend fun beginClose(): AuthorizationInvalidation

    public suspend fun close(): Unit
}
