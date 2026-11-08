/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.media.AudioManager
import android.util.Log

/**
 * Raises a stream to full for an announcement, then puts the room's own level back — even a
 * muted (0) room hears the call. Repeated boosts keep the first saved level, and a level someone
 * changed meanwhile (volume keys) is left alone. Main thread only.
 */
internal class AnnouncementVolume(private val audio: Audio) {
  interface Audio {
    fun get(stream: Int): Int
    fun max(stream: Int): Int
    fun set(stream: Int, index: Int)
  }

  private data class Saved(val original: Int, val boostedTo: Int)
  private val saved = HashMap<Int, Saved>()

  fun boost(stream: Int) {
    if (stream in saved) return
    val original = audio.get(stream)
    val max = audio.max(stream)
    saved[stream] = Saved(original, max)
    if (original < max) audio.set(stream, max)
  }

  fun restore() {
    saved.forEach { (stream, s) -> if (s.original != s.boostedTo && audio.get(stream) == s.boostedTo) audio.set(stream, s.original) }
    saved.clear()
  }

  /** [AudioManager]-backed audio; failures (e.g. Do Not Disturb policy) are logged, not thrown. */
  class System(private val manager: AudioManager) : Audio {
    override fun get(stream: Int) = manager.getStreamVolume(stream)
    override fun max(stream: Int) = manager.getStreamMaxVolume(stream)
    override fun set(stream: Int, index: Int) {
      runCatching { manager.setStreamVolume(stream, index, 0) }
          .onFailure { Log.w("ImmortalAnnounce", "could not set stream $stream volume", it) }
    }
  }
}
