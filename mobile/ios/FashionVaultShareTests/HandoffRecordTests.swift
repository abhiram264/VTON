import Foundation
import XCTest

final class HandoffRecordTests: XCTestCase {

	private let validShareId = "c1a2b3c4-d5e6-4a7b-8c9d-0e1f2a3b4c5d"

	func testRecordEncodingAndDecodingRoundTripURL() throws {
		let record = HandoffRecord(
			shareId: validShareId,
			action: .tryWithMyModel,
			kind: .url,
			url: "https://example.com/outfit/123",
			stagedFile: nil,
			createdAt: 1_700_000_000,
			expiresAt: 1_700_000_900
		)

		let data = try record.encode()
		let decoded = HandoffRecord.decode(from: data)

		XCTAssertNotNil(decoded)
		XCTAssertEqual(decoded?.schemaVersion, 1)
		XCTAssertEqual(decoded?.shareId, validShareId)
		XCTAssertEqual(decoded?.action, .tryWithMyModel)
		XCTAssertEqual(decoded?.kind, .url)
		XCTAssertEqual(decoded?.url, "https://example.com/outfit/123")
		XCTAssertNil(decoded?.stagedFile)
		XCTAssertEqual(decoded?.createdAt, 1_700_000_000)
		XCTAssertEqual(decoded?.expiresAt, 1_700_000_900)
		XCTAssertEqual(decoded, record)
	}

	func testRecordEncodingAndDecodingRoundTripImage() throws {
		let fileName = validShareId + ".img"
		let record = HandoffRecord(
			shareId: validShareId,
			action: .addToVault,
			kind: .image,
			url: nil,
			stagedFile: fileName,
			createdAt: 1_700_000_000,
			expiresAt: 1_700_000_900
		)

		let data = try record.encode()
		let decoded = HandoffRecord.decode(from: data)

		XCTAssertNotNil(decoded)
		XCTAssertEqual(decoded?.schemaVersion, 1)
		XCTAssertEqual(decoded?.shareId, validShareId)
		XCTAssertEqual(decoded?.action, .addToVault)
		XCTAssertEqual(decoded?.kind, .image)
		XCTAssertNil(decoded?.url)
		XCTAssertEqual(decoded?.stagedFile, fileName)
		XCTAssertEqual(decoded?.createdAt, 1_700_000_000)
		XCTAssertEqual(decoded?.expiresAt, 1_700_000_900)
		XCTAssertEqual(decoded, record)
	}

	func testRecordEncodingAndDecodingRoundTripImageURL() throws {
		let fileName = validShareId + ".img"
		let record = HandoffRecord(
			shareId: validShareId,
			action: .tryWithMyModel,
			kind: .imageURL,
			url: "https://example.com/item/456",
			stagedFile: fileName,
			createdAt: 1_700_000_000,
			expiresAt: 1_700_000_900
		)

		let data = try record.encode()
		let decoded = HandoffRecord.decode(from: data)

		XCTAssertNotNil(decoded)
		XCTAssertEqual(decoded?.schemaVersion, 1)
		XCTAssertEqual(decoded?.shareId, validShareId)
		XCTAssertEqual(decoded?.action, .tryWithMyModel)
		XCTAssertEqual(decoded?.kind, .imageURL)
		XCTAssertEqual(decoded?.url, "https://example.com/item/456")
		XCTAssertEqual(decoded?.stagedFile, fileName)
		XCTAssertEqual(decoded?.createdAt, 1_700_000_000)
		XCTAssertEqual(decoded?.expiresAt, 1_700_000_900)
		XCTAssertEqual(decoded, record)
	}

	func testExpiryBoundaryAt900Seconds() {
		let createdAt = 1_000_000
		let expiresAt = createdAt + 900 // Exactly 15 minutes TTL

		let record = HandoffRecord(
			shareId: validShareId,
			action: .tryWithMyModel,
			kind: .url,
			url: "https://example.com",
			createdAt: createdAt,
			expiresAt: expiresAt
		)

		// 1 second before expiry: live
		let liveDate = Date(timeIntervalSince1970: Double(createdAt + 899))
		XCTAssertFalse(
			record.isExpired(at: liveDate),
			"Record at createdAt + 899s must still be live")

		// Exactly at expiry (createdAt + 900s): expired
		let exactExpiryDate = Date(timeIntervalSince1970: Double(createdAt + 900))
		XCTAssertTrue(
			record.isExpired(at: exactExpiryDate),
			"Record exactly at createdAt + 900s must be expired")

		// 1 second after expiry: expired
		let pastExpiryDate = Date(timeIntervalSince1970: Double(createdAt + 901))
		XCTAssertTrue(
			record.isExpired(at: pastExpiryDate),
			"Record at createdAt + 901s must be expired")
	}

	func testDecodeRejectsMalformedJSON() {
		let malformedData = "not a valid json payload".data(using: .utf8)!
		XCTAssertNil(HandoffRecord.decode(from: malformedData))
	}

