package org.rotki.mobile.android

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.rotki.mobile.android.di.androidAppModule

class RotkiCompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RotkiCompanionApplication)
            modules(androidAppModule)
        }
    }
}
