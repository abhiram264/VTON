package com.aifashionvault.app.share

import java.io.File
import java.net.URI

/**
 * Pure (no React Native types, JVM-testable) builders for the ShareHandoff bridge surface.
 * [ShareHandoffNativeModule] converts these plain maps to WritableNativeMap at the JNI edge.
 */
object ShareHandoffEnvelope {

  /**
   * Renders the frozen ConsumeShareResult envelope: `{ ok: true, payload: {...} }` on success
   * and `{ ok: false, code: <typed code> }` on failure. Typed failures are values here, never
   * rejected promises.
   */
  fun build(result: PendingShareStore.ConsumeResult): Map<String, Any> =
      when (result) {
        is PendingShareStore.ConsumeResult.Consumed ->
            mapOf("ok" to true, "payload" to payloadMap(result.pending.payload))
        PendingShareStore.ConsumeResult.InvalidShareId -> failure("invalid_share_id")
        PendingShareStore.ConsumeResult.NotFound -> failure("share_not_found")
        PendingShareStore.ConsumeResult.Expired -> failure("share_expired")
        PendingShareStore.ConsumeResult.InvalidPayload -> failure("invalid_payload")
      }

  /** Kind-specific required fields; optionals are omitted, never serialized as null. */
  fun payloadMap(payload: SharePayload): Map<String, String> {
    val map =
        when (payload) {
          is SharePayload.Url -> mapOf("kind" to "url", "url" to payload.url)
          is SharePayload.Image ->
              mapOf("kind" to "image", "localImageUri" to payload.localImageUri)
          is SharePayload.ImageUrl ->
              mapOf(
                  "kind" to "image_url",
                  "url" to payload.url,
                  "localImageUri" to payload.localImageUri)
        }
    return payload.sourcePackage?.let { map + ("sourcePackage" to it) } ?: map
  }

  private fun failure(code: String): Map<String, Any> = mapOf("ok" to false, "code" to code)
}

/**
 * Decides whether a local image URI may be released (deleted) by
 * `ShareHandoffNativeModule.releaseStagedImage`: only a canonical `file:` URI whose canonical
 * path is directly under the staging directory with a bare lowercase-canonical UUID filename
 * qualifies. Everything else — other schemes, traversal, foreign absolute paths, directories,
 * non-UUID names — is rejected (silent no-op at the bridge). No URI value is ever logged
 * (ADR-015).
 */
object StagedImagePaths {

  fun stagedFile(stagingDir: File, uriString: String?): File? {
    if (uriString == null) return null
    val uri =
        try {
          URI(uriString)
        } catch (_: Exception) {
          return null
        }
    if (uri.scheme != "file") return null
    if (uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val path = uri.path ?: return null
    if (!path.startsWith("/") || path.contains('\\')) return null
    val staging = stagingDir.canonicalFile
    // canonicalFile resolves symlinks and ../ segments, so containment is checked on the
    // resolved target; parent equality rejects anything not a direct child.
    val file = File(path).canonicalFile
    if (uri.normalize().path != path) return null
    if (file.parentFile?.canonicalFile != staging) return null
    if (!isCanonicalUuid(file.name)) return null
    if (!file.isFile) return null
    return file
  }
}
