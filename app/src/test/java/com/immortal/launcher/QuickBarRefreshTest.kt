/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real QuickBar decisions, with a held Main queue and mocked Android overlay boundaries. */
class QuickBarRefreshTest {
  @Test fun unchangedWindowNotificationsDoNotQueueRefreshes() = withBar { f ->
    repeat(100) { QuickBar.setBarVisible(false); QuickBar.setPinState(false, false, false) }
    assertEquals(0, f.queue.size)
    assertEquals(0, f.enabledReads)
  }

  @Test fun changingPinBurstRefreshesOnceAtTheLatestState() = withBar { f ->
    QuickBar.setBarVisible(true); f.drain(); f.resetCounts()
    repeat(100) { QuickBar.setPinState(true, it % 2 == 0, false) }
    assertEquals(1, f.queue.size)
    f.drain()
    assertEquals(1, f.enabledReads)
    Mockito.verify(f.pin).setColorFilter(0xFFFFFFFF.toInt())
    Mockito.verify(f.pin).setVisibility(View.VISIBLE)
    Mockito.verify(f.root).setVisibility(View.VISIBLE)
  }

  @Test fun explicitConfigurationRefreshReadsTheLatestSettings() = withBar { f ->
    QuickBar.applyConfig()
    f.enabled = true; f.alwaysShow = true
    QuickBar.applyConfig(); f.drain()
    assertEquals(1, f.enabledReads)
    Mockito.verify(f.switcher).setVisibility(View.VISIBLE)
    Mockito.verify(f.root).setVisibility(View.VISIBLE)
  }

  @Test fun changesDuringRefreshKeepOneLatestFollowup() = withBar { f ->
    f.onRead = {
      f.onRead = null
      repeat(10) { QuickBar.setPinState(true, it % 2 == 0, false) }
    }
    QuickBar.applyConfig(); f.drain()
    assertEquals(2, f.enabledReads)
    assertTrue(f.queue.isEmpty())
  }

  @Test fun detachCancelsUnstartedRefresh() = withBar { f ->
    QuickBar.applyConfig(); QuickBar.detach(); f.drain()
    assertEquals(0, f.enabledReads)
    Mockito.verify(f.wm).removeView(f.root)
  }

  @Test fun rejectedSchedulingAllowsTheSameStateToRetry() = withBar { f ->
    f.acceptPost = false; QuickBar.setBarVisible(true)
    assertTrue(f.queue.isEmpty())
    f.acceptPost = true; QuickBar.setBarVisible(true); f.drain()
    assertEquals(1, f.enabledReads)
    assertTrue(QuickBar.isBarVisible())
  }

  private class Fixture(val main: Handler) {
    val queue = ArrayDeque<Runnable>()
    val wm = Mockito.mock(WindowManager::class.java)
    val root = Mockito.mock(View::class.java)
    val switcher = Mockito.mock(View::class.java)
    val pin = Mockito.mock(ImageView::class.java)
    var enabled = false
    var alwaysShow = false
    var acceptPost = true
    var enabledReads = 0
    var onRead: (() -> Unit)? = null
    fun drain() {
      var n = 0
      while (queue.isNotEmpty()) { check(n++ < 500); queue.removeFirst().run() }
    }
    fun resetCounts() {
      enabledReads = 0
      Mockito.clearInvocations(root, switcher, pin, wm)
    }
  }

  private fun field(name: String, value: Any?) {
    QuickBar::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
  }

  private fun withBar(action: (Fixture) -> Unit) {
    Mockito.mockStatic(Looper::class.java).use { loops ->
      loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(Mockito.mock(Looper::class.java))
      Mockito.mockConstruction(Handler::class.java).use {
        val mainField = QuickBar::class.java.getDeclaredField("main").apply { isAccessible = true }
        val main = mainField.get(null) as Handler
        check(Mockito.mockingDetails(main).isMock); Mockito.reset(main)
        val f = Fixture(main)
        Mockito.doAnswer { call ->
          if (f.acceptPost) { f.queue.addLast(call.getArgument(0)); true } else false
        }.`when`(main).post(Mockito.any(Runnable::class.java))
        Mockito.doAnswer { call ->
          val task = call.getArgument<Runnable>(0); f.queue.removeIf { it === task }; null
        }.`when`(main).removeCallbacks(Mockito.any(Runnable::class.java))
        QuickBar.detach(); f.drain(); QuickBar.setBarVisible(false); f.drain()
        val service = Mockito.mock(AccessibilityService::class.java)
        val prefs = Mockito.mock(SharedPreferences::class.java)
        Mockito.`when`(service.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
        Mockito.doAnswer { f.enabledReads++; f.onRead?.invoke(); f.enabled }
            .`when`(prefs).getBoolean("enabled", false)
        Mockito.doAnswer { f.alwaysShow }.`when`(prefs).getBoolean("always_show", false)
        field("host", service); field("wm", f.wm); field("view", f.root)
        field("switcher", f.switcher); field("pinButton", f.pin)
        QuickBar.applyConfig(); f.drain(); f.resetCounts()
        try { action(f) } finally {
          f.acceptPost = true; f.onRead = null
          QuickBar.detach(); f.drain(); QuickBar.setBarVisible(false); f.drain()
          Mockito.reset(main)
        }
      }
    }
  }
}
