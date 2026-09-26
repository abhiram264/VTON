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
	private static let maxTextBytes = maxTextLength * 4
	private static let maxImageBytes = 25 * 1024 * 1024
	private static let processingDeadline: TimeInterval = 20
	private static let recordTTLSeconds: TimeInterval = 15 * 60

	private static let appGroupID = "group.com.aifashionvault.shared"
	private static let stagingDirectoryName = "ShareStaging"
	private static let recordKeyPrefix = "aifashionvault.share.handoff."
	private static let shareEnabledKey = "aifashionvault.share.enabled"

	private let stateLock = NSLock()
	private var processingStarted = false
	private var terminal = false
	private var activeShareId: String?
	private var stagedFile: String?
	private var pendingStagedFile: String?
	private var timeoutWorkItem: DispatchWorkItem?

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
		reject()
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
		let shareId = UUID().uuidString.lowercased()
		guard beginAction(shareId: shareId) else { return }
		setUI(enabled: false)
		scheduleTimeout()

		// Kill switch (SHARE_IMPORT_IOS): default-on, host-owned. The host app writes
		// this App Group key from its single flag source; the extension only reads it.
		// false => decline with the neutral message; nothing staged, recorded, or written.
		let shareEnabled = (UserDefaults(suiteName: ShareViewController.appGroupID)?
			.object(forKey: ShareViewController.shareEnabledKey) as? Bool) ?? true
		guard shareEnabled else { return reject() }

		let pending = collectPendingProviders()
		guard !pending.isEmpty else { return reject() }

		let group = DispatchGroup()
		var urlValue: String?
		var textValue: String?
		var stagedFile: String?
		var imageCopyFailed = false
		var providerInputRejected = false

		if let provider = pending.urlProvider {
			group.enter()
			provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
				defer { group.leave() }
				guard let self, !self.isTerminal else { return }
				let candidate: String
				if let url = item as? URL {
					candidate = url.absoluteString
				} else if let string = item as? String {
					candidate = string
				} else {
					providerInputRejected = true
					return
				}
				guard !self.isTerminal else { return }
				if let value = ShareViewController.boundedURL(candidate) {
					urlValue = value
				} else {
					providerInputRejected = true
				}
			}
		}
		if let provider = pending.textProvider {
			group.enter()
			provider.loadDataRepresentation(forTypeIdentifier: UTType.plainText.identifier) { data, _ in
				defer { group.leave() }
				guard let self, !self.isTerminal else { return }
				guard let data,
					data.count <= ShareViewController.maxTextBytes,
					let string = String(data: data, encoding: .utf8),
					!self.isTerminal
				else {
					providerInputRejected = true
					return
				}
				textValue = ShareViewController.boundedText(string)
			}
		}
		if let provider = pending.imageProvider {
			let fileName = shareId + ".img"
			guard setPendingStagedFile(fileName) else { return }
			group.enter()
			provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { fileURL, error in
				defer { group.leave() }
				guard let self, !self.isTerminal else { return }
				guard error == nil, let fileURL, !self.isTerminal else {
					imageCopyFailed = true
					self.clearPendingStagedFile(fileName)
					return
				}
				do {
					let copiedFile = try ShareViewController.stageImage(shareId: shareId, at: fileURL)
					self.clearPendingStagedFile(fileName)
					if self.installStagedFile(copiedFile) {
						stagedFile = copiedFile
					}
				} catch {
					imageCopyFailed = true
					self.clearPendingStagedFile(fileName)
				}
			}
		}

		group.notify(queue: DispatchQueue.global(qos: .userInitiated)) { [weak self] in
			guard let self else {
				ShareViewController.removeStagedFile(stagedFile)
				return
			}
			let stagedFileSnapshot = self.stagedFileSnapshot ?? stagedFile
			guard !self.isTerminal else {
				self.reject(stagedFile: stagedFileSnapshot)
				return
			}

			// Normalization precedence (frozen): image+URL => image_url; image => image;
			// URL or text-containing-URL => url; text without URL => unsupported.
			let url = urlValue ?? ShareViewController.firstURL(inText: textValue)
			let kind: RecordKind
			switch (stagedFileSnapshot != nil, url != nil) {
			case (true, true): kind = .imageURL
			case (true, false): kind = .image
			case (false, true): kind = .url
			case (false, false): return self.reject(stagedFile: stagedFileSnapshot)
			}

			guard !imageCopyFailed, !providerInputRejected else {
				return self.reject(stagedFile: stagedFileSnapshot)
			}
			self.writeRecordAndComplete(
				shareId: shareId, action: action, kind: kind, url: url, stagedFile: stagedFileSnapshot
			)
		}
	}

	// MARK: Terminal outcomes (idempotent; one terminal state)

	private enum TerminalOutcome {
		case completed
		case rejected
	}

	private func beginAction(shareId: String) -> Bool {
		stateLock.lock()
		defer { stateLock.unlock() }
		guard !processingStarted, !terminal else { return false }
		processingStarted = true
		activeShareId = shareId
		return true
	}

	private func scheduleTimeout() {
		let work = DispatchWorkItem { [weak self] in
			self?.reject()
		}
		stateLock.lock()
		guard !terminal else {
			stateLock.unlock()
			return
		}
		timeoutWorkItem = work
		stateLock.unlock()
		DispatchQueue.global(qos: .userInitiated).asyncAfter(
			deadline: .now() + .seconds(Int(ShareViewController.processingDeadline)),
			execute: work
		)
	}

	private var isTerminal: Bool {
		stateLock.lock()
		defer { stateLock.unlock() }
		return terminal
	}

	private var stagedFileSnapshot: String? {
		stateLock.lock()
		defer { stateLock.unlock() }
		return stagedFile
	}

	@discardableResult
	private func setPendingStagedFile(_ fileName: String) -> Bool {
		stateLock.lock()
		guard !terminal else {
			stateLock.unlock()
			ShareViewController.removeStagedFile(fileName)
			return false
		}
		pendingStagedFile = fileName
		stateLock.unlock()
		return true
	}

	private func clearPendingStagedFile(_ fileName: String) {
		stateLock.lock()
		if pendingStagedFile == fileName {
			pendingStagedFile = nil
		}
		stateLock.unlock()
	}

	private func installStagedFile(_ fileName: String) -> Bool {
		stateLock.lock()
		guard !terminal, stagedFile == nil else {
			stateLock.unlock()
			ShareViewController.removeStagedFile(fileName)
			return false
		}
		stagedFile = fileName
		pendingStagedFile = nil
		stateLock.unlock()
		return true
	}

	private func finish(_ outcome: TerminalOutcome, stagedFile: String? = nil, shareId: String? = nil) {
		var fileToRemove: String?
		var pendingToRemove: String?
		var recordId: String?
		stateLock.lock()
		guard !terminal else {
			stateLock.unlock()
			if case .rejected = outcome, let stagedFile {
				ShareViewController.removeStagedFile(stagedFile)
			}
			return
		}
		terminal = true
		timeoutWorkItem?.cancel()
		timeoutWorkItem = nil
		recordId = shareId ?? activeShareId
		if case .rejected = outcome {
			fileToRemove = stagedFile ?? self.stagedFile
			pendingToRemove = pendingStagedFile
		}
		self.stagedFile = nil
		pendingStagedFile = nil
		activeShareId = nil
		stateLock.unlock()

		if case .rejected = outcome {
			ShareViewController.removeStagedFile(fileToRemove)
			ShareViewController.removeStagedFile(pendingToRemove)
			if let recordId {
				UserDefaults(suiteName: ShareViewController.appGroupID)?
					.removeObject(forKey: ShareViewController.recordKeyPrefix + recordId)
			}
		}
		notifyTerminal(outcome)
	}

	private func writeRecordAndComplete(
		shareId: String, action: HandoffAction, kind: RecordKind, url: String?, stagedFile: String?
	) {
		stateLock.lock()
		guard !terminal else {
			stateLock.unlock()
			ShareViewController.removeStagedFile(stagedFile)
			return
		}
		stateLock.unlock()

		do {
			try ShareViewController.writeHandoffRecord(
				shareId: shareId, action: action, kind: kind, url: url, stagedFile: stagedFile
			)
		} catch {
			UserDefaults(suiteName: ShareViewController.appGroupID)?
				.removeObject(forKey: ShareViewController.recordKeyPrefix + shareId)
			ShareViewController.removeStagedFile(stagedFile)
			reject(stagedFile: stagedFile, shareId: shareId)
			return
		}

		stateLock.lock()
		guard !terminal else {
			stateLock.unlock()
			UserDefaults(suiteName: ShareViewController.appGroupID)?
				.removeObject(forKey: ShareViewController.recordKeyPrefix + shareId)
			ShareViewController.removeStagedFile(stagedFile)
			return
		}
		terminal = true
		timeoutWorkItem?.cancel()
		timeoutWorkItem = nil
		self.stagedFile = nil
		pendingStagedFile = nil
		activeShareId = nil
		stateLock.unlock()
		notifyTerminal(.completed)
	}

	private func notifyTerminal(_ outcome: TerminalOutcome) {
		DispatchQueue.main.async { [weak self, extensionContext] in
			self?.setUI(enabled: false)
			switch outcome {
			case .completed:
				extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
			case .rejected:
				extensionContext?.cancelRequest(withError: ShareViewController.neutralError)
			}
		}
	}

	private func reject(stagedFile: String? = nil, shareId: String? = nil) {
		finish(.rejected, stagedFile: stagedFile, shareId: shareId)
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
		do {
			try FileManager.default.copyItem(at: sourceURL, to: destination)
		} catch {
			try? FileManager.default.removeItem(at: destination)
			throw error
		}
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
