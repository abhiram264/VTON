package com.aifashionvault.app.share

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Acceptance criteria 14, 15, 16, and store contract: cap, TTL, atomicity, one-shot consume,
 * replay rejection, distinct ids, sourcePackage omission, payload boundary validation.
 */
class PendingShareStoreTest {

  @get:Rule val tmp = TemporaryFolder()

  private var now = 1_000_000L

  private fun staging(): File = File(tmp.root, "files/share-staging").apply { mkdirs() }

  private fun store() = PendingShareStore(tmp.newFolder(), staging(), clock = { now })

  private fun stagedTmpFile(stagingDir: File = staging()): File =
      File(stagingDir, UUID.randomUUID().toString()).apply { writeBytes(ByteArray(8)) }

  @Test
  fun `put stores and consume returns payload exactly once`() {
    val store = store()
    val put = store.put(SharePayload.Url("https://example.com/p/1"))
    assertTrue(put is PendingShareStore.PutResult.Stored)
    val id = (put as PendingShareStore.PutResult.Stored).pending.shareId

    val consumed = store.consume(id)
    assertTrue(consumed is PendingShareStore.ConsumeResult.Consumed)
    val payload = (consumed as PendingShareStore.ConsumeResult.Consumed).pending.payload
    assertEquals(SharePayload.Url("https://example.com/p/1"), payload)

    val replay = store.consume(id)
    assertTrue(replay is PendingShareStore.ConsumeResult.NotFound)
  }

  @Test
  fun `share ids are opaque lowercase canonical uuids`() {
    val store = store()
    repeat(16) {
      val id = store.newShareId()
      assertEquals(36, id.length)
      assertEquals(id, id.lowercase())
      assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }
  }

  @Test
  fun `two distinct shares do not overwrite each other and each consumes at most once`() {
    val store = store()
    val a = (store.put(SharePayload.Url("https://a.example.com/1")) as PendingShareStore.PutResult.Stored).pending.shareId
    val b = (store.put(SharePayload.Url("https://b.example.com/2")) as PendingShareStore.PutResult.Stored).pending.shareId
    assertFalse(a == b)

    val consumedB = store.consume(b) as PendingShareStore.ConsumeResult.Consumed
    assertEquals(SharePayload.Url("https://b.example.com/2"), consumedB.pending.payload)

    val consumedA = store.consume(a) as PendingShareStore.ConsumeResult.Consumed
    assertEquals(SharePayload.Url("https://a.example.com/1"), consumedA.pending.payload)

    assertTrue(store.consume(b) is PendingShareStore.ConsumeResult.NotFound)
    assertTrue(store.consume(a) is PendingShareStore.ConsumeResult.NotFound)
  }

