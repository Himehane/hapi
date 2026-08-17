import Foundation
import HapiProtocol

/// Scaffold marker for the transport layer.
///
/// APIClient, AuthManager (single-flight refresh), and SSEClient arrive in
/// M1b/M1c. Until then this constant proves the target graph and the
/// HapiClient -> HapiProtocol dependency compile and link.
public enum HapiClientVersion {
    /// Version of the HapiKit client scaffold.
    public static let current = "0.1.0"

    /// Highest hub protocol generation this client speaks.
    public static let protocolVersion = ProtocolVersion.supported
}
