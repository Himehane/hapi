# HAPI iOS (native companion)

Native SwiftUI client for HAPI. Fully independent of `web/` at the code level;
it shares only the protocol contract (`docs/api/`) and the golden fixtures
(`shared/fixtures/`, produced by the consistency track).

## Requirements

- Xcode 16 or newer (iOS 17 SDK). No third-party dependencies in M0.
- Deployment target: iOS 17.0.

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
                         The chat pipeline + message window logic (ported from
                         web/src/chat/**) land in M2, validated against
                         shared/fixtures/**.
    HapiClient           Transport layer: APIClient, AuthManager (single-flight
                         refresh), SSEClient, @Observable stores, snapshots.
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
`catalogs/modes.json`; the full pipeline conformance against each fixture's
`expected` projection is the M2 gate.

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
