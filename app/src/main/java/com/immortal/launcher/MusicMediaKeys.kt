/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.KeyEvent

/**
 * Media keys for the YouTube player (there is no MediaSession behind it, so the system can't
 * route them): delivered to Hearthpane's window by remotes, adb and [SleepScheduler]'s pause.
 * Pause/skip always work; Play only while the music screen is on screen (YouTube's
 * visible-player rule). Keys are left alone when nothing is queued or nothing would change.
 */
internal object MusicMediaKeys {
  enum class Action { PLAY, PAUSE, NEXT, PREVIOUS }

  fun action(keyCode: Int, playing: Boolean, hasQueue: Boolean, onScreen: Boolean): Action? {
    if (!hasQueue) return null
    val wanted = when (keyCode) {
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (playing) Action.PAUSE else Action.PLAY
      KeyEvent.KEYCODE_MEDIA_PLAY -> Action.PLAY
      KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> Action.PAUSE
      KeyEvent.KEYCODE_MEDIA_NEXT -> Action.NEXT
      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Action.PREVIOUS
      else -> return null
    }
    return when (wanted) {
      Action.PLAY -> wanted.takeIf { onScreen && !playing }
      Action.PAUSE -> wanted.takeIf { playing }
      else -> wanted
    }
  }

  /** Handles [event] if it's a media key for the player; true when consumed. */
  fun dispatch(event: KeyEvent): Boolean {
    val s = YouTubeMusicEngine.state.value
    val action = action(event.keyCode, s.isPlaying, s.queue.current != null, s.foreground) ?: return false
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      when (action) {
        Action.PLAY -> YouTubeMusicEngine.play()
        Action.PAUSE -> YouTubeMusicEngine.pause()
        Action.NEXT -> YouTubeMusicEngine.next()
        Action.PREVIOUS -> YouTubeMusicEngine.previous()
      }
    }
    return true
  }
}
