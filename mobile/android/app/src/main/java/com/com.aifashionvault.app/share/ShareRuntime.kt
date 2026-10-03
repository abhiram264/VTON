package com.aifashionvault.app.share

import android.content.Context
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors


private const val SHARE_ROOT = "share-handoff"
private const val MAX_ORPHAN_SCAN = 64
/**
 * Process-wide holders for the share subsystem.
 *
 * A single dedicated single-thread executor runs all staging work off the UI thread; a single
 * [PendingShareStore] instance backs both the receiver activity and the RN bridge so consume
 * atomicity holds across entry points.
 */
object ShareRuntime {

  @Volatile private var store: PendingShareStore? = null

  fun pendingShareStore(context: Context): PendingShareStore {
    val appContext = context.applicationContext
    val root = File(appContext.filesDir, SHARE_ROOT).apply { mkdirs() }
    val metaDir = File(root, "share-pending")
    val staging = File(root, "share-staging").apply { mkdirs() }
    val existing = store
    if (existing != null) {
      existing.purgeExpired()
      cleanupOrphans(metaDir, staging)
      return existing
    }
    return synchronized(this) {
      store ?: run {
        cleanupOrphans(metaDir, staging)
        PendingShareStore(metaDir, staging).also { store = it }
      }
    }
  }

  fun stagingDir(context: Context): File =
      File(context.applicationContext.filesDir, "$SHARE_ROOT/share-staging").apply { mkdirs() }

  fun newStagedFile(context: Context): File =
      File(stagingDir(context), UUID.randomUUID().toString())

  private fun cleanupOrphans(metaDir: File, stagingDir: File) {
    val referenced =
        metaDir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.mapNotNull { file ->
              runCatching { PendingShareCodec.decode(file.readText()) }.getOrNull()
            }
            ?.mapNotNull { pending -> stagedFile(pending.payload) }
            ?.mapTo(mutableSetOf()) { it.path }
            .orEmpty()
    val cutoff = System.currentTimeMillis() - ShareLimits.PENDING_SHARE_TTL_MS
    stagingDir
        .listFiles { file -> file.isFile }
        ?.sortedBy { it.lastModified() }
        ?.take(MAX_ORPHAN_SCAN)
        ?.forEach { candidate ->
          val canonical =
              runCatching { candidate.canonicalFile }.getOrNull() ?: return@forEach
          if (canonical.parentFile == stagingDir.canonicalFile &&
              canonical.name !in referenced &&
              canonical.lastModified() < cutoff) {
            candidate.delete()
          }
        }
  }

  private fun stagedFile(payload: SharePayload): File? {
    val localUri =
        when (payload) {
          is SharePayload.Url -> return null
          is SharePayload.Image -> payload.localImageUri
          is SharePayload.ImageUrl -> payload.localImageUri
        }
    return runCatching { File(URI(localUri)).canonicalFile }.getOrNull()
  }

  val stagingExecutor by lazy { Executors.newSingleThreadExecutor() }
}
