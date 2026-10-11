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
internal class DisplayWriteQueue(
    private val onFailure: (Exception) -> Unit = {},
    private val apply: (DisplayEdit) -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()
  private val pending = LinkedHashMap<DisplayEdit.Control, DisplayEdit>()
  private var running = false
  private var idle = CompletableDeferred(Unit)

  fun submit(edit: DisplayEdit) {
    synchronized(lock) {
      // Remove/reinsert so brightness/auto-brightness preserve the last user's event order.
      pending.remove(edit.control)
      pending[edit.control] = edit
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
