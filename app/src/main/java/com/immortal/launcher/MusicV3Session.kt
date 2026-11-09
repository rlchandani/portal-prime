/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-lifetime UI memory for music v3 that outlives one screen visit: the one-time "why it
 * paused" note for Home. (Recent searches are stored by [MusicLocalSearch].)
 */
internal object MusicV3Session {
  private val pausedNote = MutableStateFlow(false)
  @Volatile private var explained = false

  /** True while Home should show "Music paused. YouTube plays only while its video is on screen." */
  val showPausedNote: StateFlow<Boolean> = pausedNote.asStateFlow()

  /** The music screen closed while a song was playing; explain once per process. */
  fun leftWhilePlaying() {
    if (explained) return
    explained = true
    pausedNote.value = true
  }

  fun pausedNoteShown() {
    pausedNote.value = false
  }

  const val PAUSED_NOTE = "Music paused. YouTube plays only while its video is on screen."
}
