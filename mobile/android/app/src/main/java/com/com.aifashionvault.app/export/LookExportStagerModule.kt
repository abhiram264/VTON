package com.aifashionvault.app.export

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * JS-facing bridge: `NativeModules.LookExportStager`, matching the seam frozen in
 * `mobile/src/features/export/lookExport.ts`.
 *
 * Exists because React Native core cannot do either half of a user-directed export on
 * Android: it has no private-file downloader, and its built-in `Share` is text-only
 * (`ShareModule` builds `ACTION_SEND` with `setTypeAndNormalize("text/plain")` and never
 * sets `EXTRA_STREAM`), so it can share a URL string but never the image itself.
 *
 * Methods (the JS seam's exact vocabulary):
 * - `stageFromUrl(url, maxBytes)` downloads an already-authorized http/https image into an
 *   app-private staging file and resolves its `file:` URI. The caller's `maxBytes` is honored
 *   but never raised above the app's frozen image cap.
 * - `shareStaged(uri, mimeType?, title?)` opens the OS share sheet with `ACTION_SEND` +
 *   an image media type plus `EXTRA_STREAM` (a FileProvider `content:` URI) and resolves
 *   `{action}` using RN's own vocabulary, so `dismissedAction` keeps meaning "user cancelled".
 * - `saveToPhotos(uri)` inserts into the device gallery through `MediaStore` and resolves
 *   `'saved' | 'denied' | 'failed'`.
 * - `removeStaged(uri)` deletes one staged file; `sweepStaged(maxAgeMs)` clears abandoned
 *   ones. Both are best-effort and never throw to JS, and both are silent no-ops for anything
 *   that is not an app-private staged file.
 *
 * `saveToPhotos` failures are resolved values (`denied`/`failed`), never rejections, because
 * that is what the JS seam switches on. Every other failure rejects with a bounded code from
 * [ExportStagerOutcome]. No URL, path or byte content is ever logged.
 */
class LookExportStagerModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = MODULE_NAME

  /**
   * One download at a time: the staging target and the share target are both single-valued.
   * `@Volatile` because the guard is written on the JS thread and cleared on the download
   * thread; without it a later request could observe a stale `true` and be refused forever.
   */
  @Volatile private var busy = false

  /**
   * The in-flight download, kept only so [invalidate] can settle it. The download itself
   * receives the promise as an argument: assigning a field AFTER `Thread.start()` would be a
   * data race (the worker has no happens-before edge to that write and could read null,
   * leaving the JS promiseleft unsettled).
   */
  @Volatile private var pendingPromise: Promise? = null

  @ReactMethod
  fun stageFromUrl(url: String?, maxBytes: Double, promise: Promise) {
    if (!ExportStagerOutcome.isFetchableUrl(url)) {
      promise.reject(
          ExportStagerOutcome.ERROR_NOT_HTTP, "Only http(s) image URLs can be exported")
      return
    }
    if (busy) {
      promise.reject(ExportStagerOutcome.ERROR_BUSY, "An export is already in progress")
      return
    }
    // Claimed and published BEFORE the worker exists, so the worker cannot miss either one.
    busy = true
    pendingPromise = promise
    // Network I/O never runs on the JS thread.
    Thread {
          try {
            download(url!!, maxBytes, promise)
          } finally {
            busy = false
            pendingPromise = null
          }
        }
        .apply { name = "LookExportDownload" }
        .start()
  }

  private fun download(url: String, requestedMaxBytes: Double, promise: Promise) {
    val destination = ExportStaging.newStagedFile(reactContext.cacheDir)
    var connection: HttpURLConnection? = null
    try {
      connection =
          (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
          }
      if (connection.responseCode != HttpURLConnection.HTTP_OK) {
        destination.delete()
        promise.reject(ExportStagerOutcome.ERROR_DOWNLOAD_FAILED, "Image download failed")
        return
      }
      val declared = connection.contentType
      if (!ExportStagerOutcome.isImageMediaType(declared)) {
        destination.delete()
        promise.reject(ExportStagerOutcome.ERROR_UNSUPPORTED_TYPE, "Response is not an image")
        return
      }
      val staged = ExportStaging.stage(connection.inputStream, destination, requestedMaxBytes)
      staged.fold(
          onSuccess = { promise.resolve(ExportStaging.uriOf(it.file)) },
          onFailure = {
            promise.reject(ExportStaging.bridgeCode(it), "Exported image was rejected")
          },
      )
    } catch (_: Exception) {
      destination.delete()
      promise.reject(ExportStagerOutcome.ERROR_DOWNLOAD_FAILED, "Image download failed")
    } finally {
      runCatching { connection?.disconnect() }
    }
  }

  @ReactMethod
  fun shareStaged(uri: String?, mimeType: String?, title: String?, promise: Promise) {
    val file = stagedFileOrNull(uri)
    if (file == null) {
      promise.reject(ExportStagerOutcome.ERROR_NOT_STAGED, "Not an app-staged export")
      return
    }
    val activity = reactContext.currentActivity
    if (activity == null) {
      promise.reject(ExportStagerOutcome.ERROR_NO_ACTIVITY, "No foreground activity")
      return
    }
    val resolvedMime =
        if (ExportStagerOutcome.isImageMediaType(mimeType)) {
          ExportStagerOutcome.normalizeMediaType(mimeType!!)
        } else {
          ExportStaging.sniffMediaType(file)
        }
    val contentUri =
        try {
          FileProvider.getUriForFile(reactContext, fileProviderAuthority(), file)
        } catch (_: Exception) {
          null
        }
    if (contentUri == null) {
      promise.reject(ExportStagerOutcome.ERROR_SHARE_FAILED, "Could not expose the export")
      return
    }
    val send =
        Intent(Intent.ACTION_SEND).apply {
          type = resolvedMime
          putExtra(Intent.EXTRA_STREAM, contentUri)
          if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_TITLE, title)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    val chooser = Intent.createChooser(send, title)
    if (chooser.resolveActivity(reactContext.packageManager) == null) {
      promise.reject(ExportStagerOutcome.ERROR_NO_APP, "No app can receive the image")
      return
    }
    try {
      activity.startActivity(chooser)
      // Android gives no reliable "the user actually sent it" callback for a chooser, so the
      // honest result is "the sheet was shown"; RN's own vocabulary is used so a caller that
      // checks dismissedAction keeps working unchanged.
      promise.resolve(resultMap(ExportStagerOutcome.ACTION_SHARED))
    } catch (_: Exception) {
      promise.reject(ExportStagerOutcome.ERROR_SHARE_FAILED, "Could not open the share sheet")
    }
  }

  @ReactMethod
  fun saveToPhotos(uri: String?, promise: Promise) {
    val file = stagedFileOrNull(uri)
    if (file == null) {
      promise.reject(ExportStagerOutcome.ERROR_NOT_STAGED, "Not an app-staged export")
      return
    }
    if (!hasLegacyWritePermission()) {
      // API 29+ needs no permission for the app's own MediaStore inserts; below that the
      // manifest declares WRITE_EXTERNAL_STORAGE (maxSdkVersion=28) and it must be granted.
      promise.resolve(ExportStagerOutcome.SAVE_DENIED)
      return
    }
    val mimeType = ExportStaging.sniffMediaType(file)
    val collection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
          MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    val values =
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, DISPLAY_NAME)
          put(MediaStore.Images.Media.MIME_TYPE, mimeType)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // IS_PENDING keeps a half-written image out of the gallery until the copy lands.
            put(MediaStore.Images.Media.IS_PENDING, 1)
          }
        }
    var target: Uri? = null
    try {
      target = reactContext.contentResolver.insert(collection, values)
      if (target == null) {
        promise.resolve(ExportStagerOutcome.SAVE_FAILED)
        return
      }
      val written =
          reactContext.contentResolver.openOutputStream(target)?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
            true
          } ?: false
      if (!written) {
        runCatching { reactContext.contentResolver.delete(target, null, null) }
        promise.resolve(ExportStagerOutcome.SAVE_FAILED)
        return
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        reactContext.contentResolver.update(
            target,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null)
      }
      promise.resolve(ExportStagerOutcome.SAVE_SAVED)
    } catch (_: Exception) {
      runCatching { target?.let { reactContext.contentResolver.delete(it, null, null) } }
      promise.resolve(ExportStagerOutcome.SAVE_FAILED)
    }
  }

  @ReactMethod
  fun removeStaged(uri: String?, promise: Promise) {
    // Cleanup is best-effort and never throws to JS; a non-staged path is a silent no-op.
    runCatching { stagedFileOrNull(uri)?.delete() }
    promise.resolve(null)
  }

  @ReactMethod
  fun sweepStaged(maxAgeMs: Double, promise: Promise) {
    runCatching {
      ExportStaging.cleanupStale(
          reactContext.cacheDir, System.currentTimeMillis(), maxAgeMs.toLong())
    }
    promise.resolve(null)
  }

  private fun stagedFileOrNull(uri: String?): File? =
      ExportStaging.stagedFile(ExportStaging.stagingDir(reactContext.cacheDir), uri)

  private fun hasLegacyWritePermission(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
    return reactContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
  }

  private fun fileProviderAuthority(): String =
      reactContext.packageName + FILE_PROVIDER_SUFFIX

  private fun resultMap(action: String): WritableNativeMap =
      WritableNativeMap().apply { putString("action", action) }

  override fun invalidate() {
    // A pending download must not outlive the bridge; reject rather than leak the promise.
    pendingPromise?.reject(ExportStagerOutcome.ERROR_DOWNLOAD_FAILED, "Export was released")
    pendingPromise = null
    super.invalidate()
  }

  companion object {
    const val MODULE_NAME = "LookExportStager"

    /** Must match the `<provider android:authorities>` in AndroidManifest.xml. */
    const val FILE_PROVIDER_SUFFIX = ".exportfileprovider"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val DISPLAY_NAME = "look.jpg"
  }
}
