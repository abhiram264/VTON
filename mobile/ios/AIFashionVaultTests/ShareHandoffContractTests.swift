import Foundation
import XCTest

final class ShareHandoffContractTests: XCTestCase {

	func testCanonicalUUIDAcceptsValidLowercaseUUID() {
		let validUUIDs = [
			"123e4567-e89b-12d3-a456-426614174000",
			"c1a2b3c4-d5e6-4a7b-8c9d-0e1f2a3b4c5d",
			"00000000-0000-0000-0000-000000000000",
			"ffffffff-ffff-ffff-ffff-ffffffffffff",
		]
		for uuid in validUUIDs {
			XCTAssertTrue(
				ShareHandoffContract.isCanonicalUUID(uuid),
				"Expected \(uuid) to be recognized as a valid canonical UUID")
		}
	}

	func testCanonicalUUIDRejectsUppercaseAndMixedCase() {
		let invalidUUIDs = [
			"123E4567-E89B-12D3-A456-426614174000",
			"123e4567-e89B-12d3-a456-426614174000",
			"C1A2B3C4-D5E6-4A7B-8C9D-0E1F2A3B4C5D",
		]
		for uuid in invalidUUIDs {
			XCTAssertFalse(
				ShareHandoffContract.isCanonicalUUID(uuid),
				"Expected uppercase/mixed UUID \(uuid) to be rejected")
		}
	}

	func testCanonicalUUIDRejectsMalformedAndNonCanonical() {
		let malformed = [
			"",
			"123e4567e89b12d3a456426614174000",
			"123e4567-e89b-12d3-a456",
			"123e4567-e89b-12d3-a456-426614174000-extra",
			" 123e4567-e89b-12d3-a456-426614174000",
			"123e4567-e89b-12d3-a456-426614174000 ",
			"g23e4567-e89b-12d3-a456-426614174000",
			"1234567-1234-1234-1234-123456789012",
			"123456789-1234-1234-1234-123456789012",
		]
		for uuid in malformed {
			XCTAssertFalse(
				ShareHandoffContract.isCanonicalUUID(uuid),
				"Expected malformed UUID '\(uuid)' to be rejected")
		}
	}

	func testLookURLProducesCanonicalDestination() {
		let lookId = "c1a2b3c4-d5e6-4a7b-8c9d-0e1f2a3b4c5d"
		let expected = URL(string: "fashionvault://looks/c1a2b3c4-d5e6-4a7b-8c9d-0e1f2a3b4c5d")
		let actual = ShareHandoffContract.lookURL(lookId: lookId)
		XCTAssertEqual(actual, expected)
		XCTAssertEqual(actual?.scheme, "fashionvault")
		XCTAssertEqual(actual?.host, "looks")
		XCTAssertEqual(actual?.path, "/c1a2b3c4-d5e6-4a7b-8c9d-0e1f2a3b4c5d")
	}
}
