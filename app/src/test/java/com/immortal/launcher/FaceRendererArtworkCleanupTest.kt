/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.widget.ImageView
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercise the shipped ImageView ownership boundary without decoding or network access. */
class FaceRendererArtworkCleanupTest {
  @Test fun inactivePlaybackClearsTheDrawableAndBorrowedBitmapReference() = fixture { f ->
    f.renderer.javaClass.getDeclaredMethod("updateNowPlaying", NowPlayingState::class.java)
        .apply { isAccessible = true }.invoke(f.renderer, NowPlayingState(PlaybackState.IDLE))
    f.assertReleased()
  }

  @Test fun hidingTheRetainedRendererReleasesPixelsAndInvalidatesPendingArtwork() = fixture { f ->
    val requestBefore = field(f.renderer, "artRequestId").getLong(f.renderer)
    f.renderer.setHostActive(false)
    f.assertReleased()
    assertTrue(field(f.renderer, "artRequestId").getLong(f.renderer) > requestBefore)
  }

  @Test fun terminalStopClearsPixelsWithoutRecyclingASharedSessionBitmap() = fixture { f ->
    f.renderer.stop()
    f.assertReleased()
    assertTrue(f.work.isStopped)
  }

  private class Fixture(val renderer: FaceRenderer) {
    val work = field(renderer, "work").get(renderer) as FaceRendererWorkState
    private val image = Mockito.mock(ImageView::class.java)
    private val bitmap = Mockito.mock(Bitmap::class.java)

    init {
      work.start()
      field(renderer, "nowPlayingCard").set(renderer, Mockito.mock(LinearLayout::class.java))
      field(renderer, "npArt").set(renderer, image)
      field(renderer, "lastArtUrl").set(renderer, "content://media/old-cover")
      field(renderer, "lastArtBitmap").set(renderer, bitmap)
    }

    fun assertReleased() {
      Mockito.verify(image).setImageDrawable(null)
      assertNull(field(renderer, "lastArtUrl").get(renderer))
      assertNull(field(renderer, "lastArtBitmap").get(renderer))
      Mockito.verify(bitmap, Mockito.never()).recycle()
    }
  }

  private fun fixture(test: (Fixture) -> Unit) {
    val context = Mockito.mock(Context::class.java)
    val resources = Mockito.mock(Resources::class.java)
    val metrics = Mockito.mock(DisplayMetrics::class.java).apply { density = 1f }
    val prefs = Mockito.mock(SharedPreferences::class.java)
    Mockito.`when`(context.resources).thenReturn(resources)
    Mockito.`when`(resources.displayMetrics).thenReturn(metrics)
    Mockito.`when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt()))
        .thenReturn(prefs)
    val main = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(Looper::class.java).use { loops ->
      loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
      Mockito.mockConstruction(Handler::class.java).use {
        val renderer = FaceRenderer(context)
        try { test(Fixture(renderer)) } finally { renderer.stop() }
      }
    }
  }

  private companion object {
    fun field(value: Any, name: String) =
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }
  }
}
