/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeFilmstripFormatTest {
  @Test fun jpegUsesTheRequiredThreeByteSignature() {
    assertTrue(confirmed(bytes(0xff, 0xd8, 0xff)))
    assertTrue(confirmed(bytes(0xff, 0xd8, 0xff, 0xe0, 0, 16)))
    assertFalse(confirmed(bytes(0xff, 0xd8)))
    assertFalse(confirmed(bytes(0xff, 0xd8, 0)))
  }

  @Test fun gifIsRejectedEvenWhenItDeclaresOnlyOneFrame() {
    // A complete, single-frame 1x1 GIF89a; no extension or filename is consulted.
    val gif = ascii("GIF89a") + bytes(
      1, 0, 1, 0, 0x80, 0, 0, 0, 0, 0, 0xff, 0xff, 0xff,
      0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3b
    )
    assertFalse(confirmed(gif))
    assertFalse(confirmed(ascii("GIF87a") + gif.copyOfRange(6, gif.size)))
  }

  @Test fun cacheRenamingDoesNotTurnGifBytesIntoJpeg() {
    val cachedAsJpeg = ascii("GIF89a") + ByteArray(20)
    assertFalse(confirmed(cachedAsJpeg))
    assertTrue(confirmed(png()))
    assertTrue(confirmed(webp(chunk("VP8L", vp8l()))))
  }

  @Test fun emptyAndUnknownFormatsAreRejected() {
    assertFalse(confirmed(byteArrayOf()))
    assertFalse(confirmed(ascii("not an image")))
    assertFalse(confirmed(ascii("RIFF") + le32(4) + ascii("WAVE")))
    assertFalse(confirmed(pngSignature.copyOf().also { it[0] = 0 }))
  }

  @Test fun staticPngAcceptsAllLegalIhdrColorAndDepthPairs() {
    val depths = mapOf(
      0 to listOf(1, 2, 4, 8, 16),
      2 to listOf(8, 16),
      3 to listOf(1, 2, 4, 8),
      4 to listOf(8, 16),
      6 to listOf(8, 16)
    )
    for ((color, values) in depths) for (depth in values) {
      val palette = if (color == 3) arrayOf(pngChunk("PLTE", bytes(0, 0, 0))) else emptyArray()
      assertTrue("$color/$depth", confirmed(png(ihdr(depth = depth, color = color), *palette)))
    }
    assertTrue(confirmed(png(ihdr(width = 257, height = 19, interlace = 1))))
  }

  @Test fun pngAncillaryMetadataAndConsecutiveDataChunksRemainStatic() {
    assertTrue(confirmed(png(
      ihdr(), pngChunk("tEXt", ascii("Title\u0000Photo")), pngChunk("tEXt", ascii("Author\u0000A"))
    )))
    assertTrue(confirmed(pngSignature + pngChunk("IHDR", ihdr()) +
      pngChunk("IDAT", bytes(1, 2)) + pngChunk("IDAT", bytes(3)) + pngChunk("IEND")))
  }

  @Test fun apngDeclarationsRejectTheWholeImage() {
    assertFalse(confirmed(png(ihdr(), pngChunk("acTL", be32(2) + be32(0)))))
    assertFalse(confirmed(png(ihdr(), pngChunk("acTL", be32(1) + be32(0)))))
    assertFalse(confirmed(png(ihdr(), pngChunk("fcTL", ByteArray(26)))))
    assertFalse(confirmed(png(ihdr(), pngChunk("fdAT", ByteArray(4)))))
    // Misordered animation metadata is also unsafe, even after static-looking IDAT.
    assertFalse(confirmed(pngSignature + pngChunk("IHDR", ihdr()) +
      pngChunk("IDAT", bytes(1)) + pngChunk("acTL", be32(2) + be32(0)) + pngChunk("IEND")))
  }

  @Test fun ihdrMustBeFirstUniqueCompleteAndHaveAValidChecksum() {
    assertFalse(confirmed(pngSignature + pngChunk("tEXt") + pngChunk("IHDR", ihdr()) +
      pngChunk("IDAT", bytes(1)) + pngChunk("IEND")))
    assertFalse(confirmed(png(ihdr(), pngChunk("IHDR", ihdr()))))
    assertFalse(confirmed(png(ihdr().copyOf(12))))
    assertFalse(confirmed(png(ihdr() + bytes(0))))
    val corrupt = png()
    corrupt[29] = (corrupt[29].toInt() xor 1).toByte() // IHDR CRC, not its fields.
    assertFalse(confirmed(corrupt))
  }

  @Test fun invalidIhdrFieldsAreRejectedEvenWithCorrectChecksums() {
    val invalid = listOf(
      ihdr(width = 0), ihdr(height = 0), ihdr(width = 0x80000000L),
      ihdr(height = 0xffffffffL), ihdr(depth = 0), ihdr(depth = 3),
      ihdr(depth = 4, color = 2), ihdr(depth = 16, color = 3),
      ihdr(color = 1), ihdr(color = 5), ihdr(color = 7),
      ihdr(compression = 1), ihdr(filter = 1), ihdr(interlace = 2)
    )
    for ((index, header) in invalid.withIndex()) {
      assertFalse("invalid IHDR $index", confirmed(png(header)))
    }
  }

  @Test fun paletteConstraintsAreChecked() {
    assertFalse(confirmed(png(ihdr(color = 3))))
    assertFalse(confirmed(png(ihdr(color = 3), pngChunk("PLTE"))))
    assertFalse(confirmed(png(ihdr(color = 3), pngChunk("PLTE", bytes(0, 0)))))
    assertFalse(confirmed(png(ihdr(color = 3, depth = 1), pngChunk("PLTE", ByteArray(9)))))
    assertFalse(confirmed(png(ihdr(color = 0), pngChunk("PLTE", ByteArray(3)))))
    assertFalse(confirmed(png(ihdr(), pngChunk("PLTE", ByteArray(3)), pngChunk("PLTE", ByteArray(3)))))
    assertFalse(confirmed(pngSignature + pngChunk("IHDR", ihdr()) + pngChunk("IDAT", bytes(1)) +
      pngChunk("PLTE", ByteArray(3)) + pngChunk("IEND")))
  }

  @Test fun pngRejectsUnknownCriticalChunksAndInvalidChunkNames() {
    assertFalse(confirmed(png(ihdr(), pngChunk("ABCD", bytes(1)))))
    assertFalse(confirmed(png(ihdr(), pngChunk("texT", bytes(1))))) // Reserved third letter.
    assertFalse(confirmed(png(ihdr(), pngChunk("t3Xt", bytes(1)))))
    val corruptText = pngChunk("tEXt", ascii("A\u0000B"))
    corruptText[corruptText.lastIndex] = (corruptText.last().toInt() xor 1).toByte()
    assertFalse(confirmed(png(ihdr(), corruptText)))
  }

  @Test fun pngRequiresCompleteDataAndTerminalIend() {
    val image = png()
    for (length in 0 until image.size) {
      assertFalse("PNG prefix $length", confirmed(image.copyOf(length)))
    }
    assertFalse(confirmed(image + bytes(0)))
    assertFalse(confirmed(pngSignature + pngChunk("IHDR", ihdr()) + pngChunk("IEND")))
    assertFalse(confirmed(pngSignature + pngChunk("IHDR", ihdr()) + pngChunk("IDAT") + pngChunk("IEND")))
    assertFalse(confirmed(pngSignature + pngChunk("IHDR", ihdr()) + pngChunk("IDAT", bytes(1)) +
      pngChunk("IEND", bytes(0))))
    assertFalse(confirmed(pngSignature + pngChunk("IHDR", ihdr()) + pngChunk("IDAT", bytes(1)) +
      pngChunk("tEXt") + pngChunk("IDAT", bytes(2)) + pngChunk("IEND")))
  }

  @Test fun pngUnsignedLengthsCannotWrapIntoSmallChunks() {
    val prefix = pngSignature + pngChunk("IHDR", ihdr())
    assertFalse(confirmed(prefix + be32(0xffffffffL) + ascii("tEXt") + bytes(0, 0, 0, 0)))
    assertFalse(confirmed(prefix + be32(0x7fffffffL) + ascii("IDAT") + bytes(0, 0, 0, 0)))
  }

  @Test fun pngMetadataAndChunkCountAreBoundedButPixelPayloadIsSkipped() {
    assertFalse(confirmed(png(ihdr(), pngChunk("tEXt", ByteArray(256 * 1024)))))
    val text = pngChunk("tEXt", ascii("A\u0000B"))
    val manyChunks = concatenate(Array(4094) { text })
    assertTrue(confirmed(pngSignature + pngChunk("IHDR", ihdr()) +
      manyChunks.copyOf(4093 * text.size) + pngChunk("IDAT", bytes(1)) + pngChunk("IEND")))
    assertFalse(confirmed(pngSignature + pngChunk("IHDR", ihdr()) + manyChunks +
      pngChunk("IDAT", bytes(1)) + pngChunk("IEND")))
    assertTrue(confirmed(pngSignature + pngChunk("IHDR", ihdr()) +
      pngChunk("IDAT", ByteArray(300 * 1024)) + pngChunk("IEND")))
  }

  @Test fun pngMetadataBudgetHasAnExactBoundary() {
    // Signature + IHDR + tEXt framing + IDAT framing + IEND = 69 metadata bytes.
    val payload = ascii("A\u0000") + ByteArray(256 * 1024 - 69 - 2) { 66 }
    assertTrue(confirmed(png(ihdr(), pngChunk("tEXt", payload))))
    assertFalse(confirmed(png(ihdr(), pngChunk("tEXt", payload + bytes(66)))))
  }

  @Test fun simpleAndExtendedStaticWebpAreRecognized() {
    assertTrue(confirmed(webp(chunk("VP8 ", vp8()))))
    assertTrue(confirmed(webp(chunk("VP8L", vp8l()))))
    assertTrue(confirmed(webp(chunk("VP8X", vp8x()), chunk("VP8 ", vp8()))))
    assertTrue(confirmed(webp(chunk("VP8X", vp8x()), chunk("VP8L", vp8l()))))
    assertTrue(confirmed(webp(chunk("VP8X", vp8x(flags = 0x20)),
      chunk("ICCP", bytes(1, 2, 3)), chunk("VP8 ", vp8()))))
    assertTrue(confirmed(webp(chunk("VP8X", vp8x(flags = 0x0c)),
      chunk("VP8 ", vp8()), chunk("EXIF", bytes(1)), chunk("XMP ", bytes(2)))))
    assertTrue(confirmed(webp(chunk("VP8X", vp8x(flags = 0x10)),
      chunk("ALPH", bytes(0, 0)), chunk("VP8 ", vp8()))))
  }

  @Test fun animatedWebpIsRejectedRegardlessOfDeclaredFlags() {
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x02)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0)),
      chunk("ANIM", ByteArray(6)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0)),
      chunk("ANMF", ByteArray(16)), chunk("VP8L", vp8l()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x02)),
      chunk("ANIM", ByteArray(6)), chunk("ANMF", ByteArray(16)))))
  }

  @Test fun webpWalksPastAStaticLookingImageInsteadOfReturningEarly() {
    for (image in listOf(chunk("VP8 ", vp8()), chunk("VP8L", vp8l()))) {
      assertFalse(confirmed(webp(image, chunk("ANIM", ByteArray(6)))))
      assertFalse(confirmed(webp(image, chunk("ANMF", ByteArray(16)))))
      assertFalse(confirmed(webp(image, chunk("VP8X", vp8x(flags = 0x02)))))
      assertFalse(confirmed(webp(chunk("VP8X", vp8x()), image, chunk("ANMF"))))
      assertFalse(confirmed(webp(chunk("VP8X", vp8x()), image,
        ascii("ANIM") + le32(0xffffffffL))))
      assertFalse(confirmed(webp(image, chunk("JUNK", bytes(1)), chunk("ANMF"))))
    }
  }

  @Test fun webpRejectsTruncatedContainersAndInconsistentRiffSizes() {
    val image = webp(chunk("VP8L", vp8l()))
    for (length in 0 until image.size) {
      assertFalse("WebP prefix $length", confirmed(image.copyOf(length)))
    }
    assertFalse(confirmed(image + bytes(0)))
    assertFalse(confirmed(image.copyOf().also { le32(image.size.toLong() - 9).copyInto(it, 4) }))
    assertFalse(confirmed(image.copyOf().also { le32(0xffffffffL).copyInto(it, 4) }))
    assertFalse(confirmed(webp(ascii("VP8L") + le32(0xffffffffL) + bytes(0))))
    assertFalse(confirmed(webp(ascii("VP8 ") + le32(0x80000000L) + bytes(0))))
    assertFalse(confirmed(webp(bytes(0, 1, 2))))
  }

  @Test fun webpRequiresOddPayloadPaddingAndRejectsNonzeroPadding() {
    assertTrue(confirmed(webp(chunk("VP8L", vp8l())))) // Five-byte payload plus padding.
    val missingPadding = ascii("VP8L") + le32(5) + vp8l()
    assertFalse(confirmed(webp(missingPadding)))
    val nonzeroPadding = chunk("VP8L", vp8l()).also { it[it.lastIndex] = 1 }
    assertFalse(confirmed(webp(nonzeroPadding)))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x20)),
      chunk("ICCP", bytes(1)).also { it[it.lastIndex] = 7 }, chunk("VP8 ", vp8()))))
  }

  @Test fun extendedWebpHeaderMustBeFirstUniqueAndComplete() {
    assertFalse(confirmed(webp(chunk("VP8X", vp8x().copyOf(9)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x() + bytes(0)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x()), chunk("VP8X", vp8x()), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8 ", vp8()), chunk("VP8X", vp8x()))))
    for (bit in listOf(0x01, 0x40, 0x80)) {
      assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = bit)), chunk("VP8 ", vp8()))))
    }
    assertFalse(confirmed(webp(chunk("VP8X", vp8x().also { it[1] = 1 }), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(width = 2)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(width = 0x1000000, height = 0x1000000)),
      chunk("VP8 ", vp8()))))
  }

  @Test fun webpNeedsExactlyOneRecognizedImageAndValidFrameHeaders() {
    assertFalse(confirmed(webp()))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x()))))
    assertFalse(confirmed(webp(chunk("VP8 ", vp8()), chunk("VP8L", vp8l()))))
    assertFalse(confirmed(webp(chunk("VP8 ", vp8().copyOf(9)))))
    assertFalse(confirmed(webp(chunk("VP8 ", vp8().also { it[3] = 0 }))))
    assertFalse(confirmed(webp(chunk("VP8 ", vp8().also { it[0] = 0x11 }))))
    assertFalse(confirmed(webp(chunk("VP8 ", vp8().also { it[6] = 0 }))))
    assertFalse(confirmed(webp(chunk("VP8L", vp8l().copyOf(4)))))
    assertFalse(confirmed(webp(chunk("VP8L", vp8l().also { it[0] = 0 }))))
    assertFalse(confirmed(webp(chunk("VP8L", vp8l().also { it[4] = 0x20 }))))
    assertFalse(confirmed(webp(chunk("ZZZZ", bytes(1)))))
  }

  @Test fun webpFeatureMetadataMustHaveItsDeclarationAndCorrectPlacement() {
    assertFalse(confirmed(webp(chunk("ICCP", bytes(1)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x()), chunk("ICCP", bytes(1)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x20)),
      chunk("VP8 ", vp8()), chunk("ICCP", bytes(1)))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x20)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x10)),
      chunk("VP8 ", vp8()), chunk("ALPH", bytes(0, 0)))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x10)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x10)),
      chunk("ALPH", bytes(0, 0)), chunk("VP8L", vp8l()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x10)),
      chunk("ALPH", bytes(0xc0, 0)), chunk("VP8 ", vp8()))))
  }

  @Test fun webpMetadataAndDescriptorCountAreBoundedOnBothSidesOfTheImage() {
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x20)),
      chunk("ICCP", ByteArray(256 * 1024)), chunk("VP8 ", vp8()))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x08)),
      chunk("VP8 ", vp8()), chunk("EXIF", ByteArray(256 * 1024)))))
    val descriptors = concatenate(Array(4096) { chunk("JUNK") })
    assertTrue(confirmed(webp(chunk("VP8 ", vp8()), descriptors.copyOf(4095 * 8))))
    assertFalse(confirmed(webp(chunk("VP8 ", vp8()), descriptors)))
    assertTrue(confirmed(webp(chunk("VP8 ", vp8() + ByteArray(300 * 1024)))))
  }

  @Test fun webpMetadataBudgetCountsPaddingAndHasAnExactBoundary() {
    // RIFF header + VP8X + VP8 framing + EXIF framing = 46 metadata bytes.
    val payload = ByteArray(256 * 1024 - 46)
    assertTrue(confirmed(webp(chunk("VP8X", vp8x(flags = 0x08)),
      chunk("VP8 ", vp8()), chunk("EXIF", payload))))
    assertFalse(confirmed(webp(chunk("VP8X", vp8x(flags = 0x08)),
      chunk("VP8 ", vp8()), chunk("EXIF", payload + bytes(0)))))
  }

  private fun confirmed(encoded: ByteArray): Boolean {
    val confirmed = NativeFilmstripFormat.isConfirmedStatic(encoded)
    val file = File.createTempFile("native-filmstrip-parity-", ".jpg")
    try {
      file.writeBytes(encoded)
      assertEquals("File admission must match ByteArray admission", confirmed,
        NativeFilmstripFormat.isConfirmedStatic(file))
    } finally {
      file.delete()
    }
    return confirmed
  }

  private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
  private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
  private fun be32(value: Long) = ByteArray(4) { (value ushr (24 - it * 8)).toByte() }
  private fun le32(value: Long) = ByteArray(4) { (value ushr (it * 8)).toByte() }
  private val pngSignature = bytes(137, 80, 78, 71, 13, 10, 26, 10)

  private fun ihdr(
    width: Long = 1, height: Long = 1, depth: Int = 8, color: Int = 6,
    compression: Int = 0, filter: Int = 0, interlace: Int = 0
  ) = be32(width) + be32(height) + bytes(depth, color, compression, filter, interlace)

  private fun pngChunk(type: String, payload: ByteArray = byteArrayOf()): ByteArray {
    val body = ascii(type) + payload
    val crc = CRC32().apply { update(body) }.value
    return be32(payload.size.toLong()) + body + be32(crc)
  }

  private fun png(header: ByteArray = ihdr(), vararg metadata: ByteArray): ByteArray =
    pngSignature + pngChunk("IHDR", header) + concatenate(metadata) +
      pngChunk("IDAT", bytes(0x78, 0x9c, 0x63, 0, 1, 0, 0, 5, 0, 1)) + pngChunk("IEND")

  private fun chunk(type: String, payload: ByteArray = byteArrayOf()): ByteArray =
    ascii(type) + le32(payload.size.toLong()) + payload +
      if (payload.size % 2 == 1) bytes(0) else byteArrayOf()

  private fun webp(vararg chunks: ByteArray): ByteArray {
    val body = ascii("WEBP") + concatenate(chunks)
    return ascii("RIFF") + le32(body.size.toLong()) + body
  }

  // Minimal codec frame headers: admission does not decode compressed pixels.
  private fun vp8() = bytes(0x10, 0, 0, 0x9d, 1, 0x2a, 1, 0, 1, 0)
  private fun vp8l() = bytes(0x2f, 0, 0, 0, 0)
  private fun vp8x(flags: Int = 0, width: Int = 1, height: Int = 1) =
    bytes(flags, 0, 0, 0) + le32(width.toLong() - 1).copyOf(3) + le32(height.toLong() - 1).copyOf(3)

  private fun concatenate(parts: Array<out ByteArray>): ByteArray {
    val result = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
      part.copyInto(result, offset)
      offset += part.size
    }
    return result
  }
}
