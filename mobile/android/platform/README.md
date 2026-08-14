# Android platform

`:android:platform` starts as the narrow Android lifecycle callback boundary. It exposes
`AndroidCompanionLifecycle` and its factory over the platform-neutral
`ApplicationVisibilityController` from `:core:common`; that is its only production project edge.
The application supplies process-safe callbacks for locking authority, cancelling authentication,
and discarding plaintext.

The module does not retain an `Activity` or `Application` and does not own Koin composition,
receivers, `FLAG_SECURE`, authoritative root state, UI, navigation, biometric policy, cryptography,
permissions, or storage. Those responsibilities remain in `:androidApp`, including the
identity-checked Activity attach/detach broker. The next planned platform extraction is the durable
Pairing storage seam, without widening its concrete file or codec implementations.

This Android-only module is not exported through the `RotkiShared` Apple framework.
