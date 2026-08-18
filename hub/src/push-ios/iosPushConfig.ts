import { readFileSync } from 'node:fs'

import { APNS_PRODUCTION_HOST, APNS_SANDBOX_HOST } from './apnsClient'

export const DEFAULT_PUSH_RELAY_URL = 'https://push.hapi.run'

/**
 * Transport selection (PUSH SPEC v1):
 *   HAPI_IOS_PUSH = apns | relay | off   (default: relay)
 *   HAPI_PUSH_RELAY_URL                  (default: https://push.hapi.run)
 *   direct mode requires APNS_KEY_P8_PATH, APNS_KEY_ID, APNS_TEAM_ID,
 *   APNS_BUNDLE_ID; APNS_ENV = production | sandbox (default: production).
 *
 * `relay` is the default because it needs zero operator setup - and it only
 * ever carries ciphertext (see envelope.ts), so defaulting to the official
 * relay leaks nothing. Self-hosters who own an Apple developer account can
 * switch to `apns` and cut the relay out entirely.
 */
export type IosPushConfig =
    | { mode: 'off'; reason: string }
    | { mode: 'relay'; relayUrl: string; source: 'env' | 'default' }
    | {
        mode: 'apns'
        keyP8: string
        keyP8Path: string
        keyId: string
        teamId: string
        bundleId: string
        env: 'production' | 'sandbox'
        host: string
    }

export function resolveIosPushConfig(env: NodeJS.ProcessEnv = process.env): IosPushConfig {
    const rawMode = env.HAPI_IOS_PUSH?.trim().toLowerCase() || 'relay'
    const mode = rawMode === 'apns' || rawMode === 'relay' || rawMode === 'off'
        ? rawMode
        : ((): 'relay' => {
            console.warn(`[IosPush] Unknown HAPI_IOS_PUSH value "${rawMode}"; falling back to default "relay"`)
            return 'relay'
        })()

    if (mode === 'off') {
        return { mode: 'off', reason: 'HAPI_IOS_PUSH=off' }
    }

    if (mode === 'relay') {
        const rawUrl = env.HAPI_PUSH_RELAY_URL?.trim()
        return {
            mode: 'relay',
            relayUrl: rawUrl || DEFAULT_PUSH_RELAY_URL,
            source: rawUrl ? 'env' : 'default'
        }
    }

    const keyP8Path = env.APNS_KEY_P8_PATH?.trim() ?? ''
    const keyId = env.APNS_KEY_ID?.trim() ?? ''
    const teamId = env.APNS_TEAM_ID?.trim() ?? ''
    const bundleId = env.APNS_BUNDLE_ID?.trim() ?? ''

    const missing: string[] = []
    if (!keyP8Path) missing.push('APNS_KEY_P8_PATH')
    if (!keyId) missing.push('APNS_KEY_ID')
    if (!teamId) missing.push('APNS_TEAM_ID')
    if (!bundleId) missing.push('APNS_BUNDLE_ID')
    if (missing.length > 0) {
        const reason = `HAPI_IOS_PUSH=apns but missing ${missing.join(', ')}`
        console.warn(`[IosPush] ${reason}; iOS push disabled`)
        return { mode: 'off', reason }
    }

    let keyP8: string
    try {
        keyP8 = readFileSync(keyP8Path, 'utf8')
    } catch (e) {
        const reason = `cannot read APNS_KEY_P8_PATH (${keyP8Path}): ${e instanceof Error ? e.message : e}`
        console.warn(`[IosPush] ${reason}; iOS push disabled`)
        return { mode: 'off', reason }
    }

    const rawEnv = env.APNS_ENV?.trim().toLowerCase()
    const apnsEnv: 'production' | 'sandbox' = rawEnv === 'sandbox' ? 'sandbox' : 'production'

    return {
        mode: 'apns',
        keyP8,
        keyP8Path,
        keyId,
        teamId,
        bundleId,
        env: apnsEnv,
        host: apnsEnv === 'sandbox' ? APNS_SANDBOX_HOST : APNS_PRODUCTION_HOST
    }
}
