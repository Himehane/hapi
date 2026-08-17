import type { FixtureCase } from '../fixtureTypes'
import { T0, wireMessage } from './support'

/**
 * Generic agent family: content.type === 'codex' (AGENT_MESSAGE_PAYLOAD_TYPE,
 * used by Codex/Cursor/OpenCode/Pi/…). Text and reasoning arrive as cumulative
 * stream snapshots keyed by data.id.
 */
export const codexCases: FixtureCase[] = [
    {
        name: 'codex-message-stream-snapshot',
        description: 'Codex family: two message payloads sharing a stream id (data.id) are cumulative snapshots. Expects a single agent-text block keyed by the first message, carrying the final snapshot text.',
        messages: [
            wireMessage({
                id: 'msg-codex-051',
                seq: 1,
                createdAt: T0,
                content: {
                    role: 'agent',
                    content: {
                        type: 'codex',
                        data: {
                            type: 'message',
                            id: 'item_7',
                            message: 'Tracking down the failing pagination'
                        }
                    }
                }
            }),
            wireMessage({
                id: 'msg-codex-052',
                seq: 2,
                createdAt: T0 + 900,
                content: {
                    role: 'agent',
                    content: {
                        type: 'codex',
                        data: {
                            type: 'message',
                            id: 'item_7',
                            message: 'Tracking down the failing pagination test: the cursor math drops the epoch check when seq wraps.'
                        }
                    }
                }
            })
        ]
    },
    {
        name: 'codex-reasoning',
        description: 'Codex family: reasoning payload becomes an agent-reasoning block (stream id kept internal — not part of the projection).',
        messages: [
            wireMessage({
                id: 'msg-codex-061',
                seq: 1,
                createdAt: T0,
                content: {
                    role: 'agent',
                    content: {
                        type: 'codex',
                        data: {
                            type: 'reasoning',
                            id: 'rs_0af3d219',
                            message: '**Weighing pagination approaches**\n\nThe epoch guard only fires when the server resets, so the client must drop its window on mismatch.'
                        }
                    }
                }
            })
        ]
    }
]
