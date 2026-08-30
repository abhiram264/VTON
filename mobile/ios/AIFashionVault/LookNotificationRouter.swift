import Foundation
import UIKit

/// LOOK_READY notification routing (ios-share-bridge spec): tapping a
/// completed-generation notification opens fashionvault://looks/{lookId}
/// through React Native's Linking path — the JS lookLinkHandler already
/// handles warm 'url' events and the initial URL, and dedupes by lookId so a
/// replayed URL never navigates twice.
///
/// Delivery is RCTLinkingManager's own open-url notification
/// ("RCTOpenURLNotification"), which RCTLinkingManager relays to JS as the
/// Linking 'url' event. Warm taps are delivered by the first post. Cold-start
/// taps race JS setup (the listener registers only after the bundle runs), so
/// the URL is also folded into launchOptions (Linking.getInitialURL) and
/// replayed for a short window; JS-side dedupe makes replays harmless.
///
/// Invalid/missing lookId or unknown payload type: dropped silently
/// (ADR-015 — payload contents are never logged).
enum LookNotificationRouter {

	private static let lookType = "LOOK_READY"
	private static let openURLNotificationName = Notification.Name("RCTOpenURLNotification")

	/// Cold-start race window: JS may need a few seconds to register its
	/// Linking listener after a notification-tap launch.
	private static let replayInterval: TimeInterval = 0.5
	private static let replayAttemptsMax = 20

	private static var pendingLookURL: URL?
	private static var replayAttempts = 0

	/// userInfo["type"] == "LOOK_READY" and userInfo["lookId"] is a lowercase
	/// canonical UUID -> navigation URL, else nil (neutral drop).
	static func lookURL(fromUserInfo userInfo: [AnyHashable: Any]?) -> URL? {
		guard let userInfo,
			(userInfo["type"] as? String) == LookNotificationRouter.lookType,
			let lookId = userInfo["lookId"] as? String,
			ShareHandoffContract.isCanonicalUUID(lookId)
		else { return nil }
		return ShareHandoffContract.lookURL(lookId: lookId)
	}

	/// Relay a notification tap to the React Native Linking path (warm start).
	static func route(userInfo: [AnyHashable: Any]?) {
		guard let url = lookURL(fromUserInfo: userInfo) else { return }
		DispatchQueue.main.async {
			LookNotificationRouter.pendingLookURL = url
			LookNotificationRouter.replayAttempts = 0
			LookNotificationRouter.replayTick()
		}
	}

	/// Cold-start launch options: returns a copy with .url set to the Look
	/// destination when the launch notification payload is a valid LOOK_READY,
	/// so the URL also flows through Linking.getInitialURL(). An existing .url
	/// entry is preserved (the user's explicit open wins).
	static func launchOptions(
		folding launchOptions: [UIApplication.LaunchOptionsKey: Any]?
	) -> [UIApplication.LaunchOptionsKey: Any]? {
		// Best-effort cold-start fold: a content-available APNs launch
		// surfaces the payload under .remoteNotification. There is no
		// notification-response launch key (that is
		// UIWindowScene.ConnectionOptions API); cold-launch taps are owned
		// by the UNUserNotificationCenterDelegate registered in
		// AppDelegate, which is called even for the app's own cold-launch
		// tap response in the same session. JS lookId dedupe makes the
		// two paths safe together.
		let payload = launchOptions?[.remoteNotification] as? [AnyHashable: Any]
		guard let payload,
			let url = lookURL(fromUserInfo: payload),
			launchOptions?[.url] == nil
		else { return launchOptions }
		var options = launchOptions ?? [:]
		options[.url] = url
		return options
	}

	/// Posts the open-url notification and keeps replaying while the cold-start
	/// window lasts. Delivery is idempotent on the JS side (lookId dedupe), so
	/// extra posts are harmless; the loop stops after replayAttemptsMax.
	private static func replayTick() {
		guard let url = pendingLookURL, replayAttempts < replayAttemptsMax else {
			pendingLookURL = nil
			replayAttempts = 0
			return
		}
		replayAttempts += 1
		NotificationCenter.default.post(
			name: LookNotificationRouter.openURLNotificationName, object: nil,
			userInfo: ["url": url.absoluteString])
		DispatchQueue.main.asyncAfter(deadline: .now() + LookNotificationRouter.replayInterval) {
			LookNotificationRouter.replayTick()
		}
	}
}
