import Foundation
import UIKit
import XCTest

final class LookNotificationRouterTests: XCTestCase {

	private let validLookId = "123e4567-e89b-12d3-a456-426614174000"
	private let openURLNotification = Notification.Name("RCTOpenURLNotification")

	func testLookURLFromValidUserInfo() {
		let userInfo: [AnyHashable: Any] = [
			"type": "LOOK_READY",
			"lookId": validLookId,
		]
		let expectedURL = URL(string: "fashionvault://looks/\(validLookId)")
		let actualURL = LookNotificationRouter.lookURL(fromUserInfo: userInfo)
		XCTAssertEqual(actualURL, expectedURL)
	}

	func testLookURLRejectsInvalidOrMissingType() {
		let missingType: [AnyHashable: Any] = ["lookId": validLookId]
		let wrongType: [AnyHashable: Any] = ["type": "OTHER_NOTIFICATION", "lookId": validLookId]
		let lowercaseType: [AnyHashable: Any] = ["type": "look_ready", "lookId": validLookId]

		XCTAssertNil(LookNotificationRouter.lookURL(fromUserInfo: missingType))
		XCTAssertNil(LookNotificationRouter.lookURL(fromUserInfo: wrongType))
		XCTAssertNil(LookNotificationRouter.lookURL(fromUserInfo: lowercaseType))
		XCTAssertNil(LookNotificationRouter.lookURL(fromUserInfo: nil))
	}

	func testLookURLRejectsMissingOrNonCanonicalLookId() {
		let missingId: [AnyHashable: Any] = ["type": "LOOK_READY"]
		let uppercaseId: [AnyHashable: Any] = [
			"type": "LOOK_READY",
			"lookId": "123E4567-E89B-12D3-A456-426614174000",
		]
		let malformedId: [AnyHashable: Any] = [
			"type": "LOOK_READY",
			"lookId": "not-a-uuid",
		]

		XCTAssertNil(LookNotificationRouter.lookURL(fromUserInfo: missingId))
		XCTAssertNil(LookNotificationRouter.lookURL(fromUserInfo: uppercaseId))
		XCTAssertNil(LookNotificationRouter.lookURL(fromUserInfo: malformedId))
	}

	func testPrivacyExtraPayloadKeysNeverAppearInEmittedURL() {
		let payloadWithExtras: [AnyHashable: Any] = [
			"type": "LOOK_READY",
			"lookId": validLookId,
			"imageUrl": "https://cdn.example.test/private/render.png",
			"prompt": "linen summer shirt",
			"modelId": "model-secret-uuid",
		]

		guard let url = LookNotificationRouter.lookURL(fromUserInfo: payloadWithExtras) else {
			XCTFail("Expected valid lookURL from payload with extras")
			return
		}

		XCTAssertEqual(url.absoluteString, "fashionvault://looks/\(validLookId)")
		XCTAssertFalse(url.absoluteString.contains("cdn.example.test"))
		XCTAssertFalse(url.absoluteString.contains("linen"))
		XCTAssertFalse(url.absoluteString.contains("model-secret-uuid"))
		XCTAssertNil(url.query)
	}

	func testLaunchOptionsFoldingWithValidRemoteNotification() {
		let remotePayload: [AnyHashable: Any] = [
			"type": "LOOK_READY",
			"lookId": validLookId,
		]
		let launchOptions: [UIApplication.LaunchOptionsKey: Any] = [
			.remoteNotification: remotePayload
		]

		let folded = LookNotificationRouter.launchOptions(folding: launchOptions)
		XCTAssertNotNil(folded)

		let foldedURL = folded?[.url] as? URL
		XCTAssertEqual(foldedURL, URL(string: "fashionvault://looks/\(validLookId)"))
	}

	func testLaunchOptionsFoldingPreservesExplicitURL() {
		let explicitURL = URL(string: "custom://user-explicit-destination")!
		let remotePayload: [AnyHashable: Any] = [
			"type": "LOOK_READY",
			"lookId": validLookId,
		]
		let launchOptions: [UIApplication.LaunchOptionsKey: Any] = [
			.url: explicitURL,
			.remoteNotification: remotePayload,
		]

		let folded = LookNotificationRouter.launchOptions(folding: launchOptions)
		let resolvedURL = folded?[.url] as? URL

		// Explicit open wins over push notification fold
		XCTAssertEqual(resolvedURL, explicitURL)
	}

	func testLaunchOptionsFoldingIgnoresNonLookNotification() {
		let otherPayload: [AnyHashable: Any] = [
			"type": "FRIEND_REQUEST",
			"id": "12345",
		]
		let launchOptions: [UIApplication.LaunchOptionsKey: Any] = [
			.remoteNotification: otherPayload
		]

		let folded = LookNotificationRouter.launchOptions(folding: launchOptions)
		XCTAssertNil(folded?[.url])
	}

	func testLaunchOptionsFoldingWithNilReturnsNil() {
		let folded = LookNotificationRouter.launchOptions(folding: nil)
		XCTAssertNil(folded)
	}

	func testRoutePostsRCTOpenURLNotification() {
		let payload: [AnyHashable: Any] = [
			"type": "LOOK_READY",
			"lookId": validLookId,
		]
		let expectation = self.expectation(description: "RCTOpenURLNotification posted")
		var capturedUserInfo: [AnyHashable: Any]?

		let observer = NotificationCenter.default.addObserver(
			forName: openURLNotification,
			object: nil,
			queue: .main
		) { notification in
			capturedUserInfo = notification.userInfo
			expectation.fulfill()
		}

		LookNotificationRouter.route(userInfo: payload)
		wait(for: [expectation], timeout: 2.0)
		NotificationCenter.default.removeObserver(observer)

		XCTAssertEqual(
			capturedUserInfo?["url"] as? String,
			"fashionvault://looks/\(validLookId)")
		// Invariant: no extraneous keys leaked into notification userInfo
		XCTAssertEqual(capturedUserInfo?.count, 1)
	}

	func testRouteWithInvalidPayloadDoesNotPostNotification() {
		let invalidPayload: [AnyHashable: Any] = [
			"type": "INVALID_TYPE",
			"lookId": validLookId,
		]
		let expectation = self.expectation(description: "Must not post notification")
		expectation.isInverted = true

		let observer = NotificationCenter.default.addObserver(
			forName: openURLNotification,
			object: nil,
			queue: .main
		) { _ in
			expectation.fulfill()
		}

		LookNotificationRouter.route(userInfo: invalidPayload)
		wait(for: [expectation], timeout: 0.5)
		NotificationCenter.default.removeObserver(observer)
	}
}
