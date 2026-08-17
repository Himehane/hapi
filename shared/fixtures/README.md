# Golden chat fixtures

Machine-generated conformance fixtures for the HAPI chat rendering pipeline.
The **web implementation is the source of truth**: every file under
`chat/` is produced by running the real web pipeline
(`web/src/chat/`: `normalizeDecryptedMessage` → `reduceChatBlocks` →
`buildVisibleChatBlocks`) over hand-authored wire inputs, then applying the
normative projection described below. Native clients (iOS `HapiProtocol`,
Android `:core:protocol`) port that pipeline and must reproduce `expected`
from `input` exactly.

Never edit the JSON by hand — edit the case definitions in
`web/scripts/fixtures/cases/` and regenerate.

## Layout

```
shared/fixtures/
├── VERSION            # current fixtureVersion (single integer + \n)
├── chat/<name>.json   # one fixture per case
└── README.md
```

## Document schema (`fixtureVersion: 1`)

```jsonc
{
    "fixtureVersion": 1,
    "name": "claude-assistant-text",        // equals the file name
    "description": "…",
    "input": {
        "messages": [ /* DecryptedMessage[] exactly as GET /sessions/:id/messages returns them */ ],
        "agentState": null,                 // session.agentState (permission requests) or null
        "options": { "hasMoreMessages": false }  // older history exists beyond `messages`
    },
    "expected": {
        "blocks": [ /* projected ChatBlock[] (pre tool-grouping) */ ],
        "hasReadyEvent": false,
        "latestUsage": null,                // or { inputTokens, outputTokens, contextSize, contextWindow }
        "visibleBlocks": [ /* projected blocks after tool-grouping */ ]
    }
}
```

## How to consume

- **iOS** (`ios/`, SPM test target): resolve the repo checkout from the test
  file's own location and load every `chat/*.json`, e.g.
  `URL(fileURLWithPath: #filePath)` → walk up to the package root →
  `../../shared/fixtures`. Decode `input`, run the ported pipeline, project,
  and compare against `expected`.
- **Android** (`android/`, `:core:protocol` JVM tests): pass the directory via
  Gradle — `tasks.withType<Test> { systemProperty("hapi.fixtures.dir",
  rootDir.resolve("../shared/fixtures")) }` — and read it with
  `System.getProperty("hapi.fixtures.dir")` in the test.
- Iterate **all** files in `chat/` (fail on zero files) so newly added
  fixtures are picked up without native-side changes.

### Acceptance bar

Exact match on the normative projection: serialize your projected output and
the fixture's `expected` to **canonical JSON** (recursively sorted object
keys; numbers as JSON numbers; no `undefined`/absent-key differences) and
compare for equality — per fixture, on `blocks`, `hasReadyEvent`,
`latestUsage`, and `visibleBlocks`. Field order in the files is already
canonical (sorted keys, 4-space indent, LF, trailing newline), so a
key-sorted structural deep-compare is equivalent.

### fixtureVersion policy

`fixtureVersion` (mirrored in `VERSION`) is bumped when the document schema or
the normative projection changes shape. Native suites must **fail loudly when
the on-disk version is newer than the version they support** (do not silently
skip), and should tolerate older versions only if they explicitly implement
them. Additive new fixture *files* and new wire cases do not bump the version.

## Normative field contract

The projection keeps **structure + semantics** and drops web-presentation and
advisory detail. Whatever is absent below is intentionally NOT part of the
contract — natives may derive their own equivalents but must not expect it in
fixtures. Implementation: `web/scripts/fixtures/projection.ts` (keep in sync
with this list).

Optional fields are present only when they carry a value; `invokedAt` is
omitted when null. `localId` is always present (nullable) on the block kinds
that carry it.

### Every block

`kind`, `id`, `createdAt`, `invokedAt?`

### Per kind

| kind | normative fields |
|------|------------------|
| `user-text` | `localId`, `text`, `attachments?` — each `{ id, filename, mimeType, size, path }` |
| `agent-text` | `localId`, `text` |
| `agent-reasoning` | `localId`, `text` |
| `cli-output` | `localId`, `text`, `source` (`'user' \| 'assistant'`) |
| `codex-review` | `localId`, `review` (verbatim normalized review object) |
| `generated-image` | `localId`, `imageId`, `fileName`, `mimeType` |
| `agent-event` | `event` — the normalized AgentEvent object verbatim (`type` + typed payload fields) |
| `tool-call` | `localId`, `tool`, `children?` (recursively projected; omitted when empty) |
| `tool-group` (visibleBlocks only) | `firstToolId`, `lastToolId`, `tools` (projected `tool-call` blocks in order; membership + order + count) |

### `tool` object

`{ id, name, state, input?, result?, permission? }`

- `state`: `'pending' | 'running' | 'completed' | 'error'`
- `input`: verbatim wire value (may be `null` when only the result was seen)
- `result`: verbatim wire value, present once a result/progress landed —
  including hub truncation markers (`…[hapi: truncated N chars]…`) byte-for-byte
- `permission?`: `{ status, mode?, decision?, allowedTools?, answers?, reason? }`
  — `status`: `'pending' | 'approved' | 'denied' | 'canceled'`

### Top level

- `hasReadyEvent`: boolean (a `ready` event is consumed, never a block)
- `latestUsage`: `null` or `{ inputTokens, outputTokens, contextSize, contextWindow }`
  (`contextWindow` nullable; `contextSize` already folds cache tokens in)

### Dropped (web-presentation / advisory — not in fixtures)

Block level: `meta`, `usage` (per-block), `model`, `durationMs`, `status`
(optimistic-send state), `originalText`, `streamId`, `uuid`/`parentUUID`,
`agentTimestamp`. Tool level: `createdAt`/`startedAt`/`completedAt`/
`execStartedAt`/`execCompletedAt` (timing), `description`, `nativeTitle`,
`nativeKind`, `progress`. Permission level: `id`, `date`, `createdAt`,
`completedAt`. Attachments: `previewUrl`. Generated image: `source`.
Tool group: `defaultOpen`, `historyState`, `needsOlderHistory`,
`activityTitle`, `presentationMode`, `summary`. Top level: `latestGoal`,
`latestUsage.cacheCreation`/`cacheRead`/`model`/`timestamp`.

## Regeneration & drift gate

```bash
bun run gen:fixtures        # from the repo root (runs web/scripts/generate-fixtures.ts)
```

Output is byte-deterministic (canonical serialization), so `git status` after
a regeneration is the drift signal: when `web/src/chat/**` changes behavior,
regenerated fixtures differ, the diff gets committed, and the native
conformance suites go red until the ports catch up. The web-side self-check
lives at `web/src/chat/fixtures.test.ts` (runs in `bun run test:web`): it
re-runs the pipeline over every stored `input` and fails on any divergence
from `expected` or from canonical serialization.
