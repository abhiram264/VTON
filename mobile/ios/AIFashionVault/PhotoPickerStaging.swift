import Foundation
import UIKit

/// Frozen acquisition policy for the native photo picker. Mirrors the Android
/// share/photo staging bounds (`mobile/android/.../share/ShareLimits.kt`) so a
/// picked photo cannot exceed the same byte/pixel envelope on either platform.
enum PhotoPickerLimits {
	static let maxBytes = 20_971_520
	static let maxPixels = 40_000_000
	static let maxEdge = 12_000
	static let stagedTTLSeconds: TimeInterval = 24 * 60 * 60
	static let maxStagedFiles = 8
}

/// Bounded failure codes rejected to JS. `photoSource.ts` turns a rejection
/// into a real failure; only `cancelled` and `permissionDenied` are resolved
/// envelopes, so a bridge fault can never look like a user cancellation.
///
/// The wire codes mirror the Android picker vocabulary
/// (`android/.../photopicker/PhotoPickerModule.kt`, `E_PHOTO_PICKER_*`) so both
/// platforms reject with the same bounded strings.
enum PhotoPickerFailure: Error {
	case pickerUnavailable
	case presenterUnavailable
	case unreadableSelection
	case unsupportedFormat
	case imageTooLarge
	case imageDimensionsTooLarge
	case stagingFailed

	var code: String {
		switch self {
		case .pickerUnavailable:
			return "E_PHOTO_PICKER_NO_CAMERA"
		case .presenterUnavailable:
			return "E_PHOTO_PICKER_NO_ACTIVITY"
		case .unreadableSelection, .stagingFailed:
			return "E_PHOTO_PICKER_UNREADABLE"
		case .unsupportedFormat:
			return "E_PHOTO_PICKER_UNSUPPORTED_FORMAT"
		case .imageTooLarge:
			return "E_PHOTO_PICKER_TOO_LARGE"
		case .imageDimensionsTooLarge:
			return "E_PHOTO_PICKER_TOO_LARGE_DIMENSIONS"
		}
	}
}

/// The exact result envelope `mobile/src/features/my-model/photoSource.ts`
/// consumes: `{cancelled}`, `{cancelled, permissionDenied}` or `{cancelled, uri}`.
enum PhotoPickerEnvelope {
	static func cancelled() -> [String: Any] {
		["cancelled": true]
	}

	static func permissionDenied() -> [String: Any] {
		["cancelled": false, "permissionDenied": true]
	}

	static func selected(_ fileURL: URL) -> [String: Any] {
		["cancelled": false, "uri": fileURL.absoluteString]
	}
}

/// Bounded, app-private staging for acquired photos: format normalization
/// (HEIC and every other device format is re-encoded), a hard byte cap, a
/// decoded-pixel cap and an edge cap, written into the app's own caches
/// directory so the picked file never leaves the app sandbox before upload.
enum PhotoPickerStaging {

	static let stagingDirectoryName = "PhotoStaging"

	// MARK: Policy

	/// Pure policy check. Returns the bounded failure for an image that is
	/// outside the envelope, or nil when the image is acceptable.
	static func failure(byteCount: Int, pixelWidth: Int, pixelHeight: Int) -> PhotoPickerFailure? {
		if pixelWidth <= 0 || pixelHeight <= 0 {
			return .unsupportedFormat
		}
		if pixelWidth > PhotoPickerLimits.maxEdge || pixelHeight > PhotoPickerLimits.maxEdge {
			return .imageDimensionsTooLarge
		}
		if pixelWidth * pixelHeight > PhotoPickerLimits.maxPixels {
			return .imageDimensionsTooLarge
		}
		if byteCount > PhotoPickerLimits.maxBytes {
			return .imageTooLarge
		}
		return nil
	}

	/// Largest size that satisfies both the edge and the pixel cap, preserving
	/// aspect ratio. Returns the input unchanged when it already fits. The pixel
	/// clamp is exact (the result is verified against the cap, not estimated),
	/// so a caller can never be handed a size that `failure` would still reject.
	static func boundedSize(pixelWidth: Int, pixelHeight: Int) -> (width: Int, height: Int) {
		guard pixelWidth > 0, pixelHeight > 0 else {
			return (pixelWidth, pixelHeight)
		}
		var width = pixelWidth
		var height = pixelHeight
		let edge = max(width, height)
		if edge > PhotoPickerLimits.maxEdge {
			let factor = Double(PhotoPickerLimits.maxEdge) / Double(edge)
			width = max(1, Int(Double(width) * factor))
			height = max(1, Int(Double(height) * factor))
		}
		while width * height > PhotoPickerLimits.maxPixels {
			if width >= height {
				width = max(1, width - 1)
			} else {
				height = max(1, height - 1)
			}
		}
		return (width, height)
	}

