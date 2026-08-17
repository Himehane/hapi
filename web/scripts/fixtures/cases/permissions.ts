import type { FixtureCase } from '../fixtureTypes'
import { T0, wireMessage } from './support'

/**
 * Permission requests are NOT messages: they live in session.agentState
 * (requests → completedRequests). A pending request whose tool_use message is
 * not in the loaded window must still be answerable, so the reducer
 * synthesizes a pending tool-call block for it — but only when the request is
 * pending, has no tool call/result in the transcript, and is not older than
 * the oldest loaded message (web/src/chat/reducer.ts).
 */
export const permissionCases: FixtureCase[] = [
    {
        name: 'permission-synthesized-pending',
        description: 'A pending agentState request with no matching tool_use in the (older) message window synthesizes a pending tool-call block: state pending, permission.status pending, input from request.arguments.',
        messages: [
            wireMessage({
                id: 'msg-user-121',
                seq: 1,
                createdAt: T0,
                content: {
                    role: 'user',
                    content: { type: 'text', text: 'Install the missing dependency.' }
                }
            })
        ],
        agentState: {
            requests: {
                'req-01J5XKQ8TZ3M': {
                    tool: 'Bash',
                    arguments: { command: 'bun add zod', description: 'Install zod' },
                    createdAt: T0 + 6_000
                }
            },
            completedRequests: {}
        }
    }
]
