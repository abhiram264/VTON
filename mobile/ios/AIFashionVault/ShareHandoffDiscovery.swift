import Foundation
import UIKit

/// Host-side share-handoff discovery (ios-share-bridge).
///
/// A Share Extension must not open its containing app (Apple constraint),
/// so FashionVaultShare only writes handoff records to the App Group.
/// Discovery closes the loop from the host side: on launch and on every
/// foreground it enumerates live handoff records and posts the frozen URI
/// aifashionvault://share-import/<shareId> through RCTOpenURLNotification —
/// the same delivery path LookNotificationRouter uses for warm LOOK_READY
/// taps. The host emits only the URI string; JS handleShareLink does the
/// consuming, and its handledShareIds/inFlightShareIds dedupe makes
/// replays harmless.
///
/// Expired records and their staged files are removed opportunistically.
///
/// Privacy (ADR-015): share ids, keys, filenames, and payload contents are
/// never logged; all failures are silent.
enum ShareHandoffDiscovery {

	private static let shareURIPrefix = "aifashionvault://share-import/"
	private static let openURLNotificationName = Notification.Name("RCTOpenURLNotification")

	/// Cold-start race window (mirrors LookNotificationRouter): JS may need
	/// a few seconds after launch before its Linking listener is registered.
	private static let replayInterval: TimeInterval = 0.5
	private static let replayAttemptsMax = 20

	private static var pendingShareIds: [String] = []
	private static var replayAttempts = 0
	private static var started = false
	private static var foregroundObserver: NSObjectProtocol?

	/// Idempotent: runs one discovery pass, then re-runs it on every
	/// foreground (didBecomeActive also covers willEnterForeground
	/// relaunches).
	static func start() {
		guard !started else { return }
		started = true
		discover()
		DispatchQueue.main.async {
			ShareHandoffDiscovery.foregroundObserver = NotificationCenter.default.addObserver(
				forName: UIApplication.didBecomeActiveNotification,
				object: nil, queue: .main
			) { _ in ShareHandoffDiscovery.discover() }
		}
	}

	/// Posts aifashionvault://share-import/<shareId> for every still-live
	/// handoff record; expired records and their staged files are removed.
	/// No-op when the native share-import backstop flag is disabled.
	private static func discover() {
		guard ShareHandoff.shareImportEnabled else { return }
		guard let defaults = UserDefaults(suiteName: ShareHandoff.appGroupID)
		else { return }

		var liveIds: [String] = []
		let now = Date().timeIntervalSince1970
		for key in defaults.dictionaryRepresentation().keys {
			guard key.hasPrefix(ShareHandoff.recordKeyPrefix) else { continue }
			let shareId = String(key.dropFirst(ShareHandoff.recordKeyPrefix.count))
			guard ShareHandoffContract.isCanonicalUUID(shareId) else { continue }
			guard let data = defaults.data(forKey: key) else { continue }

			// Expired: remove the record and its staged file, publish nothing.
			if let record = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
				let expiresAt = record["expiresAt"] as? Int,
				now >= Double(expiresAt)
			{
				defaults.removeObject(forKey: key)
				ShareHandoff.releaseStagedFile(record["stagedFile"] as? String)
				continue
			}
			// Live (or unparseable — the one-time JS consume claims and
			// cleans those): publish the frozen URI.
			liveIds.append(shareId)
		}

		guard !liveIds.isEmpty else { return }
		DispatchQueue.main.async {
			ShareHandoffDiscovery.pendingShareIds = liveIds
			ShareHandoffDiscovery.replayAttempts = 0
			ShareHandoffDiscovery.replayTick()
		}
	}

	/// Posts the open-url notification for each pending id and keeps
	/// replaying while the cold-start window lasts (mirrors
	/// LookNotificationRouter.replayTick). JS dedupe by shareId makes the
	/// repeated posts harmless; the loop stops after replayAttemptsMax.
	private static func replayTick() {
		guard !pendingShareIds.isEmpty, replayAttempts < replayAttemptsMax else {
			pendingShareIds = []
			replayAttempts = 0
			return
		}
		replayAttempts += 1
		for shareId in pendingShareIds {
			NotificationCenter.default.post(
				name: ShareHandoffDiscovery.openURLNotificationName, object: nil,
				userInfo: ["url": shareURIPrefix + shareId])
		}
		DispatchQueue.main.asyncAfter(deadline: .now() + ShareHandoffDiscovery.replayInterval) {
			ShareHandoffDiscovery.replayTick()
		}
	}
}
