package com.aifashionvault.app.export

import java.util.Locale

/**
 * Pure (no android.* imports, JVM-testable) validation + result builders for the
 * `NativeModules.LookExportStager` bridge.
 *
 * The bridge exists because React Native core cannot do the two halves of a user-directed
 * export on Android: it has no private-file downloader, and its built-in `Share` is
 * text-only (`ShareModule` builds `ACTION_SEND` with `setTypeAndNormalize("text/plain")` and
 * never sets `EXTRA_STREAM`), so it can share a URL string but never the image itself. This
 * module therefore owns download-into-private-staging, the OS share sheet, save-to-photos
 * and staged-file cleanup.
 *
 * The JS seam this must match is frozen in `mobile/src/features/export/lookExport.ts`:
 * `stageFromUrl(url, maxBytes) -> Promise<string>`, `removeStaged(uri) -> Promise<void>`,
 * `sweepStaged(maxAgeMs) -> Promise<void>`,
 * `saveToPhotos(uri) -> Promise<'saved' | 'denied' | 'failed'>`.
 *
 * Failures that JS classifies as outcomes (`denied`, `failed`) are resolved values, never
 * rejections. A genuine failure rejects with a bounded, non-sensitive code — no URL, path or
 * byte content ever appears in a code, a message, or a log.
 */
object ExportStagerOutcome {

  const val ERROR_NOT_HTTP = "E_EXPORT_NOT_HTTP"
  const val ERROR_DOWNLOAD_FAILED = "E_EXPORT_DOWNLOAD_FAILED"
  const val ERROR_TOO_LARGE = "E_EXPORT_TOO_LARGE"
  const val ERROR_UNSUPPORTED_TYPE = "E_EXPORT_UNSUPPORTED_TYPE"
  const val ERROR_NOT_STAGED = "E_EXPORT_NOT_STAGED"
  const val ERROR_NO_ACTIVITY = "E_EXPORT_NO_ACTIVITY"
  const val ERROR_NO_APP = "E_EXPORT_NO_APP"
  const val ERROR_SHARE_FAILED = "E_EXPORT_SHARE_FAILED"
  const val ERROR_BUSY = "E_EXPORT_BUSY"

  /** The resolved `saveToPhotos` outcome vocabulary the JS seam switches on. */
  const val SAVE_SAVED = "saved"
  const val SAVE_DENIED = "denied"
  const val SAVE_FAILED = "failed"

  /** The resolved `shareStaged` action vocabulary, matching the JS `{action?}` contract. */
  const val ACTION_SHARED = "sharedAction"
  const val ACTION_DISMISSED = "dismissedAction"

  /**
   * Only absolute http/https targets may be fetched. `file:`/`content:`/`data:` and
   * scheme-relative `//host` are refused, and an empty authority (`https://`) is refused
   * too, so the bridge can never be pointed at a local file or an unbounded scheme.
   */
  fun isFetchableUrl(url: String?): Boolean {
    if (url == null) return false
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase(Locale.ROOT)
    val schemeEnd = lower.indexOf("://")
    if (!(lower.startsWith("https://") || lower.startsWith("http://"))) return false
    return trimmed.length > schemeEnd + 3
  }

  /**
   * The declared response type must be an image. `null` means the server declared nothing,
   * which is not evidence of an image, so it is refused rather than guessed.
   */
  fun isImageMediaType(contentType: String?): Boolean {
    if (contentType == null) return false
    val normalized = normalizeMediaType(contentType)
    return normalized.startsWith("image/") && normalized.length > "image/".length
  }

  /** Strips parameters so `image/jpeg; charset=x` becomes `image/jpeg`. */
  fun normalizeMediaType(contentType: String): String =
      contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
}
