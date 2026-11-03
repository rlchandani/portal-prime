/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.EOFException
import java.io.File

/** Bind the declared size and response body to one descriptor, including after path replacement. */
internal object FleetFileRead {
  fun response(file: File): FleetHttpServer.Response {
    // procfs diagnostics report a nominal size, rather than the number of readable bytes.
    val procfs = file.canonicalPath.startsWith("/proc/")
    val input = file.inputStream()
    try {
      val length = if (procfs) -1L else input.channel.size()
      return FleetHttpServer.Response.stream(
          200,
          "application/octet-stream",
          length,
          headers = mapOf("Content-Disposition" to FleetFs.attachmentDisposition(file.name)),
          onClose = input::close,
      ) { out ->
        input.use {
          if (procfs) {
            it.copyTo(out)
          } else {
            var remaining = length
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
              val read = it.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
              if (read < 0) throw EOFException("File shortened during download")
              out.write(buffer, 0, read)
              remaining -= read
            }
          }
        }
      }
    } catch (error: Throwable) {
      runCatching { input.close() }
      throw error
    }
  }
}
