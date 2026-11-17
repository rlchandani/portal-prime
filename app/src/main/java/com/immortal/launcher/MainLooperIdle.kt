/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Looper
import android.os.MessageQueue
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Suspends until the main looper has no message due (between frames, after pending input and
 * loads), so deferred UI work lands in an otherwise empty slot. Cancellation removes the handler.
 */
internal suspend fun awaitMainLooperIdle() = suspendCancellableCoroutine<Unit> { continuation ->
  val queue = Looper.getMainLooper().queue
  val idle = MessageQueue.IdleHandler {
    if (continuation.isActive) continuation.resume(Unit)
    false
  }
  queue.addIdleHandler(idle)
  continuation.invokeOnCancellation { queue.removeIdleHandler(idle) }
}
