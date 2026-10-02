import Foundation
import XCTest

/// Bounded-policy tests for the native photo picker. These mirror the Android
/// photo-policy expectations so both platforms agree on what an acceptable
/// acquisition is. Compiled by the AIFashionVaultTests target; executing them
/// requires a macOS/Xcode toolchain (not available in this run).
final class PhotoPickerStagingTests: XCTestCase {

	// MARK: failure()

	func testAcceptsAnImageExactlyAtEveryLimit() {
		XCTAssertNil(
			PhotoPickerStaging.failure(
				byteCount: PhotoPickerLimits.maxBytes,
				pixelWidth: PhotoPickerLimits.maxEdge,
				pixelHeight: 1))
		XCTAssertNil(
			PhotoPickerStaging.failure(byteCount: 0, pixelWidth: 8000, pixelHeight: 5000))
	}

	func testRejectsBytesAboveTheCap() {
		XCTAssertEqual(
			PhotoPickerStaging.failure(
				byteCount: PhotoPickerLimits.maxBytes + 1, pixelWidth: 100, pixelHeight: 100),
			.imageTooLarge)
	}

	func testRejectsNonPositiveDimensionsAsUnsupported() {
		XCTAssertEqual(
			PhotoPickerStaging.failure(byteCount: 10, pixelWidth: 0, pixelHeight: 100),
			.unsupportedFormat)
		XCTAssertEqual(
			PhotoPickerStaging.failure(byteCount: 10, pixelWidth: 100, pixelHeight: -1),
			.unsupportedFormat)
	}

	func testRejectsAnEdgeAboveTheCap() {
		XCTAssertEqual(
			PhotoPickerStaging.failure(
				byteCount: 10,
				pixelWidth: PhotoPickerLimits.maxEdge + 1,
				pixelHeight: 10),
			.imageDimensionsTooLarge)
	}

	func testRejectsPixelsAboveTheCapWhenEachEdgeIsWithinIt() {
		let edge = 8000
		XCTAssertTrue(edge <= PhotoPickerLimits.maxEdge)
		XCTAssertTrue(edge * edge > PhotoPickerLimits.maxPixels)
		XCTAssertEqual(
			PhotoPickerStaging.failure(byteCount: 10, pixelWidth: edge, pixelHeight: edge),
			.imageDimensionsTooLarge)
	}

	// MARK: boundedSize()

	func testBoundedSizeLeavesAFittingImageUnchanged() {
		let size = PhotoPickerStaging.boundedSize(pixelWidth: 1200, pixelHeight: 900)
		XCTAssertEqual(size.width, 1200)
		XCTAssertEqual(size.height, 900)
	}

	func testBoundedSizeClampsTheLongEdgeAndPreservesAspectRatio() {
		let size = PhotoPickerStaging.boundedSize(pixelWidth: 24_000, pixelHeight: 12_000)
		XCTAssertEqual(size.width, PhotoPickerLimits.maxEdge)
		XCTAssertEqual(size.height, 6000)
	}

	/// The clamp must be verified, not estimated: a size that still exceeds the
	/// pixel cap would make `failure` reject an image the picker just accepted.
	func testBoundedSizeAlwaysProducesASizeThatPassesThePolicy() {
		let candidates = [
			(24_000, 12_000), (12_000, 12_000), (8000, 8000), (40_000, 1000), (1000, 40_000),
		]
		for (width, height) in candidates {
			let bounded = PhotoPickerStaging.boundedSize(pixelWidth: width, pixelHeight: height)
			XCTAssertNil(
				PhotoPickerStaging.failure(
					byteCount: 0, pixelWidth: bounded.width, pixelHeight: bounded.height),
				"boundedSize(\(width)x\(height)) produced \(bounded.width)x\(bounded.height)")
		}
	}

	func testBoundedSizePreservesAspectRatioWithinRounding() {
		let bounded = PhotoPickerStaging.boundedSize(pixelWidth: 9000, pixelHeight: 3000)
		let original = Double(9000) / Double(3000)
		let ratio = Double(bounded.width) / Double(bounded.height)
		XCTAssertEqual(ratio, original, accuracy: 0.01)
	}

	// MARK: envelope

	func testCancelledEnvelopeCarriesOnlyTheCancellationFlag() {
		let envelope = PhotoPickerEnvelope.cancelled()
		XCTAssertEqual(envelope["cancelled"] as? Bool, true)
		XCTAssertNil(envelope["uri"])
		XCTAssertNil(envelope["permissionDenied"])
	}

	func testPermissionDenialIsDistinctFromCancellation() {
		let envelope = PhotoPickerEnvelope.permissionDenied()
		XCTAssertEqual(envelope["cancelled"] as? Bool, false)
		XCTAssertEqual(envelope["permissionDenied"] as? Bool, true)
		XCTAssertNil(envelope["uri"])
	}

	func testSelectedEnvelopeCarriesAFileURL() {
		let url = URL(fileURLWithPath: "/tmp/PhotoStaging/abc.jpg")
		let envelope = PhotoPickerEnvelope.selected(url)
		XCTAssertEqual(envelope["cancelled"] as? Bool, false)
		XCTAssertEqual(envelope["uri"] as? String, url.absoluteString)
		XCTAssertNil(envelope["permissionDenied"])
	}

	// MARK: failure codes

	/// The rejected-promise codes are the cross-platform vocabulary shared with
	/// the Android picker module (`E_PHOTO_PICKER_*`), so a JS caller can treat
	/// both platforms identically.
	func testFailureCodesMatchTheSharedAndroidVocabulary() {
		XCTAssertEqual(PhotoPickerFailure.pickerUnavailable.code, "E_PHOTO_PICKER_NO_CAMERA")
		XCTAssertEqual(
			PhotoPickerFailure.presenterUnavailable.code, "E_PHOTO_PICKER_NO_ACTIVITY")
		XCTAssertEqual(
			PhotoPickerFailure.unreadableSelection.code, "E_PHOTO_PICKER_UNREADABLE")
		XCTAssertEqual(PhotoPickerFailure.stagingFailed.code, "E_PHOTO_PICKER_UNREADABLE")
		XCTAssertEqual(
			PhotoPickerFailure.unsupportedFormat.code, "E_PHOTO_PICKER_UNSUPPORTED_FORMAT")
		XCTAssertEqual(PhotoPickerFailure.imageTooLarge.code, "E_PHOTO_PICKER_TOO_LARGE")
		XCTAssertEqual(
			PhotoPickerFailure.imageDimensionsTooLarge.code,
			"E_PHOTO_PICKER_TOO_LARGE_DIMENSIONS")
	}

	func testEveryFailureCodeIsNamespacedAndNonEmpty() {
		let failures: [PhotoPickerFailure] = [
			.pickerUnavailable, .presenterUnavailable, .unreadableSelection,
			.unsupportedFormat, .imageTooLarge, .imageDimensionsTooLarge, .stagingFailed,
		]
		for failure in failures {
			XCTAssertTrue(
				failure.code.hasPrefix("E_PHOTO_PICKER_"),
				"\(failure) produced '\(failure.code)'")
		}
	}
}
