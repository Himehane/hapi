# HAPI iOS (native companion)

Native SwiftUI client for HAPI. Fully independent of `web/` at the code level;
it shares only the protocol contract (`docs/api/`) and the golden fixtures
(`shared/fixtures/`, produced by the consistency track).

## Requirements

- Xcode 16 or newer (iOS 17 SDK).
- Deployment target: iOS 17.0.
- Runtime dependencies (SPM, declared in `Packages/HapiKit/Package.swift`,
  used only by the `HapiUI` target): `swiftlang/swift-markdown` (GFM parsing
  for the custom renderer) and `raspu/Highlightr` (code highlighting, kept
  behind a protocol so it is swappable). `HapiProtocol`/`HapiClient` stay
  dependency-free.

## Build

Open `ios/Hapi.xcodeproj` in Xcode and run the shared `Hapi` scheme, or from
the command line:

```sh
# App (simulator, no signing)
xcodebuild build -project ios/Hapi.xcodeproj -scheme Hapi \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO

# Package tests (also runs on macOS, the package is pure Foundation)
swift test --package-path ios/Packages/HapiKit
```

CI runs both on `macos-15` via `.github/workflows/ios.yml` (triggered by
changes under `ios/**` and `shared/fixtures/**`).

## Layout

```
ios/
  Hapi.xcodeproj/        Hand-rolled minimal project (objectVersion 77).
  Hapi/                  App target sources. This is an Xcode 16 "synchronized
                         folder": add files here and they join the target
                         without touching project.pbxproj. As of M1d:
                           Models/    AppModel (pairing state machine, hub
                                      switching, deep-link routing, scene
                                      phase) + HubSession (per-active-hub
                                      APIClient/AuthManager/global SSEClient,
                                      connection state for the UI).
                           Features/  Pairing/ (welcome, VisionKit QR scan,
                                      manual entry, shared confirm + error
                                      states) and Home/ (post-pairing
                                      placeholder with hub switcher and
                                      connection dot; session list lands M2).
  Packages/HapiKit/      Local SPM package with the real logic:
    HapiProtocol         Pure-Foundation protocol layer. As of M1a+M1d:
                           Models/   wire types mirroring shared/src/schemas.ts
                                     (Session, SessionPatch + VersionedValue,
                                     AgentState, DecryptedMessage, SessionSummary,
                                     Machine, SyncEvent union, messages page)
                           Catalog/  permission-mode / flavor tables ported from
                                     shared/src/{modes,flavors,copilotModes}.ts
                           Patch/    versioned session-patch application ported
                                     from web/src/lib/sessionPatch.ts
                           Pairing/  BindLink — parses both pairing QR forms
                                     (hapicompanion://bind?hub=&code= and the
                                     web /?hub=&token= URL), form-decoding in
                                     lockstep with the Android port
                           Chat/     the chat normalization/reduction pipeline
                                     ported file-for-file from web/src/chat/**
                                     (M2b+M2c): Normalize/NormalizeUser/
                                     NormalizeAgent (decode tree incl. agy),
                                     Tracer (sidechain grouping), Reducer*
                                     (timeline, tool pairing, stream coalescing,
                                     agent-run cards, events dedupe/folding,
                                     cli-output merge), ToolGroups (+ codex
                                     exploration family), the normative fixture
                                     projection (FixtureProjection), and the
                                     JS-semantics interop layer (JSInterop:
                                     nullish coalescing, truthiness, canonical
                                     JSON serializer). Validated block-for-block
                                     against shared/fixtures/chat/** by
                                     ChatFixtureTests (one parameterized test
                                     per fixture, line-level diff on mismatch).
                           Window/   the message window state machine ported
                                     from web/src/lib/message-window-store.ts +
                                     messages.ts (M2d): MessageWindowState
                                     (cursors/epoch/generations + persisted v2
                                     snapshot shape), MessageWindowLogic (pure
                                     transitions: tail sync, older pages +
                                     epoch-mismatch reset, trims that never
                                     drop queued rows, SSE ingest, optimistic
                                     lifecycle, queued-state reconcile),
                                     MessageMerge (position order, localId echo
                                     replacement, 10 s dedup fallback) and
                                     WindowMessage (wire row + client status,
                                     tri-state invokedAt, identity-carrying
                                     class — reset preservation compares rows
                                     by instance like the web's `!==`).
                                     Retention calls the chat pipeline's
                                     normalize directly, so the two cannot
                                     drift.
    HapiClient           Transport layer. As of M1b+M1c+M1d:
                           APIClient        typed REST client (Endpoints/*):
                                            Bearer auth, 401 -> refresh ->
                                            retry-once, {error, code} parsing
                                            (APIError), 256 MB URLCache for
                                            generated images. HTTP goes through
                                            the HTTPPerforming seam, so tests
                                            inject a recording performer.
                           Auth/            JWT payload decoding, AuthManager
                                            (actor; single-flight refresh via
                                            POST /api/auth, proactive refresh
                                            10 min before exp, terminal
                                            authFailed state), Keychain
                                            credential store (per-hub records
                                            under run.hapi.companion),
                                            HubRegistry (multi-hub + active
                                            hub in UserDefaults),
                                            HubPairingService (normalize ->
                                            /health + protocolVersion check ->
                                            /api/auth -> persist; unpair with
                                            fallback), tested through the
                                            HTTPPerforming seam.
                           SSE/             actor SSEClient — handshake-gated
                                            connect (resume ok/gap surfaced),
                                            sticky per-subscription cursor with
                                            at-least-once replay, 10 s connect
                                            timeout + 90 s staleness watchdog,
                                            backoff per sse.md (1 s ×2 → 30 s,
                                            300 s after 8 attempts, 0–500 ms
                                            jitter), suspend/resume with the
                                            45 s foreground staleness check,
                                            NWPath change → immediate reconnect.
                                            SSELineParser, ReconnectPolicy/
                                            SSETimings, URLSessionSSETransport
                                            (gzip streaming-decompression
                                            verification TODO — fallback flag
                                            `acceptEncodingIdentity`).
                           MultipartEncoder for the voice-transcription
                                            endpoint (M4c).
                           Stores/          MessageWindowController (M2d):
                                            per-session actor driving the
                                            HapiProtocol window logic —
                                            single-flight tail sync with
                                            trailing drain, older-page loads,
                                            SSE ingest hooks, optimistic
                                            send/cancel, queued-state
                                            reconciliation (≤1000-id batches)
                                            — behind the MessagesProviding
                                            seam (APIClient conforms; the
                                            fixture harness scripts it).
                                            WindowSnapshotStore persists
                                            per-session windows to Caches
                                            (LRU 10) for cold-start rendering;
                                            MessageWindowControllers is the
                                            per-hub registry (hydrate on open,
                                            seed across resume/reopen id
                                            changes).
                         Remaining @Observable stores (M2) land next; feature
                         endpoints (git/files, scratchlist, voice, usage) join
                         Endpoints/ with their feature packages.
    HapiUI               Rendering foundation (M2e). SwiftUI, no app coupling:
                           Markdown/  MarkdownTransforms (string-level ports of
                                      the web remark plugins: table repair,
                                      indented-code disable, CJK autolink strip,
                                      file-path + bare-URL detection, HrefPolicy)
                                      and MarkdownRenderer (swift-markdown
                                      visitor -> block tree -> SwiftUI views;
                                      links flow through the \.hapiOpenURL
                                      environment action, workspace files use
                                      hapi-file://?path=&line= URLs)
                           Code/      CodeBlockView + SyntaxHighlighting
                                      protocol with the Highlightr engine
                                      (off-main, cached, 400-line cap)
                           Diff/      UnifiedDiffParser + DiffTextView
                                      (hunks, +/- gutters, compact/expand)
                           Theme/     HapiTheme palettes (light/dark/OLED)
                                      via the \.hapiTheme environment
                         The app target does not import HapiUI yet; it gets
                         wired in with the chat views (M2f).
```

