import AVFoundation
import PhotosUI
import React
import UIKit
import UniformTypeIdentifiers

/// Native photo acquisition for iOS, registered as `NativeModules.PhotoPicker`
/// (legacy interop from PhotoPicker.m — the same approach as ShareHandoff).
///
/// Frozen seam (`mobile/src/features/my-model/photoSource.ts`):
///   takePhoto()   -> { cancelled } | { cancelled, permissionDenied } | { cancelled, uri }
///   choosePhoto() -> { cancelled } | { cancelled, uri }
///
/// Cancellation and permission denial are resolved envelopes; every other
/// outcome is a rejected promise carrying a bounded `PhotoPickerFailure` code,
/// so a bridge fault can never be mistaken for a user cancellation. No URI,
/// path or image content is ever logged.
///
/// Library selection uses `PHPickerViewController`, which grants access to the
/// single chosen item and therefore needs no photo-library permission prompt.
/// Only camera capture requests the camera permission.
@objc(PhotoPicker)
final class PhotoPicker: NSObject {

	private var cameraResolve: RCTPromiseResolveBlock?
	private var cameraReject: RCTPromiseRejectBlock?
	private var libraryResolve: RCTPromiseResolveBlock?
	private var libraryReject: RCTPromiseRejectBlock?

	// MARK: JS surface

	@objc(takePhoto:reject:)
	func takePhoto(
		_ resolve: @escaping RCTPromiseResolveBlock,
		reject: @escaping RCTPromiseRejectBlock
	) {
		onMain { self.beginCamera(resolve: resolve, reject: reject) }
	}

	@objc(choosePhoto:reject:)
	func choosePhoto(
		_ resolve: @escaping RCTPromiseResolveBlock,
		reject: @escaping RCTPromiseRejectBlock
	) {
		onMain { self.beginLibrary(resolve: resolve, reject: reject) }
	}

	// MARK: Camera

	private func beginCamera(
		resolve: @escaping RCTPromiseResolveBlock,
		reject: @escaping RCTPromiseRejectBlock
	) {
		switch AVCaptureDevice.authorizationStatus(for: .video) {
		case .denied, .restricted:
			resolve(PhotoPickerEnvelope.permissionDenied())
		case .notDetermined:
			AVCaptureDevice.requestAccess(for: .video) { granted in
				DispatchQueue.main.async {
					if granted {
						self.presentCamera(resolve: resolve, reject: reject)
					} else {
						resolve(PhotoPickerEnvelope.permissionDenied())
					}
				}
			}
		default:
			presentCamera(resolve: resolve, reject: reject)
		}
	}

	private func presentCamera(
		resolve: @escaping RCTPromiseResolveBlock,
		reject: @escaping RCTPromiseRejectBlock
	) {
		guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
			reject(
				PhotoPickerFailure.pickerUnavailable.code,
				"Camera capture is not available on this device",
				nil)
			return
		}
		guard let presenter = PhotoPicker.topViewController() else {
			reject(
				PhotoPickerFailure.presenterUnavailable.code,
				"No view controller can present the camera",
				nil)
			return
		}
		cameraResolve = resolve
		cameraReject = reject
		let picker = UIImagePickerController()
		picker.sourceType = .camera
		picker.mediaTypes = [UTType.image.identifier]
		picker.delegate = self
		presenter.present(picker, animated: true)
	}

	// MARK: Library

	private func beginLibrary(
		resolve: @escaping RCTPromiseResolveBlock,
		reject: @escaping RCTPromiseRejectBlock
	) {
		guard let presenter = PhotoPicker.topViewController() else {
			reject(
				PhotoPickerFailure.presenterUnavailable.code,
				"No view controller can present the photo picker",
				nil)
			return
		}
		libraryResolve = resolve
		libraryReject = reject
		var configuration = PHPickerConfiguration()
		configuration.filter = .images
		configuration.selectionLimit = 1
		let picker = PHPickerViewController(configuration: configuration)
		picker.delegate = self
		presenter.present(picker, animated: true)
	}

	// MARK: Shared finish

	/// Staging is CPU- and I/O-bound, so it runs off the main thread; promise
	/// resolution is queue-agnostic in React Native.
	private func finish(
		image: UIImage,
		resolve: @escaping RCTPromiseResolveBlock,
		reject: @escaping RCTPromiseRejectBlock
	) {
		DispatchQueue.global(qos: .userInitiated).async {
			switch PhotoPickerStaging.stage(image) {
			case .success(let url):
				resolve(PhotoPickerEnvelope.selected(url))
			case .failure(let failure):
				reject(failure.code, "The photo could not be prepared", nil)
			}
		}
	}

	private func onMain(_ work: @escaping () -> Void) {
		if Thread.isMainThread {
			work()
		} else {
			DispatchQueue.main.async(execute: work)
		}
	}

	private static func topViewController() -> UIViewController? {
		let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
		let windows = scenes.flatMap { $0.windows }
		let window = windows.first { $0.isKeyWindow } ?? windows.first
		var controller = window?.rootViewController
		while let presented = controller?.presentedViewController {
			controller = presented
		}
		return controller
	}
}

extension PhotoPicker: UIImagePickerControllerDelegate, UINavigationControllerDelegate {

	func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
		picker.dismiss(animated: true)
		let resolve = cameraResolve
		cameraResolve = nil
		cameraReject = nil
		resolve?(PhotoPickerEnvelope.cancelled())
	}

	func imagePickerController(
		_ picker: UIImagePickerController,
		didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
	) {
		picker.dismiss(animated: true)
		let resolve = cameraResolve
		let reject = cameraReject
		cameraResolve = nil
		cameraReject = nil
		guard let resolve, let reject else { return }
		guard let image = info[.originalImage] as? UIImage else {
			reject(
				PhotoPickerFailure.unreadableSelection.code,
				"The camera returned no image",
				nil)
			return
		}
		finish(image: image, resolve: resolve, reject: reject)
	}
}

extension PhotoPicker: PHPickerViewControllerDelegate {

	func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
		picker.dismiss(animated: true)
		let resolve = libraryResolve
		let reject = libraryReject
		libraryResolve = nil
		libraryReject = nil
		guard let resolve, let reject else { return }
		guard let provider = results.first?.itemProvider else {
			resolve(PhotoPickerEnvelope.cancelled())
			return
		}
		guard provider.canLoadObject(ofClass: UIImage.self) else {
			reject(
				PhotoPickerFailure.unsupportedFormat.code,
				"That image format is not supported",
				nil)
			return
		}
		provider.loadObject(ofClass: UIImage.self) { object, _ in
			guard let image = object as? UIImage else {
				reject(
					PhotoPickerFailure.unreadableSelection.code,
					"The selected image could not be read",
					nil)
				return
			}
			self.finish(image: image, resolve: resolve, reject: reject)
		}
	}
}
