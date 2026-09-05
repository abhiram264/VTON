import Foundation
import React

/// Host-side bridge for the FashionVaultShare handoff (ios-share-bridge spec).
/// Registered from ShareHandoff.m as `NativeModules.ShareHandoff` via legacy
/// (non-turbo) interop — the same registration approach as the Android module;
/// works bridgeless through RN's TurboModule interop layer.
///
/// Frozen seam (mobile/src/features/share/types.ts):
///   consumeShare(shareId) -> { ok: true, payload } | { ok: false, code }
///   releaseStagedImage(localImageUri) -> void
/// Typed failures are resolved values, never rejected promises.
///
/// Privacy (ADR-015): no URLs, filenames, ids, or error detail ever reach logs
/// or rejected promises; all catches are silent.
@objc(ShareHandoff)
final class ShareHandoff: NSObject {

	// MARK: Frozen handoff constants (mirror FashionVaultShare/ShareViewController.swift)

	private enum HandoffAction: String {
		case tryWithMyModel = "try_with_my_model"
		case addToVault = "add_to_vault"
	}

	private enum RecordKind: String {
		case url
		case image
		case imageURL = "image_url"
	}


	/// Native mirror of isFeatureEnabled('SHARE_IMPORT_IOS') in
	/// mobile/src/config/featureFlags.ts. The JS flag remains the single
	/// source of truth for routing; this build-time constant is the backstop
	/// so a disabled flag also stops native data consumption. When false,
	/// consumeShare resolves share_not_found without reading the App Group
	/// store — indistinguishable from "no handoff", so nothing leaks.
	internal static let shareImportEnabled = true

	internal static let appGroupID = "group.com.aifashionvault.shared"
	private static let stagingDirectoryName = "ShareStaging"
	internal static let recordKeyPrefix = "aifashionvault.share.handoff."

	@objc static func requiresMainQueueSetup() -> Bool { false }

	// MARK: consumeShare

	@objc(consumeShare:resolve:reject:)
	func consumeShare(
		_ shareId: String?,
		resolve: @escaping RCTPromiseResolveBlock,
		reject: @escaping RCTPromiseRejectBlock
	) {
		guard ShareHandoff.shareImportEnabled else {
			resolve(ShareHandoff.failure("share_not_found"))
			return
		}
		guard let shareId, ShareHandoffContract.isCanonicalUUID(shareId) else {
			resolve(ShareHandoff.failure("invalid_share_id"))
			return
		}
		guard let defaults = UserDefaults(suiteName: ShareHandoff.appGroupID) else {
			resolve(ShareHandoff.failure("share_not_found"))
			return
		}
		let key = ShareHandoff.recordKeyPrefix + shareId
		guard let data = defaults.data(forKey: key) else {
			resolve(ShareHandoff.failure("share_not_found"))
			return
		}
		// Claim = delete-before-read: the record is removed before it is parsed
		// or returned, so a concurrent consumer or replayed id sees
		// share_not_found and at most one consumer receives the payload.
		defaults.removeObject(forKey: key)

		guard let decoded = try? JSONSerialization.jsonObject(with: data),
			let record = decoded as? [String: Any]
		else {
			resolve(ShareHandoff.failure("invalid_payload"))
			return
		}

		func finishInvalid() {
			// Shape failure also cleans up whatever the record referenced.
			ShareHandoff.cleanup(record: record)
			resolve(ShareHandoff.failure("invalid_payload"))
		}

		guard (record["schemaVersion"] as? Int) == 1,
			(record["shareId"] as? String) == shareId,
			let kindRaw = record["kind"] as? String,
			let kind = RecordKind(rawValue: kindRaw),
			let actionRaw = record["action"] as? String,
			HandoffAction(rawValue: actionRaw) != nil,
			record["createdAt"] is Int,
			let expiresAt = record["expiresAt"] as? Int
		else {
			return finishInvalid()
		}

		guard Date().timeIntervalSince1970 < Double(expiresAt) else {
			// Expired: bounded retention covers both the record and staged media.
			ShareHandoff.cleanup(record: record)
			resolve(ShareHandoff.failure("share_expired"))
			return
		}

		let url = record["url"] as? String
		let stagedFile = record["stagedFile"] as? String

		// Payload maps omit optional fields entirely (never serialized as null)
		// and never contain image bytes — only bounded metadata plus a staging
		// file reference. sourcePackage is absent per wave-1.
		let payload: [String: String]
		switch kind {
		case .url:
			guard let url, !url.isEmpty else { return finishInvalid() }
			payload = ["kind": "url", "url": url]
		case .image:
			guard let stagedImageURI = ShareHandoff.stagedImageURI(stagedFile) else {
				return finishInvalid()
			}
			payload = ["kind": "image", "localImageUri": stagedImageURI]
		case .imageURL:
			guard let url, !url.isEmpty,
				let stagedImageURI = ShareHandoff.stagedImageURI(stagedFile)
			else { return finishInvalid() }
			payload = ["kind": "image_url", "url": url, "localImageUri": stagedImageURI]
		}

		resolve(["ok": true, "payload": payload])
	}

