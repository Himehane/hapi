// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "HapiKit",
    platforms: [
        // The app targets iOS 17+. macOS is declared so `swift test` can run
        // the package on macOS CI runners (the code is pure Foundation).
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "HapiProtocol", targets: ["HapiProtocol"]),
        .library(name: "HapiClient", targets: ["HapiClient"]),
    ],
    targets: [
        // Pure protocol layer: wire models, chat pipeline, window logic.
        // Must stay Foundation-only so it can be verified against
        // shared/fixtures/** without any UI or transport concerns.
        .target(name: "HapiProtocol"),
        // Transport layer: APIClient, auth, SSE, stores (lands in M1).
        .target(name: "HapiClient", dependencies: ["HapiProtocol"]),
        .testTarget(name: "HapiProtocolTests", dependencies: ["HapiProtocol"]),
        // HapiProtocol is named directly by SSE tests (SyncEvent assertions).
        .testTarget(name: "HapiClientTests", dependencies: ["HapiClient", "HapiProtocol"]),
    ]
)
