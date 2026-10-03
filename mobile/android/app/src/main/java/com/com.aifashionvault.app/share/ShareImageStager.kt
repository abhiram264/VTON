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
  private const val JPEG_SOF0 = 0xC0
  private const val JPEG_SOF15 = 0xCF
  private const val JPEG_SOS = 0xDA
  private const val JPEG_EOI = 0xD9

  /**
   * Reads at most [maxBytes], validates the complete container, then opens the sink. Keeping the
   * capped bytes in memory is intentional: no staged file is created or written for bad input.
   */
  fun copyWithLimits(
      source: InputStream,
      sinkFactory: () -> OutputStream,
      maxBytes: Long = ShareLimits.MAX_SHARE_BYTES,
      maxPixels: Long = ShareLimits.MAX_SHARE_PIXELS,
      maxEdge: Int = ShareLimits.MAX_SHARE_EDGE,
  ): Result<StagedImage> {
    return try {
      if (maxBytes < 0 || maxBytes > Int.MAX_VALUE) {
        throw ShareValidationException(StageError.TooLarge)
      }
      val bytes = source.use { readBounded(it, maxBytes) }
      val parsed = parseImage(bytes, bytes.size)
          ?: throw ShareValidationException(StageError.UnsupportedFormat)
      if (parsed.width <= 0 || parsed.height <= 0) {
        throw ShareValidationException(StageError.UnsupportedFormat)
      }
      if (parsed.width.toLong() * parsed.height.toLong() > maxPixels ||
          parsed.width > maxEdge || parsed.height > maxEdge) {
        throw ShareValidationException(StageError.OversizedDimensions)
      }

      sinkFactory().use { sink ->
        sink.write(bytes)
        sink.flush()
        Result.success(StagedImage(parsed.format, bytes.size.toLong(), parsed.width, parsed.height))
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

  private fun readBounded(source: InputStream, maxBytes: Long): ByteArray {
    val limit = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val bytes = java.io.ByteArrayOutputStream(limit.coerceAtMost(BUFFER_SIZE))
    val buffer = ByteArray(BUFFER_SIZE)
    var total = 0L
    while (true) {
      var read = source.read(buffer)
      if (read == 0) {
        val one = source.read()
        if (one == -1) break
        if (total == maxBytes) throw ShareValidationException(StageError.TooLarge)
        buffer[0] = one.toByte()
        read = 1
      }
      if (read == -1) break
      if (read < 0 || total + read > maxBytes) {
        throw ShareValidationException(StageError.TooLarge)
      }
      bytes.write(buffer, 0, read)
      total += read
    }
    return bytes.toByteArray()
  }

  private data class ParsedImage(val format: String, val width: Int, val height: Int)

  private fun parseImage(bytes: ByteArray, length: Int): ParsedImage? {
    if (length < 12) return null
    return when {
      bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
          parseJpeg(bytes, length)?.let { ParsedImage("image/jpeg", it.first, it.second) }
      hasPngSignature(bytes, length) ->
          parsePng(bytes, length)?.let { ParsedImage("image/png", it.first, it.second) }
      hasRiffWebPSignature(bytes, length) ->
          parseWebP(bytes, length)?.let { ParsedImage("image/webp-static", it.first, it.second) }
      else -> null
    }
  }

  /** Determines the actual format from magic bytes only. Accepts JPEG, PNG, static WebP. */
  fun detectFormat(bytes: ByteArray, length: Int = bytes.size): String? {
    if (length < 12) return null
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
      return "image/jpeg"
    }
    if (hasPngSignature(bytes, length)) return "image/png"
    if (hasRiffWebPSignature(bytes, length)) return detectWebPVariant(bytes, length)
    return null
  }

  private fun hasPngSignature(bytes: ByteArray, length: Int): Boolean =
      length >= 8 &&
          bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
          bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
          bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
          bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()

  private fun hasRiffWebPSignature(bytes: ByteArray, length: Int): Boolean =
      length >= 12 &&
          bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
          bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
          bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
          bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

  /** Splits static from animated WebP; animated formats are outside the V1 allowlist. */
  private fun detectWebPVariant(bytes: ByteArray, length: Int): String? {
    if (length < 16) return null
    if (length >= 21 &&
        bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
        bytes[14] == '8'.code.toByte() && bytes[15] == 'X'.code.toByte()) {
      val flags = bytes[20].toInt() and 0xFF
      if (flags and 0x02 != 0) return null
      return "image/webp-static"
    }
    if (bytes[12] == 'V'.code.toByte() && bytes[13] == 'P'.code.toByte() &&
        bytes[14] == '8'.code.toByte() &&
        (bytes[15] == ' '.code.toByte() || bytes[15] == 'L'.code.toByte())) {
      return "image/webp-static"
    }
    return null
  }

  /** Validates JPEG markers, SOF dimensions, entropy framing and final EOI. */
  private fun parseJpeg(bytes: ByteArray, length: Int): Pair<Int, Int>? {
    var offset = 2
    var dimensions: Pair<Int, Int>? = null
    var sawScan = false
    while (offset < length) {
      if (u8(bytes, offset) != 0xFF) return null
      val markerStart = offset
      while (offset < length && u8(bytes, offset) == 0xFF) offset++
      if (offset >= length) return null
      val marker = u8(bytes, offset++)
      if (marker == 0x00 || marker == 0xD8 || marker == 0xFF) return null
      if (marker == JPEG_EOI) return if (sawScan) dimensions else null
      if (marker == 0x01 || marker in 0xD0..0xD7) continue

      val segmentLength = jpegSegmentLength(bytes, offset, length) ?: return null
      if (marker == JPEG_SOS) {
        if (dimensions == null || segmentLength < 6) return null
        val scanComponents = u8(bytes, offset + 2)
        if (scanComponents == 0 || segmentLength != 6 + scanComponents * 2) return null
        offset += segmentLength
        offset = skipJpegEntropy(bytes, offset, length) ?: return null
        sawScan = true
        continue
      }
      if (marker in JPEG_SOF0..JPEG_SOF15 && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
        if (dimensions != null || segmentLength < 8 || offset + 8 > length) return null
        val precision = u8(bytes, offset + 2)
        val componentCount = u8(bytes, offset + 7)
        if ((precision != 8 && precision != 12) || componentCount == 0 ||
            segmentLength != 8 + componentCount * 3) return null
        val height = u16(bytes, offset + 3)
        val width = u16(bytes, offset + 5)
        if (width == 0 || height == 0) return null
        dimensions = width to height
      }
      offset += segmentLength
      if (offset == markerStart) return null
    }
    return null
  }

  /** Returns the complete segment length, including its two length bytes, or null. */
  private fun jpegSegmentLength(bytes: ByteArray, offset: Int, length: Int): Int? {
    if (offset + 2 > length) return null
    val segmentLength = u16(bytes, offset)
    if (segmentLength < 2 || offset + segmentLength > length) return null
    return segmentLength
  }

  /** Scans entropy-coded data and returns the next real marker, or null if framing truncates. */
  private fun skipJpegEntropy(bytes: ByteArray, start: Int, length: Int): Int? {
    var offset = start
    var entropyBytes = 0
    while (offset < length) {
      if (u8(bytes, offset) != 0xFF) {
        offset++
        entropyBytes++
        continue
      }
      val markerStart = offset
      while (offset < length && u8(bytes, offset) == 0xFF) offset++
      if (offset >= length) return null
      when (u8(bytes, offset++)) {
        0x00 -> entropyBytes++
        in 0xD0..0xD7 -> entropyBytes++
        else -> return if (entropyBytes > 0) markerStart else null
      }
    }
    return null
  }

  /** Validates PNG signature, chunk bounds/CRCs, IHDR, IDAT presence and terminal IEND. */
  private fun parsePng(bytes: ByteArray, length: Int): Pair<Int, Int>? {
    if (length < 8 + 25 || !hasPngSignature(bytes, length)) return null
    var offset = 8
    var dimensions: Pair<Int, Int>? = null
    var sawIdat = false
    var sawIend = false
    while (offset < length) {
      if (length - offset < 12) return null
      val chunkLength = readUInt32BE(bytes, offset)
      if (chunkLength < 0L || chunkLength > Int.MAX_VALUE || offset.toLong() + 12L + chunkLength > length) {
        return null
      }
      val typeOffset = offset + 4
      val dataOffset = offset + 8
      val dataLength = chunkLength.toInt()
      val chunkEnd = dataOffset + dataLength
      if (!validChunkType(bytes, typeOffset)) return null
      val crc = java.util.zip.CRC32()
      crc.update(bytes, typeOffset, 4 + dataLength)
      if (readUInt32BE(bytes, chunkEnd) != crc.value) return null
      val type = String(bytes, typeOffset, 4, Charsets.US_ASCII)
      if (sawIdat && type != "IDAT" && type != "IEND") return null
      val critical = u8(bytes, typeOffset) in 'A'.code..'Z'.code
      if (critical && type !in setOf("IHDR", "PLTE", "IDAT", "IEND")) return null

      when (type) {
        "IHDR" -> {
          if (offset != 8 || dataLength != 13 || dimensions != null) return null
          val width = readUInt32BE(bytes, dataOffset)
          val height = readUInt32BE(bytes, dataOffset + 4)
          if (width <= 0L || height <= 0L || width > Int.MAX_VALUE || height > Int.MAX_VALUE ||
              u8(bytes, dataOffset + 8) !in 1..16 ||
              u8(bytes, dataOffset + 10) != 0 || u8(bytes, dataOffset + 11) != 0 ||
              u8(bytes, dataOffset + 12) !in 0..1) return null
          dimensions = width.toInt() to height.toInt()
        }
        "IDAT" -> {
          if (dimensions == null || sawIend) return null
          sawIdat = true
        }
        "IEND" -> {
          if (dataLength != 0 || !sawIdat || chunkEnd + 4 != length) return null
          sawIend = true
        }
      }
      offset = chunkEnd + 4
      if (sawIend) return dimensions
    }
    return null
  }

  private fun validChunkType(bytes: ByteArray, offset: Int): Boolean =
      (0 until 4).all {
        val value = u8(bytes, offset + it)
        value in 'A'.code..'Z'.code || value in 'a'.code..'z'.code
      }

  /** Validates RIFF size, padded chunk bounds, static flags and VP8/VP8L/VP8X framing. */
  private fun parseWebP(bytes: ByteArray, length: Int): Pair<Int, Int>? {
    if (length < 20 || !hasRiffWebPSignature(bytes, length)) return null
    val riffSize = readUInt32LE(bytes, 4)
    if (riffSize < 4L || riffSize != length.toLong() - 8L) return null
    var offset = 12
    var first = true
    var canvas: Pair<Int, Int>? = null
    var image: Pair<Int, Int>? = null
    var sawVp8x = false
    while (offset < length) {
      if (length - offset < 8) return null
      val type = String(bytes, offset, 4, Charsets.US_ASCII)
      val chunkSize = readUInt32LE(bytes, offset + 4)
      val paddedSize = chunkSize + (chunkSize and 1L)
      if (chunkSize > Int.MAX_VALUE || offset.toLong() + 8L + paddedSize > length) return null
      val payload = offset + 8
      val size = chunkSize.toInt()
      if (first && type != "VP8X" && type != "VP8 " && type != "VP8L") return null
      if (type == "VP8X" && !first) return null
      first = false
      when (type) {
        "VP8X" -> {
          if (sawVp8x || size < 10 || (u8(bytes, payload) and 0x82) != 0) return null
          canvas = (readUInt24LE(bytes, payload + 4) + 1) to
              (readUInt24LE(bytes, payload + 7) + 1)
          sawVp8x = true
        }
        "VP8 " -> {
          if (image != null || size < 10 || (u8(bytes, payload) and 0x01) != 0 ||
              u8(bytes, payload + 3) != 0x9D || u8(bytes, payload + 4) != 0x01 ||
              u8(bytes, payload + 5) != 0x2A) return null
          image = (readUInt16LE(bytes, payload + 6) and 0x3FFF) to
              (readUInt16LE(bytes, payload + 8) and 0x3FFF)
        }
        "VP8L" -> {
          if (image != null || size < 5 || u8(bytes, payload) != 0x2F) return null
          val packed = readUInt32LE(bytes, payload + 1)
          image = ((packed and 0x3FFF).toInt() + 1) to (((packed ushr 14) and 0x3FFF).toInt() + 1)
        }
        "ANIM", "ANMF" -> return null
      }
      offset += 8 + size + (size and 1)
    }
    if (image == null || image.first <= 0 || image.second <= 0) return null
    if (canvas != null && canvas != image) return null
    return image
  }

  /** Validates a complete in-memory image. Retained for callers that need standalone dimensions. */
  fun decodeDimensions(format: String, header: ByteArray, length: Int): Pair<Int, Int>? {
    val parsed = parseImage(header, length) ?: return null
    return if (parsed.format == format) parsed.width to parsed.height else null
  }

  private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

  private fun u16(bytes: ByteArray, offset: Int): Int =
      (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)

  private fun readUInt32BE(bytes: ByteArray, offset: Int): Long =
      (u8(bytes, offset).toLong() shl 24) or
          (u8(bytes, offset + 1).toLong() shl 16) or
          (u8(bytes, offset + 2).toLong() shl 8) or
          u8(bytes, offset + 3).toLong()

  private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
      u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

  private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
      u8(bytes, offset).toLong() or
          (u8(bytes, offset + 1).toLong() shl 8) or
          (u8(bytes, offset + 2).toLong() shl 16) or
          (u8(bytes, offset + 3).toLong() shl 24)

  private fun readUInt24LE(bytes: ByteArray, offset: Int): Int =
      u8(bytes, offset) or (u8(bytes, offset + 1) shl 8) or (u8(bytes, offset + 2) shl 16)
}

/** Internal carrier for bounded validation failures inside the stager. */
class ShareValidationException(val error: ShareImageStager.StageError) :
    Exception(error.toString())
