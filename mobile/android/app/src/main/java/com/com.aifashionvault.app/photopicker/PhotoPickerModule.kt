package com.aifashionvault.app.photopicker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aifashionvault.app.share.ShareImageStager
import com.aifashionvault.app.share.ShareValidationException
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * JS-facing bridge: `NativeModules.PhotoPicker`.
 *
 * Replaces the missing native module that `mobile/src/features/my-model/photoSource.ts`
 * requires. Two asynchronous methods, one request in flight at a time:
 *
 * - `choosePhoto()` opens the Android system photo picker (API 33+ `ACTION_PICK_IMAGES`,
 *   `ACTION_GET_CONTENT` otherwise — both surface the system picker on modern devices).
 * - `takePhoto()` requests the CAMERA permission and launches the system camera app,
 *   writing into an app-private `FileProvider` target.
 *
 * Both resolve the frozen [PhotoPickerEnvelope] shape: `{cancelled: true}` on dismissal,
 * `{cancelled: false, permissionDenied: true}` on camera denial, and
 * `{cancelled: false, uri: <file uri>}` on success. Every other outcome is a promise
 * rejection carrying a stable code, so a bridge failure can never masquerade as a
 * cancellation or an empty photo.
 *
 * Acquired bytes are streamed through [ShareImageStager], reusing the app's frozen image
 * safety policy (counted byte cap, magic-byte format allowlist, decoded dimension bounds)
 * before any file is handed to JS. The raw camera output is deleted after staging, and
 * stale staged files are pruned on each new request.
 */
class PhotoPickerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

  private enum class PendingKind {
    CHOOSE,
    CAPTURE,
  }

  private var pendingPromise: Promise? = null
  private var pendingKind: PendingKind? = null
  private var captureTarget: File? = null

  private val permissionListener =
      PermissionListener { requestCode, _, grantResults ->
        if (requestCode != REQUEST_PERMISSION) {
          false
        } else {
          val granted =
              grantResults.isNotEmpty() &&
                  grantResults.all { result -> result == PackageManager.PERMISSION_GRANTED }
          val activity = reactContext.currentActivity
          when {
            activity == null -> rejectPending(E_NO_ACTIVITY)
            granted -> launchCamera(activity)
            else -> resolve(PhotoPickerEnvelope.permissionDenied())
          }
          true
        }
      }

  init {
    reactContext.addActivityEventListener(this)
  }

  override fun getName(): String = MODULE_NAME

  @ReactMethod
  fun choosePhoto(promise: Promise) {
    if (!begin(PendingKind.CHOOSE, promise)) return
    val activity = reactContext.currentActivity
    if (activity == null) {
      rejectPending(E_NO_ACTIVITY)
      return
    }
    try {
      activity.startActivityForResult(libraryIntent(activity), REQUEST_CHOOSE)
    } catch (_: Exception) {
      rejectPending(E_LAUNCH_FAILED)
    }
  }

  @ReactMethod
  fun takePhoto(promise: Promise) {
    if (!begin(PendingKind.CAPTURE, promise)) return
    val activity = reactContext.currentActivity
    val permissionAware = activity as? PermissionAwareActivity
    if (activity == null || permissionAware == null) {
      rejectPending(E_NO_ACTIVITY)
      return
    }
    if (hasCameraPermission()) {
      launchCamera(activity)
      return
    }
    try {
      permissionAware.requestPermissions(
          arrayOf(Manifest.permission.CAMERA), REQUEST_PERMISSION, permissionListener)
    } catch (_: Exception) {
      rejectPending(E_LAUNCH_FAILED)
    }
  }

  override fun onActivityResult(
      activity: Activity,
      requestCode: Int,
      resultCode: Int,
      data: Intent?,
  ) {
    when (requestCode) {
      REQUEST_CHOOSE -> {
        if (pendingKind != PendingKind.CHOOSE) return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
          resolve(PhotoPickerEnvelope.cancelled())
          return
        }
        stageFromUri(uri)
      }
      REQUEST_CAPTURE -> {
        if (pendingKind != PendingKind.CAPTURE) return
        val target = captureTarget
        captureTarget = null
        if (resultCode != Activity.RESULT_OK || target == null) {
          target?.delete()
          resolve(PhotoPickerEnvelope.cancelled())
          return
        }
        if (!target.isFile || target.length() == 0L) {
          target.delete()
          rejectPending(E_EMPTY_CAPTURE)
          return
        }
        stageFromFile(target)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    // Picker/camera results arrive through onActivityResult; new intents carry no photo.
  }

  private fun libraryIntent(activity: Activity): Intent {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val picker = Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
      if (picker.resolveActivity(activity.packageManager) != null) {
        return picker
      }
    }
    return Intent(Intent.ACTION_GET_CONTENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      type = "image/*"
    }
  }

  private fun launchCamera(activity: Activity) {
    val target = PhotoPickerStaging.newStagedFile(reactContext.cacheDir)
    val output: Uri =
        try {
          FileProvider.getUriForFile(reactContext, fileProviderAuthority(), target)
        } catch (_: Exception) {
          target.delete()
          rejectPending(E_LAUNCH_FAILED)
          return
        }
    val intent =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
          putExtra(MediaStore.EXTRA_OUTPUT, output)
          addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    if (intent.resolveActivity(reactContext.packageManager) == null) {
      target.delete()
      rejectPending(E_NO_CAMERA)
      return
    }
    captureTarget = target
    try {
      activity.startActivityForResult(intent, REQUEST_CAPTURE)
    } catch (_: Exception) {
      target.delete()
      captureTarget = null
      rejectPending(E_LAUNCH_FAILED)
    }
  }

  private fun stageFromUri(source: Uri) {
    val input: InputStream? =
        try {
          reactContext.contentResolver.openInputStream(source)
        } catch (_: Exception) {
          null
        }
    if (input == null) {
      rejectPending(E_UNREADABLE)
      return
    }
    stage(input)
  }

  private fun stageFromFile(source: File) {
    val input: InputStream =
        try {
          FileInputStream(source)
        } catch (_: Exception) {
          source.delete()
          rejectPending(E_UNREADABLE)
          return
        }
    stage(input, source)
  }

  /** Validates and copies [input] into a fresh staged file, then deletes [discardAfter]. */
  private fun stage(input: InputStream, discardAfter: File? = null) {
    val staged = PhotoPickerStaging.newStagedFile(reactContext.cacheDir)
    val result: Result<ShareImageStager.StagedImage> =
        try {
          input.use { ShareImageStager.copyWithLimits(it, sinkFactory = { FileOutputStream(staged) }) }
        } catch (_: Exception) {
          Result.failure(IllegalStateException(E_UNREADABLE))
        }
    discardAfter?.delete()
    if (result.isSuccess) {
      resolve(PhotoPickerEnvelope.picked(PhotoPickerStaging.uriOf(staged)))
    } else {
      staged.delete()
      rejectPending(stageErrorCode(result.exceptionOrNull()))
    }
  }

  private fun begin(kind: PendingKind, promise: Promise): Boolean {
    if (pendingPromise != null) {
      promise.reject(E_BUSY, "A photo picker request is already in progress")
      return false
    }
    PhotoPickerStaging.cleanupStale(reactContext.cacheDir)
    pendingPromise = promise
    pendingKind = kind
    return true
  }

  private fun resolve(map: Map<String, Any>) {
    val promise = pendingPromise ?: return
    clearPending()
    promise.resolve(toWritableMap(map))
  }

  private fun rejectPending(code: String) {
    val promise = pendingPromise ?: return
    clearPending()
    promise.reject(code, "Photo picker failed: $code")
  }

  private fun clearPending() {
    pendingPromise = null
    pendingKind = null
    captureTarget?.delete()
    captureTarget = null
  }

  private fun hasCameraPermission(): Boolean =
      reactContext.checkSelfPermission(Manifest.permission.CAMERA) ==
          PackageManager.PERMISSION_GRANTED

  private fun fileProviderAuthority(): String =
      reactContext.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX

  private fun toWritableMap(map: Map<String, Any>): WritableNativeMap {
    val out = WritableNativeMap()
    for ((key, value) in map) {
      when (value) {
        is Boolean -> out.putBoolean(key, value)
        is String -> out.putString(key, value)
      }
    }
    return out
  }

  private fun stageErrorCode(error: Throwable?): String =
      when ((error as? ShareValidationException)?.error) {
        ShareImageStager.StageError.TooLarge -> E_TOO_LARGE
        ShareImageStager.StageError.OversizedDimensions -> E_TOO_LARGE_DIMENSIONS
        ShareImageStager.StageError.UnsupportedFormat -> E_UNSUPPORTED_FORMAT
        else -> E_UNREADABLE
      }

  companion object {
    const val MODULE_NAME = "PhotoPicker"

    private const val REQUEST_CHOOSE = 0x5043
    private const val REQUEST_CAPTURE = 0x5044
    private const val REQUEST_PERMISSION = 0x5045

    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    const val E_BUSY = "E_PHOTO_PICKER_BUSY"
    const val E_NO_ACTIVITY = "E_PHOTO_PICKER_NO_ACTIVITY"
    const val E_NO_CAMERA = "E_PHOTO_PICKER_NO_CAMERA"
    const val E_LAUNCH_FAILED = "E_PHOTO_PICKER_LAUNCH_FAILED"
    const val E_EMPTY_CAPTURE = "E_PHOTO_PICKER_EMPTY_CAPTURE"
    const val E_UNREADABLE = "E_PHOTO_PICKER_UNREADABLE"
    const val E_UNSUPPORTED_FORMAT = "E_PHOTO_PICKER_UNSUPPORTED_FORMAT"
    const val E_TOO_LARGE = "E_PHOTO_PICKER_TOO_LARGE"
    const val E_TOO_LARGE_DIMENSIONS = "E_PHOTO_PICKER_TOO_LARGE_DIMENSIONS"
  }
}
