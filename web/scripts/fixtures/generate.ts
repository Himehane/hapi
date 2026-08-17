import { mkdirSync, readdirSync, unlinkSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { fixtureCases } from './cases'
import { FIXTURE_VERSION, toFixtureInput, type FixtureCase, type FixtureDocument, type FixtureInput } from './fixtureTypes'
import { buildModesCatalog } from './modesCatalog'
import { runFixturePipeline } from './pipeline'
import { toCanonicalJson } from './serialize'

const FIXTURES_DIR = fileURLToPath(new URL('../../../shared/fixtures', import.meta.url))

export function buildFixtureDocument(fixtureCase: FixtureCase): FixtureDocument {
    // Round-trip the input through canonical JSON before running the pipeline,
    // so `expected` is computed from exactly the bytes the file will carry —
    // a consumer re-running `input` can never see values the generator had
    // but the stored JSON does not (e.g. authored `undefined`).
    const input = JSON.parse(toCanonicalJson(toFixtureInput(fixtureCase))) as FixtureInput
    return {
        fixtureVersion: FIXTURE_VERSION,
        name: fixtureCase.name,
        description: fixtureCase.description,
        input,
        expected: runFixturePipeline(input)
    }
}

export function generateAllFixtures(): void {
    const chatDir = join(FIXTURES_DIR, 'chat')
    mkdirSync(chatDir, { recursive: true })

    const names = new Set<string>()
    for (const fixtureCase of fixtureCases) {
        if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(fixtureCase.name)) {
            throw new Error(`Fixture name must be kebab-case: ${fixtureCase.name}`)
        }
        if (names.has(fixtureCase.name)) {
            throw new Error(`Duplicate fixture name: ${fixtureCase.name}`)
        }
        names.add(fixtureCase.name)
        const document = buildFixtureDocument(fixtureCase)
        writeFileSync(join(chatDir, `${fixtureCase.name}.json`), toCanonicalJson(document))
    }

    // Remove stale fixtures from renamed/deleted cases so natives never keep
    // passing against a file the web pipeline no longer generates.
    for (const entry of readdirSync(chatDir)) {
        if (entry.endsWith('.json') && !names.has(entry.slice(0, -'.json'.length))) {
            unlinkSync(join(chatDir, entry))
        }
    }

    // Catalogs: reference tables generated from shared/src modules (not from
    // the chat pipeline). Same canonical serialization and drift gate.
    const catalogsDir = join(FIXTURES_DIR, 'catalogs')
    mkdirSync(catalogsDir, { recursive: true })
    writeFileSync(join(catalogsDir, 'modes.json'), toCanonicalJson(buildModesCatalog()))

    writeFileSync(join(FIXTURES_DIR, 'VERSION'), `${FIXTURE_VERSION}\n`)
    console.log(`Wrote ${names.size} chat fixtures (fixtureVersion ${FIXTURE_VERSION}) to ${chatDir}`)
    console.log(`Wrote catalogs/modes.json to ${catalogsDir}`)
}
