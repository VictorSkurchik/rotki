# Android navigation

`:android:navigation` is the Android-only leaf that owns the authenticated root `NavHost`, its four
typed top-level destinations, and their interim placeholder shell. It has no project dependencies.
The application maps authoritative shared state to the narrow, UI-safe
`HomeConnectionBannerState` input before entering this module.

Privacy, Pairing, lock, recovery, incompatible, and revoked decisions remain in `:androidApp`
outside the navigation host. Losing root authority therefore disposes the host and its saved back
stack instead of allowing an authenticated destination to restore through a guard.

This module contains no Koin, Ktor, Room, camera, scanner, security implementation, ViewModel, or
platform composition. It does not create or enter the `RotkiShared` Apple framework. The current
Material 3 rendering is functional interim UI; the complete design system remains deferred to the
approved Claude Design step.
