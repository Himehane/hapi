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
        .library(name: "HapiUI", targets: ["HapiUI"]),
    ],
    dependencies: [
        // GFM parsing for the custom SwiftUI markdown renderer (HapiUI).
        // The org moved from `apple` to `swiftlang`; product name is `Markdown`.
        .package(url: "https://github.com/swiftlang/swift-markdown.git", from: "0.5.0"),
        // JavaScriptCore-backed highlight.js wrapper for code blocks. Kept
        // behind the `SyntaxHighlighting` protocol so it can be swapped out.
        .package(url: "https://github.com/raspu/Highlightr.git", from: "2.2.0"),
    ],
    targets: [
        // Pure protocol layer: wire models, chat pipeline, window logic.
        // Must stay Foundation-only so it can be verified against
        // shared/fixtures/** without any UI or transport concerns.
        .target(name: "HapiProtocol"),
        // Transport layer: APIClient, auth, SSE, stores (lands in M1).
        .target(name: "HapiClient", dependencies: ["HapiProtocol"]),
        // Rendering foundation: markdown renderer + pre-parse transforms,
        // code blocks with syntax highlighting, unified-diff view, theme.
        .target(
            name: "HapiUI",
            dependencies: [
                "HapiProtocol",
                .product(name: "Markdown", package: "swift-markdown"),
                .product(name: "Highlightr", package: "Highlightr"),
            ]
        ),
        .testTarget(name: "HapiProtocolTests", dependencies: ["HapiProtocol"]),
        .testTarget(name: "HapiClientTests", dependencies: ["HapiClient"]),
        .testTarget(name: "HapiUITests", dependencies: ["HapiUI"]),
    ]
)
