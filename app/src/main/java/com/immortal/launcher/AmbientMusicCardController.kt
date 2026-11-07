/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Handler
import android.util.Log
import android.view.MotionEvent

/**
 * Drives one [AmbientMusicCardSurface] (the [AmbientMusicCardView]) from [NowPlayingHub] states: applies
 * [AmbientMusicCardPolicy], owns the card's single refresh callback (the ~1s progress tick while
 * playing, plus the paused-leave and controls-timeout deadlines), and the walk-up touch routing.
 * Main thread only. [FaceRenderer] keeps artwork loading and the hub subscription.
 */
internal class AmbientMusicCardController(
    private val card: AmbientMusicCardSurface,
    private val ui: Handler,
    private val elapsedRealtime: () -> Long,
    private val isActive: () -> Boolean,
    /** Called whenever the card leaves the frame, so hidden artwork pixels are released. */
    private val releaseArtwork: () -> Unit,
) {
  private var state: NowPlayingState? = null
  private var changedAt = 0L
  private var controlsRevealedAt: Long? = null
  private var downX = 0f
  private var downY = 0f
  private val refresh = Runnable { if (isActive()) render(elapsedRealtime()) }

  /** Apply the latest hub state; true when the card is on the frame (so artwork should load). */
  fun update(next: NowPlayingState?): Boolean {
    if (next == null || !next.active) {
      state = null
      hide()
      return false
    }
    val now = elapsedRealtime()
    changedAt = AmbientMusicCardPolicy.stateChangedAt(state, next, changedAt, now)
    state = next
    return render(now)
  }

  /** Host hidden: drop the pending refresh and end any walk-up; the last state is retained. */
  fun suspend() {
    ui.removeCallbacks(refresh)
    controlsRevealedAt = null
  }

  /**
   * Walk-up controls from the host's touch stream. A tap on the card reveals previous /
   * play-pause / next in place; with them up, a tap on a button sends it through
   * [MusicTransports.forState] and a tap elsewhere only hides them. True when the tap was the
   * card's. DOWN and MOVE are never consumed, so a swipe starting on the card still changes photo.
   */
  fun handleTouch(ev: MotionEvent): Boolean {
    if (!isActive()) return false
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = ev.rawX
        downY = ev.rawY
        return false
      }
      MotionEvent.ACTION_UP -> Unit
      else -> return false
    }
    if (!AmbientMusicCardPolicy.isTap(downX, downY, ev.rawX, ev.rawY)) return false
    val now = elapsedRealtime()
    val tap = AmbientMusicCardPolicy.routeTap(
        cardVisible = card.isShown(),
        controlsVisible = AmbientMusicCardPolicy.controlsVisible(controlsRevealedAt, now),
        onCard = card.contains(ev.rawX, ev.rawY),
    )
    when (tap) {
      AmbientMusicCardTap.PASS_THROUGH -> return false
      AmbientMusicCardTap.REVEAL_CONTROLS -> controlsRevealedAt = now
      AmbientMusicCardTap.HIDE_CONTROLS -> controlsRevealedAt = null
      AmbientMusicCardTap.KEEP_CONTROLS -> {
        controlsRevealedAt = now
        card.commandAt(ev.rawX, ev.rawY, state)?.let(::send)
      }
    }
    render(now)
    return true
  }

  private fun render(now: Long): Boolean {
    ui.removeCallbacks(refresh)
    val s = state
    val revealed = AmbientMusicCardPolicy.controlsVisible(controlsRevealedAt, now)
    if (!revealed) controlsRevealedAt = null
    val visibility = AmbientMusicCardPolicy.visibility(s, now, changedAt, revealed)
    if (s == null || visibility == AmbientMusicCardVisibility.HIDDEN) {
      hide()
      return false
    }
    val paused = visibility == AmbientMusicCardVisibility.VISIBLE_PAUSED_DIM
    card.render(AmbientMusicCardModel(
        title = s.title,
        subtitle = AmbientMusicCardPolicy.subtitle(s),
        progress = if (paused) null else AmbientMusicCardPolicy.progressFraction(s, now),
        paused = paused,
        controlsVisible = revealed,
        transportAvailable = MusicTransports.forState(s) != null,
    ))
    AmbientMusicCardPolicy.nextRefreshDelayMs(s, now, changedAt, controlsRevealedAt)
        ?.let { ui.postDelayed(refresh, it) }
    return true
  }

  private fun hide() {
    ui.removeCallbacks(refresh)
    controlsRevealedAt = null
    releaseArtwork()
    card.hide()
  }

  private fun send(command: AmbientMusicCommand) {
    val s = state
    if (!AmbientMusicCardPolicy.send(command, MusicTransports.forState(s))) {
      Log.w(TAG, "music card: $command not delivered (source=${s?.source.orEmpty()})")
    }
  }

  private companion object {
    const val TAG = "ImmortalMusicCard"
  }
}
