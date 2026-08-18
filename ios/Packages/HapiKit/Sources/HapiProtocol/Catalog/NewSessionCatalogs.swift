import Foundation

/// A `(wire value, display label)` pair for the create-form option pickers.
public struct NewSessionOption: Equatable, Hashable, Sendable {
    public let value: String
    public let label: String

    public init(value: String, label: String) {
        self.value = value
        self.label = label
    }
}

/// Static option catalogs for the new-session form — data ports of
/// `shared/src/models.ts` (`CLAUDE_MODEL_LABELS`), `shared/src/effort.ts`
/// (`CLAUDE_EFFORT_LABELS`) and the web `CODEX_REASONING_EFFORT_OPTIONS`
/// (`web/src/components/NewSession/types.ts`). Data mirrors the Android
/// reference (`NewSessionCatalogs.kt`) exactly. They live in the catalog
/// package (unlike Android's feature-local copy) so the pure form logic and
/// the app UI share one source.
public enum NewSessionCatalogs {
    /// `'auto'` sentinel rows use the web's "Default" label.
    public static let claudeModels: [NewSessionOption] = [
        NewSessionOption(value: "auto", label: "Default"),
        NewSessionOption(value: "sonnet", label: "Sonnet"),
        NewSessionOption(value: "sonnet[1m]", label: "Sonnet 1M"),
        NewSessionOption(value: "opus", label: "Opus"),
        NewSessionOption(value: "opus[1m]", label: "Opus 1M"),
        NewSessionOption(value: "fable", label: "Fable"),
        NewSessionOption(value: "fable[1m]", label: "Fable 1M"),
    ]

    public static let claudeEfforts: [NewSessionOption] = [
        NewSessionOption(value: "auto", label: "Auto"),
        NewSessionOption(value: "low", label: "Low"),
        NewSessionOption(value: "medium", label: "Medium"),
        NewSessionOption(value: "high", label: "High"),
        NewSessionOption(value: "xhigh", label: "XHigh"),
        NewSessionOption(value: "max", label: "Max"),
    ]

    /// Static codex fallback when the model row advertises no efforts
    /// (the web drops `max` for codex — `EffortField.tsx`).
    public static let codexReasoningEfforts: [NewSessionOption] = [
        NewSessionOption(value: "default", label: "Default"),
        NewSessionOption(value: "low", label: "Low"),
        NewSessionOption(value: "medium", label: "Medium"),
        NewSessionOption(value: "high", label: "High"),
        NewSessionOption(value: "xhigh", label: "XHigh"),
    ]

    /// Capitalized label for a server-advertised effort id (`high` → `High`).
    public static func effortLabel(_ value: String) -> String {
        guard let first = value.first else { return value }
        return String(first).uppercased() + value.dropFirst()
    }
}
