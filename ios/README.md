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
                         without touching project.pbxproj.
  Packages/HapiKit/      Local SPM package with the real logic:
    HapiProtocol         Pure-Foundation protocol layer. As of M1a:
                           Models/   wire types mirroring shared/src/schemas.ts
                                     (Session, SessionPatch + VersionedValue,
                                     AgentState, DecryptedMessage, SessionSummary,
                                     Machine, SyncEvent union, messages page)
                           Catalog/  permission-mode / flavor tables ported from
                                     shared/src/{modes,flavors,copilotModes}.ts
                           Patch/    versioned session-patch application ported
                                     from web/src/lib/sessionPatch.ts
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
                         The message window logic (M2d) lands next.
    HapiClient           Transport layer. As of M1b+M1c:
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
                                            hub in UserDefaults).
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
                         @Observable stores and snapshots (M2) land next;
                         feature endpoints (git/files, scratchlist, voice,
                         usage) join Endpoints/ with their feature packages.
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
