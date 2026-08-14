# Android Pairing feature

`:android:feature:pairing` is an Android-only leaf whose first physical slice owns the native QR
scanner. Its narrow public API is the scanner Composable, its remembered controller, and a coarse
failure enum. CameraX, ML Kit, frame analysis, delivery latching, and lifecycle-bound camera-source
details remain private implementation.

Each emitted payload is one-shot Pairing authority. The host must submit it immediately and must
not log, persist, or retain it beyond that submission attempt.

The module has no project dependencies and does not know shared Pairing state, transport, security,
Koin, navigation, or the application root guard. `:androidApp` retains the `CAMERA` manifest and
runtime-permission flow, maps scanner callbacks to ViewModel actions, and owns settings recovery.
The CameraX source retains only the application Context. Its `PreviewView` and `LifecycleOwner` are
composition-scoped and released on disposal together with the decoder and analysis executor, so no
process-retained component captures an Activity.

The Pairing Screen, ViewModel, presentation adapter, and focused Koin module remain in
`:androidApp` until their shared migration seams are ready. The complete design system also remains
deferred to the approved Claude Design step.
