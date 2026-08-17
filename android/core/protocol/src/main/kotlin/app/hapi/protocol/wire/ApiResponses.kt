package app.hapi.protocol.wire

import kotlinx.serialization.Serializable

/**
 * Paging envelope of `GET /api/sessions/:id/messages` (`MessagesResponse.page`
 * in `shared/src/apiTypes.ts`; semantics in
 * `docs/api/client-contract/pagination.md`). Every cursor is a `(seq, at)`
 * pair — both halves always travel together.
 */
@Serializable
data class MessagesPage(
    /** `'latest' | 'before' | 'after'`. */
    val direction: String,
    val limit: Int,
    /** Server's current epoch; mismatch on `after` ⇒ [reset] latest page. */
    val epoch: Long,
    /** `true` ⇒ discard the local window, this page replaces it. */
    val reset: Boolean,
    val nextBeforeSeq: Long? = null,
    val nextBeforeAt: Long? = null,
    val nextAfterSeq: Long? = null,
    val nextAfterAt: Long? = null,
    val snapshotHeadSeq: Long? = null,
    val snapshotHeadAt: Long? = null,
    val hasMore: Boolean,
)

/** `GET /api/sessions/:id/messages` — messages in ascending display order. */
@Serializable
data class MessagesResponse(
    val messages: List<DecryptedMessage>,
    val page: MessagesPage,
)

/** `GET /api/sessions`. */
@Serializable
data class SessionsResponse(
    val sessions: List<SessionSummary>,
)

/** `GET /api/sessions/:id`. */
@Serializable
data class SessionResponse(
    val session: Session,
)

/** `GET /api/machines`. */
@Serializable
data class MachinesResponse(
    val machines: List<Machine>,
)
