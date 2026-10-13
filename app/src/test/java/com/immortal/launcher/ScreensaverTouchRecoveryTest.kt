/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Real gesture and recovery code; Android windows, input and rendering are boundaries. */
class ScreensaverTouchRecoveryTest {
  @Test fun systemStopWhileFingerIsDownDoesNotReopenPhotos() = withFrame { frame, context ->
    frame.onTouch(event(MotionEvent.ACTION_DOWN))
    DreamPolicy.onImmortalDreamingStopped(context)
    Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
  }

  @Test fun alarmTouchAlsoPreventsRecoveryDuringTheGesture() = withFrame(alarm = true) { frame, context ->
    frame.onTouch(event(MotionEvent.ACTION_DOWN))
    DreamPolicy.onImmortalDreamingStopped(context)
    Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
  }

  @Test fun interruptionWithoutTouchStillRecoversTheScreensaver() = withFrame { _, context ->
    DreamPolicy.onImmortalDreamingStopped(context)
    Mockito.verify(context, Mockito.times(1)).startActivity(Mockito.any(Intent::class.java))
  }

  @Test fun fingerDownDoesNotExitAndTheCompletedTapExitsOnce() = withFrame { frame, _ ->
    var exits = 0
    frame.onExit = { exits++ }
    frame.onTouch(event(MotionEvent.ACTION_DOWN))
    assertEquals(0, exits)
    frame.onTouch(event(MotionEvent.ACTION_UP))
    assertEquals(1, exits)
  }

  private fun event(action: Int): MotionEvent = Mockito.mock(MotionEvent::class.java).also {
    `when`(it.actionMasked).thenReturn(action)
    `when`(it.x).thenReturn(100f)
    `when`(it.y).thenReturn(100f)
  }

  private fun withFrame(alarm: Boolean = false, action: (PhotoFrameController, Context) -> Unit) {
    val context = ScreensaverTestPrefs(mapOf("enabled" to true)).context
    val power = Mockito.mock(PowerManager::class.java)
    `when`(power.isInteractive).thenReturn(true)
    `when`(context.getSystemService(PowerManager::class.java)).thenReturn(power)
    `when`(context.applicationContext).thenReturn(context)
    val frame = Mockito.mock(PhotoFrameController::class.java, Mockito.CALLS_REAL_METHODS)
    val renderer = Mockito.mock(FaceRenderer::class.java) { call ->
      if (call.method.name == "handleAlarmTouch") alarm else Mockito.RETURNS_DEFAULTS.answer(call)
    }
    PhotoFrameController::class.java.getDeclaredField("faceRenderer").apply {
      isAccessible = true; set(frame, renderer)
    }
    val previous = Triple(DreamPolicy.userExitAt, DreamPolicy.bridgeAt, DreamPolicy.inStockHandoff)
    try {
      DreamPolicy.userExitAt = 0L
      DreamPolicy.bridgeAt = 0L
      DreamPolicy.inStockHandoff = false
      Mockito.mockStatic(Log::class.java).use {
        Mockito.mockConstruction(Intent::class.java,
            Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use { action(frame, context) }
      }
    } finally {
      DreamPolicy.userExitAt = previous.first
      DreamPolicy.bridgeAt = previous.second
      DreamPolicy.inStockHandoff = previous.third
    }
  }
}
