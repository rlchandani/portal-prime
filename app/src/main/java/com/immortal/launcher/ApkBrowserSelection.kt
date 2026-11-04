/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File

/** Keep confirmation metadata with the file handed to the existing installer. */
internal object ApkBrowserSelection {
  class Prepared<T>(val file: File, val metadata: T, private val owned: Boolean) {
    fun close() {
      if (owned) runCatching { file.delete() }
    }
  }

  fun <T> prepare(cacheDir: File, source: File, readMetadata: (File) -> T?): Prepared<T>? {
    val snapshot = IncomingApkSnapshot.copy(cacheDir) { source.inputStream() } ?: return null
    var accepted = false
    try {
      val metadata = readMetadata(snapshot) ?: return null
      val prepared = Prepared<T>(snapshot, metadata, true)
      accepted = true
      return prepared
    } finally {
      if (!accepted) runCatching { snapshot.delete() }
    }
  }
}
