/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.zip.CRC32

/**
 * Conservative encoded-image admission for the native, single-frame Filmstrip decoder.
 *
 * Inspects bytes, never names or extensions. PNG/WebP container framing and animation
 * metadata are checked with bounded work; compressed pixel payloads are skipped without
 * copying. Pixel decoding (including IDAT checksums) remains the decoder's responsibility.
 * JPEG uses only the requested three-byte signature. GIF and unknown formats fall back.
 */
object NativeFilmstripFormat {
  private const val MAX_METADATA_BYTES = 256 * 1024L
  private const val MAX_CHUNKS = 4096

  fun isConfirmedStatic(encoded: ByteArray): Boolean {
    if (encoded.size >= 3 && u8(encoded, 0) == 0xff &&
      u8(encoded, 1) == 0xd8 && u8(encoded, 2) == 0xff) return true
    if (encoded.size >= 8 && u8(encoded, 0) == 137 &&
      matches(encoded, 1, "PNG\r\n\u001a\n")) return staticPng(encoded)
    if (encoded.size >= 12 && matches(encoded, 0, "RIFF") &&
      matches(encoded, 8, "WEBP")) return staticWebp(encoded)
    return false
  }

  private fun staticPng(encoded: ByteArray): Boolean {
    var position = 8
    var metadataBytes = 8L
    var chunks = 0
    var color = -1
    var depth = 0
    var paletteSeen = false
    var dataSeen = false
    var dataEnded = false
    var dataBytes = 0L
    while (position < encoded.size) {
      if (++chunks > MAX_CHUNKS || encoded.size - position < 12) return false
      val length = be32(encoded, position)
      val type = position + 4
      val payload = position + 8
      val end = payload.toLong() + length + 4L
      if (length > Int.MAX_VALUE || end > encoded.size.toLong()) return false
      if (!pngChunkName(encoded, type)) return false
      val isData = matches(encoded, type, "IDAT")
      metadataBytes += 12L + if (isData) 0L else length
      if (metadataBytes > MAX_METADATA_BYTES) return false
      if (matches(encoded, type, "acTL") || matches(encoded, type, "fcTL") ||
        matches(encoded, type, "fdAT")) return false
      if (!isData) {
        val crc = CRC32().apply { update(encoded, type, length.toInt() + 4) }.value
        if (crc != be32(encoded, (end - 4L).toInt())) return false
      }
      if (chunks == 1 && !matches(encoded, type, "IHDR")) return false
      when {
        matches(encoded, type, "IHDR") -> {
          if (chunks != 1 || length != 13L) return false
          val width = be32(encoded, payload)
          val height = be32(encoded, payload + 4)
          if (width !in 1L..Int.MAX_VALUE.toLong() || height !in 1L..Int.MAX_VALUE.toLong()) return false
          depth = u8(encoded, payload + 8)
          color = u8(encoded, payload + 9)
          val validDepth = when (color) {
            0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16
            2, 4, 6 -> depth == 8 || depth == 16
            3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8
            else -> false
          }
          if (!validDepth || u8(encoded, payload + 10) != 0 ||
            u8(encoded, payload + 11) != 0 || u8(encoded, payload + 12) > 1) return false
        }
        matches(encoded, type, "PLTE") -> {
          if (paletteSeen || dataSeen || color == 0 || color == 4 ||
            length !in 3L..768L || length % 3L != 0L) return false
          if (color == 3 && length / 3L > (1 shl depth).toLong()) return false
          paletteSeen = true
        }
        isData -> {
          if (dataEnded || (color == 3 && !paletteSeen)) return false
          dataSeen = true
          dataBytes += length
        }
        matches(encoded, type, "IEND") -> {
          return length == 0L && dataSeen && dataBytes > 0L && end == encoded.size.toLong()
        }
        else -> {
          // Unknown critical chunks cannot be safely interpreted; ancillary chunks
          // are bounded, checksum-checked metadata and cannot replace IDAT.
          if (u8(encoded, type) in 'A'.code..'Z'.code) return false
          if (dataSeen) dataEnded = true
        }
      }
      position = end.toInt()
    }
    return false
  }

