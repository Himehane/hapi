import HapiClient
import HapiProtocol
import SwiftUI

struct RootView: View {
    var body: some View {
        NavigationStack {
            ContentUnavailableView {
                Label("HAPI", systemImage: "antenna.radiowaves.left.and.right")
            } description: {
                Text("Native companion scaffold (M0). Pairing and sessions arrive in M1.")
            }
            .navigationTitle("HAPI")
            .safeAreaInset(edge: .bottom) {
                Text("HapiKit \(HapiClientVersion.current) · protocol v\(ProtocolVersion.supported)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, 8)
            }
        }
    }
}

#Preview {
    RootView()
}
