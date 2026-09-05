import UIKit
import UniformTypeIdentifiers

/// Native share-extension boundary (ADR-002): no React Native, no backend calls,
/// no attempts to open the containing app. Stages image bytes into the App Group
/// container and writes one bounded handoff record the host app consumes later.
///
/// Privacy (ADR-015): no URLs, filenames, or media references appear in errors,
/// logs, or records beyond the bounded handoff metadata itself. All catches are
/// silent; failures surface only a neutral message.
///
/// Handoff record (UserDefaults suite "group.com.aifashionvault.shared", key
/// "aifashionvault.share.handoff.<shareId>"): JSON with schemaVersion=1, shareId,
/// kind ("url" | "image" | "image_url"), url?, stagedFile? (relative filename
/// only), action ("try_with_my_model" | "add_to_vault"), createdAt/expiresAt
/// (epoch seconds, +15 min TTL). No image bytes, base64, or media URLs.
final class ShareViewController: UIViewController {

	// MARK: Handoff constants (frozen seams)


	// MARK: Bounds (spec: item count, metadata length, byte size, time)

	private static let maxAttachments = 8
	private static let maxURLLength = 2048
	private static let maxTextLength = 10_000
	private static let maxImageBytes = 25 * 1024 * 1024
	private static let processingDeadline: TimeInterval = 20
	private static let recordTTLSeconds: TimeInterval = 15 * 60

	private static let appGroupID = "group.com.aifashionvault.shared"
	private static let stagingDirectoryName = "ShareStaging"
	private static let recordKeyPrefix = "aifashionvault.share.handoff."
	private static let shareEnabledKey = "aifashionvault.share.enabled"

	private let deadline = Date().addingTimeInterval(ShareViewController.processingDeadline)
	private var finished = false

	// MARK: UI (plain UIKit, monochrome; exactly two actions + cancel)

	private let statusLabel = UILabel()
	private let tryButton = UIButton(type: .system)
	private let addButton = UIButton(type: .system)
	private let cancelButton = UIButton(type: .system)

