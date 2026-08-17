import OSLog
import SwiftUI

@main
struct HapiApp: App {
    private let log = Logger(subsystem: "run.hapi.companion", category: "app")

    var body: some Scene {
        WindowGroup {
            RootView()
                .onOpenURL { url in
                    // M1d wires this to the pairing flow
                    // (hapicompanion://bind?hub=<url>&code=<accessToken>).
                    // For M0 the scheme is registered but intentionally ignored.
                    log.info("Ignoring URL until pairing lands: \(url.absoluteString, privacy: .public)")
                }
        }
    }
}
