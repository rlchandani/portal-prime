/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.locks.ReentrantLock

/** One local read/decode producer; a cancelled waiter never begins provider work. */
internal class ArtworkDecodeAdmission {
  private val lock = ReentrantLock(true)

  fun <T> run(work: () -> T): T {
    lock.lockInterruptibly()
    try { return work() }
    finally { lock.unlock() }
  }
}
