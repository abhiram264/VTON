package com.aifashionvault.app.share

import java.io.File
import java.util.UUID

/**
 * App-private, file-backed, one-shot pending-share store.
 *
 * Contract (spec "Payload semantics"):
 * - Opaque lowercase-canonical-UUID share ids.
 * - Atomic put and consume: a consume removes the entry before returning it; the same id can
 *   never succeed twice.
 * - Before every put/consume, entries older than the TTL are removed and their staged files
 *   deleted.
 * - Cap at [ShareLimits.MAX_PENDING_SHARES]; when full after expiry cleanup, the *new* share
 *   is rejected (never silently evicting a live unconsumed entry).
 * - Payload validation at the store boundary: kind-invalid payloads reject.
 * - Persists only pending-handoff metadata; it is not a domain database.
 *
 * Storage layout: one metadata JSON file per share under [metaDir]; staged image files are
 * referenced by absolute path and are owned by the store until consumption transfers cleanup
 * ownership to the JS route/resolver flow.
 */
class PendingShareStore(
    private val metaDir: File,
    private val stagingDir: File = metaDir.parentFile ?: metaDir,
    private val clock: () -> Long = System::currentTimeMillis,
) {

  sealed interface PutResult {
    data class Stored(val pending: PendingShare) : PutResult

    data object StoreFull : PutResult

    data object InvalidPayload : PutResult
  }

  sealed interface ConsumeResult {
    data class Consumed(val pending: PendingShare) : ConsumeResult

    data object InvalidShareId : ConsumeResult

    data object NotFound : ConsumeResult

    data object Expired : ConsumeResult
    data object InvalidPayload : ConsumeResult
  }

  init {
    metaDir.mkdirs()
    cleanupTempFiles()
  }

  private fun cleanupTempFiles() {
    metaDir.listFiles { file -> file.isFile && file.name.endsWith(".tmp") }?.forEach { it.delete() }
  }

  fun newShareId(): String = UUID.randomUUID().toString()

  /**
   * Atomically stores [payload]. Returns [PutResult.StoreFull] when the cap is reached after
   * TTL cleanup; [PutResult.InvalidPayload] when the payload violates its kind contract.
   */
  @Synchronized
  fun put(payload: SharePayload): PutResult {
    if (!SharePayloadValidator.isValid(payload) || !isWithinStaging(payload)) {
      return PutResult.InvalidPayload
    }
    cleanupTempFiles()
    purgeExpired()
    val entries = listEntries()
    if (entries.size >= ShareLimits.MAX_PENDING_SHARES) return PutResult.StoreFull
    val shareId = newShareId()
    val pending = PendingShare(shareId, payload, clock())
    val metaFile = fileFor(shareId)
    val tmp = File(metaDir, "$shareId.tmp")
    tmp.writeText(PendingShareCodec.encode(pending))
    if (!tmp.renameTo(metaFile)) {
      tmp.delete()
      return PutResult.StoreFull
    }
    return PutResult.Stored(pending)
  }

  /**
   * Atomically consumes the pending share identified by [shareId]. The entry is removed before
   * the payload is returned; subsequent consumes of the same id return [ConsumeResult.NotFound].
   */
  @Synchronized
  fun consume(shareId: String?): ConsumeResult {
    if (shareId == null || !isCanonicalUuid(shareId)) return ConsumeResult.InvalidShareId
    purgeExpired()
    val metaFile = fileFor(shareId)
    if (!metaFile.exists()) return ConsumeResult.NotFound
    val pending = readPending(metaFile) ?: run {
      metaFile.delete()
      return ConsumeResult.InvalidPayload
    }
    if (!isCanonicalUuid(pending.shareId) || pending.shareId != shareId) {
      metaFile.delete()
      return ConsumeResult.InvalidPayload
    }
    if (pending.createdAtEpochMillis > clock() ||
        !SharePayloadValidator.isValid(pending.payload) ||
        !isWithinStaging(pending.payload)) {
      deleteEntry(metaFile, pending)
      return ConsumeResult.InvalidPayload
    }
    if (clock() - pending.createdAtEpochMillis > ShareLimits.PENDING_SHARE_TTL_MS) {
      deleteEntry(metaFile, pending)
      return ConsumeResult.Expired
    }
    if (!metaFile.delete()) return ConsumeResult.NotFound
    return ConsumeResult.Consumed(pending)
  }

  /** Removes expired entries and deletes their staged files. Returns the count purged. */
  @Synchronized
  fun purgeExpired(): Int {
    val now = clock()
    var purged = 0
    val files = metaDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
    for (metaFile in files) {
      val pending = readPending(metaFile) ?: continue
      val fileShareId = metaFile.name.removeSuffix(".json")
      if (!isValidEntry(pending, fileShareId, now)) continue
      if (now - pending.createdAtEpochMillis > ShareLimits.PENDING_SHARE_TTL_MS &&
          deleteEntry(metaFile, pending)) {
        purged++
      }
    }
    return purged
  }

  @Synchronized
  fun isStagedFileReferenced(uri: String?): Boolean {
    val target = StagedImagePaths.stagedFile(stagingDir, uri) ?: return false
    purgeExpired()
    return listEntries().any { stagedFileFor(it.payload)?.canonicalFile == target }
  }

  fun size(): Int = listEntries().size

  private fun listEntries(): List<PendingShare> {
    val now = clock()
    val files = metaDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
    return files.mapNotNull { metaFile ->
      val pending = readPending(metaFile) ?: return@mapNotNull null
      val fileShareId = metaFile.name.removeSuffix(".json")
      pending.takeIf { isValidEntry(it, fileShareId, now) }
    }
  }

  private fun readPending(metaFile: File): PendingShare? =
      try {
        PendingShareCodec.decode(metaFile.readText())
      } catch (_: Exception) {
        null
      }

  private fun isValidEntry(pending: PendingShare, expectedShareId: String, now: Long): Boolean =
      isCanonicalUuid(pending.shareId) &&
          pending.shareId == expectedShareId &&
          pending.createdAtEpochMillis <= now

  private fun deleteEntry(metaFile: File, pending: PendingShare): Boolean {
    val stagedFile = stagedFileFor(pending.payload)
    if (stagedFile != null && !stagedFile.delete() && stagedFile.exists()) return false
    return metaFile.delete()
  }

  private fun fileFor(shareId: String) = File(metaDir, "$shareId.json")

  private fun isWithinStaging(payload: SharePayload): Boolean =
      when (payload) {
        is SharePayload.Url -> true
        is SharePayload.Image -> stagedFileFor(payload) != null
        is SharePayload.ImageUrl -> stagedFileFor(payload) != null
      }

  private fun stagedFileFor(payload: SharePayload): File? {
    val localImageUri = when (payload) {
      is SharePayload.Url -> return null
      is SharePayload.Image -> payload.localImageUri
      is SharePayload.ImageUrl -> payload.localImageUri
    }
    return StagedImagePaths.stagedFile(stagingDir, localImageUri)
  }
}


internal fun isCanonicalUuid(id: String): Boolean {
  if (id.length != 36) return false
  if (id != id.lowercase()) return false
  if (id[8] != '-' || id[13] != '-' || id[18] != '-' || id[23] != '-') {
    return false
  }
  return id.all { it == '-' || it in '0'..'9' || it in 'a'..'f' }
}
