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

	private static let stagingDirectoryName = "ShareStaging"
	private static let orphanStagingGracePeriod: TimeInterval = 15 * 60


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
		var referencedStagedFiles = Set<String>()
		let now = Date().timeIntervalSince1970
		for key in defaults.dictionaryRepresentation().keys {
			guard key.hasPrefix(ShareHandoff.recordKeyPrefix) else { continue }
			let shareId = String(key.dropFirst(ShareHandoff.recordKeyPrefix.count))
			guard ShareHandoffContract.isCanonicalUUID(shareId) else { continue }
			guard let data = defaults.data(forKey: key) else { continue }

			guard let record = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
				ShareHandoff.isValidRecord(record, shareId: shareId, now: Int(now))
			else {
				defaults.removeObject(forKey: key)
				ShareHandoff.cleanup(shareId: shareId)
				continue
			}
			if let stagedFile = record["stagedFile"] as? String {
				referencedStagedFiles.insert(stagedFile)
			}

			// Expired: remove the record and its canonical staged file, publish nothing.
			if let expiresAt = record["expiresAt"] as? Int,
				now >= Double(expiresAt)
			{
				defaults.removeObject(forKey: key)
				ShareHandoff.cleanup(shareId: shareId)
				continue
			}
			// Live: publish the frozen URI.
			liveIds.append(shareId)
		}

		cleanupOrphanedStagedFiles(referencedFiles: referencedStagedFiles, now: now)

		guard !liveIds.isEmpty else {
			DispatchQueue.main.async {
				ShareHandoffDiscovery.pendingShareIds = []
				ShareHandoffDiscovery.replayAttempts = 0
			}
			return
		}
		DispatchQueue.main.async {
			ShareHandoffDiscovery.pendingShareIds = liveIds
			ShareHandoffDiscovery.replayAttempts = 0
			ShareHandoffDiscovery.replayTick()
		}
	}


	/// Posts the open-url notification for each pending id and refreshes the
	/// App Group snapshot after each cold-start window, so a live handoff is
	/// not silently dropped when the JS listener is slow to register.

	private static func replayTick() {
		guard !pendingShareIds.isEmpty else {
			replayAttempts = 0
			return
		}
		if replayAttempts >= replayAttemptsMax {
			replayAttempts = 0
			discover()
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

	private static func cleanupOrphanedStagedFiles(
		referencedFiles: Set<String>, now: TimeInterval
	) {
		guard let container = FileManager.default.containerURL(
			forSecurityApplicationGroupIdentifier: ShareHandoff.appGroupID)
		else { return }
		let staging = container.appendingPathComponent(stagingDirectoryName, isDirectory: true)
		guard let files = try? FileManager.default.contentsOfDirectory(
			at: staging,
			includingPropertiesForKeys: [.isRegularFileKey, .contentModificationDateKey],
			options: [.skipsHiddenFiles]
		) else { return }

		let orphanCutoff = now - orphanStagingGracePeriod
		for file in files where file.pathExtension == "img" {
			let name = file.lastPathComponent
			guard !referencedFiles.contains(name),
				let values = try? file.resourceValues(
					forKeys: [.isRegularFileKey, .contentModificationDateKey]
				),
				values.isRegularFile == true,
				let modifiedAt = values.contentModificationDate?.timeIntervalSince1970,
				modifiedAt <= orphanCutoff
			else { continue }
			ShareHandoff.releaseStagedFile(name)
		}
	}
}
