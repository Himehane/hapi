import { afterEach, describe, expect, it } from 'bun:test'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { APNS_PRODUCTION_HOST, APNS_SANDBOX_HOST } from './apnsClient'
import { DEFAULT_PUSH_RELAY_URL, resolveIosPushConfig } from './iosPushConfig'

const tempDirs: string[] = []
afterEach(() => {
    for (const dir of tempDirs.splice(0)) {
        rmSync(dir, { recursive: true, force: true })
    }
})

function makeP8File(): string {
    const dir = mkdtempSync(join(tmpdir(), 'hapi-apns-key-'))
    tempDirs.push(dir)
    const path = join(dir, 'AuthKey_TEST.p8')
    writeFileSync(path, '-----BEGIN PRIVATE KEY-----\nMIG\n-----END PRIVATE KEY-----\n')
    return path
}

describe('resolveIosPushConfig', () => {
    it('defaults to relay mode with the official relay URL', () => {
        const config = resolveIosPushConfig({})
        expect(config).toEqual({ mode: 'relay', relayUrl: DEFAULT_PUSH_RELAY_URL, source: 'default' })
        expect(DEFAULT_PUSH_RELAY_URL).toBe('https://push.hapi.run')
    })

    it('honors an explicit relay URL from HAPI_PUSH_RELAY_URL', () => {
        const config = resolveIosPushConfig({
            HAPI_IOS_PUSH: 'relay',
            HAPI_PUSH_RELAY_URL: 'https://relay.internal.example'
        })
        expect(config).toEqual({ mode: 'relay', relayUrl: 'https://relay.internal.example', source: 'env' })
    })

    it('returns off when HAPI_IOS_PUSH=off', () => {
        const config = resolveIosPushConfig({ HAPI_IOS_PUSH: 'off' })
        expect(config.mode).toBe('off')
    })

    it('falls back to the relay default on an unknown HAPI_IOS_PUSH value', () => {
        const config = resolveIosPushConfig({ HAPI_IOS_PUSH: 'bogus' })
        expect(config.mode).toBe('relay')
    })

    it('resolves apns mode with all credentials, defaulting to the production host', () => {
        const p8Path = makeP8File()
        const config = resolveIosPushConfig({
            HAPI_IOS_PUSH: 'apns',
            APNS_KEY_P8_PATH: p8Path,
            APNS_KEY_ID: 'KEY123',
            APNS_TEAM_ID: 'TEAM456',
            APNS_BUNDLE_ID: 'run.hapi.ios'
        })
        expect(config.mode).toBe('apns')
        if (config.mode !== 'apns') throw new Error('unreachable')
        expect(config.keyId).toBe('KEY123')
        expect(config.teamId).toBe('TEAM456')
        expect(config.bundleId).toBe('run.hapi.ios')
        expect(config.env).toBe('production')
        expect(config.host).toBe(APNS_PRODUCTION_HOST)
        expect(config.keyP8).toContain('BEGIN PRIVATE KEY')
    })

    it('uses the sandbox host when APNS_ENV=sandbox', () => {
        const config = resolveIosPushConfig({
            HAPI_IOS_PUSH: 'apns',
            APNS_KEY_P8_PATH: makeP8File(),
            APNS_KEY_ID: 'K',
            APNS_TEAM_ID: 'T',
            APNS_BUNDLE_ID: 'b',
            APNS_ENV: 'sandbox'
        })
        if (config.mode !== 'apns') throw new Error('expected apns mode')
        expect(config.env).toBe('sandbox')
        expect(config.host).toBe(APNS_SANDBOX_HOST)
    })

    it('disables push when apns is selected but credentials are missing', () => {
        const config = resolveIosPushConfig({
            HAPI_IOS_PUSH: 'apns',
            APNS_KEY_ID: 'K'
        })
        expect(config.mode).toBe('off')
        if (config.mode !== 'off') throw new Error('unreachable')
        expect(config.reason).toContain('APNS_KEY_P8_PATH')
        expect(config.reason).toContain('APNS_TEAM_ID')
        expect(config.reason).toContain('APNS_BUNDLE_ID')
        expect(config.reason).not.toContain('APNS_KEY_ID,')
    })

    it('disables push when the .p8 file cannot be read', () => {
        const config = resolveIosPushConfig({
            HAPI_IOS_PUSH: 'apns',
            APNS_KEY_P8_PATH: '/nonexistent/AuthKey.p8',
            APNS_KEY_ID: 'K',
            APNS_TEAM_ID: 'T',
            APNS_BUNDLE_ID: 'b'
        })
        expect(config.mode).toBe('off')
        if (config.mode !== 'off') throw new Error('unreachable')
        expect(config.reason).toContain('cannot read APNS_KEY_P8_PATH')
    })
})
