package app.hapi.companion

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.hapi.companion.di.AppGraph
import app.hapi.companion.di.HubGraph
import app.hapi.companion.di.LocalAppGraph
import app.hapi.companion.di.viewModelFactory
import app.hapi.companion.feature.chat.ChatMedia
import app.hapi.companion.feature.chat.ChatScreen
import app.hapi.companion.feature.chat.ChatViewModel
import app.hapi.companion.feature.chat.composer.DictationController
import app.hapi.companion.feature.chat.composer.HapiDictationApi
import app.hapi.companion.feature.chat.composer.MediaRecorderDictation
import app.hapi.companion.feature.files.ApiFilesGateway
import app.hapi.companion.feature.files.FileViewerScreen
import app.hapi.companion.feature.files.FileViewerViewModel
import app.hapi.companion.feature.files.FilesScreen
import app.hapi.companion.feature.files.FilesViewModel
import app.hapi.companion.feature.files.ViewerMode
import app.hapi.companion.feature.home.HomeScreen
import app.hapi.companion.feature.newsession.ApiNewSessionGateway
import app.hapi.companion.feature.newsession.NewSessionPrefs
import app.hapi.companion.feature.newsession.NewSessionScreen
import app.hapi.companion.feature.newsession.NewSessionViewModel
import app.hapi.companion.feature.pairing.ManualEntryScreen
import app.hapi.companion.feature.pairing.PairingScreen
import app.hapi.companion.feature.pairing.PairingUiState
import app.hapi.companion.feature.pairing.PairingViewModel
import app.hapi.companion.feature.pairing.QrScanScreen
import app.hapi.companion.feature.scratchlist.ContentResolverAttachmentImporter
import app.hapi.companion.feature.scratchlist.ScratchlistMedia
import app.hapi.companion.feature.scratchlist.ScratchlistScreen
import app.hapi.companion.feature.scratchlist.ScratchlistViewModel
import app.hapi.companion.feature.sessions.SessionListViewModel
import app.hapi.companion.feature.settings.SettingsScreen
import app.hapi.companion.feature.settings.SettingsViewModel
import app.hapi.companion.feature.settings.StorageScreen
import app.hapi.companion.feature.settings.StorageViewModel
import app.hapi.companion.feature.settings.UsageScreen
import app.hapi.companion.feature.settings.UsageViewModel
import app.hapi.data.auth.AuthTerminalReason
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Routes {
    const val HOME = "home"

    /** Read-only chat (B-M2d2). */
    const val CHAT = "chat/{sessionId}"

    fun chat(sessionId: String) = "chat/$sessionId"

    /** Session files browser (B-M4c): Changes / Browse / Search tabs. */
    const val FILES = "chat/{sessionId}/files"

    fun files(sessionId: String) = "chat/$sessionId/files"

    /**
     * File viewer (B-M4c). `path` is base64url (no padding) so slashes and
     * specials survive the route pattern — the web twin does the same
     * (`encodeBase64` in `files.tsx`). `staged` picks the diff side, `mode`
     * (`diff`/`file`) the initial mode, `line` a citation line hint.
     */
    const val FILE_VIEWER = "chat/{sessionId}/file?path={path}&staged={staged}&mode={mode}&line={line}"

    fun fileViewer(
        sessionId: String,
        path: String,
        staged: Boolean? = null,
        mode: String? = null,
        line: Int? = null,
    ): String = buildString {
        append("chat/").append(sessionId).append("/file?path=").append(encodeFilePath(path))
        staged?.let { append("&staged=").append(it) }
        mode?.let { append("&mode=").append(it) }
        line?.let { append("&line=").append(it) }
    }

    fun encodeFilePath(path: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))

    fun decodeFilePath(encoded: String): String? = try {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Per-session scratchlist workbench (B-M4d), pushed above its chat. */
    const val SCRATCHLIST = "chat/{sessionId}/scratchlist"

    fun scratchlist(sessionId: String) = "chat/$sessionId/scratchlist"

    /** New-session form (B-M3d); optional machine preselect. */
    const val NEW_SESSION = "newSession?machineId={machineId}"

    fun newSession(machineId: String? = null) =
        if (machineId == null) "newSession" else "newSession?machineId=$machineId"

    /** Nested pairing graph (landing ⇄ scan ⇄ manual share one ViewModel). */
    const val PAIRING = "pairing"
    const val PAIRING_LANDING = "pairing/landing"
    const val PAIRING_SCAN = "pairing/scan"
    const val PAIRING_MANUAL = "pairing/manual"

    /** Settings scaffold + owner-only dashboards (B-M4e). */
    const val SETTINGS = "settings"
    const val SETTINGS_USAGE = "settings/usage"
    const val SETTINGS_STORAGE = "settings/storage"
}

