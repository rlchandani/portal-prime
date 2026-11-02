/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection

/** Worker-only calendar transport; failure remains eligible for the existing saved-feed fallback. */
internal object CalendarFeedHttp {
  const val MAX_BYTES = 8 * 1024 * 1024

  fun fetch(connection: HttpURLConnection): String? {
    var opened: InputStream? = null
    return try {
      connection.connectTimeout = 8000
      connection.readTimeout = 12000
      connection.instanceFollowRedirects = true
      connection.setRequestProperty("User-Agent", "Immortal/1.0")
      connection.setRequestProperty("Accept", "text/calendar, text/plain, */*")
      if (Thread.currentThread().isInterrupted) throw InterruptedException("Calendar read interrupted")
      val length = connection.contentLengthLong
      if (connection.responseCode !in 200..299 || length > MAX_BYTES) null
      else {
        val input = connection.inputStream.also { opened = it }
        val bytes = readBody(input)
        if (bytes == null || length >= 0 && bytes.size.toLong() != length) null
        else bytes.toString(Charsets.UTF_8)
      }
    } catch (interrupted: InterruptedException) {
      throw interrupted
    } catch (_: Exception) {
      null
    } finally {
      runCatching { opened?.close() }
      runCatching { connection.disconnect() }
    }
  }

  private fun readBody(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream(8192)
    val buffer = ByteArray(8192)
    while (true) {
      if (Thread.currentThread().isInterrupted) throw InterruptedException("Calendar read interrupted")
      val remaining = MAX_BYTES - output.size()
      if (remaining == 0) return if (input.read() == -1) output.toByteArray() else null
      val count = input.read(buffer, 0, minOf(buffer.size, remaining))
      when {
        count < 0 -> return output.toByteArray()
        count > 0 -> output.write(buffer, 0, count)
        else -> {
          val byte = input.read()
          if (byte < 0) return output.toByteArray()
          output.write(byte)
        }
      }
    }
  }
}