  @Test
  fun `ninth unexpired entry is rejected rather than evicting an earlier entry`() {
    val metaDir = tmp.newFolder()
    val store = PendingShareStore(metaDir, staging(), clock = { now })
    repeat(ShareLimits.MAX_PENDING_SHARES) {
      assertTrue(store.put(SharePayload.Url("https://example.com/$it")) is PendingShareStore.PutResult.Stored)
    }
    val ninth = store.put(SharePayload.Url("https://example.com/9"))
    assertTrue(ninth is PendingShareStore.PutResult.StoreFull)
    assertEquals(ShareLimits.MAX_PENDING_SHARES, store.size())
    // Earlier entries still present on disk.
    val ids = metaDir.listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }
    assertEquals(ShareLimits.MAX_PENDING_SHARES, ids.size)
  }

  @Test
  fun `expired entries are purged on put and staged files deleted`() {
    val metaDir = tmp.newFolder()
    val stagingDir = staging()
    val store = PendingShareStore(metaDir, stagingDir, clock = { now })
    val staged = File(stagingDir, UUID.randomUUID().toString()).apply { writeBytes(ByteArray(4)) }
    val put =
        store.put(
            SharePayload.Image(staged.toURI().toString())) as PendingShareStore.PutResult.Stored

    now += ShareLimits.PENDING_SHARE_TTL_MS + 1

    val fresh = store.put(SharePayload.Url("https://example.com/fresh"))
    assertTrue(fresh is PendingShareStore.PutResult.Stored)
    // Expired metadata and its staged file are gone.
    assertFalse(File(metaDir, "${put.pending.shareId}.json").exists())
    assertFalse(staged.exists())

    val consumed = store.consume(put.pending.shareId)
    assertTrue(consumed is PendingShareStore.ConsumeResult.NotFound)
  }

  @Test
  fun `entry exactly at TTL is still consumable, one ms older is expired`() {
    val store = store()
    val put = store.put(SharePayload.Url("https://example.com/x")) as PendingShareStore.PutResult.Stored
    now += ShareLimits.PENDING_SHARE_TTL_MS
    assertTrue(store.consume(put.pending.shareId) is PendingShareStore.ConsumeResult.Consumed)

    val put2 = store.put(SharePayload.Url("https://example.com/y")) as PendingShareStore.PutResult.Stored
    now += ShareLimits.PENDING_SHARE_TTL_MS + 1
    assertTrue(store.consume(put2.pending.shareId) is PendingShareStore.ConsumeResult.NotFound)
  }

  @Test
  fun `invalid share ids reject as invalid_share_id`() {
    val store = store()
    store.put(SharePayload.Url("https://example.com/1"))
    assertTrue(store.consume(null) is PendingShareStore.ConsumeResult.InvalidShareId)
    assertTrue(store.consume("") is PendingShareStore.ConsumeResult.InvalidShareId)
    assertTrue(store.consume("not-a-uuid") is PendingShareStore.ConsumeResult.InvalidShareId)
    assertTrue(store.consume("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB") is PendingShareStore.ConsumeResult.InvalidShareId)
    assertTrue(store.consume("../etc/passwd") is PendingShareStore.ConsumeResult.InvalidShareId)
    // Uppercase canonical uuid is noncanonical.
    assertTrue(
        store.consume("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB".lowercase().uppercase()) is PendingShareStore.ConsumeResult.InvalidShareId)
  }

  @Test
  fun `unknown but canonical id returns share_not_found`() {
    val store = store()
    val unknown = "01234567-89ab-cdef-0123-456789abcdef"
    assertTrue(store.consume(unknown) is PendingShareStore.ConsumeResult.NotFound)
  }

  @Test
  fun `kind-invalid payloads reject at store boundary`() {
    val store = store()
    assertTrue(store.put(SharePayload.Url("")) is PendingShareStore.PutResult.InvalidPayload)
    assertTrue(store.put(SharePayload.Image("content://x/1")) is PendingShareStore.PutResult.InvalidPayload)
    assertTrue(store.put(SharePayload.ImageUrl("https://a.com/1", "https://b.com/2")) is PendingShareStore.PutResult.InvalidPayload)
  }


  @Test
  fun `image payloads require a canonical regular UUID file directly under staging`() {
    val stagingDir = staging()
    val store = PendingShareStore(tmp.newFolder(), stagingDir, clock = { now })
    val missingId = UUID.randomUUID().toString()
    val missing = File(stagingDir, missingId).toURI().toString()
    val directoryId = UUID.randomUUID().toString()
    val directory = File(stagingDir, directoryId).apply { mkdirs() }
    val nonUuid = File(stagingDir, "not-a-uuid").apply { writeText("x") }
    val uppercase = File(stagingDir, UUID.randomUUID().toString().uppercase()).apply { writeText("x") }
    val foreign = File(tmp.root, "foreign-${UUID.randomUUID()}").apply { writeText("x") }
    val alias = "${stagingDir.toURI().toString().trimEnd('/')}/../${stagingDir.name}/$missingId"
    assertTrue(store.put(SharePayload.Image(missing)) is PendingShareStore.PutResult.InvalidPayload)
    File(stagingDir, missingId).writeText("x")
    assertTrue(store.put(SharePayload.Image(File(stagingDir, missingId).toURI().toString())) is PendingShareStore.PutResult.Stored)
    assertTrue(store.put(SharePayload.Image(directory.toURI().toString())) is PendingShareStore.PutResult.InvalidPayload)
    assertTrue(store.put(SharePayload.Image(nonUuid.toURI().toString())) is PendingShareStore.PutResult.InvalidPayload)
    assertTrue(store.put(SharePayload.Image(uppercase.toURI().toString())) is PendingShareStore.PutResult.InvalidPayload)
    assertTrue(store.put(SharePayload.Image(foreign.toURI().toString())) is PendingShareStore.PutResult.InvalidPayload)
    assertTrue(store.put(SharePayload.Image(alias)) is PendingShareStore.PutResult.InvalidPayload)
  }

  @Test
  fun `metadata identity and unreadable records return typed invalid payload`() {
    val metaDir = tmp.newFolder()
    val store = PendingShareStore(metaDir, staging(), clock = { now })
    val requestedId = UUID.randomUUID().toString()
    val otherId = UUID.randomUUID().toString()
    File(metaDir, "$requestedId.json")
        .writeText(PendingShareCodec.encode(PendingShare(otherId, SharePayload.Url("https://example.com/1"), now)))
    assertTrue(store.consume(requestedId) is PendingShareStore.ConsumeResult.InvalidPayload)

    val unreadableId = UUID.randomUUID().toString()
    File(metaDir, "$unreadableId.json").mkdirs()
    assertTrue(store.consume(unreadableId) is PendingShareStore.ConsumeResult.InvalidPayload)
  }

  @Test
  fun `future metadata timestamp returns typed invalid payload and is excluded from capacity`() {
    val metaDir = tmp.newFolder()
    val store = PendingShareStore(metaDir, staging(), clock = { now })
    val futureIds = mutableListOf<String>()
    repeat(ShareLimits.MAX_PENDING_SHARES) {
      val id = UUID.randomUUID().toString()
      futureIds += id
      File(metaDir, "$id.json").writeText(
          PendingShareCodec.encode(PendingShare(id, SharePayload.Url("https://example.com/$it"), now + 1)))
    }

    assertTrue(store.consume(futureIds.first()) is PendingShareStore.ConsumeResult.InvalidPayload)

    assertEquals(0, store.size())
    assertTrue(store.put(SharePayload.Url("https://example.com/fresh")) is PendingShareStore.PutResult.Stored)
  }
  @Test
  fun `sourcePackage omitted when absent and round-trips when present`() {
    val store = store()
    val noSender = store.put(SharePayload.Url("https://example.com/1")) as PendingShareStore.PutResult.Stored
    assertEquals(null, noSender.pending.payload.sourcePackage)
    val consumed = store.consume(noSender.pending.shareId) as PendingShareStore.ConsumeResult.Consumed
    assertEquals(null, consumed.pending.payload.sourcePackage)
    assertEquals(SharePayload.Url("https://example.com/1"), consumed.pending.payload)

    val staged = stagedTmpFile()
    val withSender =
        store.put(SharePayload.Image(staged.toURI().toString(), "com.trusted.sender")) as PendingShareStore.PutResult.Stored
    val consumed2 = store.consume(withSender.pending.shareId) as PendingShareStore.ConsumeResult.Consumed
    assertEquals("com.trusted.sender", consumed2.pending.payload.sourcePackage)
  }

  @Test
  fun `codec round-trips all three kinds with optional field omission`() {
    val url = PendingShare("01234567-89ab-cdef-0123-456789abcdef", SharePayload.Url("https://a.com/1"), 5L)
    assertEquals(url, PendingShareCodec.decode(PendingShareCodec.encode(url)))

    val img =
        PendingShare(
            "01234567-89ab-cdef-0123-456789abcdef",
            SharePayload.Image("file:///files/share-staging/01234567-89ab-cdef-0123-456789abcdef", "com.sender"),
            6L)
    assertEquals(img, PendingShareCodec.decode(PendingShareCodec.encode(img)))

    val imgurl =
        PendingShare(
            "01234567-89ab-cdef-0123-456789abcdef",
            SharePayload.ImageUrl("https://a.com/1", "file:///files/share-staging/01234567-89ab-cdef-0123-456789abcdef"),
            7L)
    assertEquals(imgurl, PendingShareCodec.decode(PendingShareCodec.encode(imgurl)))

    val encoded = PendingShareCodec.encode(url)
    assertFalse(encoded.contains("sourcePackage"))
    assertFalse(encoded.contains("localImageUri"))
  }

  @Test
  fun `codec rejects malformed input`() {
    assertEquals(null, PendingShareCodec.decode(null))
    assertEquals(null, PendingShareCodec.decode(""))
    assertEquals(null, PendingShareCodec.decode("garbage that is not properties"))
    assertEquals(
        null,
        PendingShareCodec.decode("shareId=x\ncreatedAtEpochMillis=notanumber\nkind=url\nurl=https://a.com\n"))
    assertEquals(null, PendingShareCodec.decode("kind=url\nurl=https://a.com\n"))
    assertEquals(null, PendingShareCodec.decode("shareId=01234567-89ab-cdef-0123-456789abcdef\ncreatedAtEpochMillis=-1\nkind=url\nurl=https://a.com\n"))
  }
}
