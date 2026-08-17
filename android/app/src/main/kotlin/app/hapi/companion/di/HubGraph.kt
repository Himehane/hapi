package app.hapi.companion.di

import android.content.Context
import app.hapi.data.HubSession
import app.hapi.data.auth.AuthEvents
import app.hapi.data.auth.CredentialStore
import app.hapi.data.sse.OkHttpSseTransport
import app.hapi.data.sse.SseEngine
import app.hapi.data.sse.SseTokenProvider
import app.hapi.data.sse.SyncEventRouter
import app.hapi.data.sse.SyncTargets
import app.hapi.data.store.LastSeenStore
import app.hapi.data.store.MachineStore
import app.hapi.data.store.MessageWindowStores
import app.hapi.data.store.SessionStore
import app.hapi.data.store.StoreSyncTargets
import app.hapi.data.store.WindowSnapshots
import coil.ImageLoader
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
 * out to [StoreSyncTargets], which feeds the per-hub stores below. Screens
 * own the actual SSE subscriptions (session list = global pipe, open chat =
 * its session pipe), all against this one engine.
 */
class HubGraph(
    hubUrl: String,
    credentialStore: CredentialStore,
    authEvents: AuthEvents,
    /** Application context: Coil loader + cache/files roots derive from it. */
    context: Context,
) : Closeable {

    /** Child of nothing on purpose: cancelled explicitly in [close]. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session: HubSession = HubSession(
        hubUrl = hubUrl,
        credentialStore = credentialStore,
        authEvents = authEvents,
        imageCacheDir = File(File(context.cacheDir, "hub-images"), dirNameFor(hubUrl)),
    )

    /** Normalized origin (via [HubSession]'s own normalization). */
    val hubUrl: String get() = session.hubUrl

    val sseEngine: SseEngine = SseEngine(
        baseUrl = session.hubUrl,
        transport = OkHttpSseTransport(),
        tokenProvider = SessionTokenProvider(session, credentialStore),
        scope = scope,
    )

    /** Per-hub snapshot root (filesDir — survives cache pressure). */
    private val snapshotDir: File = File(File(context.filesDir, "hubs"), dirNameFor(session.hubUrl))

    val sessionStore: SessionStore = SessionStore(session.api, scope, snapshotDir)

    val machineStore: MachineStore = MachineStore(session.api, scope, snapshotDir)

    val lastSeenStore: LastSeenStore = LastSeenStore(scope, snapshotDir)

    val messageWindows: MessageWindowStores = MessageWindowStores(
        api = session.api,
        scope = scope,
        snapshots = WindowSnapshots(File(snapshotDir, "windows")),
    )

    val syncTargets: SyncTargets =
        StoreSyncTargets(sessionStore, machineStore, scope, messageWindows)

    val syncEventRouter: SyncEventRouter = SyncEventRouter(syncTargets)

    /**
     * Loads `/api/sessions/:id/generated-images/:imageId` (and any other hub
     * URL) through the authed image client: JWT interceptor + silent 401
     * re-auth + the per-hub 256 MB disk cache (images are immutable + ETagged).
     */
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .okHttpClient(session.imageClient)
        .build()

    /** Absolute URL of a generated image, for [imageLoader]. */
    fun generatedImageUrl(sessionId: String, imageId: String): String =
        "${session.hubUrl}/api/sessions/$sessionId/generated-images/$imageId"

    override fun close() {
        scope.cancel()
        imageLoader.shutdown()
        session.close()
    }

    private companion object {
        /**
         * Filesystem-safe per-hub directory name. Distinct hubs must map to
         * distinct directories (OkHttp caches require exclusive dirs);
         * origins differing only in `[^A-Za-z0-9._-]` characters cannot
         * collide because those are exactly `://` and `:`.
         */
        fun dirNameFor(hubUrl: String): String =
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
