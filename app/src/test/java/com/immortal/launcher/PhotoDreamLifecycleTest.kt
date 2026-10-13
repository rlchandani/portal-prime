/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Intent
import android.os.PowerManager
import android.util.Log
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Real Dream callbacks; only Android services and the renderer are boundary doubles. */
class PhotoDreamLifecycleTest {
  @Test fun tapStopPausesRenderingWithoutClearingTheLastImageOrRelaunching() = withDream { dream, frame ->
    DreamPolicy.userExitAt = System.currentTimeMillis()
    stopAtAndroidBoundary(dream)
    Mockito.verify(frame).setHostActive(false)
    Mockito.verify(frame, Mockito.never()).stop()
    Mockito.verify(dream, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
  }

  @Test fun interruptedDreamRetainsAStillImageAndStillAllowsItsContinuation() = withDream { dream, frame ->
    stopAtAndroidBoundary(dream)
    Mockito.verify(frame).setHostActive(false)
    DreamPolicy.onImmortalDreamingStopped(dream)
    Mockito.verify(dream).startActivity(Mockito.any(Intent::class.java))
    Mockito.verify(frame, Mockito.never()).stop()
  }

  @Test fun stopBeforeControllerConstructionStillHandlesTheIntentionalExit() = withDream(false) { dream, frame ->
    DreamPolicy.userExitAt = System.currentTimeMillis()
    stopAtAndroidBoundary(dream)
    Mockito.verifyNoInteractions(frame)
    Mockito.verify(dream, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
  }

  private fun stopAtAndroidBoundary(dream: PhotoDreamService) {
    // JVM android.jar deliberately throws at the superclass boundary. Exercise our real
    // callback up to that boundary; DreamPolicy itself is tested separately with Android doubles.
    try { dream.onDreamingStopped() } catch (error: RuntimeException) {
      if (error.message?.startsWith("Method onDreamingStopped in android.service.dreams.DreamService not mocked.") != true) throw error
    }
  }

  private fun withDream(attachFrame: Boolean = true, action: (PhotoDreamService, PhotoFrameController) -> Unit) {
    val prefs = ScreensaverTestPrefs(mapOf("enabled" to true))
    val dream = Mockito.mock(PhotoDreamService::class.java, Mockito.CALLS_REAL_METHODS)
    val frame = Mockito.mock(PhotoFrameController::class.java)
    val power = Mockito.mock(PowerManager::class.java)
    `when`(power.isInteractive).thenReturn(true)
    Mockito.doReturn(power).`when`(dream).getSystemService(PowerManager::class.java)
    Mockito.doAnswer { call -> prefs.context.getSharedPreferences(call.getArgument(0), call.getArgument(1)) }
        .`when`(dream).getSharedPreferences(Mockito.anyString(), Mockito.anyInt())
    Mockito.doReturn(dream).`when`(dream).applicationContext
    Mockito.doNothing().`when`(dream).startActivity(Mockito.any(Intent::class.java))
    if (attachFrame) PhotoDreamService::class.java.getDeclaredField("frame").apply {
      isAccessible = true; set(dream, frame)
    }
    val previous = Triple(DreamPolicy.userExitAt, DreamPolicy.bridgeAt, DreamPolicy.inStockHandoff)
    try {
      DreamPolicy.userExitAt = 0L
      DreamPolicy.bridgeAt = 0L
      DreamPolicy.inStockHandoff = false
      Mockito.mockStatic(Log::class.java).use {
        Mockito.mockConstruction(Intent::class.java,
            Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use { action(dream, frame) }
      }
    } finally {
      DreamPolicy.userExitAt = previous.first
      DreamPolicy.bridgeAt = previous.second
      DreamPolicy.inStockHandoff = previous.third
    }
  }
}
