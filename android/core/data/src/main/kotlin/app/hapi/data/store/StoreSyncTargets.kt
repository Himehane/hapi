package app.hapi.data.store

import app.hapi.data.sse.SseSubscriptionKey
import app.hapi.data.sse.SyncTargets
import app.hapi.protocol.wire.SyncEvent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Wires `SyncEventRouter` to the stores — the M2b (session list) half of the
 * event fan-out, mirroring the global-scope rules of `web/src/hooks/useSSE.ts`:
 *
 * - session lifecycle events → [SessionListStore.applySessionEvent];
 * - `machine-updated` → [MachineListStore.applyMachineEvent];
 * - global-scope message-stream events refresh the session list where the web
 *   invalidates it (`messages-invalidated`, `messages-consumed`,
 *   `message-cancelled`, `scheduled-matured`, and a `message-received`
 *   carrying `scheduledAt` — they all move the hub-computed scheduled/queued
 *   fields the client cannot derive);
 * - a `gap` handshake verdict triggers the full REST resync.
 *
 * WIRING(M2c): the message-window bookkeeping the web also performs here
 * (`markMessagesConsumed` / `removeOptimisticMessage` / ingest into the open
 * window) belongs to the message-window store — extend [onMessageEvent] when
 * it lands; the session-list handling below stays as is.
 */
class StoreSyncTargets(
    private val sessions: SessionListStore,
    private val machines: MachineListStore,
    private val scope: CoroutineScope,
    private val onToastEvent: (SyncEvent.Toast) -> Unit = {},
) : SyncTargets {

    override fun onSessionEvent(scope: SseSubscriptionKey, event: SyncEvent) {
        sessions.applySessionEvent(scope, event)
    }

    override fun onMachineEvent(scope: SseSubscriptionKey, event: SyncEvent.MachineUpdated) {
        machines.applyMachineEvent(event)
    }

    override fun onMessageEvent(scope: SseSubscriptionKey, event: SyncEvent) {
        // Session-scoped message traffic feeds the message window (M2c).
        if (scope !is SseSubscriptionKey.Global) return
        when (event) {
            is SyncEvent.MessagesInvalidated,
            is SyncEvent.MessagesConsumed,
            is SyncEvent.MessageCancelled,
            is SyncEvent.ScheduledMatured,
            -> sessions.scheduleRefresh()

            is SyncEvent.MessageReceived -> {
                if (event.message.scheduledAt != null) sessions.scheduleRefresh()
            }

            else -> Unit
        }
    }

    override fun onToast(event: SyncEvent.Toast) {
        onToastEvent(event)
    }

    override fun requestFullResync(scope: SseSubscriptionKey) {
        this.scope.launch {
            try {
                sessions.fullResync()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Offline: snapshot state stays; the next reconnect retries.
            }
            try {
                machines.refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
            }
        }
    }
}
