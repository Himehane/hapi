package app.hapi.companion.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import app.hapi.companion.feature.pairing.PairingClient
import app.hapi.companion.feature.pairing.PairingClientFactory
import app.hapi.data.api.HapiApi
import app.hapi.data.auth.AuthEvents
import app.hapi.data.auth.AuthTerminalReason
import app.hapi.data.auth.CredentialStore
import app.hapi.data.auth.EncryptedPrefsCredentialStore
import app.hapi.data.auth.HubRegistry
import app.hapi.data.auth.HubRegistryStorage
import app.hapi.protocol.pairing.BindLink
import app.hapi.protocol.wire.AuthResponse
import app.hapi.protocol.wire.HapiJson
import app.hapi.protocol.wire.HubHealthResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** One terminal auth failure, re-emitted off OkHttp threads as a flow. */
data class AuthTerminal(
    val hubUrl: String,
    val reason: AuthTerminalReason,
)

/** Process-wide Preferences DataStore (hub roster + future app settings). */
private val Context.hapiDataStore by preferencesDataStore(name = "hapi_prefs")

/**
 * Process-singleton graph, hand-rolled (no Hilt by design — see plan track B).
 * Constructed once in [app.hapi.companion.HapiApp]; Compose reads it via
 * [LocalAppGraph]; per-active-hub types live in [HubGraph], swapped by this
 * class whenever `HubRegistry.state`'s active hub changes.
 *
 * Call [start] right after construction: it loads the persisted roster
 * ([ready] flips true) and then keeps [activeHubGraph] in sync with the
 * registry.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    /** App-lifetime scope; nothing here is ever torn down before the process. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The protocol-configured Json (lenient, ignores unknown keys). */
    val json: Json = HapiJson

    val hubRegistryStorage: HubRegistryStorage =
        DataStoreHubRegistryStorage(appContext.hapiDataStore)

    val credentialStore: CredentialStore = EncryptedPrefsCredentialStore(appContext)

    val hubRegistry: HubRegistry = HubRegistry(hubRegistryStorage)

    private val mutableAuthTerminals = MutableSharedFlow<AuthTerminal>(extraBufferCapacity = 16)

    /**
     * Terminal auth failures for any hub (re-pair required). Fired by OkHttp
     * worker threads via [authEvents]; navigation collects and routes to the
     * pairing screen with an explanatory banner.
     */
    val authTerminals: SharedFlow<AuthTerminal> = mutableAuthTerminals.asSharedFlow()

    /** The [AuthEvents] sink every [HubSession][app.hapi.data.HubSession] gets. */
    val authEvents: AuthEvents = AuthEvents { hubUrl, reason ->
        mutableAuthTerminals.tryEmit(AuthTerminal(hubUrl, reason))
    }

    /**
     * The most recent unconsumed `hapicompanion://bind` deep link.
     * MainActivity posts (cold start + onNewIntent); the pairing screen
     * consumes and clears.
     */
    val pendingBindLink = MutableStateFlow<BindLink?>(null)

    /** One-line banner for the pairing screen ("signed out because …"). */
    val pairingNotice = MutableStateFlow<String?>(null)

    /**
     * Bare client for the two pre-pairing endpoints (`GET /health`,
     * `POST /api/auth`) — no interceptors: there are no credentials yet.
     */
    private val pairingHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Builds the pairing probe for a candidate hub URL (normalized upstream). */
    val pairingClientFactory: PairingClientFactory = PairingClientFactory { hubUrl ->
        val api = HapiApi(hubUrl = hubUrl, client = pairingHttpClient)
        object : PairingClient {
            override suspend fun health(): HubHealthResponse = api.health()
            override suspend fun authenticate(accessToken: String): AuthResponse =
                api.authenticate(accessToken)
        }
    }

    private val mutableActiveHubGraph = MutableStateFlow<HubGraph?>(null)

    /** Per-active-hub graph; null while unpaired. Recreated on hub switch. */
    val activeHubGraph: StateFlow<HubGraph?> = mutableActiveHubGraph.asStateFlow()

    private val mutableReady = MutableStateFlow(false)

    /** False until the persisted hub roster is loaded (gate the first frame). */
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    /** Idempotent-enough for the single Application.onCreate call site. */
    fun start() {
        scope.launch {
            hubRegistry.load()
            mutableReady.value = true
            hubRegistry.state
                .map { it.activeHubUrl }
                .distinctUntilChanged()
                .collect { activeHubUrl -> swapActiveHub(activeHubUrl) }
        }
    }

    /**
     * Removes [hubUrl]'s pairing: credentials wiped, roster entry dropped
     * (the registry auto-activates the next hub, or none). The active
     * [HubGraph] swap follows via the registry observer.
     */
    suspend fun signOut(hubUrl: String) {
        // TODO(M4a): DELETE /api/devices/register first, while the JWT works.
        withContext(Dispatchers.IO) { credentialStore.delete(hubUrl) }
        hubRegistry.removeHub(hubUrl)
    }

    /** Sequential by construction: only the [start] collector calls this. */
    private fun swapActiveHub(activeHubUrl: String?) {
        mutableActiveHubGraph.value?.close()
        mutableActiveHubGraph.value = activeHubUrl?.let { hubUrl ->
            HubGraph(
                hubUrl = hubUrl,
                credentialStore = credentialStore,
                authEvents = authEvents,
                cacheDir = appContext.cacheDir,
            )
        }
    }
}
