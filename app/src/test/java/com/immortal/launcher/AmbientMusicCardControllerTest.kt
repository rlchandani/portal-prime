/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.os.Handler
import android.view.MotionEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito

/** Lifetime and touch routing of the card controller, with the views and handler mocked. */
class AmbientMusicCardControllerTest {
  private class FakeSurface : AmbientMusicCardSurface {
    val renders = mutableListOf<AmbientMusicCardModel>()
    var hides = 0
    var shown = false
    var onCard = false
    var command: AmbientMusicCommand? = null
    override fun render(model: AmbientMusicCardModel) { renders += model; shown = true }
    override fun hide() { hides++; shown = false }
    override fun isShown() = shown
    override fun contains(rawX: Float, rawY: Float) = shown && onCard
    override fun commandAt(rawX: Float, rawY: Float, state: NowPlayingState?) = command
  }

  private val card = FakeSurface()
  private val ui = Mockito.mock(Handler::class.java)
  private var now = 100_000L
  private var active = true
  private var released = 0
  private val calls = mutableListOf<String>()
  private val controller = AmbientMusicCardController(card, ui, { now }, { active }, { released++ })
  private val playing = NowPlayingState(PlaybackState.PLAYING, title = "Song", artist = "Band",
      durationMs = 100_000L, positionMs = 10_000L, positionUpdatedElapsedMs = now, source = "pkg")

  init {
    MusicTransports.mediaSession = { object : MusicTransport {
      override fun play() { calls += "play" }
      override fun pause() { calls += "pause" }
      override fun next() { calls += "next" }
      override fun previous() { calls += "previous" }
      override fun seekTo(positionMs: Long) { calls += "seek" }
    } }
  }

  @After fun clearTransport() { MusicTransports.mediaSession = null }

  @Test fun playingRendersWithProgressAndSchedulesTheOneSecondTick() {
    assertTrue(controller.update(playing))
    val model = card.renders.single()
    assertEquals(0.1f, model.progress!!, 0.0001f)
    assertFalse(model.paused)
    assertTrue(model.transportAvailable)
    Mockito.verify(ui).postDelayed(any(Runnable::class.java), Mockito.eq(AmbientMusicCardPolicy.PROGRESS_TICK_MS))
  }

  @Test fun pausedRendersDimWithoutALine() {
    assertTrue(controller.update(playing.copy(state = PlaybackState.PAUSED)))
    val model = card.renders.single()
    assertTrue(model.paused)
    assertEquals(null, model.progress)
    assertEquals("Paused · Band", model.subtitle)
  }

  @Test fun pausedCardLeavesTheFrameWhenItsDeadlineFires() {
    controller.update(playing.copy(state = PlaybackState.PAUSED))
    val refresh = ArgumentCaptor.forClass(Runnable::class.java)
    Mockito.verify(ui).postDelayed(refresh.capture(), Mockito.eq(AmbientMusicCardPolicy.PAUSED_LEAVES_FRAME_AFTER_MS))
    now += AmbientMusicCardPolicy.PAUSED_LEAVES_FRAME_AFTER_MS
    refresh.value.run()
    assertEquals(1, card.hides)
    assertEquals(1, released)
  }

  @Test fun noTrackHidesAndReleasesArtwork() {
    assertFalse(controller.update(null))
    assertEquals(1, card.hides)
    assertEquals(1, released)
    Mockito.verify(ui, Mockito.never()).postDelayed(any(Runnable::class.java), anyLong())
  }

  @Test fun suspendDropsThePendingRefreshAndARefreshAfterStopIsInert() {
    controller.update(playing)
    val refresh = ArgumentCaptor.forClass(Runnable::class.java)
    Mockito.verify(ui).postDelayed(refresh.capture(), anyLong())
    controller.suspend()
    Mockito.verify(ui, Mockito.atLeastOnce()).removeCallbacks(refresh.value)
    active = false
    val before = card.renders.size
    refresh.value.run()
    assertEquals(before, card.renders.size)
    assertEquals(0, card.hides)
  }

  @Test fun tapOnCardRevealsControlsThenAButtonSendsThroughTheTransport() {
    controller.update(playing)
    card.onCard = true
    assertTrue(tap(500f, 500f))
    assertTrue(card.renders.last().controlsVisible)

    card.command = AmbientMusicCommand.PAUSE
    assertTrue(tap(500f, 500f))
    assertEquals(listOf("pause"), calls)
  }

  @Test fun tapElsewhereHidesControlsWithoutExitingAndThenPassesThrough() {
    controller.update(playing)
    card.onCard = true
    tap(1f, 1f)
    card.onCard = false
    assertTrue(tap(1f, 1f)) // hides controls, consumed
    assertFalse(tap(1f, 1f)) // controls gone: the host's tap-to-exit applies again
  }

  @Test fun controlsTimeOutAfterEightSeconds() {
    controller.update(playing.copy(durationMs = 0L))
    card.onCard = true
    tap(5f, 5f)
    val refresh = ArgumentCaptor.forClass(Runnable::class.java)
    Mockito.verify(ui).postDelayed(refresh.capture(), Mockito.eq(AmbientMusicCardPolicy.CONTROLS_TIMEOUT_MS))
    now += AmbientMusicCardPolicy.CONTROLS_TIMEOUT_MS
    refresh.value.run()
    assertFalse(card.renders.last().controlsVisible)
  }

  @Test fun swipesAndMovesAreNeverConsumed() {
    controller.update(playing)
    card.onCard = true
    assertFalse(controller.handleTouch(event(MotionEvent.ACTION_DOWN, 0f, 0f)))
    assertFalse(controller.handleTouch(event(MotionEvent.ACTION_MOVE, 100f, 0f)))
    assertFalse(controller.handleTouch(event(MotionEvent.ACTION_UP, 300f, 0f)))
  }

  private fun tap(x: Float, y: Float): Boolean {
    assertFalse(controller.handleTouch(event(MotionEvent.ACTION_DOWN, x, y)))
    return controller.handleTouch(event(MotionEvent.ACTION_UP, x + 2f, y + 2f))
  }

  private fun event(action: Int, x: Float, y: Float): MotionEvent =
      Mockito.mock(MotionEvent::class.java).also {
        Mockito.`when`(it.actionMasked).thenReturn(action)
        Mockito.`when`(it.rawX).thenReturn(x)
        Mockito.`when`(it.rawY).thenReturn(y)
      }
}
