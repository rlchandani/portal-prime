/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class EnginePhotoContentTypeTest {
  private fun inspect(bytes: ByteArray) = EnginePhotoContentType.inspect(ByteArrayInputStream(bytes))
  @Test fun gifPlaybackIsPreservedInsteadOfFlattenedToAJpeg() {
    val result = inspect("GIF89a".toByteArray())
    assertEquals("image/gif", result.mime); assertTrue(result.animation)
  }
  @Test fun alphaPngKeepsItsRealContentTypeEvenWhenTheCacheFilenameEndsInJpg() {
    val result = inspect(byteArrayOf(137.toByte()) + "PNG\r\n\u001a\n".toByteArray())
    assertEquals("image/png", result.mime); assertFalse(result.animation)
  }
  @Test fun extendedWebpAnimationFlagIsReadWithoutLoadingTheEncodedPhoto() {
    val bytes = ByteArray(32)
    "RIFF".toByteArray().copyInto(bytes); "WEBP".toByteArray().copyInto(bytes, 8)
    "VP8X".toByteArray().copyInto(bytes, 12)
    assertFalse(inspect(bytes).animation)
    bytes[20] = 2
    assertEquals("image/webp", inspect(bytes).mime); assertTrue(inspect(bytes).animation)
  }
  @Test fun jpegAndUnrecognizedBytesStillGoThroughTheBoundedDecoder() {
    assertEquals(EnginePhotoContentType.Format("image/jpeg"),
        inspect(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte())))
    assertFalse(inspect("invalid".toByteArray()).animation)
  }
}
