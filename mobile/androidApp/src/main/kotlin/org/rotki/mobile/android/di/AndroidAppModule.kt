package org.rotki.mobile.android.di

import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import org.rotki.mobile.CompanionFacade
import org.rotki.mobile.android.AndroidSecurityComposition
import org.rotki.mobile.android.pairing.AndroidEpochClock
import org.rotki.mobile.android.pairing.PairingConnectionUiState
import org.rotki.mobile.android.pairing.PairingViewModel
import org.rotki.mobile.android.pairing.PendingAuthorizationRetryConnector
import org.rotki.mobile.android.pairing.PendingPairingCleanupConnector
import org.rotki.mobile.android.pairing.PendingPairingConnector
import org.rotki.mobile.core.ports.Clock

internal val androidAppModule =
    module {
        singleOf(::AndroidSecurityComposition)
        single<CompanionFacade> { get<AndroidSecurityComposition>().facade }
        single<Clock> { AndroidEpochClock }
        single<PendingPairingConnector> {
            PendingPairingConnector(
                get<AndroidSecurityComposition>()::connectPendingPairing,
            )
        }
        single<PendingPairingCleanupConnector> {
            PendingPairingCleanupConnector(
                get<AndroidSecurityComposition>()::retryIncompletePairingCleanup,
            )
        }
        single<PendingAuthorizationRetryConnector> {
            PendingAuthorizationRetryConnector(
                get<AndroidSecurityComposition>().authorizationController::onExplicitForegroundRetry,
            )
        }
        single<PairingConnectionUiState> {
            get<AndroidSecurityComposition>().initialPairingConnectionState
        }
        viewModelOf(::PairingViewModel)
    }
