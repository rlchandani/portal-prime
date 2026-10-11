/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal sealed interface DisplayEdit {
  enum class Control { BRIGHTNESS, AUTO_BRIGHTNESS, VOLUME }
  val control: Control
  data class Brightness(val percent: Int) : DisplayEdit { override val control = Control.BRIGHTNESS }
  data class AutoBrightness(val enabled: Boolean) : DisplayEdit { override val control = Control.AUTO_BRIGHTNESS }
  data class Volume(val percent: Int) : DisplayEdit { override val control = Control.VOLUME }
}

/** Process-owned writes finish after a pane leaves; only latest unsent values are retained. */
internal class LatestSystemWrites<K, V>(
    private val keyOf: (V) -> K,
    private val onFailure: (Exception) -> Unit = {},
    private val apply: (V) -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()
  private val pending = LinkedHashMap<K, V>()
  private var running = false
  private var idle = CompletableDeferred(Unit)

  fun submit(edit: V) {
    synchronized(lock) {
      // Remove/reinsert so brightness/auto-brightness preserve the last user's event order.
      val key = keyOf(edit)
      pending.remove(key)
      pending[key] = edit
      if (!running) {
        running = true
        idle = CompletableDeferred()
        scope.launch { drain() }
      }
    }
  }

  suspend fun awaitIdle() = synchronized(lock) { idle }.await()

  private fun drain() {
    while (true) {
      val edit = synchronized(lock) {
        if (pending.isEmpty()) {
          running = false
          idle.complete(Unit)
          return
        }
        val first = pending.entries.first()
        pending.remove(first.key)
        first.value
      }
      try { apply(edit) } catch (error: Exception) { runCatching { onFailure(error) } }
    }
  }
}

internal class DisplayWriteQueue(onFailure: (Exception) -> Unit = {}, apply: (DisplayEdit) -> Unit) {
  private val writes = LatestSystemWrites(DisplayEdit::control, onFailure, apply)
  fun submit(edit: DisplayEdit) = writes.submit(edit)
  suspend fun awaitIdle() = writes.awaitIdle()
}

/** Share one ordering boundary across retained panes and Activity recreation; never retain an Activity. */
internal object DisplayWrites {
  @Volatile private var instance: DisplayWriteQueue? = null

  fun queue(context: android.content.Context): DisplayWriteQueue = instance ?: synchronized(this) {
    instance ?: run {
      val application = context.applicationContext
      DisplayWriteQueue(onFailure = { android.util.Log.w("DisplayWrites", "Device control write failed", it) }) { edit ->
        when (edit) {
          is DisplayEdit.Brightness -> check(DisplayControl.setBrightnessPercent(application, edit.percent))
          is DisplayEdit.AutoBrightness -> check(DisplayControl.setAutoBrightness(application, edit.enabled))
          is DisplayEdit.Volume -> DisplayControl.setMediaVolumePercent(application, edit.percent)
        }
      }.also { instance = it }
    }
  }
}
