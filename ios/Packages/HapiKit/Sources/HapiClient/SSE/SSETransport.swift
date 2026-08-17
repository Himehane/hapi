import Foundation

/// What a transport reports while a single SSE connection attempt is alive:
/// exactly one `.connected` first (the HTTP response headers), then raw body
/// chunks. The stream finishes when the server closes the connection and
/// throws on transport errors.
public enum SSETransportEvent: Sendable {
    case connected(HTTPURLResponse)
    case bytes(Data)
}

public enum SSETransportError: Error, Sendable {
    /// The response was not HTTP at all (never happens against a hub).
    case notHTTP
}

/// One-shot connection factory. `SSEClient` calls `connect` once per attempt
/// and drives every retry itself; a transport must never auto-reconnect.
/// Cancelling the consuming task (or dropping the iterator) must tear the
/// underlying connection down.
public protocol SSETransport: Sendable {
    func connect(_ request: URLRequest) -> AsyncThrowingStream<SSETransportEvent, Error>
}

/// Production transport on `URLSession.bytes(for:)`.
///
/// Uses a dedicated session configuration because the defaults are wrong for
/// a long-lived stream that manages its own retries:
///
/// - `timeoutIntervalForRequest` 300 s — this is URLSession's *idle* timeout
///   (time between chunks). It must exceed the 30 s heartbeat interval by a
///   wide margin (≥ 120 s required; the 90 s staleness watchdog in
///   `SSEClient` fires long before it anyway).
/// - `timeoutIntervalForResource` 7 days — the stream is expected to live
///   "forever"; the default 7-minute-per-resource cap would kill it.
/// - `waitsForConnectivity` false — a dead link must surface as an error
///   immediately so the reconnect state machine owns the schedule.
/// - No caching, ephemeral storage — an event stream must never be served
///   from or written to a cache.
public struct URLSessionSSETransport: SSETransport {
    private let session: URLSession

    public init(session: URLSession = URLSessionSSETransport.makeSession()) {
        self.session = session
    }

    public static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 300
        configuration.timeoutIntervalForResource = 7 * 24 * 60 * 60
        configuration.waitsForConnectivity = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        return URLSession(configuration: configuration)
    }

    public func connect(_ request: URLRequest) -> AsyncThrowingStream<SSETransportEvent, Error> {
        let session = self.session
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await session.bytes(for: request)
                    guard let http = response as? HTTPURLResponse else {
                        throw SSETransportError.notHTTP
                    }
                    continuation.yield(.connected(http))
                    // AsyncBytes iterates single bytes (buffered internally);
                    // coalesce to one chunk per line so the parser is called
                    // O(lines), not O(bytes).
                    var chunk = Data()
                    chunk.reserveCapacity(1024)
                    for try await byte in bytes {
                        chunk.append(byte)
                        if byte == 0x0A {
                            continuation.yield(.bytes(chunk))
                            chunk.removeAll(keepingCapacity: true)
                        }
                    }
                    if !chunk.isEmpty {
                        continuation.yield(.bytes(chunk))
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }
}
