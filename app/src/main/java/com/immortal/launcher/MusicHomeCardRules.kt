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
    /** The card sat paused for [MusicHomeCardRules.PAUSED_HIDE_MS]. */
    val dismissed: Boolean,
    /** ✕ on the minimized tile paused the music and closed the tile ([MusicTileCloseRules]). */
    val closed: Boolean = false,
)

/**
 * When Home shows the Now Playing card that hosts the ONE YouTube player (so music keeps playing
 * after leaving the Music screen). Pure.
 *
 * Shown on Home, Apps and Tools whenever a song is current, unless the Music screen is open (it hosts
 * the player itself), Home is being customized, or the card hid itself after sitting paused. That
 * lasts until music plays again (from Music, the header chip's Play, a phone) — see [afterPlaying].
 *
 * The card's ✕ never stops the music: it MINIMIZES the card to a video-only tile
 * ([MusicHomeCardMode.TILE]), the same player at YouTube's minimum size, still visible and uncovered,
 * so it keeps playing and the header chip keeps controlling it. The tile's own ✕ pauses and closes
 * ([MusicTileCloseRules]): YouTube plays only in a visible player, so a closed tile can't keep playing.
 */
internal object MusicHomeCardRules {
  /** A paused card hides itself after this long, so Home doesn't keep a paused video forever. */
  const val PAUSED_HIDE_MS = 10 * 60_000L

  fun shows(i: MusicHomeCardInputs): Boolean =
      i.hasCurrent && !i.musicOpen && !i.customizing && !i.dismissed && !i.closed

  /** Music started playing: the card comes back. */
  fun afterPlaying(dismissed: Boolean, playing: Boolean): Boolean = dismissed && !playing

  fun mode(minimized: Boolean): MusicHomeCardMode = if (minimized) MusicHomeCardMode.TILE else MusicHomeCardMode.CARD

  /** The paused auto-hide applies only while paused, in either mode; a playing card never hides. */
  fun autoHides(playing: Boolean): Boolean = !playing
}

/**
 * ✕ on the minimized tile, at [atMs] (elapsed realtime): the music was paused and the tile closed;
 * Undo within [MusicTileCloseRules.UNDO_MS] brings the tile back and resumes if it [wasPlaying].
 */
internal data class MusicTileUndo(val atMs: Long, val wasPlaying: Boolean)

/**
 * The tile's ✕ = pause and close (the owner's "if I can close it" ask; YouTube forbids background
 * playback, so closing can't keep the music going). Pure. The queue and position stay saved (a pause,
 * not a stop): the header chip offers "Resume · <title>", a phone sees "paused", and any request to
 * play (the chip, a phone, Music, a move from another Portal) brings the tile back, as Undo does.
 */
internal object MusicTileCloseRules {
  /** How long "Paused · Undo" shows. */
  const val UNDO_MS = 5_000L

  fun canUndo(offer: MusicTileUndo?, nowMs: Long): Boolean =
      offer != null && nowMs >= offer.atMs && nowMs - offer.atMs < UNDO_MS

  /** Playing, or asked to play once the player is on screen: either way the player must show. */
  fun playRequested(s: YtEngineState): Boolean = s.isPlaying || s.playWhenVisible

  /** What ✕ remembers for Undo: the music was playing or about to (not already paused). */
  fun wasPlaying(s: YtEngineState): Boolean = s.isPlaying || s.wantsPlay || s.playWhenVisible

  /** How much longer the Undo toast shows (0 once expired). */
  fun remainingMs(offer: MusicTileUndo, nowMs: Long): Long = (offer.atMs + UNDO_MS - nowMs).coerceIn(0L, UNDO_MS)
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
  private val closedFlow = MutableStateFlow(false)
  /** The tile's ✕ closed it ([MusicTileCloseRules]); lifted by Undo or any request to play. */
  val closed: StateFlow<Boolean> = closedFlow.asStateFlow()
  private val undoFlow = MutableStateFlow<MusicTileUndo?>(null)
  /** The live "Paused · Undo" offer, else null. */
  val undo: StateFlow<MusicTileUndo?> = undoFlow.asStateFlow()
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

  /** The tile's ✕ (the caller pauses the engine): hide it and offer Undo. */
  fun close(nowMs: Long, wasPlaying: Boolean) {
    closedFlow.value = true
    undoFlow.value = MusicTileUndo(nowMs, wasPlaying)
  }

  /**
   * Undo: the tile is back (still minimized). Returns whether to resume playback, or null when the
   * offer had expired (nothing changes).
   */
  fun undoClose(nowMs: Long): Boolean? {
    val offer = undoFlow.value
    undoFlow.value = null
    if (offer == null || !MusicTileCloseRules.canUndo(offer, nowMs)) return null
    closedFlow.value = false
    return offer.wasPlaying
  }

  /** The toast's time ran out; a newer offer (closed again meanwhile) is kept. */
  fun undoExpired(offer: MusicTileUndo) {
    undoFlow.compareAndSet(offer, null)
  }

  fun place(rect: MusicFloatRect?) {
    placementFlow.value = rect
  }

  /** The header chip's Play, or music started anywhere: the card (or tile) shows again. */
  fun restore() {
    dismissedFlow.value = false
    reopen()
  }

  /**
   * The engine is playing, or was asked to play once the player is visible (a phone's Play, a move
   * here): the player must be on screen for that, so a dismissal or a ✕ close lifts.
   */
  fun onPlaying(playing: Boolean) {
    dismissedFlow.value = MusicHomeCardRules.afterPlaying(dismissedFlow.value, playing)
    if (playing) reopen()
  }

  private fun reopen() {
    closedFlow.value = false
    undoFlow.value = null
  }
}
