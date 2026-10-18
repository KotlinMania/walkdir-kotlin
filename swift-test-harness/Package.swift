// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "SwiftTestHarness",
    platforms: [
        .macOS(.v15)
    ],
    dependencies: [
        .package(name: "Walkdir", path: "../build/SPMPackage/macosArm64/Debug")
    ],
    targets: [
        .executableTarget(
            name: "SwiftTestHarnessTests",
            dependencies: [
                .product(name: "WalkdirLibrary", package: "Walkdir")
            ],
            path: "Tests/SwiftTestHarnessTests",
            linkerSettings: [
                .unsafeFlags([
                    "-L", "../build/swift-test",
                    "-lWalkdir",
                ]),
            ]
        ),
    ]
)