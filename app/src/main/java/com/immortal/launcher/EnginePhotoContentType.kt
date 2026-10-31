/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream

/** Encoded formats determine response MIME and preserve legacy GIF/animated WebP playback. */
internal object EnginePhotoContentType {
  data class Format(val mime: String, val animation: Boolean = false)

  fun inspect(input: InputStream): Format {
    val header = ByteArray(32)
    var read = 0
    while (read < header.size) {
      val n = input.read(header, read, header.size - read)
      if (n < 0) break
      if (n == 0) {
        val byte = input.read()
        if (byte < 0) break
        header[read++] = byte.toByte()
      } else read += n
    }
    fun at(offset: Int, text: String): Boolean = read >= offset + text.length &&
        text.indices.all { header[offset + it].toInt() and 255 == text[it].code }
    return when {
      at(0, "GIF87a") || at(0, "GIF89a") -> Format("image/gif", animation = true)
      read >= 8 && header[0].toInt() and 255 == 137 && at(1, "PNG\r\n\u001a\n") -> Format("image/png")
      at(0, "RIFF") && at(8, "WEBP") ->
        Format("image/webp", animation = at(12, "VP8X") && read > 20 && header[20].toInt() and 2 != 0)
      else -> Format("image/jpeg")
    }
  }
}
