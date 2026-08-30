package com.aifashionvault.app.share

import java.io.InputStream
import java.io.OutputStream

/**
 * Streams a foreign image into an app-private staging file while enforcing the frozen share
 * safety policy: counted byte cap, header-based format allowlist, and decoded dimension
 * bounds. Pure JVM (no android.* imports) so it is unit-testable with plain streams.
 *
 * Metadata reported by the provider (MIME, size, display name) is deliberately ignored —
 * the bytes themselves are the only source of truth.
 */
object ShareImageStager {

  /** Result of a successful staging: what the bytes actually are. */
  data class StagedImage(val format: String, val byteCount: Long, val width: Int, val height: Int)

  /** Bounded, user-safe validation failure. Carries no URL/URI/name/bytes. */
  sealed interface StageError {
    data object Unreadable : StageError

    data object TooLarge : StageError

    data object UnsupportedFormat : StageError

    data object OversizedDimensions : StageError

    data class Io(val safeCode: String) : StageError
  }

  private const val BUFFER_SIZE = 64 * 1024

  /**
   * Copies [source] into the stream produced by [sinkFactory] with the byte cap and header
   * validation applied during the copy. Both streams are always closed by this method.
   */
  fun copyWithLimits(
      source: InputStream,
      sinkFactory: () -> OutputStream,
      maxBytes: Long = ShareLimits.MAX_SHARE_BYTES,
      maxPixels: Long = ShareLimits.MAX_SHARE_PIXELS,
      maxEdge: Int = ShareLimits.MAX_SHARE_EDGE,
  ): Result<StagedImage> {
    return try {
      source.use { src ->
        val window = ByteArray(BUFFER_SIZE)
        val filled = readHeader(src, window)
        val detected = detectFormat(window, filled)
        if (detected == null) {
          return@use Result.failure(ShareValidationException(StageError.UnsupportedFormat))
        }
        val dimensions = decodeDimensions(detected, window, filled)
        if (dimensions == null) {
          return@use Result.failure(ShareValidationException(StageError.UnsupportedFormat))
        }
        val (width, height) = dimensions
        if (width <= 0 || height <= 0) {
          return@use Result.failure(ShareValidationException(StageError.UnsupportedFormat))
        }
        if (width.toLong() * height.toLong() > maxPixels || width > maxEdge || height > maxEdge) {
          return@use Result.failure(ShareValidationException(StageError.OversizedDimensions))
        }
        sinkFactory().use { sink ->
          var total = 0L
          if (filled > 0) {
            sink.write(window, 0, filled)
            total = filled.toLong()
            if (total > maxBytes) {
              throw ShareValidationException(StageError.TooLarge)
            }
          }
          val buffer = window
          while (true) {
            val read = src.read(buffer)
            if (read == -1) break
            if (total + read > maxBytes) {
              throw ShareValidationException(StageError.TooLarge)
            }
            sink.write(buffer, 0, read)
            total += read
          }
          sink.flush()
          Result.success(StagedImage(detected, total, width, height))
        }
      }
    } catch (e: ShareValidationException) {
      Result.failure(e)
    } catch (e: Exception) {
      when (e) {
        is java.io.FileNotFoundException,
        is java.net.SocketException,
        is SecurityException ->
            Result.failure(ShareValidationException(StageError.Unreadable))
        else ->
            Result.failure(
                ShareValidationException(StageError.Io(e.javaClass.simpleName.take(40))))
      }
    }
  }

  /** Reads up to the sniffing window size without buffering the whole stream. */
  private fun readHeader(source: InputStream, window: ByteArray): Int {
    var filled = 0
    while (filled < window.size) {
      val read = source.read(window, filled, window.size - filled)
      if (read == -1) break
      filled += read
    }
    return filled
  }

