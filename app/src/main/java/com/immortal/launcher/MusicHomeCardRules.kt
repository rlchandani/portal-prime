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
    /** The card sat paused for [MusicHomeCardRules.PAUSED_HIDE_MS] (✕ only minimizes now). */
    val dismissed: Boolean,
)

/**
 * When Home shows the Now Playing card that hosts the ONE YouTube player (so music keeps playing
 * after leaving the Music screen). Pure.
 *
 * Shown on Home, Apps and Tools whenever a song is current, unless the Music screen is open (it hosts
 * the player itself), Home is being customized, or the card hid itself after sitting paused. That
 * lasts until music plays again (from Music, the header chip's Play, a phone) — see [afterPlaying].
 *
 * ✕ never stops the music: it MINIMIZES the card to a video-only tile ([MusicHomeCardMode.TILE]),
 * the same player at YouTube's minimum size, still visible and uncovered, so it keeps playing and
 * the header chip keeps controlling it. Only Pause (chip, card, media keys, a phone) stops it.
 */
internal object MusicHomeCardRules {
  /** A paused card hides itself after this long, so Home doesn't keep a paused video forever. */
  const val PAUSED_HIDE_MS = 10 * 60_000L

  fun shows(i: MusicHomeCardInputs): Boolean = i.hasCurrent && !i.musicOpen && !i.customizing && !i.dismissed

  /** Music started playing: the card comes back. */
  fun afterPlaying(dismissed: Boolean, playing: Boolean): Boolean = dismissed && !playing

  fun mode(minimized: Boolean): MusicHomeCardMode = if (minimized) MusicHomeCardMode.TILE else MusicHomeCardMode.CARD

  /** The paused auto-hide applies only while paused, in either mode; a playing card never hides. */
  fun autoHides(playing: Boolean): Boolean = !playing
}

/** The full Now Playing card, or the minimized video-only tile (✕) that keeps playing. */
internal enum class MusicHomeCardMode(val kind: MusicFloatKind) {
  CARD(MusicFloatKind.CARD),
  TILE(MusicFloatKind.TILE),
}

/**
 * Process-lifetime card memory shared by Home's card and header chip, plus where the card is now
 * (for the toasts and notifications that avoid it). Thread-safe.
 */
internal object MusicHomeCardState {
  private val dismissedFlow = MutableStateFlow(false)
  val dismissed: StateFlow<Boolean> = dismissedFlow.asStateFlow()
  private val minimizedFlow = MutableStateFlow(false)
  val minimized: StateFlow<Boolean> = minimizedFlow.asStateFlow()
  private val placementFlow = MutableStateFlow<MusicFloatRect?>(null)
  /** The card's (or tile's) rectangle on Home while it shows, else null. */
  val placement: StateFlow<MusicFloatRect?> = placementFlow.asStateFlow()

  /** The paused card hid itself ([MusicHomeCardRules.PAUSED_HIDE_MS]). */
  fun dismiss() {
    dismissedFlow.value = true
  }

  /** ✕: collapse to the video-only tile. The music keeps playing. */
  fun minimize() {
    minimizedFlow.value = true
  }

  /** The tile's expand button: the full card again, playback untouched. */
  fun expand() {
    minimizedFlow.value = false
  }

  fun place(rect: MusicFloatRect?) {
    placementFlow.value = rect
  }

  /** The header chip's Play, or music started anywhere. */
  fun restore() {
    dismissedFlow.value = false
  }

  fun onPlaying(playing: Boolean) {
    dismissedFlow.value = MusicHomeCardRules.afterPlaying(dismissedFlow.value, playing)
  }
}
