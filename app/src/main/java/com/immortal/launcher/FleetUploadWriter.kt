/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import java.io.IOException
import java.io.OutputStream

internal object FleetUploadWriter {
  /**
   * Keeps the destination intact until the body and output close both complete.
   * Resolve symlinks before staging so uploads retain direct-write target semantics.
   */
  fun write(destination: File, writeBody: (OutputStream) -> Long): Long {
    val target = destination.canonicalFile
    val parent = target.parentFile ?: throw IOException("Upload destination has no parent")
    parent.mkdirs()
    val staging = File.createTempFile(".immortal-upload-", ".part", parent)
    try {
      val written = staging.outputStream().use(writeBody)
      if (!staging.renameTo(target)) {
        throw IOException("Could not commit upload to ${target.path}")
      }
      return written
    } finally {
      staging.delete()
    }
  }
}