  /** Determines the actual format from magic bytes only. Accepts JPEG, PNG, static WebP. */
  fun detectFormat(bytes: ByteArray, length: Int = bytes.size): String? {
    if (length < 12) return null
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
      return "image/jpeg"
    }
    if (bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte() &&
        bytes[4] == 0x0D.toByte() &&
        bytes[5] == 0x0A.toByte() &&
        bytes[6] == 0x1A.toByte() &&
        bytes[7] == 0x0A.toByte()) {
      return "image/png"
    }
    if (bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() &&
        bytes[11] == 'P'.code.toByte()) {
      return detectWebPVariant(bytes, length)
    }
    return null
  }

  /** Splits static from animated WebP; animated formats are outside the V1 allowlist. */
  private fun detectWebPVariant(bytes: ByteArray, length: Int): String? {
    if (length < 16) return null
    if (length >= 21 &&
        bytes[12] == 'V'.code.toByte() &&
        bytes[13] == 'P'.code.toByte() &&
        bytes[14] == '8'.code.toByte() &&
        bytes[15] == 'X'.code.toByte()) {
      val flags = bytes[20].toInt() and 0xFF
      if (flags and 0x02 != 0) return null // animated WebP
      return "image/webp-static"
    }
    if (bytes[12] == 'V'.code.toByte() &&
        bytes[13] == 'P'.code.toByte() &&
        bytes[14] == '8'.code.toByte() &&
        (bytes[15] == ' '.code.toByte() || bytes[15] == 'L'.code.toByte())) {
      return "image/webp-static"
    }
    return null
  }

  /** Decodes dimensions from the header window. Returns null for malformed headers. */
  fun decodeDimensions(format: String, header: ByteArray, length: Int): Pair<Int, Int>? {
    return when (format) {
      "image/jpeg" -> decodeJpegDimensions(header)
      "image/png" -> decodePngDimensions(header, length)
      "image/webp-static" -> decodeWebPDimensions(header, length)
      else -> null
    }
  }

  private fun decodeJpegDimensions(header: ByteArray): Pair<Int, Int>? {
    var i = 2
    while (i + 9 < header.size) {
      if (header[i] != 0xFF.toByte()) return null // not at a marker boundary
      val marker = header[i + 1].toInt() and 0xFF
      if (marker == 0xD8 || (marker in 0xD0..0xD7) || marker == 0x01) {
        i += 2
        continue
      }
      if (marker == 0xDA) return null // SOS before SOF
      if (marker == 0xD9) return null // EOI before SOF
      val segLen = ((header[i + 2].toInt() and 0xFF) shl 8) or (header[i + 3].toInt() and 0xFF)
      if (segLen < 2) return null
      val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
      if (isSof) {
        if (i + 9 >= header.size) return null // SOF outside sniff window
        val height = ((header[i + 5].toInt() and 0xFF) shl 8) or (header[i + 6].toInt() and 0xFF)
        val width = ((header[i + 7].toInt() and 0xFF) shl 8) or (header[i + 8].toInt() and 0xFF)
        if (height <= 0 || width <= 0) return null
        return width to height
      }
      i += 2 + segLen
    }
    return null
  }

  private fun decodePngDimensions(header: ByteArray, length: Int): Pair<Int, Int>? {
    if (length < 24) return null
    val width = readUInt32BE(header, 16)
    val height = readUInt32BE(header, 20)
    if (width <= 0 || height <= 0) return null
    return width to height
  }

  private fun decodeWebPDimensions(header: ByteArray, length: Int): Pair<Int, Int>? {
    if (length < 16) return null
    return when {
      // Lossy "VP8 ": frame tag at payload+6, dims LE at +6..+9
      header[15] == ' '.code.toByte() && length >= 30 -> {
        val width = readUInt16LE(header, 26) and 0x3FFF
        val height = readUInt16LE(header, 28) and 0x3FFF
        if (width <= 0 || height <= 0) null else width to height
      }
      // Lossless "VP8L": 14-bit width/height in signature dword at payload+1
      header[15] == 'L'.code.toByte() && length >= 26 -> {
        val bits = readUInt32LE(header, 21)
        val width = (bits and 0x3FFF) + 1
        val height = ((bits ushr 14) and 0x3FFF) + 1
        if (width <= 0 || height <= 0) null else width to height
      }
      // Extended "VP8X": 24-bit LE canvas dims at payload+4
      header[15] == 'X'.code.toByte() && length >= 30 -> {
        val width = readUInt24LE(header, 24) + 1
        val height = readUInt24LE(header, 27) + 1
        if (width <= 0 || height <= 0) null else width to height
      }
      else -> null
    }
  }

  private fun readUInt32BE(b: ByteArray, off: Int): Int =
      ((b[off].toInt() and 0xFF) shl 24) or
          ((b[off + 1].toInt() and 0xFF) shl 16) or
          ((b[off + 2].toInt() and 0xFF) shl 8) or
          (b[off + 3].toInt() and 0xFF)

  private fun readUInt16LE(b: ByteArray, off: Int): Int =
      (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

  private fun readUInt32LE(b: ByteArray, off: Int): Int =
      (b[off].toInt() and 0xFF) or
          ((b[off + 1].toInt() and 0xFF) shl 8) or
          ((b[off + 2].toInt() and 0xFF) shl 16) or
          ((b[off + 3].toInt() and 0xFF) shl 24)

  private fun readUInt24LE(b: ByteArray, off: Int): Int =
      (b[off].toInt() and 0xFF) or
          ((b[off + 1].toInt() and 0xFF) shl 8) or
          ((b[off + 2].toInt() and 0xFF) shl 16)
}

/** Internal carrier for bounded validation failures inside the stager. */
class ShareValidationException(val error: ShareImageStager.StageError) :
    Exception(error.toString())
