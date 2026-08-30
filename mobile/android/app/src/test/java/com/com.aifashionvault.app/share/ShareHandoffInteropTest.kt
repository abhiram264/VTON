package com.aifashionvault.app.share

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * HIGH-1: the ConsumeShareResult envelope rendered by the ShareHandoff bridge, built purely
 * (no WritableNativeMap — JNI-backed and unusable in JVM tests). Success yields ok=true plus
 * a payload map with kind-specific required fields and omitted optionals; each failure kind
 * yields ok=false with the exact typed code; the envelope never contains null optionals.
 */
class ShareHandoffEnvelopeTest {

  private val payloadUrl = SharePayload.Url("https://example.com/a")
  private val payloadImage = SharePayload.Image("file:///tmp/x/uuid")
  private val payloadImageUrl =
      SharePayload.ImageUrl("https://example.com/a", "file:///tmp/x/uuid")

  @Test
  fun `success yields ok true with url payload required fields`() {
    val env = ShareHandoffEnvelope.build(consumed(payloadUrl))
    assertEquals(true, env["ok"])
    val payload = env["payload"] as Map<*, *>
    assertEquals("url", payload["kind"])
    assertEquals("https://example.com/a", payload["url"])
    assertFalse(payload.containsKey("localImageUri"))
    assertFalse(payload.containsKey("sourcePackage"))
  }

  @Test
  fun `success yields ok true with image payload required fields`() {
    val env = ShareHandoffEnvelope.build(consumed(payloadImage))
    assertEquals(true, env["ok"])
    val payload = env["payload"] as Map<*, *>
    assertEquals("image", payload["kind"])
    assertEquals("file:///tmp/x/uuid", payload["localImageUri"])
    assertFalse(payload.containsKey("url"))
  }

  @Test
  fun `success yields ok true with image_url payload required fields`() {
    val env = ShareHandoffEnvelope.build(consumed(payloadImageUrl))
    assertEquals(true, env["ok"])
    val payload = env["payload"] as Map<*, *>
    assertEquals("image_url", payload["kind"])
    assertEquals("https://example.com/a", payload["url"])
    assertEquals("file:///tmp/x/uuid", payload["localImageUri"])
  }

  @Test
  fun `sourcePackage round-trips as payload field and is omitted when absent`() {
    val withPkg =
        ShareHandoffEnvelope.payloadMap(SharePayload.Url("https://a.example/", "com.sender"))
    assertEquals("com.sender", withPkg["sourcePackage"])
    val withoutPkg = ShareHandoffEnvelope.payloadMap(SharePayload.Url("https://a.example/"))
    assertFalse(withoutPkg.containsKey("sourcePackage"))
  }

  @Test
  fun `each failure kind yields ok false with the exact typed code`() {
    val cases =
        mapOf(
            PendingShareStore.ConsumeResult.InvalidShareId to "invalid_share_id",
            PendingShareStore.ConsumeResult.NotFound to "share_not_found",
            PendingShareStore.ConsumeResult.Expired to "share_expired",
            PendingShareStore.ConsumeResult.InvalidPayload to "invalid_payload")
    for ((result, code) in cases) {
      val env = ShareHandoffEnvelope.build(result)
      assertEquals(false, env["ok"])
      assertEquals(code, env["code"])
      assertEquals(2, env.size)
    }
  }

  @Test
  fun `success envelope contains exactly ok and payload`() {
    val env = ShareHandoffEnvelope.build(consumed(payloadImageUrl))
    assertEquals(setOf("ok", "payload"), env.keys)
  }

  @Test
  fun `envelope never contains null optional values`() {
    val env = ShareHandoffEnvelope.build(consumed(SharePayload.Url("https://a.example/")))
    val payload = env["payload"] as Map<*, *>
    assertTrue(payload.values.none { it == null })
  }

  private fun consumed(payload: SharePayload) =
      PendingShareStore.ConsumeResult.Consumed(
          PendingShare("e0d6823c-3dcd-4a53-a0eb-ec078a1f9b8d", payload, 0L))
}

/**
 * MEDIUM: staged-file release seam path validation. Only a canonical file: URI whose
 * canonical path is a regular file directly under <cacheDir>/share-staging with a bare
 * lowercase-canonical UUID filename is releasable; everything else returns null (silent
 * no-op at the bridge).
 */
class StagedImagePathsTest {

  @get:Rule val tmp = TemporaryFolder()

  private val uuid = "0fbf0a34-6f1e-4d2f-9bcd-2ee6c9a7e111"

  private fun staging(): File {
    val dir = File(tmp.root, "cache/share-staging")
    dir.mkdirs()
    return dir
  }

  private fun stagedFile(dir: File, name: String = uuid): File =
      File(dir, name).apply { writeText("x") }

  @Test
  fun `valid staged file under staging dir is accepted`() {
    val dir = staging()
    val file = stagedFile(dir)
    assertEquals(file, StagedImagePaths.stagedFile(dir, file.toURI().toString()))
  }

  @Test
  fun `null and empty input are rejected`() {
    val dir = staging()
    assertNull(StagedImagePaths.stagedFile(dir, null))
    assertNull(StagedImagePaths.stagedFile(dir, ""))
  }

  @Test
  fun `non-file schemes are rejected`() {
    val dir = staging()
    assertNull(StagedImagePaths.stagedFile(dir, "content://media/external/$uuid"))
    assertNull(StagedImagePaths.stagedFile(dir, "https://evil.example/$uuid"))
  }

  @Test
  fun `absolute foreign path is rejected`() {
    val dir = staging()
    assertNull(StagedImagePaths.stagedFile(dir, "file:///etc/passwd"))
    assertNull(StagedImagePaths.stagedFile(dir, "file:///data/data/other/shared/$uuid"))
  }

  @Test
  fun `parent traversal escapes are rejected`() {
    val dir = staging()
    val outside = File(tmp.root, "outside-$uuid").apply { writeText("x") }
    val traversal =
        "file://${dir.canonicalFile.parent}/${dir.name}/../${dir.name}/../${uuid}"
    assertNull(StagedImagePaths.stagedFile(dir, traversal))
    assertNull(StagedImagePaths.stagedFile(dir, "file://${dir.canonicalPath}/../outside-$uuid"))
  }

  @Test
  fun `non-uuid filename is rejected`() {
    val dir = staging()
    val nonUuid = stagedFile(dir, "not-a-uuid.jpg")
    assertNull(StagedImagePaths.stagedFile(dir, nonUuid.toURI().toString()))
    val upper = stagedFile(dir, uuid.uppercase())
    assertNull(StagedImagePaths.stagedFile(dir, upper.toURI().toString()))
  }

  @Test
  fun `nested directory components are rejected`() {
    val dir = staging()
    val nested = File(dir, "subdir/$uuid").apply { parentFile!!.mkdirs(); writeText("x") }
    assertNull(StagedImagePaths.stagedFile(dir, nested.toURI().toString()))
  }

  @Test
  fun `the staging directory itself and missing files are rejected`() {
    val dir = staging()
    assertNull(StagedImagePaths.stagedFile(dir, dir.toURI().toString()))
    assertNull(StagedImagePaths.stagedFile(dir, File(dir, uuid).toURI().toString()))
  }
}
