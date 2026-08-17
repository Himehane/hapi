import type { FixtureCase } from '../fixtureTypes'
import { T0, wireMessage } from './support'

/**
 * Event family: content.type === 'event', data is the AgentEvent union.
 */
export const eventCases: FixtureCase[] = [
    {
        name: 'event-ready',
        description: 'Event family: a ready event sets top-level hasReadyEvent and is consumed — it must NOT surface as a chat block.',
        messages: [
            wireMessage({
                id: 'msg-user-071',
                seq: 1,
                createdAt: T0,
                content: {
                    role: 'user',
                    content: { type: 'text', text: 'hi' }
                }
            }),
            wireMessage({
                id: 'msg-event-072',
                seq: 2,
                createdAt: T0 + 1_200,
                content: {
                    role: 'agent',
                    content: {
                        type: 'event',
                        data: { type: 'ready' }
                    }
                }
            })
        ]
    },
    {
        name: 'event-limit-reached',
        description: 'Event family: limit-reached renders as an agent-event block with the event payload carried verbatim (endsAt is unix seconds).',
        messages: [
            wireMessage({
                id: 'msg-user-081',
                seq: 1,
                createdAt: T0,
                content: {
                    role: 'user',
                    content: { type: 'text', text: 'Keep going with the migration.' }
                }
            }),
            wireMessage({
                id: 'msg-event-082',
                seq: 2,
                createdAt: T0 + 2_000,
                content: {
                    role: 'agent',
                    content: {
                        type: 'event',
                        data: { type: 'limit-reached', endsAt: 1755010800, limitType: 'five_hour' }
                    }
                }
            })
        ]
    }
]
