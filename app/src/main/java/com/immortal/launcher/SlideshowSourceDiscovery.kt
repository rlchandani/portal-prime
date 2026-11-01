/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Main owns discovery lifetime; PhotoSourceWork owns blocking IO and transport teardown. */
internal class SlideshowSourceDiscovery(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
  private var pending: Job? = null
  @Volatile private var closed = false
  private var revision = 0L

  @MainThread
  fun <T> load(block: () -> T?, onResult: (T?) -> Unit) {
    if (closed) return
    val owner = ++revision
    pending?.cancel()
    pending = scope.launch {
      val result = try {
        PhotoSourceWork.load(block)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        null // Unreachable sources retain the controller's existing built-in fallback.
      }
      currentCoroutineContext().ensureActive()
      if (!closed && owner == revision) onResult(result)
    }
  }

  @MainThread
  fun close() {
    if (closed) return
    closed = true
    revision++
    pending?.cancel()
    pending = null
    scope.cancel()
  }
}
