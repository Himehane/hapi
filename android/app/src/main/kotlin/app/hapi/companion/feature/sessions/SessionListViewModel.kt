package app.hapi.companion.feature.sessions

import app.hapi.data.sse.SseEngine
import app.hapi.data.sse.SseSubscriptionKey
import app.hapi.data.sse.SyncEventRouter
import app.hapi.data.store.LastSeenStore
import app.hapi.data.store.MachineListStore
import app.hapi.data.store.MessageWindowStores
import app.hapi.data.store.SessionListStore
import app.hapi.data.store.StoreSyncTargets
import app.hapi.protocol.wire.Machine
import app.hapi.protocol.wire.SessionSummary
import app.hapi.protocol.wire.SyncEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Sessions whose metadata carries no machine id group under this filter id. */
const val UNKNOWN_MACHINE_ID: String = "__unknown__"

/** One rendered list row: the summary plus everything derived for display. */
data class SessionRowUi(
    val summary: SessionSummary,
    /** `getSessionTitle` port: name → summary text → path tail → id prefix. */
    val title: String,
    /** Secondary line: summary text when it is not already the title, else the path. */
    val subtitle: String?,
    val machineLabel: String?,
    /** Raw flavor id (`claude`, `codex`, …); resolve labels via the catalog. */
    val flavor: String?,
    val unread: Boolean,
) {
    val id: String get() = summary.id
}

data class MachineFilterUi(
    /** Machine id or [UNKNOWN_MACHINE_ID]. */
    val id: String,
    val label: String,
    val sessionCount: Int,
)

data class SessionListUiState(
    val rows: List<SessionRowUi>,
    /** Render the chip bar only when at least two machines have sessions. */
    val machineFilters: List<MachineFilterUi>,
    /** `null` = All. Always one of [machineFilters] ids (stale picks fall back). */
    val activeMachineFilter: String?,
    val isRefreshing: Boolean,
    /** True once either the snapshot or a refresh produced a list. */
    val hasLoaded: Boolean,
    /** Last refresh failed — show the offline banner over snapshot data. */
    val isOffline: Boolean,
) {
    val showMachineFilterBar: Boolean get() = machineFilters.size >= 2
}

/**
 * Session-list state machine: combines [SessionListStore] / [MachineListStore]
 * / [LastSeenStore] with the machine-filter selection into [uiState], owns the
 * global SSE subscription while started, and forwards pin/archive actions with
 * store-side optimistic updates.
 *
 * Plain constructor — no Android dependency, so JVM tests drive it with fake
 * stores. Navigation hosts it behind a per-hub lifecycle holder built from
 * `HubGraph`; the screen calls [start]/[stop] with its composition
 * (foreground/background belongs to `SseEngine.setLifecycleForeground`,
 * wired at the Application level in a later package).
 */