  private fun staticWebp(encoded: ByteArray): Boolean {
    if (le32(encoded, 4) != encoded.size.toLong() - 8L) return false
    var position = 12
    var metadataBytes = 12L
    var chunks = 0
    var flags = -1
    var canvasWidth = 0L
    var canvasHeight = 0L
    var imageWidth = 0L
    var imageHeight = 0L
    var imageSeen = false
    var losslessImage = false
    var alphaSeen = false
    var iccSeen = false
    var exifSeen = false
    var xmpSeen = false
    while (position < encoded.size) {
      if (++chunks > MAX_CHUNKS || encoded.size - position < 8) return false
      val length = le32(encoded, position + 4)
      val payload = position + 8
      val end = payload.toLong() + length
      val padding = length and 1L
      val paddedEnd = end + padding
      if (paddedEnd > encoded.size.toLong()) return false
      if (padding != 0L && u8(encoded, end.toInt()) != 0) return false
      if (matches(encoded, position, "ANIM") || matches(encoded, position, "ANMF")) return false
      val lossy = matches(encoded, position, "VP8 ")
      val lossless = matches(encoded, position, "VP8L")
      metadataBytes += 8L + padding + if (lossy || lossless) 0L else length
      if (metadataBytes > MAX_METADATA_BYTES) return false
      when {
        matches(encoded, position, "VP8X") -> {
          if (position != 12 || flags != -1 || length != 10L) return false
          flags = u8(encoded, payload)
          if ((flags and 0x02) != 0 || (flags and 0xc1) != 0 ||
            u8(encoded, payload + 1) != 0 || u8(encoded, payload + 2) != 0 ||
            u8(encoded, payload + 3) != 0) return false
          canvasWidth = le24(encoded, payload + 4) + 1L
          canvasHeight = le24(encoded, payload + 7) + 1L
          if (canvasWidth * canvasHeight > 0xffffffffL) return false
        }
        lossy -> {
          if (imageSeen || length < 10L) return false
          val tag = le24(encoded, payload)
          if ((tag and 1L) != 0L || ((tag ushr 1) and 7L) > 3L || (tag and 16L) == 0L ||
            !matches(encoded, payload + 3, "\u009d\u0001\u002a")) return false
          imageWidth = (u8(encoded, payload + 6) or (u8(encoded, payload + 7) shl 8)).toLong() and 0x3fffL
          imageHeight = (u8(encoded, payload + 8) or (u8(encoded, payload + 9) shl 8)).toLong() and 0x3fffL
          if (imageWidth == 0L || imageHeight == 0L) return false
          imageSeen = true
        }
        lossless -> {
          if (imageSeen || alphaSeen || length < 5L || u8(encoded, payload) != 0x2f) return false
          val header = le32(encoded, payload + 1)
          if ((header ushr 29) != 0L) return false
          imageWidth = (header and 0x3fffL) + 1L
          imageHeight = ((header ushr 14) and 0x3fffL) + 1L
          imageSeen = true
          losslessImage = true
        }
        matches(encoded, position, "ALPH") -> {
          if (flags == -1 || (flags and 0x10) == 0 || alphaSeen || imageSeen || length < 1L) return false
          val header = u8(encoded, payload)
          if ((header and 0xc0) != 0 || (header and 3) > 1 || ((header ushr 4) and 3) > 1) return false
          alphaSeen = true
        }
        matches(encoded, position, "ICCP") -> {
          if (flags == -1 || (flags and 0x20) == 0 || iccSeen || imageSeen || length == 0L) return false
          iccSeen = true
        }
        matches(encoded, position, "EXIF") -> {
          if (flags == -1 || (flags and 0x08) == 0 || exifSeen || length == 0L) return false
          exifSeen = true
        }
        matches(encoded, position, "XMP ") -> {
          if (flags == -1 || (flags and 0x04) == 0 || xmpSeen || length == 0L) return false
          xmpSeen = true
        }
        else -> {
          // WebP readers may ignore unknown RIFF chunks. Still walk their entire
          // descriptors and charge their payloads to the metadata budget.
          for (offset in 0 until 4) if (u8(encoded, position + offset) !in 32..126) return false
        }
      }
      position = paddedEnd.toInt()
    }
    if (!imageSeen) return false
    if (flags == -1) return !alphaSeen && !iccSeen && !exifSeen && !xmpSeen
    return imageWidth == canvasWidth && imageHeight == canvasHeight &&
      (flags and 0x20 != 0) == iccSeen && (flags and 0x08 != 0) == exifSeen &&
      (flags and 0x04 != 0) == xmpSeen &&
      ((flags and 0x10) == 0 || losslessImage || alphaSeen)
  }

  private fun pngChunkName(encoded: ByteArray, position: Int): Boolean {
    for (offset in 0 until 4) {
      val value = u8(encoded, position + offset)
      if (value !in 'A'.code..'Z'.code && value !in 'a'.code..'z'.code) return false
    }
    return u8(encoded, position + 2) in 'A'.code..'Z'.code
  }

  private fun matches(encoded: ByteArray, position: Int, value: String): Boolean {
    for (index in value.indices) if (u8(encoded, position + index) != value[index].code) return false
    return true
  }

  private fun u8(encoded: ByteArray, position: Int): Int = encoded[position].toInt() and 0xff
  private fun be32(encoded: ByteArray, position: Int): Long =
    (u8(encoded, position).toLong() shl 24) or (u8(encoded, position + 1).toLong() shl 16) or
      (u8(encoded, position + 2).toLong() shl 8) or u8(encoded, position + 3).toLong()

  private fun le32(encoded: ByteArray, position: Int): Long =
    u8(encoded, position).toLong() or (u8(encoded, position + 1).toLong() shl 8) or
      (u8(encoded, position + 2).toLong() shl 16) or (u8(encoded, position + 3).toLong() shl 24)

  private fun le24(encoded: ByteArray, position: Int): Long =
    u8(encoded, position).toLong() or (u8(encoded, position + 1).toLong() shl 8) or
      (u8(encoded, position + 2).toLong() shl 16)
}

/** Stable fallback signal: deliberately carries no source path, bytes, or decoder cause. */
class NativeFilmstripUnsupportedFormat : IllegalStateException("Unsupported native Filmstrip image format")
