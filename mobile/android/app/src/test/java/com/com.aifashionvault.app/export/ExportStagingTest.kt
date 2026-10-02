package com.aifashionvault.app.export

import com.aifashionvault.app.share.ShareImageStager
import com.aifashionvault.app.share.ShareLimits
import com.aifashionvault.app.share.ShareValidationException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Export staging reuses the app's frozen image policy and only ever exposes files that are
 * direct children of the staging directory, so a hostile `fileUri` from JS cannot make the
 * bridge read, share or delete an arbitrary file.
 */
class ExportStagingTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun validPngIsStagedByteIdenticalAndReported() {
    val bytes = pngBytes(8, 4)
    val destination = File(tmp.root, "staged")

    val staged = ExportStaging.stage(ByteArrayInputStream(bytes), destination).getOrThrow()

    assertArrayEquals(bytes, staged.file.readBytes())
    assertEquals("image/png", staged.mediaType)
    assertEquals(bytes.size.toLong(), staged.byteCount)
  }

  @Test
  fun unsupportedFormatIsRejectedAndLeavesNoStagedFile() {
    val gif = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(64)
    val destination = File(tmp.root, "staged")

    val error = ExportStaging.stage(ByteArrayInputStream(gif), destination).exceptionOrNull()

    assertEquals(ExportStagerOutcome.ERROR_UNSUPPORTED_TYPE, ExportStaging.bridgeCode(error))
    assertFalse(destination.exists())
  }

  @Test
  fun dimensionsBeyondTheFrozenEdgeAreRejectedAndLeaveNoFile() {
    val destination = File(tmp.root, "staged")

    val error =
        ExportStaging
            .stage(ByteArrayInputStream(pngBytes(ShareLimits.MAX_SHARE_EDGE + 1, 2)), destination)
            .exceptionOrNull()

    assertEquals(ExportStagerOutcome.ERROR_TOO_LARGE, ExportStaging.bridgeCode(error))
    assertFalse(destination.exists())
  }

  @Test
  fun aCallerSuppliedByteCapIsHonoredAndRejectsEarly() {
    val bytes = pngBytes(4, 4)
    val destination = File(tmp.root, "staged")

    // The image is valid but larger than the caller's own bound.
    val error =
        ExportStaging
            .stage(ByteArrayInputStream(bytes), destination, requestedMaxBytes = 4.0)
            .exceptionOrNull()

    assertEquals(ExportStagerOutcome.ERROR_TOO_LARGE, ExportStaging.bridgeCode(error))
    assertFalse(destination.exists())
  }

  @Test
  fun aCallerSuppliedCapCanNeverRaiseTheFrozenAppLimit() {
    val bytes = pngBytes(4, 4)
    val destination = File(tmp.root, "staged")

    // A huge caller bound is clamped to the app cap, so this valid small image still stages.
    val staged =
        ExportStaging
            .stage(ByteArrayInputStream(bytes), destination, requestedMaxBytes = 1e18)
            .getOrThrow()

    assertEquals(bytes.size.toLong(), staged.byteCount)
  }

  @Test
  fun aStreamBeyondTheCountedByteCapIsRejectedAndLeavesNoFile() {
    val destination = File(tmp.root, "staged")

    val error = ExportStaging.stage(headerThenEndlessZeroes(), destination).exceptionOrNull()

    assertEquals(ExportStagerOutcome.ERROR_TOO_LARGE, ExportStaging.bridgeCode(error))
    assertFalse(destination.exists())
  }

  @Test
  fun everyStageErrorMapsToABoundedExportCode() {
    assertEquals(
        ExportStagerOutcome.ERROR_TOO_LARGE,
        ExportStaging.bridgeCode(
            ShareValidationException(ShareImageStager.StageError.TooLarge)))
    assertEquals(
        ExportStagerOutcome.ERROR_TOO_LARGE,
        ExportStaging.bridgeCode(
            ShareValidationException(ShareImageStager.StageError.OversizedDimensions)))
    assertEquals(
        ExportStagerOutcome.ERROR_UNSUPPORTED_TYPE,
        ExportStaging.bridgeCode(
            ShareValidationException(ShareImageStager.StageError.UnsupportedFormat)))
    assertEquals(
        ExportStagerOutcome.ERROR_DOWNLOAD_FAILED,
        ExportStaging.bridgeCode(
            ShareValidationException(ShareImageStager.StageError.Unreadable)))
    assertEquals(
        ExportStagerOutcome.ERROR_DOWNLOAD_FAILED,
        ExportStaging.bridgeCode(
            ShareValidationException(ShareImageStager.StageError.Io("X"))))
    // A non-staging failure still yields a bounded code, never the cause's text.
    assertEquals(
        ExportStagerOutcome.ERROR_DOWNLOAD_FAILED,
        ExportStaging.bridgeCode(RuntimeException("https://leaky.example.com/x")))
  }

  @Test
  fun sniffedMediaTypeComesFromTheBytesNotTheName() {
    val png = File(tmp.root, "extensionless").apply { writeBytes(pngBytes(2, 2)) }
    assertEquals("image/png", ExportStaging.sniffMediaType(png))

    val notAnImage = File(tmp.root, "also-extensionless").apply { writeText("plain text") }
    assertEquals(ExportStaging.DEFAULT_MEDIA_TYPE, ExportStaging.sniffMediaType(notAnImage))
  }

  @Test
  fun stagedUriIsAFileUriTheBridgeCanResolveBack() {
    val dir = ExportStaging.stagingDir(tmp.root)
    val file = ExportStaging.newStagedFile(tmp.root).apply { writeText("x") }

    val uri = ExportStaging.uriOf(file)

    assertTrue(uri.startsWith("file:"))
    assertEquals(file.canonicalFile, ExportStaging.stagedFile(dir, uri))
  }

  @Test
  fun foreignOrMalformedUrisAreRefused() {
    val dir = ExportStaging.stagingDir(tmp.root)
    val staged = ExportStaging.newStagedFile(tmp.root).apply { writeText("x") }
    val outside = File(tmp.root, UUID.randomUUID().toString()).apply { writeText("x") }

    assertNull(ExportStaging.stagedFile(dir, null))
    assertNull(ExportStaging.stagedFile(dir, ""))
    assertNull(ExportStaging.stagedFile(dir, "content://media/external/images/1"))
    assertNull(ExportStaging.stagedFile(dir, "https://example.com/x.jpg"))
    assertNull(ExportStaging.stagedFile(dir, "file://host" + staged.path))
    assertNull(ExportStaging.stagedFile(dir, "file:" + staged.path + "?x=1"))
    assertNull(ExportStaging.stagedFile(dir, "file:" + staged.path + "#f"))
    // Traversal out of the staging directory.
    assertNull(
        ExportStaging.stagedFile(dir, "file:" + dir.canonicalPath + "/../" + outside.name))
    // A real sibling file outside the staging directory.
    assertNull(ExportStaging.stagedFile(dir, ExportStaging.uriOf(outside)))
    // A non-UUID filename inside the directory is not ours.
    val nonUuid = File(dir, "not-a-uuid").apply { writeText("x") }
    assertNull(ExportStaging.stagedFile(dir, ExportStaging.uriOf(nonUuid)))
    // A directory is never a staged file.
    val subdir = File(dir, UUID.randomUUID().toString()).apply { mkdirs() }
    assertNull(ExportStaging.stagedFile(dir, ExportStaging.uriOf(subdir)))
    assertNotNull(ExportStaging.stagedFile(dir, ExportStaging.uriOf(staged)))
  }

  @Test
  fun sweepDeletesOnlyStaleDirectChildFiles() {
    val dir = ExportStaging.stagingDir(tmp.root)
    val now = 1_000_000L
    val ttl = 60_000L
    val stale =
        File(dir, UUID.randomUUID().toString()).apply {
          writeText("x")
          setLastModified(now - ttl - 1)
        }
    val fresh =
        File(dir, UUID.randomUUID().toString()).apply {
          writeText("x")
          setLastModified(now - ttl + 1)
        }
    // Exactly at the cutoff is NOT stale: the window is [now-ttl, now].
    val boundary =
        File(dir, UUID.randomUUID().toString()).apply {
          writeText("x")
          setLastModified(now - ttl)
        }
    val nested = File(dir, "nested").apply { mkdirs(); setLastModified(0) }

    assertEquals(1, ExportStaging.cleanupStale(tmp.root, now, ttl))

    assertFalse(stale.exists())
    assertTrue(fresh.exists())
    assertTrue(boundary.exists())
    assertTrue(nested.isDirectory)
  }

  private fun headerThenEndlessZeroes(): InputStream {
    val header = pngBytes(2, 2)
    val padding = ByteArray(64 * 1024)
    return object : InputStream() {
      private var index = 0

      override fun read(): Int = if (index < header.size) header[index++].toInt() and 0xFF else 0

      override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (index < header.size) {
          val count = minOf(length, header.size - index)
          System.arraycopy(header, index, target, offset, count)
          index += count
          return count
        }
        val count = minOf(length, padding.size)
        System.arraycopy(padding, 0, target, offset, count)
        return count
      }
    }
  }

  private fun uint32BE(value: Int): ByteArray =
      byteArrayOf(
          (value ushr 24).toByte(),
          (value ushr 16).toByte(),
          (value ushr 8).toByte(),
          value.toByte())

  private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(typeBytes); update(data) }.value
    return uint32BE(data.size) + typeBytes + data + uint32BE(crc.toInt())
  }

  private fun pngBytes(width: Int, height: Int): ByteArray {
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val ihdr =
        byteArrayOf(
            (width ushr 24).toByte(),
            (width ushr 16).toByte(),
            (width ushr 8).toByte(),
            width.toByte(),
            (height ushr 24).toByte(),
            (height ushr 16).toByte(),
            (height ushr 8).toByte(),
            height.toByte(),
            8,
            2,
            0,
            0,
            0)
    return signature +
        pngChunk("IHDR", ihdr) +
        pngChunk("IDAT", ByteArray(0)) +
        pngChunk("IEND", ByteArray(0))
  }
}
