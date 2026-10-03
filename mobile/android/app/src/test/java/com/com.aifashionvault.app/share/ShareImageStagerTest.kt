package com.aifashionvault.app.share

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Acceptance criteria 3, 5, 6, 7, 8: staged copy, format/limit enforcement with boundary
 * cases, failure cleanup, and format sniffing.
 */
class ShareImageStagerTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(typeBytes); update(data) }.value
    return uint32BE(data.size) + typeBytes + data + uint32BE(crc.toInt())
  }

  private fun pngBytes(width: Int, height: Int, payload: ByteArray = ByteArray(0)): ByteArray {
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
    return signature + pngChunk("IHDR", ihdr) + pngChunk("IDAT", payload) + pngChunk("IEND", ByteArray(0))
  }

  private fun jpegBytes(width: Int, height: Int, filler: Int = 64): ByteArray {
    var bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
    bytes += "JFIF".toByteArray() + ByteArray(10)
    bytes +=
        byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(), 0, 11, 8,
            (height shr 8).toByte(), height.toByte(),
            (width shr 8).toByte(), width.toByte(),
            1, 1, 0x11, 0)
    bytes += byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 8, 1, 1, 0, 0, 0x3F, 0)
    bytes += ByteArray(filler)
    bytes += byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    return bytes
  }
  private fun uint32BE(value: Int) =
      byteArrayOf(
          (value ushr 24).toByte(),
          (value ushr 16).toByte(),
          (value ushr 8).toByte(),
          value.toByte())

  private fun webpHeader(width: Int, height: Int, animated: Boolean = false): ByteArray {
    val vp8x = byteArrayOf(0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    val w24 = width - 1
    val h24 = height - 1
    val vp8xPayload = vp8x.copyOf().also {
      it[0] = if (animated) 0x02 else 0
      it[4] = (w24 and 0xFF).toByte()
      it[5] = ((w24 ushr 8) and 0xFF).toByte()
      it[6] = ((w24 ushr 16) and 0xFF).toByte()
      it[7] = (h24 and 0xFF).toByte()
      it[8] = ((h24 ushr 8) and 0xFF).toByte()
      it[9] = ((h24 ushr 16) and 0xFF).toByte()
    }
    val vp8Payload =
        byteArrayOf(0, 0, 0, 0x9D.toByte(), 1, 0x2A, (width and 0xFF).toByte(), ((width ushr 8) and 0x3F).toByte(), (height and 0xFF).toByte(), ((height ushr 8) and 0x3F).toByte())
    val vp8xChunk = "VP8X".toByteArray() + uint32LE(10) + vp8xPayload
    val vp8Chunk = "VP8 ".toByteArray() + uint32LE(10) + vp8Payload
    val body = "WEBP".toByteArray() + vp8xChunk + vp8Chunk
    return "RIFF".toByteArray() + uint32LE(body.size) + body
  }

  private fun uint32LE(value: Int) =
      byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())


  private fun stagedFile() = tmp.newFile()

  private fun stage(bytes: ByteArray, maxBytes: Long = ShareLimits.MAX_SHARE_BYTES) =
      ShareImageStager.copyWithLimits(
          ByteArrayInputStream(bytes), { FileOutputStream(stagedFile()) }, maxBytes)

  @Test
  fun `valid png staged with dimensions and format`() {
    val png = pngBytes(100, 200, ByteArray(16))
    val file = stagedFile()
    val result =
        ShareImageStager.copyWithLimits(
            ByteArrayInputStream(png), { FileOutputStream(file) })
    assertTrue(result.isSuccess)
    val staged = result.getOrThrow()
    assertEquals("image/png", staged.format)
    assertEquals(100, staged.width)
    assertEquals(200, staged.height)
    assertEquals(png.size.toLong(), staged.byteCount)
    assertTrue(png.contentEquals(file.readBytes()))
  }

  @Test
  fun `valid jpeg staged with dimensions`() {
    val jpeg = jpegBytes(640, 480)
    val result = stage(jpeg)
    assertTrue(result.isSuccess)
    val staged = result.getOrThrow()
    assertEquals("image/jpeg", staged.format)
    assertEquals(640, staged.width)
    assertEquals(480, staged.height)
  }
  @Test
  fun `metadata-heavy jpeg stages when its SOF follows a large APP segment`() {
    val base = jpegBytes(640, 480)
    val tail = base.copyOfRange(2, base.size)
    val jpeg =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0xFD.toByte(), 0xEA.toByte()) +
            ByteArray(65_000) + tail
    val file = stagedFile()
    val result =
        ShareImageStager.copyWithLimits(ByteArrayInputStream(jpeg), { FileOutputStream(file) })
    assertTrue(result.isSuccess)
    assertTrue(jpeg.contentEquals(file.readBytes()))
  }

  @Test
  fun `static webp staged, animated webp rejected`() {
    val static = webpHeader(300, 200)
    assertTrue(stage(static).isSuccess)
    assertEquals("image/webp-static", stage(static).getOrThrow().format)

    val animated = webpHeader(300, 200, animated = true)
    assertTrue(stage(animated).isFailure)
  }

  @Test
  fun `unsupported formats reject`() {
    // GIF
    assertTrue(stage("GIF89a".toByteArray() + ByteArray(20)).isFailure)
    // SVG (text)
    assertTrue(stage("<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()).isFailure)
    // TIFF (II*\0)
    assertTrue(stage(byteArrayOf(0x49, 0x49, 0x2A, 0) + ByteArray(20)).isFailure)
    // HEIF (ftyp heic) — ftyp box: size then 'ftyp'
    val heif =
        byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(16)
    assertTrue(stage(heif).isFailure)
    // unknown random bytes
    assertTrue(stage(ByteArray(64) { it.toByte() }).isFailure)
  }
  @Test
  fun `malformed jpeg png and webp headers reject`() {
    val jpeg = jpegBytes(10, 10).copyOf().apply {
      this[20] = 0xFF.toByte()
      this[21] = 0xC0.toByte()
      this[22] = 0
      this[23] = 2
    }
    val png = pngBytes(10, 10).copyOf().apply { this[12] = 'X'.code.toByte() }
    val webp = webpHeader(10, 10).copyOf().apply { this[16] = 9 }
    assertTrue(stage(jpeg).isFailure)
    assertTrue(stage(png).isFailure)
    assertTrue(stage(webp).isFailure)
  }

  @Test
  fun `zero or invalid dimensions reject`() {
    assertTrue(stage(pngBytes(0, 100, ByteArray(8))).isFailure)
    assertTrue(stage(pngBytes(100, 0, ByteArray(8))).isFailure)
  }

  @Test
  fun `byte cap boundary - exactly at limit passes, one above rejects`() {
    val png = pngBytes(10, 10, ByteArray(32))
    val atLimit = stage(png, maxBytes = png.size.toLong())
    assertTrue(atLimit.isSuccess)
    assertEquals(png.size.toLong(), atLimit.getOrThrow().byteCount)

    val above = stage(png, maxBytes = png.size.toLong() - 1)
    assertTrue(above.isFailure)
    assertEquals(
        ShareImageStager.StageError.TooLarge, (above.exceptionOrNull() as ShareValidationException).error)
  }
  @Test
  fun `byte cap rejects before any staged bytes are written`() {
    val staged = ByteArrayOutputStream()
    val result =
        ShareImageStager.copyWithLimits(
            ByteArrayInputStream(pngBytes(10, 10)), { staged }, maxBytes = 1)
    assertEquals(
        ShareImageStager.StageError.TooLarge,
        (result.exceptionOrNull() as ShareValidationException).error)
    assertEquals(0, staged.size())
  }

  @Test
  fun `pixel cap boundary - at limit passes, one above rejects`() {
    // PNG is header-only; pixel count is width*height from IHDR regardless of byte size.
    // 6325 x 6325 = 40005625 > 40M ; 6324 x 6324 = 39992976 < 40M
    assertTrue(stage(pngBytes(6324, 6324, ByteArray(8))).isSuccess)
    val above = stage(pngBytes(6325, 6325, ByteArray(8)))
    assertTrue(above.isFailure)
    assertEquals(
        ShareImageStager.StageError.OversizedDimensions,
        (above.exceptionOrNull() as ShareValidationException).error)
  }

  @Test
  fun `edge cap boundary - at limit passes, one above rejects`() {
    assertTrue(stage(pngBytes(12000, 1, ByteArray(8))).isSuccess)
    val above = stage(pngBytes(12001, 1, ByteArray(8)))
    assertTrue(above.isFailure)
    assertEquals(
        ShareImageStager.StageError.OversizedDimensions,
        (above.exceptionOrNull() as ShareValidationException).error)
    assertTrue(stage(pngBytes(1, 12000, ByteArray(8))).isSuccess)
    assertTrue(stage(pngBytes(1, 12001, ByteArray(8))).isFailure)
  }

  @Test
  fun `plain unsupported bytes reject`() {
    assertTrue(stage("this is plain text, not an image at all".toByteArray()).isFailure)
  }

  @Test
  fun `header rejection does not write staged data`() {
    // The stager rejects this header before opening the caller-provided sink.
    val file = stagedFile()
    val result =
        ShareImageStager.copyWithLimits(
            ByteArrayInputStream(pngBytes(20000, 1, ByteArray(8))),
            { FileOutputStream(file) })
    assertTrue(result.isFailure)
    assertEquals(0L, file.length())
  }

  @Test
  fun `unreadable source rejects as unreadable`() {
    val broken =
        object : java.io.InputStream() {
          override fun read(): Int = throw java.io.FileNotFoundException("no grant")
        }
    val result = ShareImageStager.copyWithLimits(broken, { FileOutputStream(stagedFile()) })
    assertTrue(result.isFailure)
    assertEquals(
        ShareImageStager.StageError.Unreadable,
        (result.exceptionOrNull() as ShareValidationException).error)
  }

  @Test
  fun `mid-stream provider failure closes source without opening sink`() {
    val png = pngBytes(100, 100, ByteArray(70_000))
    var sourceClosed = false
    var delivered = 0
    val flaky =
        object : ByteArrayInputStream(png) {
          override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (delivered >= 33) throw java.io.IOException("provider died mid-stream")
            val read = super.read(bytes, offset, length)
            if (read > 0) delivered += read
            return read
          }

          override fun close() {
            sourceClosed = true
            super.close()
          }
        }
    val staged =
        object : ByteArrayOutputStream() {
          var closed = false

          override fun close() {
            closed = true
            super.close()
          }
        }

    val result = ShareImageStager.copyWithLimits(flaky, { staged })

    assertEquals(
        ShareImageStager.StageError.Io("IOException"),
        (result.exceptionOrNull() as ShareValidationException).error)
    assertEquals(0, staged.size())
    assertTrue(sourceClosed)
    assertFalse(staged.closed)
  }

  @Test
  fun `detectFormat exposes header-only format sniffing`() {
    assertEquals("image/jpeg", ShareImageStager.detectFormat(jpegBytes(2, 2)))
    assertEquals("image/png", ShareImageStager.detectFormat(pngBytes(2, 2)))
    assertEquals("image/webp-static", ShareImageStager.detectFormat(webpHeader(2, 2)))
    assertEquals(null, ShareImageStager.detectFormat(ByteArray(64)))
    assertEquals(null, ShareImageStager.detectFormat("GIF89a...".toByteArray()))
  }
}
