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

/** The facts Home's Now Playing card is decided from. */
internal data class MusicHomeCardInputs(
    /** The engine holds a current song this process (playing, paused, or released after the grace). */
    val hasCurrent: Boolean,
    /** The full Music screen (or the legacy compact player) is open over Home. */
    val musicOpen: Boolean,
    /** Home is in Customize: the card would sit on the widgets being arranged. */
    val customizing: Boolean,
    /** ✕ was tapped (or the card sat paused for [MusicHomeCardRules.PAUSED_HIDE_MS]). */
    val dismissed: Boolean,
)

/**
 * When Home shows the Now Playing card that hosts the ONE YouTube player (so music keeps playing
 * after leaving the Music screen). Pure.
 *
 * Shown on Home, Apps and Tools whenever a song is current, unless the Music screen is open (it hosts
 * the player itself), Home is being customized, or the card was dismissed. A dismissal lasts until
 * music plays again (from Music, the header chip's Play, a phone) — see [afterPlaying].
 */
internal object MusicHomeCardRules {
  /** A paused card hides itself after this long, so Home doesn't keep a paused video forever. */
  const val PAUSED_HIDE_MS = 10 * 60_000L

  fun shows(i: MusicHomeCardInputs): Boolean = i.hasCurrent && !i.musicOpen && !i.customizing && !i.dismissed

  /** Music started playing: the card comes back. */
  fun afterPlaying(dismissed: Boolean, playing: Boolean): Boolean = dismissed && !playing
}

/** Process-lifetime card memory (the ✕), shared by Home's card and header chip. Thread-safe. */
internal object MusicHomeCardState {
  private val dismissedFlow = MutableStateFlow(false)
  val dismissed: StateFlow<Boolean> = dismissedFlow.asStateFlow()

  /** ✕ on the card: pause and hide. */
  fun dismiss() {
    dismissedFlow.value = true
  }

  /** The header chip's Play, or music started anywhere. */
  fun restore() {
    dismissedFlow.value = false
  }

  fun onPlaying(playing: Boolean) {
    dismissedFlow.value = MusicHomeCardRules.afterPlaying(dismissedFlow.value, playing)
  }
}
