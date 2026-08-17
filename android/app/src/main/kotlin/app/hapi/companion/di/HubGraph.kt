package app.hapi.companion.di

import app.hapi.data.HubSession
import app.hapi.data.auth.AuthEvents
import app.hapi.data.auth.CredentialStore
import app.hapi.data.sse.OkHttpSseTransport
import app.hapi.data.sse.SseEngine
import app.hapi.data.sse.SseSubscriptionKey
import app.hapi.data.sse.SseTokenProvider
import app.hapi.data.sse.SyncEventRouter
import app.hapi.data.sse.SyncTargets
import app.hapi.protocol.wire.SyncEvent
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Everything scoped to the **active** hub. [AppGraph] creates one per
 * active-hub change (observing `HubRegistry.state`) and [close]s the previous
 * one — nothing here survives a hub switch.
 *
 * Wiring: [HubSession] (REST + silent re-auth, from `:core:data`) → its
 * `ensureFreshToken` adapts to the [SseEngine]'s token provider (SSE
 * authenticates only at connect time) → [SyncEventRouter] fans engine events
 * out to [SyncTargets], which is still a no-op stub.
 */
class HubGraph(
    hubUrl: String,
    credentialStore: CredentialStore,
    authEvents: AuthEvents,
    /** App cache dir; the per-hub generated-image OkHttp cache nests inside. */
    cacheDir: File,
) : Closeable {

    /** Child of nothing on purpose: cancelled explicitly in [close]. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session: HubSession = HubSession(
        hubUrl = hubUrl,
        credentialStore = credentialStore,
        authEvents = authEvents,
        imageCacheDir = File(File(cacheDir, "hub-images"), cacheDirNameFor(hubUrl)),
    )

    /** Normalized origin (via [HubSession]'s own normalization). */
    val hubUrl: String get() = session.hubUrl

    val sseEngine: SseEngine = SseEngine(
        baseUrl = session.hubUrl,
        transport = OkHttpSseTransport(),
        tokenProvider = SessionTokenProvider(session, credentialStore),
        scope = scope,
    )

    // TODO(M2b): replace with the real store-backed targets (session list /
    // message windows) and subscribe the engine's Global key on foreground.
    val syncTargets: SyncTargets = NoopSyncTargets

    val syncEventRouter: SyncEventRouter = SyncEventRouter(syncTargets)

    override fun close() {
        scope.cancel()
        session.close()
    }

    private companion object {
        /**
         * Filesystem-safe per-hub cache directory name. Distinct hubs must
         * map to distinct directories (OkHttp caches require exclusive dirs);
         * origins differing only in `[^A-Za-z0-9._-]` characters cannot
         * collide because those are exactly `://` and `:`.
         */
        fun cacheDirNameFor(hubUrl: String): String =
            hubUrl.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

/**
 * Adapts [HubSession.ensureFreshToken] to the engine's [SseTokenProvider].
 *
 * `forceRefresh` (the hub just 401'd the previous token) drops both JWT
 * caches — the persisted copy and the authenticator's in-memory one — so
 * `ensureFreshToken` has to do a genuine `POST /api/auth` exchange instead of
 * returning a token the hub already rejected (rotated jwt-secret, clock skew).
 */
class SessionTokenProvider(
    private val session: HubSession,
    private val credentialStore: CredentialStore,
) : SseTokenProvider {

    override suspend fun freshToken(forceRefresh: Boolean): String? {
        if (forceRefresh) {
            withContext(Dispatchers.IO) {
                credentialStore.get(session.hubUrl)?.let { credentials ->
                    credentialStore.set(credentials.copy(jwt = null, jwtObtainedAtMs = null))
                }
            }
            session.authenticator.clearCachedJwt()
        }
        return session.ensureFreshToken()
    }
}

/** Stub sink until the M2b stores land; events are dropped on the floor. */
object NoopSyncTargets : SyncTargets {
    override fun onSessionEvent(scope: SseSubscriptionKey, event: SyncEvent) = Unit
    override fun onMachineEvent(scope: SseSubscriptionKey, event: SyncEvent.MachineUpdated) = Unit
    override fun onMessageEvent(scope: SseSubscriptionKey, event: SyncEvent) = Unit
    override fun onToast(event: SyncEvent.Toast) = Unit
    override fun requestFullResync(scope: SseSubscriptionKey) = Unit
}
