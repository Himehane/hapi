import Foundation

/// The hub protocol generation this client understands.
///
/// Real wire models (sessions, messages, patches, catalogs) land in M1a.
/// This type exists from M0 so the target, its tests, and the app's package
/// linkage have a stable anchor.
public struct ProtocolVersion: Sendable {
    /// Highest protocol generation supported by this build.
    public static let supported = 1
}