	// MARK: releaseStagedImage

	@objc(releaseStagedImage:resolve:reject:)
	func releaseStagedImage(
		_ localImageUri: String?,
		resolve: @escaping RCTPromiseResolveBlock,
		reject: @escaping RCTPromiseRejectBlock
	) {
		// Confined, best-effort cleanup: only a file URL whose resolved path is
		// a direct child of the share-staging directory may be deleted.
		// Anything suspicious resolves nil silently (ADR-015: never log URIs).
		guard let localImageUri,
			let uri = URL(string: localImageUri),
			uri.scheme?.lowercased() == "file",
			uri.host == nil,
			uri.query == nil,
			uri.fragment == nil,
			uri.path.hasPrefix("/"),
			!uri.path.contains("\\"),
			let resolved = ShareHandoff.confinedStagedFileURL(path: uri.path)
		else {
			resolve(nil)
			return
		}
		try? FileManager.default.removeItem(at: resolved)
		resolve(nil)
	}

	// MARK: Staging confinement

	private static func stagingDirectory() -> URL? {
		guard let base = FileManager.default.containerURL(
			forSecurityApplicationGroupIdentifier: appGroupID)
		else { return nil }
		return base.appendingPathComponent(stagingDirectoryName, isDirectory: true)
	}

	/// Absolute file URL string of <container>/ShareStaging/<stagedFile>, or nil
	/// unless the reference is a plain direct child of the staging directory
	/// whose symlink-resolved path stays inside it (rejects traversal and
	/// symlink escapes via resolve-and-prefix-check).
	private static func stagedImageURI(_ stagedFile: String?) -> String? {
		guard let stagedFile,
			!stagedFile.isEmpty,
			!stagedFile.contains("/"),
			!stagedFile.contains("\\"),
			stagedFile != ".",
			stagedFile != "..",
			let staging = stagingDirectory(),
			let confined = confinedStagedFileURL(
				path: staging.appendingPathComponent(stagedFile).path)
		else { return nil }
		return confined.absoluteString
	}

	/// Resolves `path` and returns it only when it is a direct child of the
	/// symlink-resolved staging directory.
	private static func confinedStagedFileURL(path: String) -> URL? {
		guard let staging = stagingDirectory() else { return nil }
		let resolvedStaging = staging.resolvingSymlinksInPath()
		let resolved = URL(fileURLWithPath: path).resolvingSymlinksInPath()
		guard resolved.path.hasPrefix(resolvedStaging.path + "/"),
			resolved.deletingLastPathComponent().path == resolvedStaging.path
		else { return nil }
		return resolved
	}

	/// Deletes the staged file referenced by a claimed record (confinement
	/// re-checked; untrusted names can never delete outside the staging dir).
	private static func cleanup(record: [String: Any]) {
		guard let stagedImageURI = stagedImageURI(record["stagedFile"] as? String),
			let url = URL(string: stagedImageURI)
		else { return }
		try? FileManager.default.removeItem(at: url)
	}

	/// Deletes a staged file named by an expired handoff record
	/// (confinement-checked; see stagedImageURI). Shared with
	/// ShareHandoffDiscovery for opportunistic expiry cleanup.
	static func releaseStagedFile(_ stagedFile: String?) {
		guard let uri = stagedImageURI(stagedFile), let url = URL(string: uri)
		else { return }
		try? FileManager.default.removeItem(at: url)
	}

	private static func failure(_ code: String) -> [String: Any] {
		["ok": false, "code": code]
	}
}
