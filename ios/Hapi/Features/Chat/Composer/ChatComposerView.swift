import HapiClient
import SwiftUI

/// A pending (M4) attachment chip — the row renders only when non-empty, so
/// the M4 upload flow can light it up without composer surgery.
struct ComposerAttachment: Identifiable, Equatable {
    let filename: String
    let mimeType: String

    var id: String { filename }
}

/// The chat input bar (A-M3a): multiline text field (return = newline, the
/// mobile default), a send button whose long-press offers "Send & steer"
/// while a turn is active (`messageDelivery.ts` — queue is always the
/// default), and an abort button during thinking. Attachments are an M4 seam
/// (chips render when present; no picker yet).
struct ChatComposerView: View {
    let interactor: ChatInteractor
    var attachments: [ComposerAttachment] = []

    private var text: Binding<String> {
        Binding(
            get: { interactor.composerText },
            set: { interactor.setComposerText($0) }
        )
    }

    var body: some View {
        let composer = interactor.composer
        VStack(spacing: 6) {
            if !attachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(attachments) { attachment in
                            Label(attachment.filename, systemImage: "paperclip")
                                .font(.caption)
                                .lineLimit(1)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(
                                    .fill.tertiary,
                                    in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                                )
                        }
                    }
                }
            }
            HStack(alignment: .bottom, spacing: 8) {
                TextField("Message the agent…", text: text, axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(.fill.tertiary, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                if composer.canSteer {
                    abortButton
                }
                sendButton(composer)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.bar)
    }

    // MARK: - Buttons

    private var abortButton: some View {
        Button {
            interactor.abortSession()
        } label: {
            Image(systemName: "stop.fill")
                .font(.subheadline)
                .frame(width: 38, height: 38)
                .background(.red.opacity(0.15), in: Circle())
                .foregroundStyle(.red)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Stop the current turn")
    }

    @ViewBuilder
    private func sendButton(_ composer: ComposerState) -> some View {
        let hasText = !composer.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let enabled = hasText && !composer.isSending
        let label = sendLabel(composer, enabled: enabled)
        if composer.canSteer && enabled {
            // Tap sends (queue); long-press opens the deliberate steer intent.
            Menu {
                Button("Send & steer into current turn") {
                    interactor.sendMessage(steer: true)
                }
            } label: {
                label
            } primaryAction: {
                interactor.sendMessage()
            }
            .accessibilityLabel("Send — long-press to steer")
        } else {
            Button {
                interactor.sendMessage()
            } label: {
                label
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .accessibilityLabel("Send")
        }
    }

    private func sendLabel(_ composer: ComposerState, enabled: Bool) -> some View {
        Group {
            if composer.isSending {
                ProgressView()
                    .controlSize(.small)
            } else {
                Image(systemName: "arrow.up")
                    .font(.subheadline.weight(.semibold))
            }
        }
        .frame(width: 38, height: 38)
        .background(
            enabled ? AnyShapeStyle(.tint) : AnyShapeStyle(.fill.tertiary),
            in: Circle()
        )
        .foregroundStyle(enabled ? AnyShapeStyle(.white) : AnyShapeStyle(.secondary))
    }
}
