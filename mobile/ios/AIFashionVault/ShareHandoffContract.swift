import Foundation

/// Shared contract helpers (also used by LookNotificationRouter).
enum ShareHandoffContract {
	private static let canonicalUUIDPattern =
		"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

	/// Lowercase canonical UUID only — uppercase hex, wrong grouping, and
	/// non-hyphenated forms are rejected (mirrors the JS regex in types.ts).
	static func isCanonicalUUID(_ value: String) -> Bool {
		value.range(of: canonicalUUIDPattern, options: .regularExpression) != nil
	}

	/// Look navigation destination (frozen): fashionvault://looks/{lookId}.
	static func lookURL(lookId: String) -> URL? {
		URL(string: "fashionvault://looks/\(lookId)")
	}
}
