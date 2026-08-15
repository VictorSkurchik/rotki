package org.rotki.mobile.auth

internal actual fun createPlatformCompanionAuthorizationInstaller(): CompanionAuthorizationInstaller? =
    DefaultCompanionAuthorizationInstaller()
