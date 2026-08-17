import Foundation
import HapiClient
import HapiProtocol
import Observation

/// Everything the app holds for the currently active hub: the typed REST
/// client, its auth manager, and the **global** SSE subscription
/// (`scope: .global` — one per app session; per-chat `.session` subscriptions
/// arrive with M2).
///
/// Lifecycle is driven by `AppModel` from `scenePhase`: the SSE connection
/// starts on the first foreground, suspends in background, and resumes (with
/// the 45 s staleness check) on return. `AppModel` builds a fresh instance per
/// hub switch and calls `shutdown()` on the old one — a `HubSession` never
/// outlives its hub selection.
@MainActor @Observable
final class HubSession {
    /// Normalized hub origin (registry / credential key).
    let hubUrl: String
    let baseURL: URL
    let authManager: AuthManager
    let api: APIClient

    /// Global SSE connection state, for the UI's connection dot.
    private(set) var connectionState: SSEConnectionState = .idle
    /// Verdict of the latest SSE handshake (`ok` = replay covered the gap).
    private(set) var lastResumeVerdict: ResumeVerdict?
    /// From the latest handshake; needed for `POST /api/visibility` (M3b).
    private(set) var subscriptionId: String?

    /// Fired once when the hub terminally rejects the stored credentials
    /// (access token rotated/revoked). `AppModel` reacts by dropping the hub
    /// back to pairing with a banner.
    @ObservationIgnored var onTerminalAuthFailure: (@MainActor () -> Void)?

    @ObservationIgnored private var sse: SSEClient?
    @ObservationIgnored private var consumeTask: Task<Void, Never>?
    @ObservationIgnored private var reportedAuthFailure = false
    @ObservationIgnored private var isShutDown = false

    init?(
        hubUrl: String,
        credentialStore: any CredentialStoring,
        performer: any HTTPPerforming = URLSessionHTTPPerformer.shared
    ) {
        guard let baseURL = URL(string: hubUrl) else { return nil }
        self.hubUrl = hubUrl
        self.baseURL = baseURL
        self.authManager = AuthManager(
            baseURL: baseURL,
            credentialStore: credentialStore,
            performer: performer
        )
        self.api = APIClient(baseURL: baseURL, authManager: authManager, performer: performer)
    }

    // MARK: - Lifecycle (driven by AppModel from scenePhase)

    /// First call starts the global SSE subscription; later calls resume a
    /// suspended one (which distrusts sockets silent for 45 s or more).
    func enterForeground() {
        guard !isShutDown, !reportedAuthFailure else { return }
        if let sse {
            Task { await sse.resume() }
        } else {
            startGlobalSSE()
        }
    }

    /// Parks the SSE retry loop; a live connection is left to the OS.
    func enterBackground() {
        guard let sse else { return }
        Task { await sse.suspend() }
    }

    /// Tears everything down. The session is unusable afterwards.
    func shutdown() {
        isShutDown = true
        onTerminalAuthFailure = nil
        consumeTask?.cancel()
        consumeTask = nil
        if let sse {
            self.sse = nil
            Task { await sse.stop() }
        }
        connectionState = .idle
    }

    // MARK: - Global SSE

    private func startGlobalSSE() {
        let auth = authManager
        let configuration = SSEClientConfiguration(
            baseUrl: baseURL,
            tokenProvider: { [weak self] in
                do {
                    return try await auth.validToken()
                } catch AuthError.reauthenticationRequired {
                    // Terminal: only re-pairing recovers. Surface once on the
                    // main actor; returning nil parks the SSE loop in backoff
                    // until AppModel shuts this session down.
                    let session = self // weak capture, may already be gone
                    Task { @MainActor in
                        session?.handleTerminalAuthFailure()
                    }
                    return nil
                } catch {
                    // Transient (offline, hub restarting): the SSE loop backs
                    // off and asks again.
                    return nil
                }
            },
            scope: .global
        )
        let client = SSEClient(configuration: configuration, pathObserver: NWPathObserver())
        sse = client
        consumeTask = Task { [weak self] in
            let stream = await client.start()
            for await event in stream {
                guard let self, !Task.isCancelled else { return }
                self.handle(event)
            }
        }
    }

    private func handle(_ event: SSEClientEvent) {
        switch event {
        case .stateChanged(let state):
            connectionState = state
        case .handshake(let resume, let subscriptionId):
            lastResumeVerdict = resume
            self.subscriptionId = subscriptionId
            // TODO(M2): `resume == .ok` means the replay that follows covers
            // every missed event (skip the REST resync); `.gap` triggers a
            // full session-list refetch. Routing lands with the M2 stores.
        case .event:
            // TODO(M2): route SyncEvents into the session-list store and the
            // per-session message-window stores. Until those exist the global
            // stream is consumed for connection state only.
            break
        }
    }

    private func handleTerminalAuthFailure() {
        guard !reportedAuthFailure, !isShutDown else { return }
        reportedAuthFailure = true
        let callback = onTerminalAuthFailure
        shutdown()
        callback?()
    }
}