	override func viewDidLoad() {
		super.viewDidLoad()

		let titleLabel = UILabel()
		titleLabel.text = "Fashion Vault"
		titleLabel.font = .preferredFont(forTextStyle: .headline)
		titleLabel.textAlignment = .center
		titleLabel.textColor = .label

		statusLabel.text = "Choose how to import this item."
		statusLabel.font = .preferredFont(forTextStyle: .subheadline)
		statusLabel.textAlignment = .center
		statusLabel.textColor = .secondaryLabel
		statusLabel.numberOfLines = 0

		for (button, title, action) in [
			(tryButton, "Try with My Model", #selector(tryWithMyModelTapped)),
			(addButton, "Add to Vault", #selector(addToVaultTapped)),
		] as [(UIButton, String, Selector)] {
			button.setTitle(title, for: .normal)
			let boldDescriptor = UIFontDescriptor.preferredFontDescriptor(withTextStyle: .body)
				.withSymbolicTraits(.traitBold)
				?? UIFontDescriptor.preferredFontDescriptor(withTextStyle: .body)
			button.titleLabel?.font = UIFont(descriptor: boldDescriptor, size: 0)
			button.tintColor = .label
			button.addTarget(self, action: action, for: .touchUpInside)
			button.layer.cornerRadius = 8
			button.layer.borderWidth = 1
			button.layer.borderColor = UIColor.label.cgColor
			button.heightAnchor.constraint(equalToConstant: 44).isActive = true
		}

		cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)

		let stack = UIStackView(arrangedSubviews: [titleLabel, statusLabel, tryButton, addButton, cancelButton])
		stack.axis = .vertical
		stack.spacing = 12
		stack.isLayoutMarginsRelativeArrangement = true
		stack.layoutMargins = UIEdgeInsets(top: 16, left: 16, bottom: 16, right: 16)
		stack.translatesAutoresizingMaskIntoConstraints = false
		view.addSubview(stack)
		view.backgroundColor = .systemBackground

		NSLayoutConstraint.activate([
			stack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
			stack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
			stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
		])
	}

	// MARK: Actions

	@objc private func tryWithMyModelTapped() { performAction(.tryWithMyModel) }
	@objc private func addToVaultTapped() { performAction(.addToVault) }

	@objc private func cancelTapped() {
		guard !finished else { return }
		finished = true
		extensionContext?.cancelRequest(withError: ShareViewController.neutralError)
	}

	private func setUI(enabled: Bool) {
		tryButton.isEnabled = enabled
		addButton.isEnabled = enabled
		cancelButton.isEnabled = enabled
	}

	// MARK: Normalize + stage + record

	private struct PendingProviders {
		var urlProvider: NSItemProvider?
		var textProvider: NSItemProvider?
		var imageProvider: NSItemProvider?
		var isEmpty: Bool { urlProvider == nil && textProvider == nil && imageProvider == nil }
	}

	private func collectPendingProviders() -> PendingProviders {
		var pending = PendingProviders()
		guard let items = extensionContext?.inputItems as? [NSExtensionItem] else { return pending }
		var scanned = 0
		for item in items {
			guard let attachments = item.attachments else { continue }
			for provider in attachments {
				scanned += 1
				guard scanned <= ShareViewController.maxAttachments else { return pending }
				if pending.imageProvider == nil,
					provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
					pending.imageProvider = provider
				}
				if pending.urlProvider == nil,
					provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
					pending.urlProvider = provider
				}
				if pending.textProvider == nil,
					provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
					pending.textProvider = provider
				}
			}
		}
		return pending
	}

	private func performAction(_ action: HandoffAction) {
		guard !finished else { return }
		finished = true // one share creates at most one handoff; duplicate callbacks are no-ops
		setUI(enabled: false)

		// Kill switch (SHARE_IMPORT_IOS): default-on, host-owned. The host app writes
		// this App Group key from its single flag source; the extension only reads it.
		// false => decline with the neutral message; nothing staged, recorded, or written.
		let shareEnabled = (UserDefaults(suiteName: ShareViewController.appGroupID)?
			.object(forKey: ShareViewController.shareEnabledKey) as? Bool) ?? true
		guard shareEnabled else { return reject(stagedFile: nil) }

		let shareId = UUID().uuidString.lowercased()
		let pending = collectPendingProviders()
		guard !pending.isEmpty else { return reject(stagedFile: nil) }

		let group = DispatchGroup()
		var urlValue: String?
		var textValue: String?
		var stagedFile: String?
		var imageCopyFailed = false

		if let provider = pending.urlProvider {
			group.enter()
			provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
				defer { group.leave() }
				if let url = item as? URL {
					urlValue = ShareViewController.boundedURL(url.absoluteString)
				} else if let string = item as? String {
					urlValue = ShareViewController.boundedURL(string)
				}
			}
		}
		if let provider = pending.textProvider {
			group.enter()
			provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
				defer { group.leave() }
				if let string = item as? String {
					textValue = ShareViewController.boundedText(string)
				}
			}
		}
		if let provider = pending.imageProvider {
			group.enter()
			provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { fileURL, _ in
				defer { group.leave() }
				// Copy BEFORE this completion returns: the temporary file is deleted on return.
				guard let fileURL, Date() < self.deadline else { return }
				do {
					stagedFile = try ShareViewController.stageImage(shareId: shareId, at: fileURL)
				} catch {
					imageCopyFailed = true // failed copy => reject; never a partial handoff
				}
			}
		}

		group.notify(queue: DispatchQueue.global(qos: .userInitiated)) { [weak self] in
			guard let self else {
				ShareViewController.removeStagedFile(stagedFile)
				return
			}

			// Normalization precedence (frozen): image+URL => image_url; image => image;
			// URL or text-containing-URL => url; text without URL => unsupported.
			let url = urlValue ?? ShareViewController.firstURL(inText: textValue)
			let kind: RecordKind
			switch (stagedFile != nil, url != nil) {
			case (true, true): kind = .imageURL
			case (true, false): kind = .image
			case (false, true): kind = .url
			case (false, false): return self.reject(stagedFile: nil)
			}

			if imageCopyFailed || Date() >= self.deadline {
				return self.reject(stagedFile: stagedFile)
			}
			do {
				try ShareViewController.writeHandoffRecord(
					shareId: shareId, action: action, kind: kind, url: url, stagedFile: stagedFile)
				self.complete()
			} catch {
				self.reject(stagedFile: stagedFile, shareId: shareId)
			}
		}
	}

	// MARK: Terminal outcomes (idempotent; `finished` guards duplicates)

	private func complete() {
		DispatchQueue.main.async { [extensionContext] in
			extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
		}
	}

	private func reject(stagedFile: String?, shareId: String? = nil) {
		ShareViewController.removeStagedFile(stagedFile)
		if let shareId {
			UserDefaults(suiteName: ShareViewController.appGroupID)?
				.removeObject(forKey: ShareViewController.recordKeyPrefix + shareId)
		}
		DispatchQueue.main.async { [extensionContext] in
			extensionContext?.cancelRequest(withError: ShareViewController.neutralError)
		}
	}

	private static var neutralError: NSError {
		NSError(
			domain: "com.aifashionvault.FashionVaultShare", code: 0,
			userInfo: [NSLocalizedDescriptionKey: "Fashion Vault can't use this item."])
	}

	// MARK: Bounded helpers

	private static func boundedURL(_ string: String) -> String? {
		guard string.count <= maxURLLength,
			let url = URL(string: string),
			let scheme = url.scheme?.lowercased(),
			scheme == "http" || scheme == "https"
		else { return nil }
		return string
	}

	private static func boundedText(_ string: String) -> String {
		String(string.prefix(maxTextLength))
	}

	private static func firstURL(inText text: String?) -> String? {
		guard let text,
			let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue),
			let match = detector.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
			let range = Range(match.range, in: text)
		else { return nil }
		return boundedURL(String(text[range]))
	}

	// MARK: App Group staging + handoff record

	private static func stagingDirectory() throws -> URL {
		guard let base = FileManager.default.containerURL(
			forSecurityApplicationGroupIdentifier: appGroupID)
		else { throw HandoffError.storageUnavailable }
		let directory = base.appendingPathComponent(stagingDirectoryName, isDirectory: true)
		try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
		return directory
	}

	private static func stageImage(shareId: String, at sourceURL: URL) throws -> String {
		let directory = try stagingDirectory()
		let attributes = try FileManager.default.attributesOfItem(atPath: sourceURL.path)
		let size = (attributes[.size] as? NSNumber)?.intValue ?? 0
		guard size > 0, size <= maxImageBytes else { throw HandoffError.imageRejected }
		// Opaque unique filename derived from the shareId; contains no user data.
		let fileName = shareId + ".img"
		let destination = directory.appendingPathComponent(fileName)
		try? FileManager.default.removeItem(at: destination)
		try FileManager.default.copyItem(at: sourceURL, to: destination)
		return fileName
	}

	private static func removeStagedFile(_ fileName: String?) {
		guard let fileName, let directory = try? stagingDirectory() else { return }
		try? FileManager.default.removeItem(at: directory.appendingPathComponent(fileName))
	}

	private static func writeHandoffRecord(
		shareId: String, action: HandoffAction, kind: RecordKind, url: String?, stagedFile: String?
	) throws {
		try HandoffRecord.writeHandoffRecord(
			shareId: shareId, action: action, kind: kind, url: url, stagedFile: stagedFile
		)
	}
}
