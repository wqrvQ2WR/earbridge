// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "EarBridge",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "EarBridge", path: "Sources/EarBridge")
    ]
)
