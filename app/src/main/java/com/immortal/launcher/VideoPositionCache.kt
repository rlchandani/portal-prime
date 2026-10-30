/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A late native read must never become a replacement clip's resume position. */
internal class VideoPositionCache {
  private data class Sample(val sourceRevision: Long, val positionMs: Int)
  @Volatile private var sample = Sample(Long.MIN_VALUE, 0)
  fun read(sourceRevision: Long): Int {
    val value = sample
    return if (value.sourceRevision == sourceRevision) value.positionMs else 0
  }
  fun publish(sourceRevision: Long, positionMs: Int) {
    sample = Sample(sourceRevision, positionMs)
  }
}
