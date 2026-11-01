/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A retained player already owns its paused position; only a recreated surface needs a seek. */
internal data class VideoPlaybackRetention(
    val sourceRevision: Long,
    val surfaceRevision: Long,
    val positionMs: Int,
) {
  fun needsSeek(currentSourceRevision: Long, currentSurfaceRevision: Long): Boolean =
      positionMs > 0 && sourceRevision == currentSourceRevision &&
          surfaceRevision != currentSurfaceRevision
}
