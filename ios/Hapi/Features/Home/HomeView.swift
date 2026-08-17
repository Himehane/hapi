import HapiClient
import SwiftUI

/// Post-pairing home: the session list for the active hub, with the hub
/// switcher (switch / add / sign out) and the live global-SSE connection dot
/// in the toolbar. Tapping a row pushes the M2f chat placeholder.
struct HomeView: View {
    let session: HubSession

    @Environment(AppModel.self) private var model
    @State private var confirmSignOut = false
    @State private var path: [String] = []

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                if let failedHub = model.authFailureNotice {
                    authFailureBanner(failedHub: failedHub)
                }
                SessionListView(session: session) { sessionId in
                    path.append(sessionId)
                }
            }
            .navigationDestination(for: String.self) { sessionId in
                SessionDetailStubView(sessionId: sessionId)
            }
            .navigationTitle(HubDisplay.host(session.hubUrl))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    connectionIndicator
                }
                ToolbarItem(placement: .topBarTrailing) {
                    hubMenu
                }
            }
            .confirmationDialog(
                "Sign out of \(HubDisplay.host(session.hubUrl))?",
                isPresented: $confirmSignOut,
                titleVisibility: .visible
            ) {
                Button("Sign Out", role: .destructive) {
                    model.signOut(hub: session.hubUrl)
                }
            } message: {
                Text("Removes the stored access token for this hub. "
                    + "Pair again to reconnect.")
            }
        }
        .sheet(isPresented: $model.showAddHub) {
            PairingFlowView(context: .addHub)
        }
    }

    // MARK: - Connection state

    private var connectionIndicator: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(connectionColor)
                .frame(width: 8, height: 8)
            Text(connectionLabel)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Connection: \(connectionLabel)")
    }

    private var connectionColor: Color {
        switch session.connectionState {
        case .connected: .green
        case .connecting, .backoff: .orange
        case .idle, .suspended: .gray
        }
    }

    private var connectionLabel: String {
        switch session.connectionState {
        case .connected: "Live"
        case .connecting: "Connecting…"
        case .backoff: "Reconnecting…"
        case .suspended: "Paused"
        case .idle: "Offline"
        }
    }

    // MARK: - Hub switcher

    private var hubMenu: some View {
        Menu {
            Section("Hubs") {
                ForEach(model.hubs, id: \.self) { hub in
                    Button {
                        model.switchHub(to: hub)
                    } label: {
                        if hub == session.hubUrl {
                            Label(HubDisplay.host(hub), systemImage: "checkmark")
                        } else {
                            Text(HubDisplay.host(hub))
                        }
                    }
                }
            }
            Button {
                model.showAddHub = true
            } label: {
                Label("Add Hub…", systemImage: "plus")
            }
            Button(role: .destructive) {
                confirmSignOut = true
            } label: {
                Label("Sign Out…", systemImage: "rectangle.portrait.and.arrow.right")
            }
        } label: {
            Label("Hubs", systemImage: "server.rack")
        }
    }

    private func authFailureBanner(failedHub: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text("\(HubDisplay.host(failedHub)) rejected its stored "
                + "credentials and was signed out. Pair it again from the hub "
                + "menu.")
                .font(.footnote)
            Spacer(minLength: 0)
            Button {
                model.authFailureNotice = nil
            } label: {
                Image(systemName: "xmark")
                    .font(.footnote.bold())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}