/**
 * Root navigation: `pairing` (start when no active hub) ⇄ `home` (session
 * list) → `chat/{sessionId}`. Reacts to the graph's cross-cutting flows —
 * terminal auth events and active-hub removal route back to pairing (with an
 * explanatory banner), pending `hapicompanion://bind` deep links route to the
 * pairing confirm card, and a hub switch pops any open chat (its session
 * belongs to the previous hub).
 */
@Composable
fun HapiNavigation() {
    val graph = LocalAppGraph.current
    val ready by graph.ready.collectAsState()
    if (!ready) {
        // Sub-frame gap while the persisted hub roster loads.
        Surface(modifier = Modifier.fillMaxSize()) {}
        return
    }

    val navController = rememberNavController()
    val registryState by graph.hubRegistry.state.collectAsState()
    val activeHubGraph by graph.activeHubGraph.collectAsState()
    val startDestination = remember {
        if (graph.hubRegistry.activeHubUrl == null) Routes.PAIRING else Routes.HOME
    }

    // Silent re-auth gave up for good: back to pairing, with the reason.
    LaunchedEffect(navController) {
        graph.authTerminals.collect { terminal ->
            if (terminal.hubUrl == graph.hubRegistry.activeHubUrl) {
                graph.pairingNotice.value = terminalNotice(terminal.reason)
                navController.navigateClearingBackStack(Routes.PAIRING)
            }
        }
    }

    // A bind deep link arrived (cold start or onNewIntent): surface the
    // pairing screen; the landing destination consumes the link itself.
    val pendingBind by graph.pendingBindLink.collectAsState()
    LaunchedEffect(pendingBind) {
        if (pendingBind != null) {
            navController.navigateClearingBackStack(Routes.PAIRING)
        }
    }

    // Last hub signed out (or roster wiped): nothing to show but pairing.
    // Any other active-hub change invalidates an open chat (old hub's session).
    LaunchedEffect(registryState.activeHubUrl) {
        val activeHubUrl = registryState.activeHubUrl
        if (activeHubUrl == null) {
            val onPairing = navController.currentDestination
                ?.hierarchy?.any { it.route == Routes.PAIRING } == true
            if (!onPairing) {
                navController.navigateClearingBackStack(Routes.PAIRING)
            }
        } else if (
            navController.currentDestination?.route in
                setOf(Routes.CHAT, Routes.FILES, Routes.FILE_VIEWER, Routes.SCRATCHLIST)
        ) {
            navController.popBackStack(Routes.HOME, inclusive = false)
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.HOME) {
            val activeHubUrl = registryState.activeHubUrl ?: return@composable
            val hubGraph = activeHubGraph ?: return@composable
            val scope = rememberCoroutineScope()
            val holder = viewModel<SessionListViewModelHolder>(
                key = "sessions:${hubGraph.hubUrl}",
                factory = viewModelFactory { SessionListViewModelHolder(hubGraph) },
            )
            HomeScreen(
                viewModel = holder.viewModel,
                activeHubUrl = activeHubUrl,
                pairedHubs = registryState.hubs,
                onSwitchHub = { hub -> scope.launch { graph.hubRegistry.setActiveHub(hub) } },
                onPairAnotherHub = { navController.navigate(Routes.PAIRING) },
                onSignOut = { scope.launch { graph.signOut(activeHubUrl) } },
                onOpenSession = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                onNewSession = { navController.navigate(Routes.newSession()) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            val hubGraph = activeHubGraph ?: return@composable
            val holder = viewModel<SettingsViewModelHolder>(
                key = "settings:${hubGraph.hubUrl}",
                factory = viewModelFactory { SettingsViewModelHolder(graph, hubGraph) },
            )
            SettingsScreen(
                viewModel = holder.viewModel,
                onOpenUsage = { navController.navigate(Routes.SETTINGS_USAGE) },
                onOpenStorage = { navController.navigate(Routes.SETTINGS_STORAGE) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_USAGE) {
            val hubGraph = activeHubGraph ?: return@composable
            val holder = viewModel<UsageViewModelHolder>(
                key = "settingsUsage:${hubGraph.hubUrl}",
                factory = viewModelFactory { UsageViewModelHolder(hubGraph) },
            )
            UsageScreen(
                viewModel = holder.viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS_STORAGE) {
            val hubGraph = activeHubGraph ?: return@composable
            val holder = viewModel<StorageViewModelHolder>(
                key = "settingsStorage:${hubGraph.hubUrl}",
                factory = viewModelFactory { StorageViewModelHolder(hubGraph) },
            )
            StorageScreen(
                viewModel = holder.viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            val hubGraph = activeHubGraph ?: return@composable
            val appContext = LocalContext.current.applicationContext
            val holder = viewModel<ChatViewModelHolder>(
                key = "chat:${hubGraph.hubUrl}:$sessionId",
                factory = viewModelFactory { ChatViewModelHolder(hubGraph, sessionId, appContext) },
            )
            ChatScreen(
                viewModel = holder.viewModel,
                media = remember(hubGraph, sessionId) {
                    ChatMedia(hubGraph.imageLoader) { imageId ->
                        hubGraph.generatedImageUrl(sessionId, imageId)
                    }
                },
                onBack = { navController.popBackStack() },
                onNavigateToSession = { supersededId ->
                    // Resume/reopen handed the conversation to a different id:
                    // replace this chat entry with the superseding session.
                    navController.navigate(Routes.chat(supersededId)) {
                        popUpTo(Routes.CHAT) { inclusive = true }
                    }
                },
                dictation = holder.dictation,
                onOpenFiles = { navController.navigate(Routes.files(sessionId)) },
                onOpenFile = { path, line ->
                    // Chat citations open full mode; the cited line renders as
                    // a hint chip (no per-line highlight — B-M4c trade-off).
                    navController.navigate(Routes.fileViewer(sessionId, path, mode = "file", line = line))
                },
                onOpenScratchlist = { navController.navigate(Routes.scratchlist(sessionId)) },
            )
        }

        composable(
            route = Routes.FILES,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            val hubGraph = activeHubGraph ?: return@composable
            val holder = viewModel<FilesViewModelHolder>(
                key = "files:${hubGraph.hubUrl}:$sessionId",
                factory = viewModelFactory { FilesViewModelHolder(hubGraph, sessionId) },
            )
            FilesScreen(
                viewModel = holder.viewModel,
                onBack = { navController.popBackStack() },
                onOpenFile = { path, staged ->
                    navController.navigate(Routes.fileViewer(sessionId, path, staged = staged))
                },
            )
        }

        composable(
            route = Routes.FILE_VIEWER,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("path") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("staged") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("mode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("line") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            val hubGraph = activeHubGraph ?: return@composable
            val encodedPath = entry.arguments?.getString("path") ?: return@composable
            val path = Routes.decodeFilePath(encodedPath) ?: return@composable
            val staged = entry.arguments?.getString("staged")?.toBooleanStrictOrNull()
            val mode = when (entry.arguments?.getString("mode")) {
                "diff" -> ViewerMode.DIFF
                "file" -> ViewerMode.FILE
                else -> null
            }
            val line = entry.arguments?.getString("line")?.toIntOrNull()
            val holder = viewModel<FileViewerViewModelHolder>(
                key = "file:${hubGraph.hubUrl}:$sessionId:$encodedPath:$staged:$mode",
                factory = viewModelFactory {
                    FileViewerViewModelHolder(hubGraph, sessionId, path, staged, mode, line)
                },
            )
            FileViewerScreen(
                viewModel = holder.viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.SCRATCHLIST,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId") ?: return@composable
            val hubGraph = activeHubGraph ?: return@composable
            val appContext = LocalContext.current.applicationContext
            val holder = viewModel<ScratchlistViewModelHolder>(
                key = "scratchlist:${hubGraph.hubUrl}:$sessionId",
                factory = viewModelFactory { ScratchlistViewModelHolder(hubGraph, sessionId, appContext) },
            )
            // "Send to composer" reuses the chat ViewModel of the entry below
            // this route (same holder key + the chat entry as owner), so the
            // inserted text lands in the live composer state, not a stale
            // draft. Guarded: without a chat below, the affordance hides.
            val chatEntry = remember(entry) {
                runCatching { navController.getBackStackEntry(Routes.CHAT) }.getOrNull()
            }
            val chatHolder = chatEntry?.let { owner ->
                viewModel<ChatViewModelHolder>(
                    viewModelStoreOwner = owner,
                    key = "chat:${hubGraph.hubUrl}:$sessionId",
                    factory = viewModelFactory { ChatViewModelHolder(hubGraph, sessionId, appContext) },
                )
            }
            ScratchlistScreen(
                viewModel = holder.viewModel,
                media = remember(hubGraph, sessionId) {
                    ScratchlistMedia(hubGraph.imageLoader) { attachmentId ->
                        hubGraph.scratchlistAttachmentUrl(sessionId, attachmentId)
                    }
                },
                onBack = { navController.popBackStack() },
                onSendToComposer = chatHolder?.let { chat ->
                    { scratchEntry ->
                        chat.viewModel.insertComposerText(scratchEntry.text)
                        navController.popBackStack()
                    }
                },
            )
        }

        composable(
            route = Routes.NEW_SESSION,
            arguments = listOf(
                navArgument("machineId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val hubGraph = activeHubGraph ?: return@composable
            val machineId = entry.arguments?.getString("machineId")
            val holder = viewModel<NewSessionViewModelHolder>(
                key = "newSession:${hubGraph.hubUrl}",
                factory = viewModelFactory {
                    NewSessionViewModelHolder(hubGraph, graph.newSessionPrefs, machineId)
                },
            )
            NewSessionScreen(
                viewModel = holder.viewModel,
                onBack = { navController.popBackStack() },
                onCreated = { sessionId ->
                    // Navigate-replace: the form pops so back from the new
                    // chat lands on the session list, not a stale form.
                    navController.navigate(Routes.chat(sessionId)) {
                        popUpTo(Routes.HOME)
                        launchSingleTop = true
                    }
                },
            )
        }

        navigation(startDestination = Routes.PAIRING_LANDING, route = Routes.PAIRING) {
            composable(Routes.PAIRING_LANDING) { entry ->
                val viewModel = pairingViewModel(navController, entry)
                val state by viewModel.state.collectAsState()
                val prefill by viewModel.prefill.collectAsState()
                val notice by graph.pairingNotice.collectAsState()

                // Consume the deep link into the shared pairing ViewModel.
                LaunchedEffect(pendingBind) {
                    pendingBind?.let { link ->
                        viewModel.prefillFromLink(link)
                        graph.pendingBindLink.value = null
                    }
                }
                NavigateHomeOnSuccess(navController, state)

                PairingScreen(
                    state = state,
                    prefill = prefill,
                    notice = notice,
                    onDismissNotice = { graph.pairingNotice.value = null },
                    onScanQr = { navController.navigate(Routes.PAIRING_SCAN) },
                    onManualEntry = { navController.navigate(Routes.PAIRING_MANUAL) },
                    onPairPrefill = viewModel::pairFromPrefill,
                    onSwitchToPrefilledHub = viewModel::switchToPrefilledHub,
                    onDismissPrefill = viewModel::dismissPrefill,
                    onDismissError = viewModel::dismissError,
                )
            }

            composable(Routes.PAIRING_SCAN) { entry ->
                val viewModel = pairingViewModel(navController, entry)
                val state by viewModel.state.collectAsState()
                NavigateHomeOnSuccess(navController, state)

                QrScanScreen(
                    state = state,
                    onPairLink = { link -> viewModel.pair(link.hubUrl, link.accessToken) },
                    onManualEntry = { navController.navigate(Routes.PAIRING_MANUAL) },
                    onBack = { navController.popBackStack() },
                    onDismissError = viewModel::dismissError,
                )
            }

            composable(Routes.PAIRING_MANUAL) { entry ->
                val viewModel = pairingViewModel(navController, entry)
                val state by viewModel.state.collectAsState()
                NavigateHomeOnSuccess(navController, state)

                ManualEntryScreen(
                    state = state,
                    onPair = viewModel::pair,
                    onBack = { navController.popBackStack() },
                    onDismissError = viewModel::dismissError,
                )
            }
        }
    }
}

// ------------------------------------------------------ ViewModel holders --

/**
 * Androidx-lifecycle shell around the plain [SessionListViewModel]: survives
 * config changes with the nav entry, owns the combine scope, and tears the
 * global SSE pipe down when the entry clears. Keyed per hub so a hub switch
 * builds a fresh one against the new [HubGraph].
 */
private class SessionListViewModelHolder(hubGraph: HubGraph) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = SessionListViewModel(
        sessionStore = hubGraph.sessionStore,
        machineStore = hubGraph.machineStore,
        lastSeenStore = hubGraph.lastSeenStore,
        scope = scope,
        hubKey = hubGraph.hubUrl,
    )

    override fun onCleared() {
        viewModel.stop()
        scope.cancel()
    }
}

/** Same shell for the per-session [ChatViewModel] (+ its dictation controller). */
private class ChatViewModelHolder(
    hubGraph: HubGraph,
    sessionId: String,
    appContext: Context,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = ChatViewModel(
        sessionId = sessionId,
        api = hubGraph.session.api,
        sessionStore = hubGraph.sessionStore,
        machineStore = hubGraph.machineStore,
        lastSeenStore = hubGraph.lastSeenStore,
        messageWindows = hubGraph.messageWindows,
        sseEngine = hubGraph.sseEngine,
        syncTargets = hubGraph.syncTargets,
        scope = scope,
        drafts = hubGraph.chatDrafts,
        scratchlist = hubGraph.scratchlistStore,
    )

    /**
     * Holder-scoped so a recording survives rotation; [onCleared] discards
     * any take still open when the screen goes away for good.
     */
    val dictation = DictationController(
        api = HapiDictationApi(hubGraph.session.api),
        recorder = MediaRecorderDictation(appContext),
        scope = scope,
    )

    override fun onCleared() {
        dictation.cancel()
        // Leaving the chat for good (not rotation): un-sent uploaded
        // attachments are discarded after a best-effort hub delete (B-M3f;
        // attachments are not part of drafts v1).
        viewModel.discardAttachments()
        viewModel.stop()
        scope.cancel()
    }
}

/** Shell for the files browser (B-M4c); keyed per hub+session. */
private class FilesViewModelHolder(hubGraph: HubGraph, sessionId: String) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = FilesViewModel(
        sessionId = sessionId,
        gateway = ApiFilesGateway(hubGraph.session.api),
        scope = scope,
    )

    override fun onCleared() {
        scope.cancel()
    }
}

/** Shell for the single-file viewer (B-M4c). */
private class FileViewerViewModelHolder(
    hubGraph: HubGraph,
    sessionId: String,
    path: String,
    staged: Boolean?,
    mode: ViewerMode?,
    line: Int?,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = FileViewerViewModel(
        sessionId = sessionId,
        path = path,
        initialStaged = staged,
        initialMode = mode,
        focusLine = line,
        gateway = ApiFilesGateway(hubGraph.session.api),
        scope = scope,
    )

    override fun onCleared() {
        scope.cancel()
    }
}

/**
 * Shells for the settings graph (B-M4e). Settings/usage/storage are hub-scoped
 * (keys include the hub origin): a hub switch swaps in fresh state.
 */
private class SettingsViewModelHolder(graph: AppGraph, hubGraph: HubGraph) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = SettingsViewModel(
        themePrefs = graph.themePrefs,
        languagePrefs = graph.languagePrefs,
        hubUrl = hubGraph.hubUrl,
        currentJwt = {
            // Store reads may block (EncryptedSharedPreferences) and the
            // fallback exchange is network I/O.
            withContext(Dispatchers.IO) {
                hubGraph.session.authenticator.currentJwt() ?: hubGraph.session.ensureFreshToken()
            }
        },
        fetchHealth = { hubGraph.session.api.health() },
        scope = scope,
    )

    override fun onCleared() {
        scope.cancel()
    }
}

private class UsageViewModelHolder(hubGraph: HubGraph) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = UsageViewModel(
        gateway = hubGraph.session.api::getUsageSummary,
        scope = scope,
    )

    override fun onCleared() {
        scope.cancel()
    }
}

private class StorageViewModelHolder(hubGraph: HubGraph) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = StorageViewModel(
        gateway = hubGraph.session.api::getSqliteStorageUsage,
        scope = scope,
    )

    override fun onCleared() {
        scope.cancel()
    }
}

/** Shell for the per-session scratchlist workbench (B-M4d). */
private class ScratchlistViewModelHolder(
    hubGraph: HubGraph,
    sessionId: String,
    appContext: Context,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = ScratchlistViewModel(
        sessionId = sessionId,
        store = hubGraph.scratchlistStore,
        scope = scope,
        importer = ContentResolverAttachmentImporter(appContext),
    )

    override fun onCleared() {
        viewModel.stop()
        scope.cancel()
    }
}

/** Shell for the create form (B-M3d); draft persistence survives via prefs. */
private class NewSessionViewModelHolder(
    hubGraph: HubGraph,
    prefs: NewSessionPrefs,
    initialMachineId: String?,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val viewModel = NewSessionViewModel(
        gateway = ApiNewSessionGateway(hubGraph.session.api),
        machineStore = hubGraph.machineStore,
        prefs = prefs,
        scope = scope,
        initialMachineId = initialMachineId,
    )

    override fun onCleared() {
        scope.cancel()
    }
}

/**
 * The one [PairingViewModel], scoped to the pairing nav-graph entry so the
 * landing/scan/manual destinations share pairing state and it is cleared as
 * soon as the graph pops.
 */
@Composable
private fun pairingViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): PairingViewModel {
    val graph = LocalAppGraph.current
    val parentEntry = remember(entry) { navController.getBackStackEntry(Routes.PAIRING) }
    return viewModel(
        viewModelStoreOwner = parentEntry,
        factory = viewModelFactory {
            PairingViewModel(
                clientFactory = graph.pairingClientFactory,
                credentialStore = graph.credentialStore,
                registry = graph.hubRegistry,
            )
        },
    )
}

/** Pairing finished: clear any stale notice and land on home, stack reset. */
@Composable
private fun NavigateHomeOnSuccess(navController: NavHostController, state: PairingUiState) {
    val graph = LocalAppGraph.current
    LaunchedEffect(state) {
        if (state is PairingUiState.Success) {
            graph.pairingNotice.value = null
            navController.navigateClearingBackStack(Routes.HOME)
        }
    }
}

private fun NavHostController.navigateClearingBackStack(route: String) {
    navigate(route) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Why the app fell back to pairing. Plain English until the M5 i18n pass
 * (emitted outside composition, so no `stringResource` here).
 */
private fun terminalNotice(reason: AuthTerminalReason): String = when (reason) {
    AuthTerminalReason.ACCESS_TOKEN_REJECTED ->
        "This device's pairing was revoked — the hub's access token changed. Pair again to continue."
    AuthTerminalReason.RETRY_EXHAUSTED ->
        "The hub kept rejecting this device's session. Pair again to continue."
    AuthTerminalReason.MISSING_CREDENTIALS ->
        "The stored credentials for this hub went missing. Pair again to continue."
}
