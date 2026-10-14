/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real cursor state; Android path/gesture and Main scheduling are the mocked boundaries. */
class RemoteCursorStateTest {
  @Test fun fourConcurrentClientsDoNotLoseRelativeMovement() = withInput { service ->
    val start = CountDownLatch(1)
    val ready = CountDownLatch(4)
    val workers = Executors.newFixedThreadPool(4)
    try {
      val results = (0 until 4).map {
        workers.submit(Callable<Unit> {
          ready.countDown()
          check(start.await(5, TimeUnit.SECONDS))
          repeat(2000) { check(RemoteInput.cursorMove(1f, 1f)) }
        })
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
      results.forEach { it.get(15, TimeUnit.SECONDS) }
      Mockito.mockConstruction(Path::class.java).use { paths ->
        assertTrue(RemoteInput.tap())
        Mockito.verify(paths.constructed().single()).moveTo(8100f, 8200f)
      }
    } finally {
      start.countDown(); workers.shutdownNow()
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  @Test fun movementDuringTapPathCreationDoesNotChangeTheCapturedTouch() = withInput {
    Mockito.mockConstruction(Path::class.java) { _, _ ->
      assertTrue(RemoteInput.cursorMove(10f, 20f))
    }.use { paths ->
      assertTrue(RemoteInput.tap())
      Mockito.verify(paths.constructed().single()).moveTo(100f, 200f)
    }
  }

  @Test fun movementDuringSwipePathCreationKeepsItsStartAndEndConsistent() = withInput {
    Mockito.mockConstruction(Path::class.java) { _, _ ->
      assertTrue(RemoteInput.cursorMove(10f, 20f))
    }.use { paths ->
      assertTrue(RemoteInput.swipe(40f, 50f))
      val path = paths.constructed().single()
      Mockito.verify(path).moveTo(100f, 200f)
      Mockito.verify(path).lineTo(140f, 250f)
    }
  }

  @Test fun movementAndSwipeStillClampToTheScreenEdges() = withInput {
    assertTrue(RemoteInput.cursorMove(-1000f, 1000000f))
    Mockito.mockConstruction(Path::class.java).use { paths ->
      assertTrue(RemoteInput.swipe(-100f, 100f))
      Mockito.verify(paths.constructed().single()).moveTo(0f, 99999f)
      Mockito.verify(paths.constructed().single()).lineTo(0f, 99999f)
    }
  }

  @Test fun firstTouchStillStartsAtTheScreenCentre() = withInput {
    resetPoint(-1f, -1f)
    Mockito.mockConstruction(Path::class.java).use { paths ->
      assertTrue(RemoteInput.tap())
      Mockito.verify(paths.constructed().single()).moveTo(50000f, 50000f)
    }
  }

  private fun resetPoint(x: Float, y: Float) {
    // Fixture reset occurs before clients start and after all workers have joined.
    RemoteInput::class.java.getDeclaredField("curX").apply { isAccessible = true }.setFloat(null, x)
    RemoteInput::class.java.getDeclaredField("curY").apply { isAccessible = true }.setFloat(null, y)
  }

  private fun withInput(action: (AccessibilityService) -> Unit) {
    Mockito.mockStatic(Looper::class.java).use { loops ->
      val loop = Mockito.mock(Looper::class.java)
      loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(loop)
      Mockito.mockConstruction(Handler::class.java).use {
        val main = RemoteCursor::class.java.getDeclaredField("main").apply { isAccessible = true }.get(null) as Handler
        check(Mockito.mockingDetails(main).isMock); Mockito.reset(main)
        Mockito.`when`(main.post(Mockito.any(Runnable::class.java))).thenReturn(true)
        val service = Mockito.mock(AccessibilityService::class.java)
        val resources = Mockito.mock(Resources::class.java)
        val metrics = Mockito.mock(DisplayMetrics::class.java).apply { widthPixels = 100000; heightPixels = 100000 }
        Mockito.`when`(service.resources).thenReturn(resources)
        Mockito.`when`(resources.displayMetrics).thenReturn(metrics)
        Mockito.`when`(service.dispatchGesture(Mockito.any(GestureDescription::class.java), Mockito.isNull(), Mockito.isNull())).thenReturn(true)
        Mockito.mockConstruction(GestureDescription.StrokeDescription::class.java).use {
          Mockito.mockConstruction(GestureDescription.Builder::class.java) { builder, _ ->
            Mockito.`when`(builder.addStroke(Mockito.any(GestureDescription.StrokeDescription::class.java))).thenReturn(builder)
            val gesture = Mockito.mock(GestureDescription::class.java)
            Mockito.`when`(builder.build()).thenReturn(gesture)
          }.use {
            RemoteInput.register(service); resetPoint(100f, 200f)
            try { action(service) } finally {
              RemoteInput.unregister(); resetPoint(-1f, -1f)
              RemoteCursor.detach(); Mockito.reset(main)
            }
          }
        }
      }
    }
  }
}
