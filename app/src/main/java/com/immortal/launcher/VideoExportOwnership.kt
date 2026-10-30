/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File

/** A native export may outlive its controller. Ownership follows the process and exact files. */
internal object VideoExportOwnership {
  private var active: Lease? = null
  val isIdle: Boolean @Synchronized get() = active == null

  @Synchronized fun acquire(source: File, output: File): Lease? {
    if (active != null) return null
    return Lease(setOf(source.absolutePath, output.absolutePath)).also { active = it }
  }

  @Synchronized fun owns(file: File): Boolean = active?.paths?.contains(file.absolutePath) == true

  class Lease internal constructor(internal val paths: Set<String>) : AutoCloseable {
    override fun close() {
      synchronized(VideoExportOwnership) {
        if (active === this) active = null
      }
    }
  }
}