	// MARK: Staging

	/// Normalizes and writes `image` into the app-private staging directory.
	/// Re-encoding to JPEG is deliberate: a device HEIC selection is converted
	/// rather than merely relabelled, so downstream uploads never receive a
	/// format the API rejects.
	static func stage(_ image: UIImage) -> Result<URL, PhotoPickerFailure> {
		guard let source = image.cgImage else {
			return .failure(.unsupportedFormat)
		}
		let target = boundedSize(pixelWidth: source.width, pixelHeight: source.height)
		guard let scaled = scaledImage(image, width: target.width, height: target.height) else {
			return .failure(.stagingFailed)
		}
		guard let data = jpegData(scaled) else {
			return .failure(.stagingFailed)
		}
		if let violation = failure(
			byteCount: data.count, pixelWidth: target.width, pixelHeight: target.height) {
			return .failure(violation)
		}
		guard let directory = stagingDirectory() else {
			return .failure(.stagingFailed)
		}
		let destination = directory.appendingPathComponent(
			UUID().uuidString + ".jpg", isDirectory: false)
		do {
			try data.write(to: destination, options: .atomic)
		} catch {
			return .failure(.stagingFailed)
		}
		prune()
		return .success(destination)
	}

	/// Two-pass bounded encode: prefer quality, fall back to a smaller encode,
	/// and let the caller's byte check report `imageTooLarge` when even that
	/// does not fit — a size problem must never be reported as a staging fault.
	static func jpegData(_ image: UIImage) -> Data? {
		if let data = image.jpegData(compressionQuality: 0.9), data.count <= PhotoPickerLimits.maxBytes {
			return data
		}
		return image.jpegData(compressionQuality: 0.6)
	}

	static func stagingDirectory() -> URL? {
		guard let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
		else { return nil }
		let directory = caches.appendingPathComponent(stagingDirectoryName, isDirectory: true)
		if !FileManager.default.fileExists(atPath: directory.path) {
			do {
				try FileManager.default.createDirectory(
					at: directory, withIntermediateDirectories: true)
			} catch {
				return nil
			}
		}
		return directory
	}

	/// Bounded cleanup of the staging directory: drops files past the TTL and
	/// then the oldest survivors beyond the file-count cap, so a cancelled or
	/// crashed acquisition cannot accumulate unbounded private media.
	static func prune(now: Date = Date()) {
		guard let directory = stagingDirectory(),
			let entries = try? FileManager.default.contentsOfDirectory(
				at: directory,
				includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey],
				options: [.skipsHiddenFiles])
		else { return }
		var survivors: [(url: URL, modified: Date)] = []
		for entry in entries {
			let values = try? entry.resourceValues(
				forKeys: [.contentModificationDateKey, .isRegularFileKey])
			guard values?.isRegularFile == true else { continue }
			let modified = values?.contentModificationDate ?? .distantPast
			if now.timeIntervalSince(modified) > PhotoPickerLimits.stagedTTLSeconds {
				try? FileManager.default.removeItem(at: entry)
			} else {
				survivors.append((entry, modified))
			}
		}
		guard survivors.count >= PhotoPickerLimits.maxStagedFiles else { return }
		let ordered = survivors.sorted { $0.modified < $1.modified }
		for stale in ordered.prefix(ordered.count - PhotoPickerLimits.maxStagedFiles + 1) {
			try? FileManager.default.removeItem(at: stale.url)
		}
	}

	private static func scaledImage(_ image: UIImage, width: Int, height: Int) -> UIImage? {
		guard let source = image.cgImage else { return nil }
		if source.width == width && source.height == height {
			return image
		}
		let format = UIGraphicsImageRendererFormat.default()
		format.scale = 1
		format.opaque = true
		let renderer = UIGraphicsImageRenderer(
			size: CGSize(width: width, height: height), format: format)
		return renderer.image { _ in
			image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
		}
	}
}
