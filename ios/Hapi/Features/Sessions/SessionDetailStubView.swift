import SwiftUI

// TODO(M2f): push ChatView — this stub only proves the list → detail
// navigation and shows which session was opened.
struct SessionDetailStubView: View {
    let sessionId: String

    var body: some View {
        ContentUnavailableView {
            Label("Session \(sessionId.prefix(8))", systemImage: "bubble.left.and.bubble.right")
        } description: {
            Text("Chat lands in M2f.\n\(sessionId)")
                .font(.footnote)
                .monospaced()
        }
        .navigationTitle("Session")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack {
        SessionDetailStubView(sessionId: "0b9f5a1c-4c63-4a5e-8f6f-demo")
    }
}
