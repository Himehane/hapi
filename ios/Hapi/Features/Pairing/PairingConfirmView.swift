import HapiClient
import SwiftUI

/// Final confirmation step shared by every pairing path (deep link, QR scan,
/// manual entry): shows what is about to be paired, runs `AppModel.pair`
/// with progress, and renders ``PairingErrorView`` states on failure.
///
/// On success `AppModel` clears all pairing presentation state and flips to
/// `.paired`, which dismisses this view's container — nothing to do here.
struct PairingConfirmView: View {
    let pending: PendingPairing

    @Environment(AppModel.self) private var model
    @State private var isPairing = false
    @State private var failure: PairingFailure?

    private var isAddingAnotherHub: Bool {
        if case .paired = model.state { return true }
        return false
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Hub") {
                    Text(pending.hubUrl)
                        .multilineTextAlignment(.trailing)
                        .textSelection(.enabled)
                }
                LabeledContent("Access token") {
                    Text(HubDisplay.maskedToken(pending.accessToken))
                        .monospaced()
                }
            } footer: {
                Text(isAddingAnotherHub
                    ? "This hub will be added to your paired hubs and become active."
                    : "The app checks the hub is reachable, then exchanges the token for a session.")
            }

            if let failure {
                Section {
                    PairingErrorView(failure: failure)
                }
            }

            Section {
                Button {
                    pairNow()
                } label: {
                    if isPairing {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("Pairing…")
                        }
                        .frame(maxWidth: .infinity)
                    } else {
                        Text(failure == nil ? "Pair" : "Try Again")
                            .frame(maxWidth: .infinity)
                    }
                }
                .disabled(isPairing)
            }
        }
        .navigationTitle(isAddingAnotherHub ? "Add Hub" : "Pair")
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled(isPairing)
    }

    private func pairNow() {
        guard !isPairing else { return }
        isPairing = true
        failure = nil
        Task {
            do {
                try await model.pair(hubUrl: pending.hubUrl, accessToken: pending.accessToken)
                // Success: AppModel flipped to .paired and closed the pairing
                // surfaces; this view is on its way out.
            } catch let pairingFailure as PairingFailure {
                failure = pairingFailure
            } catch {
                failure = .unreachable
            }
            isPairing = false
        }
    }
}

/// The pairing screen's error states, one presentation per
/// ``PairingFailure`` case.
struct PairingErrorView: View {
    let failure: PairingFailure

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: icon)
                .foregroundStyle(.red)
        }
    }

    private var icon: String {
        switch failure {
        case .invalidHubURL: "questionmark.circle"
        case .unreachable: "wifi.exclamationmark"
        case .protocolMismatch: "arrow.triangle.2.circlepath"
        case .invalidAccessToken: "key"
        case .hubError: "exclamationmark.triangle"
        case .storageFailure: "externaldrive.badge.xmark"
        }
    }

    private var title: String {
        switch failure {
        case .invalidHubURL: "Invalid hub URL"
        case .unreachable: "Hub unreachable"
        case .protocolMismatch: "Version mismatch"
        case .invalidAccessToken: "Token rejected"
        case .hubError: "Hub error"
        case .storageFailure: "Could not save"
        }
    }

    private var message: String {
        switch failure {
        case .invalidHubURL:
            "Enter the hub's full address, e.g. http://192.168.1.20:3006."
        case .unreachable:
            "No HAPI hub answered at this address. Check that the hub is "
                + "running and that this device can reach it (same network, "
                + "or the relay tunnel is up)."
        case .protocolMismatch(let hubVersion, let supportedVersion):
            "The hub speaks protocol v\(hubVersion), this app supports "
                + "v\(supportedVersion). Update the older side, then try again."
        case .invalidAccessToken:
            "The hub rejected this access token — it may have been rotated. "
                + "Get a fresh pairing code from the hub and try again."
        case .hubError(let status):
            "The hub answered unexpectedly (HTTP \(status)). Try again in a "
                + "moment."
        case .storageFailure:
            "Pairing succeeded but the credentials could not be stored on "
                + "this device. Try again."
        }
    }
}
