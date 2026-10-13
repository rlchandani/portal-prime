/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Exercise the real singleton with a held Android Main queue and WindowManager boundary. */
class RemoteCursorTest {
  @Test fun busyMainKeepsOnlyTheNewestPendingPosition() = withCursor { f ->
    repeat(100) { RemoteCursor.showAt(it, it + 10) }
    assertEquals(1, f.queue.size)
  }

  @Test fun burstPaintsTheLatestPositionOnlyOnce() = withCursor { f ->
    repeat(100) { RemoteCursor.showAt(it, it + 10) }
    f.drain()
    assertEquals(listOf(85 to 95), f.painted)
    assertEquals(1, f.delayed.size)
    assertEquals(4000L, f.delayed.single().second)
  }

  @Test fun disconnectCancelsPaintingThatHasNotStarted() = withCursor { f ->
    RemoteCursor.showAt(30, 40)
    RemoteCursor.detach()
    f.drain()
    assertTrue(f.painted.isEmpty())
    assertTrue(f.delayed.isEmpty())
  }

  @Test fun movementArrivingDuringAPaintKeepsOneLatestFollowup() = withCursor { f ->
    f.onPaint = {
      f.onPaint = null
      repeat(10) { RemoteCursor.showAt(200 + it, 300 + it) }
    }
    RemoteCursor.showAt(100, 110)
    f.drain()
    assertEquals(listOf(86 to 96, 195 to 295), f.painted)
  }

  @Test fun idleHideAndLaterRevealKeepTheSameDot() = withCursor { f ->
    RemoteCursor.showAt(40, 50); f.drain()
    val hide = f.delayed.single().first
    hide.run(); f.drain()
    RemoteCursor.showAt(60, 70); f.drain()
    assertEquals(2, f.painted.size)
    Mockito.verify(f.wm, Mockito.times(1)).addView(Mockito.any(View::class.java), Mockito.any(WindowManager.LayoutParams::class.java))
    assertEquals(1, f.delayed.size)
  }

  @Test fun rejectedSchedulingDoesNotStrandTheNextAcceptedMove() = withCursor { f ->
    f.acceptPost = false
    RemoteCursor.showAt(10, 20)
    assertTrue(f.queue.isEmpty())
    f.acceptPost = true
    RemoteCursor.showAt(50, 60); f.drain()
    assertEquals(listOf(36 to 46), f.painted)
  }

  private class Fixture(val main: Handler, val wm: WindowManager) {
    val queue = ArrayDeque<Runnable>()
    val delayed = mutableListOf<Pair<Runnable, Long>>()
    val painted = mutableListOf<Pair<Int, Int>>()
    var acceptPost = true
    var onPaint: (() -> Unit)? = null
    fun drain() {
      var count = 0
      while (queue.isNotEmpty()) { check(count++ < 500); queue.removeFirst().run() }
    }
  }

  private fun withCursor(action: (Fixture) -> Unit) {
    Mockito.mockStatic(Looper::class.java).use { loops ->
      loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(Mockito.mock(Looper::class.java))
      Mockito.mockConstruction(Handler::class.java).use {
        // Other JVM tests may have initialized this singleton under their Android doubles.
        val field = RemoteCursor::class.java.getDeclaredField("main").apply { isAccessible = true }
        val main = field.get(null) as Handler
        check(Mockito.mockingDetails(main).isMock)
        Mockito.reset(main)
        val wm = Mockito.mock(WindowManager::class.java)
        val f = Fixture(main, wm)
        Mockito.doAnswer { call ->
          if (f.acceptPost) { f.queue.addLast(call.getArgument(0)); true } else false
        }.`when`(main).post(Mockito.any(Runnable::class.java))
        Mockito.doAnswer { call -> f.delayed.add(call.getArgument<Runnable>(0) to call.getArgument<Long>(1)); true }
            .`when`(main).postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
        Mockito.doAnswer { call ->
          val task = call.getArgument<Runnable>(0)
          f.queue.removeIf { it === task }; f.delayed.removeAll { it.first === task }; null
        }.`when`(main).removeCallbacks(Mockito.any(Runnable::class.java))
        Mockito.doAnswer { call ->
          val params = call.getArgument<WindowManager.LayoutParams>(1)
          f.painted.add(params.x to params.y); f.onPaint?.invoke(); null
        }.`when`(wm).updateViewLayout(Mockito.any(View::class.java), Mockito.any(WindowManager.LayoutParams::class.java))
        val service = Mockito.mock(AccessibilityService::class.java)
        val resources = Mockito.mock(Resources::class.java)
        val metrics = Mockito.mock(DisplayMetrics::class.java).apply { density = 1.125f }
        Mockito.`when`(service.resources).thenReturn(resources)
        Mockito.`when`(resources.displayMetrics).thenReturn(metrics)
        Mockito.`when`(service.getSystemService(Context.WINDOW_SERVICE)).thenReturn(wm)
        Mockito.mockStatic(Log::class.java).use {
          Mockito.mockConstruction(View::class.java).use {
            Mockito.mockConstruction(GradientDrawable::class.java).use {
              Mockito.mockConstruction(WindowManager.LayoutParams::class.java).use {
                RemoteCursor.detach(); f.drain(); f.painted.clear(); f.delayed.clear()
                RemoteCursor.attach(service); f.drain()
                try { action(f) } finally {
                  f.acceptPost = true; f.onPaint = null
                  RemoteCursor.detach(); f.drain(); Mockito.reset(main)
                }
              }
            }
          }
        }
      }
    }
  }
}
