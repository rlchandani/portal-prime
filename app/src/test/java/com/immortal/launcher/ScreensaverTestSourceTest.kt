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
    var nightUntil: Long? = null
    var pinMode: String? = null
    var pinInterval: Int? = null
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
      Mockito.`when`(prefs.getLong(Mockito.eq("night_until"), Mockito.anyLong())).thenAnswer { nightUntil ?: it.getArgument<Long>(1) }
      Mockito.`when`(editor.putLong(Mockito.eq("night_until"), Mockito.anyLong())).thenAnswer {
        nightUntil = it.getArgument(1); editor
      }
      Mockito.`when`(editor.remove("night_until")).thenAnswer { nightUntil = null; editor }
      Mockito.`when`(prefs.getString(Mockito.eq("pin_mode"), Mockito.any())).thenAnswer { pinMode ?: it.getArgument<String?>(1) }
      Mockito.`when`(prefs.getInt(Mockito.eq("pin_interval_sec"), Mockito.anyInt())).thenAnswer { pinInterval ?: it.getArgument<Int>(1) }
      Mockito.`when`(editor.putString(Mockito.eq("pin_mode"), Mockito.any())).thenAnswer { pinMode = it.getArgument(1); editor }
      Mockito.`when`(editor.putInt(Mockito.eq("pin_interval_sec"), Mockito.anyInt())).thenAnswer { pinInterval = it.getArgument(1); editor }
      Mockito.`when`(editor.remove("pin_mode")).thenAnswer { pinMode = null; editor }
      Mockito.`when`(editor.remove("pin_interval_sec")).thenAnswer { pinInterval = null; editor }
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

  @Test fun forcedNightNeedsTheSourceAndExpiresWithIt() {
    if (!ScreensaverTestSource.available()) return
    val p = Prefs()
    assertTrue(ScreensaverTestSource.enable(p.context, 30, nowMs = 0L))
    assertFalse(ScreensaverTestSource.forcedNight(p.context, 1L))
    assertTrue(ScreensaverTestSource.enable(p.context, 30, nowMs = 0L, night = true))
    assertTrue(ScreensaverTestSource.forcedNight(p.context, 1L))
    assertFalse(ScreensaverTestSource.forcedNight(p.context, 30 * 60_000L + 1))
    // Re-enabling without night drops the forced dark room.
    assertTrue(ScreensaverTestSource.enable(p.context, 30, nowMs = 0L))
    assertFalse(ScreensaverTestSource.forcedNight(p.context, 1L))
  }

  @Test fun disablingAlsoEndsForcedNight() {
    if (!ScreensaverTestSource.available()) return
    val p = Prefs()
    ScreensaverTestSource.enable(p.context, 30, nowMs = 0L, night = true)
    assertTrue(ScreensaverTestSource.disable(p.context))
    assertFalse(ScreensaverTestSource.forcedNight(p.context, 1L))
    assertEquals(null, p.nightUntil)
  }

  @Test fun markerStaysInsideEveryCoverCrop() {
    // Panels: Portal+ landscape, Mini portrait and landscape. Cover crops centre on x and on the
    // framing target (.45) on y; a corner marker was cut off on a Portal+ capture.
    val panels = listOf(2160f / 1440f, 800f / 1280f, 1280f / 800f)
    for (scene in ScreensaverTestSource.scenes) for (panel in panels) {
      val aspect = scene.width.toFloat() / scene.height
      val visibleX = minOf(1f, panel / aspect)
      val visibleY = minOf(1f, aspect / panel)
      val y0 = (.45f - visibleY / 2f).coerceIn(0f, 1f - visibleY)
      assertTrue(kotlin.math.abs(ScreensaverTestSource.MARKER_X - .5f) < visibleX / 2f)
      assertTrue(ScreensaverTestSource.MARKER_Y in y0..(y0 + visibleY))
    }
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

  @Test fun pinAcceptsOnlyKnownModesAndTheSettingsIntervalRange() {
    assertEquals(ScreensaverTestSource.Pin("filmstrip", 12), ScreensaverTestSource.Pin.of("filmstrip", 12))
    assertEquals(ScreensaverTestSource.Pin("shuffle", 5), ScreensaverTestSource.Pin.of("shuffle", 5))
    assertEquals(null, ScreensaverTestSource.Pin.of("notAMode", 30))
    assertEquals(null, ScreensaverTestSource.Pin.of("kenBurns", 4))
    assertEquals(null, ScreensaverTestSource.Pin.of("kenBurns", 601))
  }

  @Test fun pinMayHoldACatalogFace() {
    assertEquals(ScreensaverTestSource.Pin("smartFrame", 30, "flip"), ScreensaverTestSource.Pin.of("smartFrame", 30, "flip"))
    assertEquals(ScreensaverTestSource.Pin("smartFrame", 30, "bold"), ScreensaverTestSource.Pin.of("smartFrame", 30, "bold"))
    assertEquals(null, ScreensaverTestSource.Pin.of("smartFrame", 30, "notAFace"))
    assertEquals(null, ScreensaverTestSource.Pin.of("smartFrame", 30)?.face)
  }

  @Test fun pinnedReplacesOnlyModeAndInterval() {
    val household = ScreensaverConfig.Settings(mode = "shuffle", intervalSec = 45, photoMotion = false)
    val pinned = ScreensaverTestSource.pinned(household, ScreensaverTestSource.Pin("polaroid", 10))
    assertEquals(household.copy(mode = "polaroid", intervalSec = 10), pinned)
    assertEquals(household, ScreensaverTestSource.pinned(household, null))
  }

  @Test fun pinLastsAsLongAsTheSourceAndAPlainEnableDropsIt() {
    if (!ScreensaverTestSource.available()) return
    val p = Prefs()
    val pin = ScreensaverTestSource.Pin("mosaic", 15)
    assertTrue(ScreensaverTestSource.enable(p.context, 10, nowMs = 0L, pin = pin))
    assertEquals(pin, ScreensaverTestSource.pin(p.context, 1_000L))
    assertEquals(null, ScreensaverTestSource.pin(p.context, p.until!! + 1))
    assertTrue(ScreensaverTestSource.enable(p.context, 10, nowMs = 0L))
    assertEquals(null, ScreensaverTestSource.pin(p.context, 1_000L))
    assertTrue(ScreensaverTestSource.enable(p.context, 10, nowMs = 0L, pin = pin))
    assertTrue(ScreensaverTestSource.disable(p.context))
    assertEquals(null, p.pinMode)
    assertEquals(null, ScreensaverTestSource.pin(p.context, 1_000L))
  }
}
