# Isolate the mobile Gradle build

The monorepo will gain a self-contained `mobile/` build with its own Gradle wrapper, settings, and version catalog, containing the KMP shared code, Android application, and iOS Xcode project integration. Existing Python, pnpm, and Cargo builds remain independent and are not orchestrated by a new root Gradle build, while shared repository commits may still change Engine Protocol and mobile code atomically.
