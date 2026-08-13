# P0.3 SKIE interop spike

This disposable build characterizes the Swift-facing boundary selected for the
native iOS client. It is not a production shared module and it does not contain
application UI or Engine protocol code.

## Pinned toolchain

- Kotlin and Kotlin Gradle plugin: `2.4.10`
- SKIE: `0.10.14`
- kotlinx.coroutines: `1.11.0`
- Gradle wrapper: `9.5.0`
- Java toolchain: the Gradle runtime uses JDK 17 or newer

SKIE analytics, default-argument overload generation, and all preview SwiftUI
and Combine helpers are explicitly disabled. SKIE can be disabled with the
Gradle property `rotki.skie.enabled=false`.

## What the executable proves

The macOS framework is consumed by a real Swift executable, and the same
consumer is type-checked in Swift 5 language mode against both iOS device and
iOS Simulator frameworks. The consumer:

- iterates a typed Kotlin `StateFlow` with `for await`;
- exhaustively switches over all eleven `CompanionState` sealed children;
- exhaustively switches over the ordinary Kotlin enum;
- verifies Swift `Task` cancellation reaches Kotlin;
- verifies Kotlin cancellation reaches the Swift task; and
- verifies cancelling Flow collection terminates normally.

The same Kotlin framework also links for iOS device and Apple-silicon iOS
simulator targets. A separate SKIE-disabled build proves the classic Objective-C
framework remains available as the fallback.

## Run

```shell
./verify.sh
```

The verifier always runs clean SKIE-enabled and clean SKIE-disabled builds. It
also rejects a disabled framework that retained generated Swift headers or
modules, preventing stale enabled output from producing a false fallback pass.

The local run used Xcode 26.6. Kotlin 2.4.10 documents compatibility through
Xcode 26.4, so CI must select Xcode 26.4 and the local 26.6 result is recorded as
useful evidence outside the documented compatibility row.

The consumer is intentionally compiled in Swift 5 language mode. A diagnostic
Swift 6 type-check of the iOS Simulator framework under local Xcode 26.6 failed
while importing SKIE-generated API notes, so this spike makes no Swift 6 source
compatibility claim. Re-evaluate that mode only against a supported future
Kotlin/SKIE/Xcode matrix.

## Limits and supersession

SKIE Flow failures must be represented as typed state because a custom Kotlin
exception escaping a Flow can terminate the Swift process. SKIE types and
generated helpers must remain inside one Swift adapter; native Security and
CryptoKit types must never enter the shared facade.

M2.1 and I5 will move the accepted contract and characterization tests into the
single production `mobile` Gradle build. This nested build must then be removed
in the same change that proves equivalent JVM and Apple coverage.