The app target stays thin; features live in `HapiKit` so they are testable
with `swift test` and free of UI concerns.

### Fixtures

`HapiProtocolTests` reads the golden fixtures from the repo-root
`shared/fixtures/` directory, resolved from the test file's own `#filePath`
(package root `ios/Packages/HapiKit` -> `../../../shared/fixtures`), so the
suite needs a full repo checkout. Since M1a, `FixtureDecodingTests` decodes
every `chat/*.json` input as `[DecryptedMessage]` (+ `AgentState`), and
`CatalogTests` verifies the ported mode tables against
`catalogs/modes.json`. Since M2b/M2c, `ChatFixtureTests` is the pipeline
gate: for every chat fixture it runs the ported normalize → reduce → group
pipeline over the stored `input`, applies the normative projection, and
compares canonical JSON byte-for-byte against the stored `expected` —
failures are per-fixture and print the first differing line with context.
Since M2d, `HapiClientTests/PaginationFixtureTests` replays every
`pagination/*.json` op script against the real `MessageWindowController`
driven by a scripted `MessagesProviding`: it asserts the exact `GET /messages`
query objects (`expectedRequests`, including the explicit-null
`untilAt`/`untilSeq` of the first catch-up request), the older-load outcomes,
the queued-state reconcile candidates, and the final window projection
(`expectedState`) — all canonical-JSON compares with the same per-op labels
and first-differing-line diffs.

