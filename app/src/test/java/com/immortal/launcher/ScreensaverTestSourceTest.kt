/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ScreensaverTestSourceTest {
  private class Prefs {
    var until: Long? = null
    val context: Context = Mockito.mock(Context::class.java)

    init {
      val prefs = Mockito.mock(SharedPreferences::class.java)
      val editor = Mockito.mock(SharedPreferences.Editor::class.java)
      Mockito.`when`(context.getSharedPreferences("screensaver_test_source", Context.MODE_PRIVATE)).thenReturn(prefs)
      Mockito.`when`(prefs.getLong(Mockito.eq("until"), Mockito.anyLong())).thenAnswer { until ?: it.getArgument<Long>(1) }
      Mockito.`when`(prefs.edit()).thenReturn(editor)
      Mockito.`when`(editor.putLong(Mockito.eq("until"), Mockito.anyLong())).thenAnswer {
        until = it.getArgument(1); editor
      }
      Mockito.`when`(editor.remove("until")).thenAnswer { until = null; editor }
      Mockito.`when`(editor.commit()).thenReturn(true)
    }
  }

  @Test fun verificationBuildsCompileTheSourceIn() {
    // Unit tests run the debug variant; release and dev compile the flag false.
    assertEquals(BuildConfig.PORTAL_SCREENSAVER_TEST_SOURCE, ScreensaverTestSource.available())
  }

  @Test fun enablingExpiresOnItsOwnAndIsClamped() {
    if (!ScreensaverTestSource.available()) return
    val p = Prefs()
    assertFalse(ScreensaverTestSource.active(p.context, 1_000L))
    assertTrue(ScreensaverTestSource.enable(p.context, 10_000, nowMs = 1_000L))
    assertEquals(1_000L + ScreensaverTestSource.MAX_MINUTES * 60_000L, p.until)
    assertTrue(ScreensaverTestSource.active(p.context, 2_000L))
    assertFalse(ScreensaverTestSource.active(p.context, p.until!! + 1))
    assertTrue(ScreensaverTestSource.enable(p.context, 0, nowMs = 0L))
    assertEquals(60_000L, p.until)
  }

  @Test fun disablingSwitchesStraightBack() {
    if (!ScreensaverTestSource.available()) return
    val p = Prefs()
    ScreensaverTestSource.enable(p.context, 30, nowMs = 0L)
    assertTrue(ScreensaverTestSource.disable(p.context))
    assertFalse(ScreensaverTestSource.active(p.context, 1L))
  }

  @Test fun scenesCoverEveryFramingCaseAndCycle() {
    val aspects = ScreensaverTestSource.scenes.map { it.width.toFloat() / it.height }
    assertTrue(aspects.any { it > 1.6f })   // wide landscape
    assertTrue(aspects.any { it < .8f })    // portrait
    assertTrue(aspects.any { it == 1f })    // square
    assertTrue(ScreensaverTestSource.scenes.any { it.memory })
    assertEquals(ScreensaverTestSource.scene(0).place, ScreensaverTestSource.scene(ScreensaverTestSource.scenes.size).place)
    assertEquals(ScreensaverTestSource.scene(-1).place, ScreensaverTestSource.scenes.last().place)
  }

  @Test fun syntheticAgendaHasOneLiveAndOneUpcomingEvent() {
    val now = 10_000_000L
    val events = ScreensaverTestSource.events(now)
    assertEquals(1, events.count { it.startMillis <= now && it.endMillis > now })
    val next = events.filter { it.startMillis > now }.minByOrNull { it.startMillis }!!
    assertEquals(25 * 60_000L, next.startMillis - (now - now % 60_000L))
  }
}
