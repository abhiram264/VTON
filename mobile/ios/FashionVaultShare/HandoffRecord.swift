import Foundation

enum HandoffAction: String, Equatable {
	case tryWithMyModel = "try_with_my_model"
	case addToVault = "add_to_vault"
}

enum RecordKind: String, Equatable {
	case url
	case image
	case imageURL = "image_url"
}

enum HandoffError: Error, Equatable {
	case storageUnavailable
	case imageRejected
	case invalidRecord
}

struct HandoffRecord: Equatable {
	static let schemaVersion = 1
	static let recordTTLSeconds: TimeInterval = 15 * 60 // 900 seconds
	static let appGroupID = "group.com.aifashionvault.shared"
	static let recordKeyPrefix = "aifashionvault.share.handoff."

	let schemaVersion: Int
	let shareId: String
	let kind: RecordKind
	let action: HandoffAction
	let createdAt: Int
	let expiresAt: Int
	let url: String?
	let stagedFile: String?

	init(
		shareId: String,
		action: HandoffAction,
		kind: RecordKind,
		url: String? = nil,
		stagedFile: String? = nil,
		createdAt: Int = Int(Date().timeIntervalSince1970),
		expiresAt: Int? = nil,
		schemaVersion: Int = schemaVersion
	) {
		self.schemaVersion = schemaVersion
		self.shareId = shareId
		self.action = action
		self.kind = kind
		self.createdAt = createdAt
		self.expiresAt = expiresAt ?? (createdAt + Int(HandoffRecord.recordTTLSeconds))
		self.url = url
		self.stagedFile = stagedFile
	}

	func toDictionary() -> [String: Any] {
		var dict: [String: Any] = [
			"schemaVersion": schemaVersion,
			"shareId": shareId,
			"kind": kind.rawValue,
			"action": action.rawValue,
			"createdAt": createdAt,
			"expiresAt": expiresAt,
		]
		if let url { dict["url"] = url }
		if let stagedFile { dict["stagedFile"] = stagedFile }
		return dict
	}

	func encode() throws -> Data {
		let dict = toDictionary()
		guard JSONSerialization.isValidJSONObject(dict),
			let data = try? JSONSerialization.data(withJSONObject: dict)
		else {
			throw HandoffError.invalidRecord
		}
		return data
	}

	static func isValidStagedFile(_ fileName: String) -> Bool {
		guard !fileName.isEmpty,
			!fileName.contains("/"),
			!fileName.contains("\\"),
			!fileName.contains(".."),
			!fileName.hasPrefix(".")
		else { return false }
		return true
	}

	static func decode(from data: Data) -> HandoffRecord? {
		guard let json = try? JSONSerialization.jsonObject(with: data),
			let dict = json as? [String: Any]
		else { return nil }
		return decode(from: dict)
	}

	static func decode(from dict: [String: Any]) -> HandoffRecord? {
		guard let version = dict["schemaVersion"] as? Int,
			version == schemaVersion,
			let shareId = dict["shareId"] as? String,
			!shareId.isEmpty,
			let kindRaw = dict["kind"] as? String,
			let kind = RecordKind(rawValue: kindRaw),
			let actionRaw = dict["action"] as? String,
			let action = HandoffAction(rawValue: actionRaw),
			let createdAt = dict["createdAt"] as? Int,
			let expiresAt = dict["expiresAt"] as? Int
		else { return nil }

		let url = dict["url"] as? String
		let stagedFile = dict["stagedFile"] as? String

		if let stagedFile {
			guard isValidStagedFile(stagedFile) else { return nil }
		}

		switch kind {
		case .url:
			guard let url, !url.isEmpty else { return nil }
		case .image:
			guard let stagedFile, !stagedFile.isEmpty else { return nil }
		case .imageURL:
			guard let url, !url.isEmpty, let stagedFile, !stagedFile.isEmpty else { return nil }
		}

		return HandoffRecord(
			shareId: shareId,
			action: action,
			kind: kind,
			url: url,
			stagedFile: stagedFile,
			createdAt: createdAt,
			expiresAt: expiresAt,
			schemaVersion: version
		)
	}

	func isExpired(at date: Date = Date()) -> Bool {
		date.timeIntervalSince1970 >= Double(expiresAt)
	}

	static func isExpired(record: HandoffRecord, now: Date = Date()) -> Bool {
		record.isExpired(at: now)
	}

	static func writeHandoffRecord(
		shareId: String,
		action: HandoffAction,
		kind: RecordKind,
		url: String?,
		stagedFile: String?,
		defaults: UserDefaults? = UserDefaults(suiteName: appGroupID)
	) throws {
		guard let defaults else {
			throw HandoffError.storageUnavailable
		}
		let record = HandoffRecord(
			shareId: shareId,
			action: action,
			kind: kind,
			url: url,
			stagedFile: stagedFile
		)
		let data = try record.encode()
		defaults.set(data, forKey: recordKeyPrefix + shareId)
		_ = defaults.synchronize()
	}
}
