import HapiClient
import HapiUI
import SwiftUI

/// Switches the app root on pairing state and hosts the presentation that
/// must survive that switch: the deep-link pairing confirm sheet, the
/// "already paired" notice, the HapiUI palette matching the system
/// appearance, and the app-wide markdown link handler.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        @Bindable var model = model
        Group {
            switch model.state {
            case .unpaired:
                PairingFlowView()
            case .paired:
                if let session = model.session {
                    // `.id` resets navigation + list state on hub switch —
                    // each hub gets a fresh HomeView over its own stores.
                    HomeView(session: session)
                        .id(session.hubUrl)
                } else {
                    // Defensive: .paired always carries a session; fall back
                    // to pairing rather than a dead screen.
                    PairingFlowView()
                }
            }
        }
        .sheet(item: $model.pendingPairing) { pending in
            NavigationStack {
                PairingConfirmView(pending: pending)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") {
                                model.pendingPairing = nil
                            }
                        }
                    }
            }
        }
        .alert(
            "Hub already paired",
            isPresented: Binding(
                get: { model.infoNotice != nil },
                set: { presented in
                    if !presented {
                        model.infoNotice = nil
                    }
                }
            )
        ) {
            Button("OK") {
                model.infoNotice = nil
            }
        } message: {
            Text(model.infoNotice ?? "")
        }
        .hapiTheme(HapiTheme.resolve(for: colorScheme))
        .handlesHapiLinks()
    }
}

#Preview {
    RootView()
        .environment(AppModel(
            registry: HubRegistry(defaults: UserDefaults(suiteName: "preview") ?? .standard),
            credentialStore: InMemoryCredentialStore()
        ))
}
