/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Runs the real refresh boundary; only worker admission and Android scheduling are substituted. */
class FaceRendererWeatherAdmissionTest {
  @Test fun faceWithoutWeatherDoesNotAdmitIoOrScheduleRefresh() = fixture(false) { f ->
    repeat(3) { f.refresh.run() }
    Mockito.verify(f.io, Mockito.never()).submit(Mockito.any(Runnable::class.java))
    Mockito.verify(f.handler, Mockito.never())
        .postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
    assertNull(field(f.renderer, "weatherRequest").get(f.renderer))
  }

  @Test fun visibleWeatherKeepsItsExistingSingleInFlightRequestAndRefreshCadence() = fixture(true) { f ->
    f.refresh.run()
    f.refresh.run()
    Mockito.verify(f.io, Mockito.times(1)).submit(Mockito.any(Runnable::class.java))
    Mockito.verify(f.handler, Mockito.times(2)).postDelayed(f.refresh, 30 * 60_000L)
    assertSame(f.request, field(f.renderer, "weatherRequest").get(f.renderer))
  }

  @Test fun hiddenHostDoesNotAdmitWeatherEvenWhenItsViewIsRetained() = fixture(true) { f ->
    f.work.setHostActive(false)
    f.refresh.run()
    Mockito.verify(f.io, Mockito.never()).submit(Mockito.any(Runnable::class.java))
    Mockito.verify(f.handler, Mockito.never())
        .postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
  }

  private class Fixture(val renderer: FaceRenderer, val handler: Handler, visible: Boolean) {
    val work = field(renderer, "work").get(renderer) as FaceRendererWorkState
    val io = Mockito.mock(ExecutorService::class.java)
    val request = Mockito.mock(Future::class.java)
    val refresh = field(renderer, "refreshWeather").get(renderer) as Runnable

    init {
      work.start()
      field(renderer, "io").set(renderer, io)
      field(renderer, "weatherView").set(renderer,
          if (visible) Mockito.mock(TextView::class.java) else null)
      Mockito.`when`(io.submit(Mockito.any(Runnable::class.java))).thenReturn(request)
    }
  }

  private fun fixture(visible: Boolean, test: (Fixture) -> Unit) {
    val context = Mockito.mock(Context::class.java)
    val main = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(Looper::class.java).use { loops ->
      loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
      Mockito.mockConstruction(Handler::class.java).use { handlers ->
        val renderer = FaceRenderer(context)
        try { test(Fixture(renderer, handlers.constructed().single(), visible)) }
        finally { renderer.stop() }
      }
    }
  }

  private companion object {
    fun field(value: Any, name: String) =
        value.javaClass.getDeclaredField(name).apply { isAccessible = true }
  }
}
