package app.hapi.companion

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import app.hapi.companion.di.LocalAppGraph
import app.hapi.companion.di.viewModelFactory
import app.hapi.companion.feature.home.HomePlaceholderScreen
import app.hapi.companion.feature.pairing.ManualEntryScreen
import app.hapi.companion.feature.pairing.PairingScreen
import app.hapi.companion.feature.pairing.PairingUiState
import app.hapi.companion.feature.pairing.PairingViewModel
import app.hapi.companion.feature.pairing.QrScanScreen
import app.hapi.data.auth.AuthTerminalReason
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"

    /** Nested pairing graph (landing ⇄ scan ⇄ manual share one ViewModel). */
    const val PAIRING = "pairing"
    const val PAIRING_LANDING = "pairing/landing"
    const val PAIRING_SCAN = "pairing/scan"
    const val PAIRING_MANUAL = "pairing/manual"
}

/**
 * Root navigation: `pairing` (start when no active hub) ⇄ `home`. Reacts to
 * the graph's cross-cutting flows — terminal auth events and active-hub
 * removal route back to pairing (with an explanatory banner), pending
 * `hapicompanion://bind` deep links route to the pairing confirm card.
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
    LaunchedEffect(registryState.activeHubUrl) {
        if (registryState.activeHubUrl == null) {
            val onPairing = navController.currentDestination
                ?.hierarchy?.any { it.route == Routes.PAIRING } == true
            if (!onPairing) {
                navController.navigateClearingBackStack(Routes.PAIRING)
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.HOME) {
            val activeHubUrl = registryState.activeHubUrl ?: return@composable
            val scope = rememberCoroutineScope()
            // TODO(M2b): replace HomePlaceholder with SessionListScreen.
            HomePlaceholderScreen(
                activeHubUrl = activeHubUrl,
                pairedHubs = registryState.hubs,
                onSwitchHub = { hub -> scope.launch { graph.hubRegistry.setActiveHub(hub) } },
                onPairAnotherHub = { navController.navigate(Routes.PAIRING) },
                onSignOut = { scope.launch { graph.signOut(activeHubUrl) } },
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
