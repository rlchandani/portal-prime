/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import java.io.InputStream

/** Copy an externally opened APK before reading its metadata or offering installation. */
internal object IncomingApkSnapshot {
  fun copy(cacheDir: File, openSource: () -> InputStream?): File? {
    val apk = runCatching { File.createTempFile("incoming-", ".apk", cacheDir) }.getOrNull()
        ?: return null
    val copied =
        runCatching {
              openSource().use { input ->
                requireNotNull(input)
                apk.outputStream().use { input.copyTo(it) }
              }
            }
            .isSuccess
    if (!copied) {
      runCatching { apk.delete() }
      return null
    }
    return apk
  }
}
