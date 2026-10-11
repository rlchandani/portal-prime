/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class NativeFilmstripDecodeTest {
  private val gif = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(32)

  @Test fun encodedAnimatedSourceRejectsInsteadOfReturningMissingCard() {
    expectUnsupported { SlideshowDecoder.nativeFilmstripBytes(gif) }
  }

  @Test fun cachedGifWithJpegFilenameStillRejectsWholeNativeDeck() {
    val cacheFile = File.createTempFile("native-filmstrip-format-", ".jpg")
    try {
      cacheFile.writeBytes(gif)
      expectUnsupported { SlideshowDecoder.nativeFilmstripFile(cacheFile.path) }
    } finally { cacheFile.delete() }
  }

  @Test fun unavailableAndOversizeInputsRemainBoundedDecodeFailures() {
    assertNull(SlideshowDecoder.nativeFilmstripBytes(byteArrayOf()))
    assertNull(SlideshowDecoder.nativeFilmstripBytes(gif, maxEdge = 0))
    assertNull(SlideshowDecoder.nativeFilmstripBytes(ByteArray(SlideshowDecodeBudget.MAX_ENCODED_BYTES + 1)))
  }

  private fun expectUnsupported(action: () -> Any?) {
    try { action(); fail("Animated or unknown content must reject the native deck") }
    catch (_: NativeFilmstripUnsupportedFormat) { /* Distinct from a missing card. */ }
  }
}
