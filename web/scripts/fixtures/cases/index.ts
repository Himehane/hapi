import type { FixtureCase } from '../fixtureTypes'
import { claudeOutputCases } from './claudeOutput'
import { codexCases } from './codex'
import { eventCases } from './events'
import { userCases } from './user'
import { truncationCases } from './truncation'
import { permissionCases } from './permissions'
import { toolGroupCases } from './toolGroups'

/** Batch 1. Each case becomes shared/fixtures/chat/<name>.json. */
export const fixtureCases: FixtureCase[] = [
    ...claudeOutputCases,
    ...codexCases,
    ...eventCases,
    ...userCases,
    ...truncationCases,
    ...permissionCases,
    ...toolGroupCases
]
