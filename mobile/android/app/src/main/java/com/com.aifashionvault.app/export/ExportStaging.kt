package com.aifashionvault.app.export

import com.aifashionvault.app.share.ShareImageStager
import com.aifashionvault.app.share.ShareLimits
import com.aifashionvault.app.share.ShareValidationException
import com.aifashionvault.app.share.isCanonicalUuid
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.util.UUID

/**
 * Pure JVM (no android.* imports) private staging for exported Looks.
 *
 * An authorized result is downloaded into an app-private cache directory and handed to JS as
 * a stable `file:` URI, which the share/save calls then convert to a `content:` URI through
 * FileProvider. Keeping the bytes app-private until the OS needs them is what bounds the
 * export: the app never writes a shared/external path itself.
 *
 * Byte/format/dimension limits reuse the app's existing frozen image policy
 * ([ShareImageStager] + [ShareLimits]) instead of defining a second, drifting copy, so an
 * image the share pipeline would refuse is refused here too.
 */
object ExportStaging {

  /** Direct child of `context.cacheDir`; must match `res/xml/look_export_paths.xml`. */
  const val DIR_NAME = "export-staging"

  private const val MAX_SWEEP = 64

  /**
   * The staging directory, created on demand: every caller (download target, sweep, staged
   * resolution) needs it to exist, and a missing parent would otherwise surface as a
   * confusing `FileNotFoundException` from the first write.
   */
  fun stagingDir(cacheDir: File): File = File(cacheDir, DIR_NAME).apply { mkdirs() }

  /** Reserves a fresh UUID-named file inside the staging directory. */
  fun newStagedFile(cacheDir: File): File = File(stagingDir(cacheDir), UUID.randomUUID().toString())

  /**
   * Reads at most the smaller of [requestedMaxBytes] and the frozen app cap from [source]
   * into [destination], validating that the bytes really are an allowed image. The caller's
   * bound is honored but can never RAISE the app's own limit. On any rejection
   * [destination] is removed, so a failed export never leaves a half-written file for the
   * share sheet to pick up.
   */
  fun stage(source: InputStream, destination: File, requestedMaxBytes: Double = 0.0): Result<Staged> {
    val cap =
        if (requestedMaxBytes >= 1.0 && requestedMaxBytes < ShareLimits.MAX_SHARE_BYTES) {
          requestedMaxBytes.toLong()
        } else {
          ShareLimits.MAX_SHARE_BYTES
        }
    val staged =
        try {
          ShareImageStager.copyWithLimits(
              source,
              { FileOutputStream(destination) },
              cap,
              ShareLimits.MAX_SHARE_PIXELS,
              ShareLimits.MAX_SHARE_EDGE,
          )
        } catch (error: Exception) {
          destination.delete()
          return Result.failure(error)
        }
    return staged.fold(
        onSuccess = { Result.success(Staged(destination, it.format, it.byteCount)) },
        onFailure = {
          destination.delete()
          Result.failure(it)
        },
    )
  }

  /** The `file:` URI shape JS receives and later hands back to share/save/remove. */
  fun uriOf(file: File): String = file.toURI().toString()

  /**
   * Resolves a staged file from a URI, refusing anything that is not a canonical `file:` URI
   * naming a regular file that is a DIRECT child of the canonical staging directory. Other
   * schemes, authorities, queries, fragments, traversal, nested paths and non-UUID names are
   * all refused, so a hostile or buggy JS caller cannot make the bridge read, share or delete
   * an arbitrary file.
   */
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
    if (uri.normalize().path != path) return null
    val canonicalDir = runCatching { stagingDir.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    if (file.parentFile != canonicalDir) return null
    if (!isCanonicalUuid(file.name)) return null
    if (!file.isFile) return null
    return file
  }

  /**
   * Deletes staged files older than [ttlMs]. Only direct children of the canonical staging
   * directory are eligible. Returns the number deleted; never throws.
   */
  fun cleanupStale(cacheDir: File, nowMs: Long, ttlMs: Long): Int {
    val dir = stagingDir(cacheDir)
    val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: return 0
    val cutoff = nowMs - ttlMs
    var deleted = 0
    val candidates =
        canonicalDir
            .listFiles { file -> file.isFile }
            ?.sortedBy { it.lastModified() }
            ?.take(MAX_SWEEP)
            .orEmpty()
    for (candidate in candidates) {
      val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
      if (canonical.parentFile != canonicalDir) continue
      if (canonical.lastModified() >= cutoff) continue
      if (canonical.delete()) deleted++
    }
    return deleted
  }

  /** Sniffs the staged bytes so the share/save MIME type never has to be invented. */
  fun sniffMediaType(file: File): String {
    val header = ByteArray(12)
    val read =
        runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(0)
    if (read < 12) return DEFAULT_MEDIA_TYPE
    return ShareImageStager.detectFormat(header, read) ?: DEFAULT_MEDIA_TYPE
  }

  /** Maps a staging failure to its bounded, non-sensitive bridge code. */
  fun bridgeCode(error: Throwable?): String =
      when ((error as? ShareValidationException)?.error) {
        ShareImageStager.StageError.TooLarge -> ExportStagerOutcome.ERROR_TOO_LARGE
        ShareImageStager.StageError.OversizedDimensions -> ExportStagerOutcome.ERROR_TOO_LARGE
        ShareImageStager.StageError.UnsupportedFormat -> ExportStagerOutcome.ERROR_UNSUPPORTED_TYPE
        ShareImageStager.StageError.Unreadable -> ExportStagerOutcome.ERROR_DOWNLOAD_FAILED
        is ShareImageStager.StageError.Io -> ExportStagerOutcome.ERROR_DOWNLOAD_FAILED
        null -> ExportStagerOutcome.ERROR_DOWNLOAD_FAILED
      }

  /** The media type used when a staged file cannot be sniffed (it never should be). */
  const val DEFAULT_MEDIA_TYPE = "image/jpeg"

  /** A validated private copy plus what the bytes actually are. */
  data class Staged(val file: File, val mediaType: String, val byteCount: Long)
}
