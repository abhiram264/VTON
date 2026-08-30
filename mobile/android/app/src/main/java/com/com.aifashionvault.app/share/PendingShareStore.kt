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
  }

  fun newShareId(): String = UUID.randomUUID().toString()

  /**
   * Atomically stores [payload]. Returns [PutResult.StoreFull] when the cap is reached after
   * TTL cleanup; [PutResult.InvalidPayload] when the payload violates its kind contract.
   */
  @Synchronized
  fun put(payload: SharePayload): PutResult {
    if (!SharePayloadValidator.isValid(payload)) return PutResult.InvalidPayload
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
    if (!metaFile.exists()) {
      return if (isCanonicalUuid(shareId)) ConsumeResult.NotFound else ConsumeResult.InvalidShareId
    }
    val pending =
        PendingShareCodec.decode(metaFile.readText())
            ?: run {
              // Unparseable entry: treat as invalid payload but still remove it.
              metaFile.delete()
              return ConsumeResult.InvalidPayload
            }
    if (!SharePayloadValidator.isValid(pending.payload)) {
      metaFile.delete()
      return ConsumeResult.InvalidPayload
    }
    if (clock() - pending.createdAtEpochMillis > ShareLimits.PENDING_SHARE_TTL_MS) {
      // Expired between purge and read; clean up now.
      metaFile.delete()
      stagedFileFor(pending.payload)?.delete()
      return ConsumeResult.Expired
    }
    if (!metaFile.delete()) {
      return ConsumeResult.NotFound
    }
    return ConsumeResult.Consumed(pending)
  }

  /** Removes expired entries and deletes their staged files. Returns the count purged. */
  @Synchronized
  fun purgeExpired(): Int {
    val now = clock()
    var purged = 0
    val files = metaDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
    for (metaFile in files) {
      val pending = PendingShareCodec.decode(metaFile.readText()) ?: continue
      if (now - pending.createdAtEpochMillis > ShareLimits.PENDING_SHARE_TTL_MS) {
        stagedFileFor(pending.payload)?.delete()
        if (metaFile.delete()) purged++
      }
    }
    return purged
  }

  fun size(): Int = listEntries().size

  private fun listEntries(): List<PendingShare> {
    val files = metaDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
    return files.mapNotNull { PendingShareCodec.decode(it.readText()) }
  }

  private fun fileFor(shareId: String) = File(metaDir, "$shareId.json")

  private fun stagedFileFor(payload: SharePayload): File? =
      when (payload) {
        is SharePayload.Image -> File(java.net.URI(payload.localImageUri).path)
        is SharePayload.ImageUrl -> File(java.net.URI(payload.localImageUri).path)
        is SharePayload.Url -> null
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
