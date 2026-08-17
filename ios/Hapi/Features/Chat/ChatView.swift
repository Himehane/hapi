import HapiClient
import HapiProtocol
import HapiUI
import SwiftUI

/// Read-only chat screen (M2f): `ScrollView + LazyVStack` over the reduced
/// `VisibleChatBlock`s, newest at the bottom.
///
/// Scrolling model (iOS 17 APIs, kept deliberately simple):
/// - `defaultScrollAnchor(.bottom)` opens the thread at the newest message
///   AND keeps the bottom edge pinned while the reader is at the bottom —
///   that is the auto-stick;
/// - `scrollPosition(id:anchor:.top)` is used for the two programmatic
///   jumps: the new-messages pill (scroll to the bottom sentinel) and
///   **older-page re-anchoring** — before `loadOlder()` the current top
///   block id is captured, and when `historyVersion` bumps (rows were
///   prepended above the viewport, which shifts a non-bottom-anchored
///   scroll view) the position is re-set to that id so the reader stays on
///   the block they were looking at. Lazy estimated layout makes this
///   re-anchor approximate to the row, which is the documented trade-off;
/// - at-bottom detection uses a 1 pt bottom sentinel's lazy realization
///   (`onAppear`/`onDisappear`) — slightly eager because of lazy prefetch,
///   which errs toward sticking, the harmless direction.
struct ChatView: View {
    @State private var model: ChatModel
    /// Scroll position binding (anchor `.top`, so it tracks/targets the
    /// top-visible block).
    @State private var positionID: String?
    @State private var isAtBottom = true
    /// Newest block id last seen while at the bottom (new-messages pill).
    @State private var newestSeenID: String?
    /// Top-visible block captured when an older page was requested.
    @State private var pendingAnchorID: String?

    private static let bottomSentinelID = "chat-bottom-sentinel"

    init(session: HubSession, sessionId: String) {
        _model = State(initialValue: ChatModel(session: session, sessionId: sessionId))
    }

    var body: some View {
        Group {
            if model.isInitialLoading {
                initialLoading
            } else if model.loadFailed {
                loadFailedState
            } else if model.blocks.isEmpty {
                emptyState
            } else {
                blockList
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            banners
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                headerTitle
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .environment(\.chatMedia, model.imageLoader)
        .onAppear {
            model.start()
        }
        .onDisappear {
            // Pop-only navigation below this screen, so disappear == closed.
            model.stop()
        }
    }

    // MARK: - Thread

    private var blockList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                if model.hasMore || model.isLoadingOlder {
                    OlderHistoryRow(isLoading: model.isLoadingOlder)
                        .onAppear {
                            requestOlderPage()
                        }
                }
                ForEach(model.blocks, id: \.stableId) { block in
                    ChatBlockCard(block: block, basePath: model.basePath)
                }
                bottomSentinel
            }
            .scrollTargetLayout()
            .padding(.horizontal, 12)
            .padding(.top, 10)
        }
        .defaultScrollAnchor(.bottom)
        .scrollPosition(id: $positionID, anchor: .top)
        .scrollDismissesKeyboard(.interactively)
        .overlay(alignment: .bottom) {
            newMessagesPill
        }
        .onChange(of: model.historyVersion) {
            reanchorAfterPrepend()
        }
        .onChange(of: newestBlockID) {
            if isAtBottom {
                newestSeenID = newestBlockID
            }
        }
    }

    private var bottomSentinel: some View {
        Color.clear
            .frame(height: 1)
            .id(Self.bottomSentinelID)
            .onAppear {
                isAtBottom = true
                newestSeenID = newestBlockID
            }
            .onDisappear {
                isAtBottom = false
            }
    }

    private var newestBlockID: String? {
        model.blocks.last?.stableId
    }

    /// Capture the anchor BEFORE the prepend lands, then ask for the page.
    private func requestOlderPage() {
        if pendingAnchorID == nil {
            let id = positionID
            // The binding may currently point at chrome rows; fall back to
            // the oldest real block.
            let isBlock = id.map { candidate in
                model.blocks.contains { $0.stableId == candidate }
            } ?? false
            pendingAnchorID = isBlock ? id : model.blocks.first?.stableId
        }
        model.loadOlder()
    }

    /// `historyVersion` bumped: rows were prepended above the viewport.
    /// Re-set the position to the captured block on the next runloop turn
    /// (after SwiftUI laid the new rows out).
    private func reanchorAfterPrepend() {
        guard let anchor = pendingAnchorID else { return }
        pendingAnchorID = nil
        guard !isAtBottom else { return } // bottom-anchored: nothing shifted
        Task { @MainActor in
            positionID = anchor
        }
    }

    // MARK: - New-messages pill

    private var unseenCount: Int {
        guard !isAtBottom, let seen = newestSeenID else { return 0 }
        guard let index = model.blocks.lastIndex(where: { $0.stableId == seen }) else { return 0 }
        return model.blocks.count - 1 - index
    }

    @ViewBuilder
    private var newMessagesPill: some View {
        let count = unseenCount
        if count > 0 {
            Button {
                newestSeenID = newestBlockID
                positionID = Self.bottomSentinelID
            } label: {
                Text(count == 1 ? "1 new message ↓" : "\(count) new messages ↓")
                    .font(.footnote.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 7)
                    .background(.tint, in: Capsule())
                    .foregroundStyle(.white)
                    .shadow(radius: 3, y: 1)
            }
            .buttonStyle(.plain)
            .padding(.bottom, 12)
        }
    }

    // MARK: - Chrome

    private var headerTitle: some View {
        HStack(spacing: 8) {
            StatusDot(active: model.header.active, thinking: model.header.thinking)
            VStack(alignment: .leading, spacing: 0) {
                Text(model.header.title)
                    .font(.headline)
                    .lineLimit(1)
                if let subtitle = model.header.subtitle {
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
    }

    @ViewBuilder
    private var banners: some View {
        VStack(spacing: 0) {
            if let warning = model.warning {
                HStack(spacing: 8) {
                    Text(warning)
                        .font(.footnote)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    Button("Retry") {
                        model.retry()
                    }
                    .font(.footnote.weight(.semibold))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity)
                .background(.red.opacity(0.14))
                .foregroundStyle(.red)
            }
            if case .backoff = model.connectionState {
                Text("Live updates interrupted — reconnecting…")
                    .font(.footnote)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 5)
                    .background(.orange.opacity(0.15))
                    .foregroundStyle(.orange)
            }
        }
    }

    // MARK: - Empty / loading / error

    private var initialLoading: some View {
        VStack(spacing: 12) {
            ProgressView()
            Text("Loading messages…")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var loadFailedState: some View {
        ContentUnavailableView {
            Label("Couldn't load this session", systemImage: "wifi.slash")
        } description: {
            Text("Check the connection to your hub and try again.")
        } actions: {
            Button("Retry") {
                model.retry()
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No messages yet", systemImage: "bubble.left.and.bubble.right")
        } description: {
            Text("Messages will appear here as the agent works.")
        }
    }
}

/// Centered "· · ·" / spinner row doubling as the load-older top sentinel.
private struct OlderHistoryRow: View {
    let isLoading: Bool

    var body: some View {
        HStack(spacing: 8) {
            if isLoading {
                ProgressView()
                    .controlSize(.small)
                Text("Loading older messages…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Text("· · ·")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}
