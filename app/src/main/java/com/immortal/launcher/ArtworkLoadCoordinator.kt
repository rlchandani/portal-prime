/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Serializes downloads/decodes without blocking ready artwork behind network I/O.
 * Cache callbacks must themselves be thread-safe and never perform a load.
 */
internal class ArtworkLoadCoordinator {
  private val loadLock = Any()

  fun <T : Any> fetch(cached: () -> T?, load: () -> T?, publish: (T) -> Unit): T? {
    cached()?.let { return it }
    return synchronized(loadLock) {
      // A queued request may now be cached by the producer that just finished.
      cached() ?: load()?.also(publish)
    }
  }
}
