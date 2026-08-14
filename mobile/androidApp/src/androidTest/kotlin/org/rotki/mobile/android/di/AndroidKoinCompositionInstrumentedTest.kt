package org.rotki.mobile.android.di

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.rotki.mobile.android.AndroidSecurityComposition
import org.rotki.mobile.android.MainActivity
import org.rotki.mobile.android.RotkiCompanionApplication

@RunWith(AndroidJUnit4::class)
class AndroidKoinCompositionInstrumentedTest {
    @Test
    fun processGraphIsSingletonAndMainActivityUsesIt() {
        val application =
            ApplicationProvider.getApplicationContext<RotkiCompanionApplication>()
        val koin = GlobalContext.get()
        val firstComposition = koin.get<AndroidSecurityComposition>()

        assertSame(application, koin.get<Context>())
        assertSame(firstComposition, koin.get<AndroidSecurityComposition>())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertSame(firstComposition, koin.get<AndroidSecurityComposition>())
            }
        }
    }
}