class SessionListViewModel(
    private val sessionStore: SessionListStore,
    private val machineStore: MachineListStore,
    private val lastSeenStore: LastSeenStore,
    private val sseEngine: SseEngine,
    private val scope: CoroutineScope,
    /** Last-seen baseline scope, e.g. the hub origin. */
    private val hubKey: String = "default",
    /** Open message windows, so global-pipe message events keep them fresh (M2c wiring). */
    private val messageWindows: MessageWindowStores? = null,
    private val onToast: (SyncEvent.Toast) -> Unit = {},
) {
    private val machineFilter = MutableStateFlow<String?>(null)
    private val isRefreshing = MutableStateFlow(false)
    private val isOffline = MutableStateFlow(false)
    private val hasRefreshedOnce = MutableStateFlow(false)

    private val _errors = MutableSharedFlow<SessionListError>(extraBufferCapacity = 8)

    /** Transient action failures (pin/archive) for a snackbar. */
    val errors: SharedFlow<SessionListError> = _errors.asSharedFlow()

    private val router =
        SyncEventRouter(StoreSyncTargets(sessionStore, machineStore, scope, messageWindows, onToast))
    private var sseJob: Job? = null
    private var refreshJob: Job? = null

    val uiState: StateFlow<SessionListUiState> = combine(
        sessionStore.sessions,
        machineStore.machines,
        lastSeenStore.state,
        machineFilter,
        combine(isRefreshing, isOffline, hasRefreshedOnce) { refreshing, offline, loaded ->
            Triple(refreshing, offline, loaded)
        },
    ) { sessions, machines, lastSeen, filter, (refreshing, offline, refreshedOnce) ->
        buildUiState(
            sessions = sessions,
            machines = machines,
            lastSeen = lastSeen.lastSeen,
            filter = filter,
            isRefreshing = refreshing,
            isOffline = offline,
            hasLoaded = refreshedOnce || sessions.isNotEmpty(),
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = SessionListUiState(
            rows = emptyList(),
            machineFilters = emptyList(),
            activeMachineFilter = null,
            isRefreshing = false,
            hasLoaded = sessionStore.sessions.value.isNotEmpty(),
            isOffline = false,
        ),
    )

    /**
     * Opens the global SSE pipe (dual-subscription model: this connection
     * drives the list; the open chat adds its own in M2d2) and kicks an
     * initial refresh. Safe to call repeatedly.
     */
    fun start() {
        if (sseJob?.isActive == true) return
        val key = SseSubscriptionKey.Global
        sseJob = scope.launch {
            sseEngine.events(key)
                // Subscribe only after this collector is registered — the
                // engine's SharedFlow has zero replay, so a handshake emitted
                // before registration would be lost.
                .onSubscription { sseEngine.subscribe(key) }
                .collect { router.route(key, it) }
        }
        // Explicit fetch on entry: the snapshot may be stale and a
        // `resume: ok` handshake deliberately skips the REST resync.
        refresh()
    }

    /** Tears the global pipe down (the engine keeps the resume cursor). */
    fun stop() {
        sseJob?.cancel()
        sseJob = null
        sseEngine.unsubscribe(SseSubscriptionKey.Global)
    }

    /** Pull-to-refresh / initial load. Coalesces concurrent calls. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            isRefreshing.value = true
            try {
                sessionStore.refresh()
                machineStore.refresh()
                isOffline.value = false
                hasRefreshedOnce.value = true
                // First successful list for this hub seeds the unread baseline
                // so historical sessions do not all light up as unread.
                lastSeenStore.initializeBaseline(hubKey, sessionStore.sessions.value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                isOffline.value = true
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun setMachineFilter(machineId: String?) {
        machineFilter.value = machineId
    }

    /** Call when navigating into a session: stamps the last-seen watermark. */
    fun onSessionOpened(sessionId: String) {
        val summary = sessionStore.sessions.value.firstOrNull { it.id == sessionId } ?: return
        lastSeenStore.markSeen(sessionId, summary.updatedAt)
    }

    /** `PUT /sessions/:id/pin` with optimistic re-sort; failures surface on [errors]. */
    fun setPinMode(sessionId: String, mode: PinMode) {
        scope.launch {
            try {
                sessionStore.setPinMode(sessionId, mode.wire)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _errors.tryEmit(SessionListError.PinFailed(sessionId, error.message))
            }
        }
    }

    /** `POST /sessions/:id/archive` with optimistic removal; failures surface on [errors]. */
    fun archiveSession(sessionId: String) {
        scope.launch {
            try {
                sessionStore.archiveSession(sessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                _errors.tryEmit(SessionListError.ArchiveFailed(sessionId, error.message))
            }
        }
    }

    // ------------------------------------------------------------ mapping --

    private fun buildUiState(
        sessions: List<SessionSummary>,
        machines: List<Machine>,
        lastSeen: Map<String, Long>,
        filter: String?,
        isRefreshing: Boolean,
        isOffline: Boolean,
        hasLoaded: Boolean,
    ): SessionListUiState {
        val machinesById = machines.associateBy { it.id }

        fun machineLabel(machineId: String?): String? {
            if (machineId == null) return null
            val metadata = machinesById[machineId]?.metadata ?: return machineId.take(8)
            val displayName = metadata.displayName?.takeIf { it.isNotBlank() }
            return displayName ?: metadata.host
        }

        // Filter chips derive from ALL sessions (pre-filter), like the web —
        // filtering first would drop chips and silently clear the selection.
        val filters = sessions
            .groupBy { it.metadata?.machineId ?: UNKNOWN_MACHINE_ID }
            .map { (id, group) ->
                MachineFilterUi(
                    id = id,
                    label = if (id == UNKNOWN_MACHINE_ID) "" else machineLabel(id).orEmpty(),
                    sessionCount = group.size,
                )
            }
            .sortedByDescending { it.sessionCount }

        // A persisted pick whose machine no longer has sessions falls back to
        // All; with fewer than two machines the bar hides and never filters.
        val activeFilter = filter
            ?.takeIf { filters.size >= 2 && filters.any { chip -> chip.id == it } }

        val visible = if (activeFilter == null) {
            sessions
        } else {
            sessions.filter { (it.metadata?.machineId ?: UNKNOWN_MACHINE_ID) == activeFilter }
        }

        val rows = visible.map { summary ->
            val title = sessionTitle(summary)
            val summaryText = summary.metadata?.summary?.text?.takeIf { it.isNotBlank() }
            SessionRowUi(
                summary = summary,
                title = title,
                subtitle = when {
                    summaryText != null && summaryText != title -> summaryText
                    else -> summary.metadata?.path
                },
                machineLabel = machineLabel(summary.metadata?.machineId),
                flavor = summary.metadata?.flavor,
                unread = LastSeenStore.isUnread(summary, lastSeen[summary.id] ?: 0),
            )
        }

        return SessionListUiState(
            rows = rows,
            machineFilters = filters,
            activeMachineFilter = activeFilter,
            isRefreshing = isRefreshing,
            hasLoaded = hasLoaded,
            isOffline = isOffline,
        )
    }

    companion object {
        /** `getSessionTitle` (`web/src/lib/sessionTitle.ts`). */
        fun sessionTitle(summary: SessionSummary): String {
            val metadata = summary.metadata
            metadata?.name?.takeIf { it.isNotEmpty() }?.let { return it }
            metadata?.summary?.text?.takeIf { it.isNotEmpty() }?.let { return it }
            metadata?.path?.let { path ->
                val tail = path.split('/').lastOrNull { it.isNotEmpty() }
                if (tail != null) return tail
            }
            return summary.id.take(8)
        }
    }
}

/** `PUT /sessions/:id/pin` modes. */
enum class PinMode(val wire: String) {
    None("none"),
    Project("project"),
    Global("global"),
}

sealed interface SessionListError {
    val sessionId: String
    val message: String?

    data class PinFailed(override val sessionId: String, override val message: String?) : SessionListError
    data class ArchiveFailed(override val sessionId: String, override val message: String?) : SessionListError
}
