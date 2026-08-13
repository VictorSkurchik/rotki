// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "IOSSecuritySpike",
    platforms: [
        .iOS(.v17),
        .macOS(.v13),
    ],
    products: [
        .library(name: "IOSSecuritySpike", targets: ["IOSSecuritySpike"]),
    ],
    targets: [
        .target(name: "IOSSecuritySpike"),
        .testTarget(
            name: "IOSSecuritySpikeTests",
            dependencies: ["IOSSecuritySpike"]
        ),
    ]
)
