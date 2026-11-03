/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.IOException
import java.io.InputStream

/** TCP chunks are arbitrary; PCM16 mono writes must preserve complete two-byte frames. */
internal object LanPcmStream {
  fun copy16BitMono(
      input: InputStream,
      isActive: () -> Boolean,
      write: (ByteArray, Int, Int) -> Int,
  ) {
    val buffer = ByteArray(2048)
    var carry = 0
    while (isActive()) {
      val read = input.read(buffer, carry, buffer.size - carry)
      if (read <= 0 || !isActive()) return
      val available = carry + read
      val complete = available - available % 2
      var offset = 0
      while (offset < complete && isActive()) {
        val written = write(buffer, offset, complete - offset)
        if (written <= 0 || written > complete - offset || written % 2 != 0)
          throw IOException("PCM playback made no valid frame progress ($written)")
        offset += written
      }
      if (!isActive()) return
      carry = available - complete
      if (carry != 0) buffer[0] = buffer[complete]
    }
  }
}
