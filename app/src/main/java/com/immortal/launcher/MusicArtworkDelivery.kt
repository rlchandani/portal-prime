/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Own local decode results through the cancellable IO-to-UI handoff. [publish]
 * transfers ownership only when it completes; never use this for cached/borrowed art.
 */
internal suspend fun <T : Any> deliverOwnedMusicArtwork(
    load: () -> T?, dispose: (T) -> Unit, publish: (T?) -> Unit,
) {
  val unpublished = AtomicReference<T?>()
  try {
    val result = runInterruptible(Dispatchers.IO) { load().also(unpublished::set) }
    publish(result)
    unpublished.set(null)
  } finally {
    unpublished.getAndSet(null)?.let(dispose)
  }
}
