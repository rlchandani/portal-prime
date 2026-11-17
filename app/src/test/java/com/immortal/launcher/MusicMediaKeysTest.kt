/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Media keys (remote, adb, sleep scheduler) drive the YouTube player; play only on screen. */
class MusicMediaKeysTest {
  private fun act(key: Int, playing: Boolean = true, hasQueue: Boolean = true, onScreen: Boolean = true) =
      MusicMediaKeys.action(key, playing, hasQueue, onScreen)

  @Test fun playPauseToggles() {
    assertEquals(MusicMediaKeys.Action.PAUSE, act(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playing = true))
    assertEquals(MusicMediaKeys.Action.PLAY, act(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playing = false))
    assertEquals(MusicMediaKeys.Action.PAUSE, act(KeyEvent.KEYCODE_HEADSETHOOK, playing = true))
  }

  @Test fun pauseAlwaysWorksPlayOnlyOnScreen() {
    assertEquals(MusicMediaKeys.Action.PAUSE, act(KeyEvent.KEYCODE_MEDIA_PAUSE, onScreen = false))
    assertNull(act(KeyEvent.KEYCODE_MEDIA_PLAY, playing = false, onScreen = false))
    assertNull(act(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, playing = false, onScreen = false))
    assertEquals(MusicMediaKeys.Action.PLAY, act(KeyEvent.KEYCODE_MEDIA_PLAY, playing = false))
  }

  @Test fun skipKeysAndStop() {
    assertEquals(MusicMediaKeys.Action.NEXT, act(KeyEvent.KEYCODE_MEDIA_NEXT))
    assertEquals(MusicMediaKeys.Action.PREVIOUS, act(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    assertEquals(MusicMediaKeys.Action.PAUSE, act(KeyEvent.KEYCODE_MEDIA_STOP))
  }

  @Test fun nothingQueuedOrOtherKeysAreLeftAlone() {
    assertNull(act(KeyEvent.KEYCODE_MEDIA_PAUSE, hasQueue = false))
    assertNull(act(KeyEvent.KEYCODE_VOLUME_UP))
    assertNull(act(KeyEvent.KEYCODE_MEDIA_PAUSE, playing = false))
  }
}