	func testDecodeRejectsWrongSchemaVersion() {
		var dict: [String: Any] = validRecordDict()
		dict["schemaVersion"] = 2
		XCTAssertNil(HandoffRecord.decode(from: dict))

		dict["schemaVersion"] = 0
		XCTAssertNil(HandoffRecord.decode(from: dict))
	}

	func testDecodeRejectsUnknownKindOrAction() {
		var badKindDict = validRecordDict()
		badKindDict["kind"] = "video"
		XCTAssertNil(HandoffRecord.decode(from: badKindDict))

		badKindDict["kind"] = ""
		XCTAssertNil(HandoffRecord.decode(from: badKindDict))

		var badActionDict = validRecordDict()
		badActionDict["action"] = "unauthorized_action"
		XCTAssertNil(HandoffRecord.decode(from: badActionDict))

		badActionDict["action"] = ""
		XCTAssertNil(HandoffRecord.decode(from: badActionDict))
	}

	func testDecodeRejectsMissingOrEmptyShareId() {
		var dict = validRecordDict()
		dict.removeValue(forKey: "shareId")
		XCTAssertNil(HandoffRecord.decode(from: dict))

		dict["shareId"] = ""
		XCTAssertNil(HandoffRecord.decode(from: dict))
	}

	func testDecodeRejectsHostileStagedFileTraversalAndAbsolutePaths() {
		let hostilePaths = [
			"../escape.img",
			"../../root.img",
			"foo/../bar.img",
			"/etc/passwd",
			"/var/mobile/Containers/img.png",
			"subfolder/file.img",
			"sub\\file.img",
			".",
			"..",
			".hidden",
			"",
		]

		for badPath in hostilePaths {
			var dict = validRecordDict()
			dict["kind"] = RecordKind.image.rawValue
			dict["stagedFile"] = badPath
			dict.removeValue(forKey: "url")

			XCTAssertNil(
				HandoffRecord.decode(from: dict),
				"Expected decode to reject hostile stagedFile: '\(badPath)'")
		}
	}

	func testDecodeEnforcesKindFieldRequirements() {
		// .url kind requires non-empty url
		var urlWithoutURL = validRecordDict()
		urlWithoutURL["kind"] = RecordKind.url.rawValue
		urlWithoutURL.removeValue(forKey: "url")
		XCTAssertNil(HandoffRecord.decode(from: urlWithoutURL))

		urlWithoutURL["url"] = ""
		XCTAssertNil(HandoffRecord.decode(from: urlWithoutURL))

		// .image kind requires non-empty stagedFile
		var imageWithoutFile = validRecordDict()
		imageWithoutFile["kind"] = RecordKind.image.rawValue
		imageWithoutFile.removeValue(forKey: "stagedFile")
		XCTAssertNil(HandoffRecord.decode(from: imageWithoutFile))

		// .imageURL kind requires both
		var imageURLWithoutFile = validRecordDict()
		imageURLWithoutFile["kind"] = RecordKind.imageURL.rawValue
		imageURLWithoutFile["url"] = "https://example.com"
		imageURLWithoutFile.removeValue(forKey: "stagedFile")
		XCTAssertNil(HandoffRecord.decode(from: imageURLWithoutFile))

		var imageURLWithoutURL = validRecordDict()
		imageURLWithoutURL["kind"] = RecordKind.imageURL.rawValue
		imageURLWithoutURL["stagedFile"] = "image.img"
		imageURLWithoutURL.removeValue(forKey: "url")
		XCTAssertNil(HandoffRecord.decode(from: imageURLWithoutURL))
	}

	func testWriteHandoffRecordToUserDefaults() throws {
		let suiteName = "test.fashionvault.shared.\(UUID().uuidString)"
		guard let testDefaults = UserDefaults(suiteName: suiteName) else {
			XCTFail("Failed to allocate test UserDefaults suite")
			return
		}

		try HandoffRecord.writeHandoffRecord(
			shareId: validShareId,
			action: .tryWithMyModel,
			kind: .url,
			url: "https://example.com/share",
			stagedFile: nil,
			defaults: testDefaults
		)

		let key = HandoffRecord.recordKeyPrefix + validShareId
		guard let data = testDefaults.data(forKey: key) else {
			XCTFail("Expected data in UserDefaults for key \(key)")
			return
		}

		let decoded = HandoffRecord.decode(from: data)
		XCTAssertNotNil(decoded)
		XCTAssertEqual(decoded?.shareId, validShareId)
		XCTAssertEqual(decoded?.action, .tryWithMyModel)
		XCTAssertEqual(decoded?.kind, .url)
		XCTAssertEqual(decoded?.url, "https://example.com/share")

		// Clean up
		testDefaults.removePersistentDomain(forName: suiteName)
	}

	private func validRecordDict() -> [String: Any] {
		[
			"schemaVersion": 1,
			"shareId": validShareId,
			"kind": RecordKind.url.rawValue,
			"action": HandoffAction.tryWithMyModel.rawValue,
			"url": "https://example.com/look",
			"createdAt": 1_700_000_000,
			"expiresAt": 1_700_000_900,
		]
	}
}
