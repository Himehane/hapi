package app.hapi.companion.feature.chat

import app.hapi.companion.feature.sessions.SessionListViewModel
import app.hapi.data.sse.SseEngine
import app.hapi.data.sse.SseSubscriptionKey
import app.hapi.data.sse.SyncEventRouter
import app.hapi.data.sse.SyncTargets
import app.hapi.data.store.LastSeenStore
import app.hapi.data.store.MachineListStore
import app.hapi.data.store.MessageWindowStore
import app.hapi.data.store.MessageWindowStores
import app.hapi.data.store.SessionDetailStore
import app.hapi.protocol.catalog.Flavors
import app.hapi.protocol.chat.NormalizedMessage
import app.hapi.protocol.chat.ToolGroupBlock
import app.hapi.protocol.chat.ToolGroupingOptions
import app.hapi.protocol.chat.VisibleChatBlock
import app.hapi.protocol.chat.buildVisibleChatBlocks
import app.hapi.protocol.chat.normalizeDecryptedMessage
import app.hapi.protocol.chat.reduceChatBlocks
import app.hapi.protocol.window.MessageWindowState
import app.hapi.protocol.window.WindowMessage
import app.hapi.protocol.wire.Machine
import app.hapi.protocol.wire.Session
import app.hapi.protocol.wire.SessionSummary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

/** Chat top-bar model: title cascade + status + meta line. */
data class ChatHeaderUi(
    val title: String,
    /** "Flavor · machine · worktree/path" meta line; null when nothing known. */
    val subtitle: String?,
    val active: Boolean,
    val thinking: Boolean,
)

/** What [ChatScreen] renders. */
data class ChatUiState(
    val sessionId: String,
    val header: ChatHeaderUi,
    /** Workspace root, for path display in tool cards. */
    val basePath: String?,
    val blocks: List<VisibleChatBlock>,
    val hasMore: Boolean,
    val isLoadingOlder: Boolean,
    val isSyncingTail: Boolean,
    /** First sync still running and nothing (snapshot included) to show yet. */
    val isInitialLoading: Boolean,
    /** Initial load produced nothing and the last attempt failed → error state. */
    val loadFailed: Boolean,
    /** Tail sync warning — the connection/staleness banner. */
    val warning: String?,
    /** Bumps on tail-side content changes; drives the new-messages pill. */
    val tailRevision: Long,
)

/**
 * Per-session chat state machine (read-only M2 slice):
 *
 * - owns the session-scope SSE subscription while [start]ed (dual-subscription
 *   model: the list screen owns the global pipe) and routes engine events into
 *   the shared [SyncTargets] — `session-updated` patches the cached detail,
 *   message events reach this session's [MessageWindowStore], a `gap`
 *   handshake verdict triggers the full resync incl. the window's catch-up
 *   tail sync (`StoreSyncTargets.requestFullResync`);
 * - opens the [MessageWindowStore] (snapshot hydration), [MessageWindowStore.activate]s
 *   it and starts a tail sync; [loadOlder] forwards to `fetchOlder`;
 * - runs the chat pipeline (normalize → reduce → toolGroups) over the window +
 *   the detail's `agentState` on [pipelineDispatcher], throttled to one run
 *   per [pipelineIntervalMs] (first input renders immediately). Normalization
 *   is memoized per message id by row instance identity, exactly like the web
 *   (`SessionChat.tsx` normalizedCache);
 * - stamps the [LastSeenStore] watermark on entry and on every `updatedAt`
 *   movement while the screen is open.
 *
 * Plain constructor — JVM tests drive it with fake stores and a scripted
 * transport; Navigation hosts it behind a lifecycle-aware holder.
 */
