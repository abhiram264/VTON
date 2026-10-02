package com.aifashionvault.app.photopicker

import java.io.File
import java.util.UUID

/**
 * App-private staging for photo-picker results.
 *
 * Picked and captured images are copied into a dedicated directory under the application
 * cache so the JS layer receives a stable `file:` URI it can upload, and so the app never
 * holds a raw foreign `content:` grant. The cache directory is deliberately used (not
 * filesDir): staged photos are temporary and may be reclaimed by the OS.
 *
 * Pure JVM (only [java.io.File]) so the naming, containment and retention rules are
 * unit-testable without an Android device.
 */
object PhotoPickerStaging {

  const val DIR_NAME = "photo-picker"

  /** Staged photos are transient; anything older than a day is abandoned. */
  const val STAGED_FILE_TTL_MS = 86_400_000L

  /** Bounds the per-run cleanup scan; the directory only ever holds a handful of files. */
  private const val MAX_CLEANUP_SCAN = 64

  fun stagingDir(cacheDir: File): File = File(cacheDir, DIR_NAME)

  /** Creates the staging directory (if needed) and reserves a fresh UUID-named file. */
  fun newStagedFile(cacheDir: File): File {
    val dir = stagingDir(cacheDir).apply { mkdirs() }
    return File(dir, UUID.randomUUID().toString())
  }

  /** The canonical URI shape the share pipeline already uses for staged images. */
  fun uriOf(file: File): String = file.toURI().toString()

  /**
   * Deletes staged files older than [STAGED_FILE_TTL_MS]. Only direct children of the
   * canonical staging directory are eligible, so a symlinked or nested path cannot escape.
   * Returns the number of files deleted.
   */
  fun cleanupStale(cacheDir: File, nowMs: Long = System.currentTimeMillis()): Int {
    val dir = stagingDir(cacheDir)
    val canonicalDir = runCatching { dir.canonicalFile }.getOrNull() ?: return 0
    val cutoff = nowMs - STAGED_FILE_TTL_MS
    var deleted = 0
    dir.listFiles { file -> file.isFile }
        ?.sortedBy { it.lastModified() }
        ?.take(MAX_CLEANUP_SCAN)
        ?.forEach { candidate ->
          val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
          if (canonical.parentFile == canonicalDir && canonical.lastModified() < cutoff) {
            if (candidate.delete()) deleted++
          }
        }
    return deleted
  }
}
