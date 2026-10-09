/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.res.Resources
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class BarWatchServiceTest {
  @Test fun everyAcquiredWindowIsReleasedEvenAfterFirstWindowMatches() {
    val bar = window(AccessibilityWindowInfo.TYPE_SYSTEM)
    val app = window(AccessibilityWindowInfo.TYPE_APPLICATION)
    withWatcher(listOf(bar, app)) { watcher, event ->
      watcher.onAccessibilityEvent(event)
      Mockito.verify(bar).recycle()
      Mockito.verify(app).recycle()
      Mockito.verify(app, Mockito.never()).type
    }
  }

  @Test fun windowsAreReleasedWhenNoStatusBarIsPresent() {
    val app = window(AccessibilityWindowInfo.TYPE_APPLICATION)
    withWatcher(listOf(app)) { watcher, event ->
      watcher.onAccessibilityEvent(event)
      Mockito.verify(app).recycle()
    }
  }

  @Test fun windowsAreReleasedWhenBoundsReadFails() {
    val broken = window(AccessibilityWindowInfo.TYPE_SYSTEM)
    val app = window(AccessibilityWindowInfo.TYPE_APPLICATION)
    Mockito.doThrow(IllegalStateException("Synthetic stale window"))
        .`when`(broken).getBoundsInScreen(Mockito.any(Rect::class.java))
    withWatcher(listOf(broken, app)) { watcher, event ->
      val error = runCatching { watcher.onAccessibilityEvent(event) }.exceptionOrNull()
      assertEquals("Synthetic stale window", error?.message)
      Mockito.verify(broken).recycle()
      Mockito.verify(app).recycle()
    }
  }

  @Test fun windowsAreReleasedWhenDisplayMetricsReadFails() {
    val app = window(AccessibilityWindowInfo.TYPE_APPLICATION)
    withWatcher(listOf(app)) { watcher, event ->
      `when`(watcher.resources).thenThrow(IllegalStateException("Synthetic resources failure"))
      val error = runCatching { watcher.onAccessibilityEvent(event) }.exceptionOrNull()
      assertEquals("Synthetic resources failure", error?.message)
      Mockito.verify(app).recycle()
    }
  }

  @Test fun unrelatedEventsDoNotAcquireWindows() {
    withWatcher(emptyList()) { watcher, event ->
      `when`(event.eventType).thenReturn(AccessibilityEvent.TYPE_VIEW_CLICKED)
      watcher.onAccessibilityEvent(event)
      Mockito.verify(watcher, Mockito.never()).windows
    }
  }

  private fun window(type: Int): AccessibilityWindowInfo =
      Mockito.mock(AccessibilityWindowInfo::class.java).also { `when`(it.type).thenReturn(type) }

  private fun withWatcher(
      windows: List<AccessibilityWindowInfo>,
      check: (BarWatchService, AccessibilityEvent) -> Unit,
  ) {
    val watcher = Mockito.mock(BarWatchService::class.java)
    val event = Mockito.mock(AccessibilityEvent::class.java)
    val resources = Mockito.mock(Resources::class.java)
    val metrics = DisplayMetrics().apply { widthPixels = 2160; heightPixels = 1440 }
    `when`(watcher.windows).thenReturn(windows)
    `when`(watcher.resources).thenReturn(resources)
    `when`(resources.displayMetrics).thenReturn(metrics)
    `when`(event.eventType).thenReturn(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
    Mockito.doCallRealMethod().`when`(watcher).onAccessibilityEvent(event)
    // Android's looper, rectangle methods and handler are framework boundaries.
    // JVM tests keep the real service decision/cleanup while omitting QuickBar UI work.
    Mockito.mockStatic(android.os.Looper::class.java).use { loopers ->
      loopers.`when`<android.os.Looper> { android.os.Looper.getMainLooper() }
          .thenReturn(Mockito.mock(android.os.Looper::class.java))
      Mockito.mockConstruction(Rect::class.java) { rect, _ ->
        `when`(rect.width()).thenReturn(2160)
        `when`(rect.height()).thenReturn(80)
      }.use {
        Mockito.mockConstruction(android.os.Handler::class.java).use { check(watcher, event) }
      }
    }
  }
}
