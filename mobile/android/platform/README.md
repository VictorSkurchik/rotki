# Android platform

`:android:platform` owns two narrow Android seams. `AndroidCompanionLifecycle` coordinates
application-visibility and fail-closed background callbacks over `:core:common`. Public Pairing
storage factories return only the `PairingRecordStore` and `PairingCleanupJournal` ports from
`:core:security-api`; the atomic file adapters and strict binary codec remain implementation details.

Both durable files keep their established names and bytes under `Context.noBackupFilesDir` and use
`AtomicFile` rollback. Every adapter type has one process-wide lock, so independently created factory
instances still serialize access to the same canonical file. The application retains one pair for
its process lifetime and preserves startup reconciliation and cleanup ordering.

The module does not retain an `Activity` or `Application` and does not own Koin composition,
receivers, `FLAG_SECURE`, authoritative root state, UI, navigation, biometric policy, cryptographic
keys, or permissions. Those responsibilities remain in `:androidApp`, including the identity-checked
Activity attach/detach broker. Its only production project dependencies are `:core:common`,
`:core:security-api`, and the protocol value types required by the private codec; it has no edge to
`:shared`.

The root module guard permits only `kotlinx-coroutines-core` as a direct production external
dependency. Its broad AndroidX and kotlinx bans are bypassed only for that module and for the exact
AndroidX Test/JUnit artifacts declared from test buckets.

Storage instrumentation runs on the module-owned API 28 and API 36 Gradle-managed devices:

```bash
./gradlew :android:platform:pixel2Api28DebugAndroidTest
./gradlew :android:platform:pixel8Api36DebugAndroidTest
```

This Android-only module is not exported through the `RotkiShared` Apple framework.