## Pairing (M1d)

How to pair the app with a hub (`docs/api/client-contract/auth.md` is the
contract; the app accepts multiple hubs and keeps one active):

- **Local hub, manual entry** — the everyday dev loop:
  1. Start the stack from the repo root: `bun run dev` (or just the hub). The
     hub prints its URL and the access token (`CLI_API_TOKEN`, auto-generated
     into the hub's `settings.json` on first run).
  2. In the app: *Enter Manually* → hub URL (e.g. `http://192.168.1.20:3006`
     — the phone must reach the hub's LAN address, not `localhost`; a typed
     address without a scheme gets `http://` prefixed) → paste the token →
     *Continue* → *Pair*.
  3. The app checks `GET /health` (reachability + `protocolVersion`), then
     exchanges the token via `POST /api/auth` and stores it in the Keychain.
- **QR scan** — start the hub with `--relay`: the terminal prints two QR
  codes. The scanner accepts **both** — the companion deeplink
  (`hapicompanion://bind?hub=…&code=…`, canonical) and the web direct-access
  URL (`https://<web>/?hub=…&token=…`). The web app's Settings → Companion
  Pairing screen renders the deeplink QR too.
- **Deep link** — opening a `hapicompanion://bind` link routes through the
  same confirm sheet; a link for an already-paired hub just switches to it.
- **Simulator**: camera scanning is unavailable (`DataScannerViewController`
  unsupported) — the scanner screen says so; use manual entry. Plain-HTTP LAN
  hubs work because `NSAllowsLocalNetworking` stays enabled (ATS default
  otherwise).
- **Sign out** (home → hub menu) deletes the stored token for that hub and
  falls back to the next paired hub, or to pairing. A hub that terminally
  rejects its stored token (rotated/revoked → `POST /api/auth` 401) is signed
  out automatically with a banner.

Manual test pass for the app layer (the pairing sequence itself is covered by
`PairingLogicTests` via injected HTTP fakes; `AppModel`/views are UI-bound):
pair via manual entry against a local hub → kill + relaunch (restores paired
state, SSE reconnects) → background/foreground (connection dot pauses and
resumes) → pair a second hub and switch between them → sign out of both →
scan both `--relay` QR forms → open a `hapicompanion://bind` link from Notes
(unpaired: confirm; paired: "already paired" notice) → rotate
`CLI_API_TOKEN` on the hub and watch the auto sign-out banner.

## Milestones (track A of the native-clients plan)

- **M0** — this scaffold: project, HapiKit package, CI, one passing test.
- **M1** — foundations: HapiProtocol wire models + catalogs; APIClient + auth
  (Keychain, single-flight 401 refresh); SSEClient + reconnect state machine +
  versioned patch application (incl. gzip streaming check); pairing flow
  (VisionKit scan + `hapicompanion://bind` deep link + multi-hub).
- **M2** — read-only chat: session list; chat pipeline port
  (normalize/reducer/toolGroups, fixtures green is the gate); message window
  store; Markdown/code/diff renderers; read-only ChatView with paging.
- **M3** — interaction: composer (optimistic send, queue/steer, drafts,
  reopen migration, slash commands); permission UX; new session; attachments.
- **M4** — secondary features: files/git; Scratchlist; dictation;
  usage/storage (Swift Charts); settings.
- **M5** — polish: zh-CN localization, Dynamic Type/VoiceOver, long-session
  memory profiling, App Store material.

## Notes

- The `hapicompanion://` URL scheme is registered via `Hapi/Info.plist`
  (only `CFBundleURLTypes` lives there; everything else is generated through
  `GENERATE_INFOPLIST_FILE` + `INFOPLIST_KEY_*` build settings).
- `run.hapi.companion` is the bundle id; signing is `Automatic` and CI builds
  with `CODE_SIGNING_ALLOWED=NO`.
- CI uses the runner's default Xcode; each job prints `xcodebuild -version`
  first so failures are attributable to a toolchain bump.
