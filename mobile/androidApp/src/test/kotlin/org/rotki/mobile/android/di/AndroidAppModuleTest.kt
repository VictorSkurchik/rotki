package org.rotki.mobile.android.di

import org.junit.Test
import org.koin.android.test.verify.androidVerify

class AndroidAppModuleTest {
    @Test
    fun `application module has a complete dependency graph`() {
        androidAppModule.androidVerify()
    }
}
