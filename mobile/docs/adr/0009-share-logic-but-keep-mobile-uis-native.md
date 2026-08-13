# Share logic but keep mobile UIs native

The Android Client will use Jetpack Compose and the iOS Client will use SwiftUI, while Kotlin Multiplatform is limited to non-UI code. This favors native interaction, accessibility, platform tooling, and direct ecosystem integration over maximum code sharing; mobile presentation code is intentionally duplicated.
