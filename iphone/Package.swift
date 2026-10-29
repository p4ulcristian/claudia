// swift-tools-version: 6.2

import PackageDescription

// Stamp the SDK we really build against (iOS 26.5) into LC_BUILD_VERSION; the Linux linker
// otherwise writes the deployment target, and App Store Connect rejects anything below the
// iOS 26 SDK. Keep in step with SDK_VERSION in tools/testflight.sh. On each binary's own
// target (the app and each extension), so every link gets it once.
let sdkStamp: [LinkerSetting] = [.unsafeFlags(["-Xlinker", "-platform_version", "-Xlinker", "ios",
                                               "-Xlinker", "17.0", "-Xlinker", "26.5"])]

let package = Package(
    name: "Claudia",
    platforms: [
        .iOS(.v17),
    ],
    products: [
        .library(name: "Claudia", targets: ["Claudia"]),
        // The summary Live Activity (xtool.yml `extensions`), packaged as PlugIns/Widget.appex,
        // bundle id <app>.Widget.
        .library(name: "Widget", targets: ["ClaudiaWidget"]),
        // Decrypts the notification text (xtool.yml `extensions`), packaged as
        // PlugIns/NotificationService.appex, bundle id <app>.NotificationService.
        .library(name: "NotificationService", targets: ["ClaudiaNotifications"]),
    ],
    targets: [
        .target(
            name: "Claudia",
            dependencies: ["ClaudiaShared"],
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: sdkStamp
        ),
        .target(
            name: "ClaudiaWidget",
            dependencies: ["ClaudiaShared"],
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: sdkStamp
        ),
        .target(
            name: "ClaudiaNotifications",
            dependencies: ["ClaudiaShared"],
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: sdkStamp
        ),
        // What the app and its extensions both know: the Live Activity's data, the device key.
        .target(
            name: "ClaudiaShared",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
