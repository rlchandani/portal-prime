/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler

/**
 * Ends the warm-mic bridge the moment Jarvis's card is really recording: a recording-configuration callback,
 * registered only for the ~0.5 s of a handoff (never while idle-listening), that fires once when a second
 * recording appears beside Hearthpane's own. Main thread.
 */
internal class JarvisRecording(context: Context, private val handler: Handler) {
  private val audio = context.getSystemService(AudioManager::class.java)
  private var callback: AudioManager.AudioRecordingCallback? = null
  private val expire = Runnable { unwatch() }

  /** Call [onLive] once when another un-silenced recording starts, or never if none does within [WATCH_MS]. */
  fun watch(onLive: () -> Unit) {
    unwatch()
    val manager = audio ?: return
    val cb = object : AudioManager.AudioRecordingCallback() {
      override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
        // Ours plus one more: Jarvis's card. Ours is silenced once the card is on top, so count every recording.
        if (callback !== this || configs.size < 2) return
        unwatch()
        onLive()
      }
    }
    callback = cb
    manager.registerAudioRecordingCallback(cb, handler)
    handler.postDelayed(expire, WATCH_MS)
  }

  private fun unwatch() {
    handler.removeCallbacks(expire)
    callback?.let { audio?.unregisterAudioRecordingCallback(it) }
    callback = null
  }

  private companion object {
    const val WATCH_MS = 2_000L
  }
}