class ChatViewModel(
    val sessionId: String,
    private val sessionStore: SessionDetailStore,
    private val machineStore: MachineListStore,
    private val lastSeenStore: LastSeenStore,
    private val messageWindows: MessageWindowStores,
    private val sseEngine: SseEngine,
    syncTargets: SyncTargets,
    private val scope: CoroutineScope,
    private val pipelineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pipelineIntervalMs: Long = PIPELINE_INTERVAL_MS,
) {
    private val router = SyncEventRouter(syncTargets)
    private val subscriptionKey = SseSubscriptionKey.Session(sessionId)

    private val windowStore = MutableStateFlow<MessageWindowStore?>(null)
    private val detailLoadFailed = MutableStateFlow(false)

    private var sseJob: Job? = null
    private var initJob: Job? = null
    private var seenJob: Job? = null
    private var olderJob: Job? = null

    // Pipeline memo state — touched only inside the single uiState map stage.
    private val normalizeCache = HashMap<String, NormalizeCacheEntry>()
    private var previousGroups: List<ToolGroupBlock> = emptyList()

    private class NormalizeCacheEntry(val source: WindowMessage, val normalized: NormalizedMessage?)

    private data class PipelineInputs(
        val window: MessageWindowState,
        val detail: Session?,
        val summary: SessionSummary?,
        val machines: List<Machine>,
        val detailLoadFailed: Boolean,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ChatUiState> = windowStore
        .filterNotNull()
        .flatMapLatest { store ->
            combine(
                store.state,
                sessionStore.sessionDetail(sessionId),
                sessionStore.sessions.map { list -> list.firstOrNull { it.id == sessionId } }
                    .distinctUntilChanged(),
                machineStore.machines,
                detailLoadFailed,
                ::PipelineInputs,
            )
        }
        // The web samples pipeline runs through React batching; here: emit the
        // first value immediately, then at most one (latest) run per interval.
        .conflate()
        .transform { inputs ->
            emit(inputs)
            delay(pipelineIntervalMs)
        }
        .map(::buildUiState)
        .flowOn(pipelineDispatcher)
        .stateIn(scope, SharingStarted.Eagerly, initialState())

    // ------------------------------------------------------------ lifecycle --

    /** Idempotent; call from the screen's composition, paired with [stop]. */
    fun start() {
        if (initJob?.isActive == true || sseJob?.isActive == true) return

        initJob = scope.launch {
            val store = messageWindows.open(sessionId)
            store.activate()
            windowStore.value = store

            // Subscribe only after the window exists: every routed message
            // event / gap resync then finds a peekable window, and the
            // collector registers before `subscribe` because the engine's
            // SharedFlow has zero replay.
            sseJob = scope.launch {
                sseEngine.events(subscriptionKey)
                    .onSubscription { sseEngine.subscribe(subscriptionKey) }
                    .collect { router.route(subscriptionKey, it) }
            }

            launch { runCatching { store.syncTail() } }
            loadDetail()
        }

        seenJob = scope.launch {
            // Watermark = updatedAt currently on screen, from whichever cache
            // is fresher (summary via global events, detail via this pipe).
            merge(
                sessionStore.sessions
                    .map { list -> list.firstOrNull { it.id == sessionId }?.updatedAt },
                sessionStore.sessionDetail(sessionId).map { it?.updatedAt },
            )
                .filterNotNull()
                .distinctUntilChanged()
                .collect { updatedAt -> lastSeenStore.markSeen(sessionId, updatedAt) }
        }
    }

    /** Tears the session pipe down (engine keeps the resume cursor). */
    fun stop() {
        sseJob?.cancel()
        sseJob = null
        initJob?.cancel()
        seenJob?.cancel()
        olderJob?.cancel()
        sseEngine.unsubscribe(subscriptionKey)
        sessionStore.releaseDetail(sessionId)
    }

    /** Initial-load error state → try again (detail + tail). */
    fun retry() {
        scope.launch {
            loadDetail()
            windowStore.value?.let { store -> runCatching { store.syncTail(ensureAfterCurrent = true) } }
        }
    }

    /** Top-edge reached: one older page (no-ops while one is in flight). */
    fun loadOlder() {
        val store = windowStore.value ?: return
        if (olderJob?.isActive == true) return
        olderJob = scope.launch {
            runCatching { store.fetchOlder() }
        }
    }

    private suspend fun loadDetail() {
        try {
            sessionStore.loadSessionDetail(sessionId)
            detailLoadFailed.value = false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            detailLoadFailed.value = true
        }
    }

    // ------------------------------------------------------------- pipeline --

    private fun initialState() = ChatUiState(
        sessionId = sessionId,
        header = ChatHeaderUi(title = sessionId.take(8), subtitle = null, active = false, thinking = false),
        basePath = null,
        blocks = emptyList(),
        hasMore = false,
        isLoadingOlder = false,
        isSyncingTail = true,
        isInitialLoading = true,
        loadFailed = false,
        warning = null,
        tailRevision = 0,
    )

    private fun buildUiState(inputs: PipelineInputs): ChatUiState {
        val window = inputs.window

        // Queued-not-yet-invoked rows belong to the composer bar (M3a), not
        // the thread — shared predicate with the window store, like the web.
        val visibleMessages = window.messages.filter { !it.isQueuedForInvocation }

        val normalized = ArrayList<NormalizedMessage>(visibleMessages.size)
        val seen = HashSet<String>(visibleMessages.size * 2)
        for (message in visibleMessages) {
            if (!seen.add(message.id)) continue
            val cached = normalizeCache[message.id]
            if (cached != null && cached.source === message) {
                cached.normalized?.let(normalized::add)
                continue
            }
            val next = normalizeDecryptedMessage(message.wire)
            normalizeCache[message.id] = NormalizeCacheEntry(message, next)
            next?.let(normalized::add)
        }
        normalizeCache.keys.retainAll(seen)

        val agentState = inputs.detail?.agentState
        val reduced = reduceChatBlocks(normalized, agentState)
        val visibleBlocks = buildVisibleChatBlocks(
            reduced.blocks,
            ToolGroupingOptions(hasMoreMessages = window.hasMore, previousGroups = previousGroups),
        )
        previousGroups = visibleBlocks.filterIsInstance<ToolGroupBlock>()

        val isEmpty = visibleBlocks.isEmpty()
        // syncGeneration 0 = no tail sync has even begun (the moment between
        // open and syncTail) — still "loading", never a flash of empty state.
        val syncSettled = !window.isSyncingTail && window.syncGeneration > 0
        return ChatUiState(
            sessionId = sessionId,
            header = buildHeader(inputs),
            basePath = inputs.detail?.metadata?.path ?: inputs.summary?.metadata?.path,
            blocks = visibleBlocks,
            hasMore = window.hasMore,
            isLoadingOlder = window.isLoadingMore,
            isSyncingTail = window.isSyncingTail,
            isInitialLoading = isEmpty && !syncSettled && window.warning == null,
            loadFailed = isEmpty && syncSettled &&
                (window.warning != null || inputs.detailLoadFailed),
            warning = window.warning,
            tailRevision = window.tailRevision,
        )
    }

    private fun buildHeader(inputs: PipelineInputs): ChatHeaderUi {
        val detail = inputs.detail
        val summary = inputs.summary

        // Detail first — the fresher source once loaded (this pipe patches it
        // live); a detail without usable metadata falls through to the list
        // summary, then to the id prefix (`getSessionTitle` cascade).
        val title = detail?.let(::detailTitle)
            ?: summary?.let(SessionListViewModel::sessionTitle)
            ?: sessionId.take(8)

        val flavor = detail?.metadata?.flavor ?: summary?.metadata?.flavor
        val machineId = detail?.metadata?.machineId ?: summary?.metadata?.machineId
        val machineLabel = machineId?.let { id ->
            val metadata = inputs.machines.firstOrNull { it.id == id }?.metadata
            metadata?.displayName?.takeIf { it.isNotBlank() } ?: metadata?.host ?: id.take(8)
        }
        val worktree = (detail?.metadata?.worktree ?: summary?.metadata?.worktree)
            ?.let { it.name.ifBlank { it.branch } }
        val subtitle = listOfNotNull(flavor?.let(Flavors::label), machineLabel, worktree)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" · ")

        return ChatHeaderUi(
            title = title,
            subtitle = subtitle,
            active = detail?.active ?: summary?.active ?: false,
            thinking = detail?.thinking ?: summary?.thinking ?: false,
        )
    }

    /** Detail title cascade; null when the metadata carries nothing usable. */
    private fun detailTitle(detail: Session): String? {
        val metadata = detail.metadata ?: return null
        metadata.name?.takeIf { it.isNotEmpty() }?.let { return it }
        metadata.summary?.text?.takeIf { it.isNotEmpty() }?.let { return it }
        return metadata.path.split('/').lastOrNull { it.isNotEmpty() }
    }

    private companion object {
        /** Web-equivalent render batching for the pipeline (the "sample(100ms)"). */
        const val PIPELINE_INTERVAL_MS: Long = 100
    }
}
